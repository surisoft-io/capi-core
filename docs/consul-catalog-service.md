a# Consul Catalog Service

`ConsulCatalogService` is the component that polls Consul, reconciles the catalog against CAPI's in-memory state, and publishes the resulting routing map to the data plane. It is the single source of truth for "which services should CAPI serve right now."

This document walks through one complete discovery cycle, from the scheduler firing to the moment the new routes become visible to the request path.

## Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  CAPIMain.startSchedulers()                                         │
│    ScheduledExecutorService.scheduleAtFixedRate(lambda, 0, interval)│
└─────────────────────────────────────────────────────────────────────┘
        ↓ (every `interval` ms, never overlapping)
┌─────────────────────────────────────────────────────────────────────┐
│  [scheduler-thread]  runCycle()                                     │
│    1.  invalidServiceMap.clear()                                    │
│    2.  fetchAndUnion()                                              │
│          ├─ for each host: supplyAsync → [consul-catalog-N]         │
│          │       fetchHostSync()                                    │
│          │         ├─ GET /v1/catalog/services                      │
│          │         └─ N × GET /v1/catalog/service/<name> (async)    │
│          └─ wait & union HostResults                                │
│    3.  buildServices() — shape into Service objects                 │
│    4.  reconcile() — diff vs serviceCache → ServiceDelta            │
│    5.  prefetchOpenApiForDelta() — fetch only for added/changed     │
│         filterFailedFetches() — drop failed ones                    │
│    6.  apply()                                                      │
│         ├─ serviceCache mutations                                   │
│         ├─ transportHandler.onAppear/onChange/onDisappear           │
│         └─ transportHandler.afterCycle() ← SNAPSHOT PUBLISH ★       │
│    7.  maybeFlipReadinessLatch()                                    │
│    8.  emitSummary() — one INFO log line                            │
│  [scheduler-thread]  runCycle() returns                             │
└─────────────────────────────────────────────────────────────────────┘
        ↓ (immediately, if overrun; else wait until next tick)
   loop ↺
```

The ★ at step 6 is the moment a cycle becomes visible to the request path. Everything before it is the scheduler thread computing the next state; everything from ★ onwards is the request path seeing a new, fully-built view of the routes. That moment is atomic from a reader's perspective — readers either see the previous snapshot or this one, never partial.

## Lifecycle

### Scheduler setup

The cycle is driven by a Java `ScheduledExecutorService` created in `CAPIMain.startSchedulers(...)`:

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
long interval = capiConfiguration.getConsulCatalogDiscoverInterval();

scheduler.scheduleAtFixedRate(() -> {
    try {
        startup.getConsulCatalogService().runCycle();
        if (startup.getMcpServerClient() != null) {
            startup.getMcpServerClient().refreshMcpServerTools();
        }
    } catch (Exception e) {
        log.error("Consul discovery error: {}", e.getMessage());
    }
}, 0, interval, TimeUnit.MILLISECONDS);
```

Notes:

- The pool has **two threads**, shared with the Consul KV store, MCP trust store, and OPA bundle poll tasks.
- `initialDelay = 0` — the first cycle fires immediately at startup.
- The lambda is wrapped in `try/catch`. Without this wrapper, `scheduleAtFixedRate` silently stops firing the task forever on the first uncaught exception.
- `scheduleAtFixedRate` guarantees the same task does **not** execute concurrently with itself. If a cycle takes longer than `interval`, the next one starts immediately when the current one finishes (catch-up semantics), without an idle gap.

### Dedicated executor for per-host work

`ConsulCatalogService` owns a dedicated executor for the per-host catalog fetches:

```java
this.catalogExecutor = Executors.newFixedThreadPool(
    Math.max(1, consulHosts.size()),
    r -> {
        Thread t = new Thread(r, "consul-catalog-" + threadCounter.getAndIncrement());
        t.setDaemon(true);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return t;
    }
);
```

This replaces `ForkJoinPool.commonPool()` (the implicit default for `CompletableFuture.supplyAsync`). It gives three properties:

- **Bounded concurrency** — at most `numHosts` catalog tasks run at once.
- **Named, daemon threads** (`consul-catalog-N`) visible in thread dumps and APM profilers.
- **Lowered priority** — a best-effort hint to the OS scheduler to favor request-path threads under contention.

## The cycle, step by step

All paragraphs below reference `ConsulCatalogService.runCycle()`. Each phase runs on the scheduler thread unless noted otherwise.

### Step 1: Reset the invalid-services registry

```java
invalidServiceMap.clear();
```

The `/info/invalid-services` admin endpoint will return an empty list until something fails later in this cycle and gets recorded. This is a deliberate trade-off in favor of a simpler clear-then-populate model. Consumers polling that endpoint will see a brief readability gap during the cycle.

### Step 2: Fan out to all Consul hosts in parallel

```java
CycleResult result = fetchAndUnion();
```

For each configured Consul host, fire `fetchHost(host)` which returns `CompletableFuture.supplyAsync(() -> fetchHostSync(host), catalogExecutor)`. The scheduler thread itself does not do the per-host work — it hands off `N` tasks to `N` `consul-catalog-N` threads and immediately moves on.

Then the scheduler thread waits for each future with a cap:

```java
for (CompletableFuture<HostResult> future : hostFutures) {
    try {
        results.add(future.get(PER_HOST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    } catch (Exception e) {
        results.add(HostResult.failed(null));   // isolate failure, keep going
    }
}
```

`PER_HOST_TIMEOUT` is 30 seconds. A host that fails or times out is recorded as `HostResult.failed(null)` so the rest of the cycle continues with the surviving hosts. **One bad host does not abort the cycle.**

#### Inside `fetchHostSync(host)` — runs on `consul-catalog-N`

```
[consul-catalog-1] fetchHostSync(host)
  ├─ GET host/v1/catalog/services           ← sync via sendAsync().get(20s)
  │     → list of all service names
  ├─ for each service name:
  │     httpClient.sendAsync(GET host/v1/catalog/service/<name>)  ← N parallel async calls
  ├─ for each future:
  │     response = future.get(20s)
  │     processServiceByName(name, body) → Kept | EmptyFromConsul | FilteredEmpty | Failed
  └─ return HostResult(...)
```

`processServiceByName` runs two filters:

- `isForThisInstance(o)` — does this CAPI instance own the service? Single-instance and multi-instance ownership rules are evaluated here. See [Service Registration and Consul Metadata](consul-metadata.md) for the rules.
- `isPublishable(o)` — is `meta.state == PUBLISHED` (or absent)?

Per-service GETs use `sendAsync` (no thread held during the request) and are then harvested with a 20-second per-call cap. The `consul-catalog-N` thread is parked during the wait; the JDK `HttpClient` does the actual socket work on its own internal pool.

#### `union(hostResults)` back on the scheduler thread

Once all host futures have resolved or timed out, the scheduler thread merges the results:

- `catalogUnion` — union of service names seen across healthy hosts.
- `mergedPerService` — per service name, concatenated list of `ConsulObject` entries across hosts.
- `emptyFromConsul` — service names where Consul returned an empty array (different from "not in catalog" — see §7.2 below).
- `totalFailed`, `hostsFailed` — bookkeeping that drives the §7.1 cleanup-skip guard later.

### Step 3: Shape into `Service` objects

```java
Map<String, Service> incoming = buildServices(result.reconciledObjects());
```

For each service name, group its instances by `meta.group`, then for each group build a canonical `Service` object via `createServiceObject`:

- Service ID is **always** `name + ":" + group`, stable regardless of `routeGroupFirst`. This is invariant §Q6 — IDs do not flip.
- `findServiceMetaForGroup(group, objects)` returns the first `ServiceMeta` matching this group.
- The optional `serviceMetaExtrasPrefix` lets metadata keys with that prefix be promoted to `extraServiceMeta` (used by the tracer to attach attributes to spans).
- `applyPerInstanceOverrides(svc)` overlays this CAPI's per-instance settings (`secured`, `routeGroupFirst`, `openApiEndpoint`, `scheme`, `ignoreOpenApi`, `ingress`) on top of the base metadata. `secured` and `routeGroupFirst` are applied only when the instance block declared them explicitly (`assumeParent*` flags); the rest are applied when non-null. `ingress` repoints every mapping at a per-instance backend, rebuilding the `Set<Mapping>` because hostname and port participate in `Mapping.hashCode`.
- `setContext(svc)` sets the routing prefix based on `routeGroupFirst`: `/group/name` or `/name/group`.

Output: a `Map<String, Service>` keyed by stable service ID, representing the Consul-described world as of this cycle.

### Step 4: Diff against the cache

```java
ServiceDelta delta = reconcile(incoming, result);
```

For each incoming service, compare against the cached one (`serviceCache.peek(...)`):

- `cached == null` → new → `delta.added`.
- `serviceUtils.didServiceChange(cached, in)` returns true → `delta.changed` with both old and new references.
- Otherwise → not in the delta (no-op for this cycle).

`didServiceChange` checks the mapping list (size + membership), `secured`, `routeGroupFirst`, `version`, `subscriptionGroup`, and `openApiEndpoint` string equality. It does **not** compare parsed OpenAPI object trees.

#### §7.1 — cleanup-skip on partial failure

```java
if (!result.clean()) {
    log.warn("Consul discovery cycle had {} failed lookup(s) across {} host(s); skipping route cleanup", ...);
    return new ServiceDelta(added, changed, List.of());
}
```

If any host failed or any per-service lookup failed, **the removal phase is skipped entirely**. Returning a delta with empty `gone` guarantees that a partial view of Consul never triggers mass deletions. The trade-off: stale entries may linger for a cycle or two, in exchange for never wiping live routes on a network blip.

#### §7.2 — preserve on empty-from-Consul

If the cycle is clean, iterate the cache to find services no longer in `incoming`:

```java
if (result.emptyFromConsul().contains(cached.getName())) {
    log.debug("Preserving {} — Consul returned empty for name {}", e.getKey(), cached.getName());
    continue;
}
gone.add(cached);
```

A service name returning `[]` from Consul (no live instances right now) is different from the name disappearing from the catalog. Preserve the cached entry to avoid flapping during deploys.

### Step 5: Fetch OpenAPI for the diff only

```java
Set<String> failedFetches = prefetchOpenApiForDelta(delta);
if (!failedFetches.isEmpty()) {
    delta = filterFailedFetches(delta, failedFetches);
}
```

`prefetchOpenApiForDelta` builds a "to-fetch" working set:

- All `delta.added` — genuinely new services need a spec.
- For each `delta.changed`:
  - If the OpenAPI endpoint URL is unchanged **and** the `version` meta is unchanged **and** `oldSvc.getOpenAPI()` exists → copy the parsed spec from `oldSvc` to `newSvc`. No fetch.
  - If the endpoint URL changed **or** the `version` meta was bumped → add to the fetch set. A `version` bump is the explicit "reload my definition" signal, so it must force a re-fetch even at the same URL; keying the decision purely on the URL (as `627b6cb` briefly did) leaves the cached spec stale forever.

If the fetch set is empty, return `Set.of()` immediately. **Steady-state cycles with no changes do zero OpenAPI work.**

For services in the fetch set:

- Fire `httpClient.sendAsync(...)` for each.
- Harvest with `.get(PER_REQUEST_TIMEOUT * 2)` (20 seconds).
- On success, `serviceUtils.processOpenApiSpec(svc, response)` parses with `OpenAPIV3Parser` and stamps `svc.setOpenAPI(...)`. On parse failure, register the service in `invalidServiceMap` with reason `OPENAPI_INVALID_SPEC`.
- On timeout or any other exception, register the service with reason `OPENAPI_FETCH_FAILED`.
- If the service opted in with `match-openapi-version`, the parsed spec is then checked against the announced version — see below.
- All failures are returned as a `Set<String>` of service IDs.

#### The `match-openapi-version` check

`open-api` is normally a load-balanced URL. During a rolling deploy the first new pod bumps `version`, CAPI re-fetches, and an **old** pod can answer — so CAPI would cache the previous spec against the new version and never retry, because the version it recorded already matches.

Services that opt in with `"match-openapi-version":"true"` get one extra assertion: `serviceUtils.openApiVersionMismatch(svc)` requires the parsed spec's `info.version` to equal the `version` meta. On mismatch the service joins the failed set with reason `OPENAPI_VERSION_MISMATCH`, so `filterFailedFetches` drops it from the delta and **the previous route and spec keep serving**. Because the cached `version` meta is left untouched too, the next cycle still sees a change and retries — the rollout converges by itself once every pod serves the new spec.

The check **fails open**: if `version` is unset or the spec declares no `info.version`, CAPI warns and accepts the spec. A missing value is a configuration mistake, not evidence of staleness, and freezing the service over it would be worse than the race being guarded.

State for this lives in `versionMismatches` — `(firstSeen, consecutive, nextAttemptAt, detail)` per service — deliberately **outside `invalidServiceMap`**, which is cleared every cycle (Step 1). The *age* of a mismatch is what distinguishes a rollout in flight from a service frozen by a misconfigured flag, so it has to survive that clear. Entries are pruned each cycle against the current catalog, and after `MISMATCH_ATTEMPTS_BEFORE_BACKOFF` (3) consecutive failures the re-fetch backs off to `MISMATCH_BACKOFF` (5 minutes) — a full download **and** `OpenAPIV3Parser` parse per cycle is expensive enough that a permanently mismatched service should not pay it at cycle rate.

`filterFailedFetches` produces a new `ServiceDelta` with the failed IDs removed from `added` and `changed`. `gone` is untouched (removals do not depend on OpenAPI). A changed service whose new fetch failed stays in the cache with its previous spec until the next cycle — no route blip from a transient backend error.

### Step 6: Apply the delta (commit point)

```java
apply(delta);
```

This is the single-writer phase. Runs entirely on the scheduler thread. Order is added → changed → gone, then `afterCycle()` on every handler.

```java
for (Service s : delta.added()) {
    serviceCache.put(s.getId(), s);
    for (TransportHandler h : transportHandlers) {
        if (h.supports(s)) h.onAppear(s);          // builds RestClient, etc.
    }
}
for (ChangedPair p : delta.changed()) {
    serviceCache.put(p.newSvc().getId(), p.newSvc());
    for (TransportHandler h : transportHandlers) {
        if (h.supports(p.newSvc())) h.onChange(p.oldSvc(), p.newSvc());
    }
}
for (Service s : delta.gone()) {
    for (TransportHandler h : transportHandlers) {
        if (h.supports(s)) h.onDisappear(s);
    }
    serviceCache.remove(s.getId());
}
for (TransportHandler h : transportHandlers) {
    h.afterCycle();                                // SNAPSHOT PUBLISH
}
```

`TransportHandler.afterCycle()` is the snapshot commit. `RestTransportHandler.afterCycle()` calls `restClientSnapshot.publish(restClientMap)`, which does a `Map.copyOf` and a single volatile-store. `RestGateway` reads the snapshot on every request via a single volatile-read and always observes a fully-built map — never a mid-mutation state.

Until `afterCycle()` runs, `RestGateway` continues to serve from the previous cycle's snapshot. The flip happens atomically.

### Step 7: Readiness latch

```java
maybeFlipReadinessLatch(result);
```

If this is the first clean cycle since process start, set `connectedToConsul = true` permanently. The flag drives `/info/health` on the admin port — it is a one-way latch and does not reset on subsequent failures (§7.3).

### Step 8: Summary log

```java
emitSummary(result, delta, durationMs);
```

One INFO line per cycle:

```
consul-cycle hosts=4 hostsFailed=0 failedLookups=0 added=2 changed=0 gone=0 cleanupSkipped=false durationMs=132
```

This is the canonical "is this cycle healthy?" log line. Grep `consul-cycle` to see cycle behavior over time.

## After the cycle returns

The scheduler thread continues with the rest of the lambda body in `CAPIMain.startSchedulers`:

```java
if (startup.getMcpServerClient() != null) {
    startup.getMcpServerClient().refreshMcpServerTools();
}
```

When that returns, the lambda exits and the scheduler queues the next firing:

- **If the cycle finished within `interval`**: next firing scheduled at `start + interval`. The thread is idle until then.
- **If the cycle took longer than `interval`**: next firing is immediately due (the original scheduled time has already passed). A free thread in the scheduler pool picks it up right away with no idle gap. This is the catch-up behavior of `scheduleAtFixedRate`.

Consistent overruns mean the scheduler is permanently busy, with cycles running back-to-back. Cycle duration should be monitored via the `durationMs` field in the summary log.

## Threading model

| Work | Runs on |
|------|---------|
| `runCycle()` orchestration, harvest `.get()` calls, `buildServices`, `reconcile`, `apply` | Scheduler thread (1 of 2 in `CAPIMain.startSchedulers`). |
| `fetchHostSync(host)` per host | `catalogExecutor` (`consul-catalog-N`, daemon, low priority). |
| Underlying HTTP I/O (TCP, TLS, decoding) | JDK `HttpClient` internal pool. |
| Reading `restClientSnapshot.current()` | XNIO IO threads (request path). Lock-free volatile read. |

Only the scheduler thread writes to `serviceCache` and `restClientMap`. There is no other writer to race against (apart from `RouteConsistencyChecker`, which is a separately scheduled task and runs on the same scheduler pool).

## Invariants

- **Service IDs are stable** — `name:group`, never flipped. Cache lookups are consistent regardless of `routeGroupFirst`.
- **§7.1 cleanup-skip** — never remove services on a partial Consul view. Costs occasional stale entries; saves against mass-deregistration storms.
- **§7.2 empty-from-Consul preservation** — `[]` from Consul means "no live instances right now," not "service gone." Preserve cached entries.
- **§7.3 readiness latch** — once Consul has been reachable, the node stays ready even if later cycles degrade.
- **§7.5 GOAWAY tolerance** — ALB connection rotation is not an error. `sendWithGoawayRetry` retries once silently and logs at DEBUG.
- **§7.8 host independence** — per-host failures are isolated. The cycle continues with whatever hosts responded.
- **OpenAPI fetch on diff only** — added services and changed services where the endpoint URL changed. Otherwise the cached spec is carried over.
- **Atomic snapshot publish** — `RestGateway` sees full cycles, never partials. The flip from previous to current is a single volatile-store.
- **Cycle is single-writer** — only the scheduler thread mutates the live maps during a cycle.

## Failure registry

Services that this CAPI instance attempted to register but could not are recorded in `invalidServiceMap` during the cycle:

| Reason | Meaning |
|---|---|
| `OPENAPI_FETCH_FAILED` | the spec could not be retrieved — timeout, connection error, malformed URL |
| `OPENAPI_INVALID_SPEC` | retrieved but not parseable, or a non-200 response |
| `OPENAPI_VERSION_MISMATCH` | parsed, but `info.version` did not match the `version` meta on a service that opted in with `match-openapi-version`. **Expected transiently during a rolling deploy**; the service keeps serving its previous spec |

The map is exposed via:

- `GET /info/invalid-services` — full list, sorted by service ID.
- `GET /info/capi` — `invalidServiceCount` field, for dashboards that only need the count.

The map is cleared at the start of every cycle (`invalidServiceMap.clear()`), so it represents the most recent cycle's failures only. Services that have been intentionally filtered (not declared for this instance, or not in `PUBLISHED` state) are **never** recorded as invalid.

## Related documentation

- [Service Registration and Consul Metadata](consul-metadata.md) — how to register services and what every metadata field means.
- [Admin API](admin-api.md) — operational endpoints including `/info/invalid-services`.
- [Configuration](configuration.md) — the `consulCatalogDiscoverInterval`, host list, and authentication tokens.