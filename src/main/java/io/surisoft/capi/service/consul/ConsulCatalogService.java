package io.surisoft.capi.service.consul;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.surisoft.capi.configuration.CAPIConfiguration;
import io.surisoft.capi.processor.ServiceCapiInstanceMapper;
import io.surisoft.capi.schema.*;
import io.surisoft.capi.utils.Constants;
import io.surisoft.capi.utils.ServiceUtils;
import org.cache2k.Cache;
import org.cache2k.CacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ConsulCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ConsulCatalogService.class);

    private static final String GET_ALL_SERVICES = "/v1/catalog/services";
    private static final String GET_SERVICE_BY_NAME = "/v1/catalog/service/";
    private static final Duration PER_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PER_HOST_TIMEOUT = Duration.ofSeconds(30);

    /** Slack the caller (fetchAndUnion) waits beyond a host's own internal budget, so the
     *  host task returns a (possibly partial) result and frees its catalog thread rather
     *  than being abandoned mid-flight — the latter is what let detached fetches pile up
     *  on catalogExecutor across cycles and spiral into hundreds of Consul timeouts. */
    private static final Duration HOST_FUTURE_GRACE = Duration.ofSeconds(5);

    // §7.3 startup readiness latch — one-way. Set once on first clean cycle, never reset.
    private static volatile boolean connectedToConsul = false;

    public static boolean isConnectedToConsul() {
        return connectedToConsul;
    }

    private final List<CAPIConfiguration.HostConfig> consulHosts;
    private final Cache<String, Service> serviceCache;
    private final ServiceUtils serviceUtils;
    private final List<TransportHandler> transportHandlers;
    private final String capiInstanceName;
    private final boolean strictToInstanceName;
    private final String serviceMetaExtrasPrefix; // nullable
    private final Map<String, InvalidService> invalidServiceMap;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile HttpClient httpClient;

    /** Defense-in-depth: skip a tick if the previous cycle hasn't returned. The scheduler
     *  already serialises runCycle, so this only fires if runCycle is ever driven from more
     *  than one caller — but it makes "never overlap" an enforced invariant, not an assumption. */
    /**
     * Services whose fetched spec failed the {@code match-openapi-version} check, and since when.
     * Kept OUTSIDE {@link #invalidServiceMap} because that map is cleared every cycle — the age of
     * a mismatch is exactly what separates "rollout in flight, converging" from "misconfigured,
     * frozen forever", so it has to survive the clear.
     */
    private final Map<String, VersionMismatch> versionMismatches = new ConcurrentHashMap<>();

    /** Mismatches tolerated at full cycle rate before the re-fetch backs off. */
    private static final int MISMATCH_ATTEMPTS_BEFORE_BACKOFF = 3;
    /** Interval between re-fetches once backed off. Long enough to stop hammering a backend
     *  whose owner mis-set the flag, short enough that a slow rollout still converges on its own. */
    private static final Duration MISMATCH_BACKOFF = Duration.ofMinutes(5);

    /**
     * @param firstSeen      when this service first failed the version check
     * @param consecutive    consecutive failed checks (reset on success)
     * @param nextAttemptAt  earliest time the spec may be re-fetched
     * @param detail         the last mismatch description, re-published each cycle
     */
    private record VersionMismatch(Instant firstSeen, int consecutive, Instant nextAttemptAt, String detail) {}

    private final AtomicBoolean cycleInProgress = new AtomicBoolean(false);

    /**
     * Dedicated executor for per-host catalog fetches. Replaces the implicit use of
     * ForkJoinPool.commonPool() that supplyAsync(...) defaults to. Three reasons:
     *  - Bounded concurrency: at most numHosts catalog tasks run at once.
     *  - Named, daemon threads ("consul-catalog-N") show up in thread dumps / Dynatrace.
     *  - Lowered priority hints the OS scheduler to favor XNIO request threads under
     *    contention. This is JVM/OS best-effort, not a hard guarantee.
     */
    private final ExecutorService catalogExecutor;

    public ConsulCatalogService(List<CAPIConfiguration.HostConfig> consulHosts,
                                Cache<String, Service> serviceCache,
                                ServiceUtils serviceUtils,
                                List<TransportHandler> transportHandlers,
                                HttpClient httpClient,
                                String capiInstanceName,
                                boolean strictToInstanceName,
                                String serviceMetaExtrasPrefix,
                                Map<String, InvalidService> invalidServiceMap) {
        this.consulHosts = consulHosts;
        this.serviceCache = serviceCache;
        this.serviceUtils = serviceUtils;
        this.transportHandlers = transportHandlers;
        this.httpClient = httpClient;
        this.capiInstanceName = capiInstanceName;
        this.strictToInstanceName = strictToInstanceName;
        this.serviceMetaExtrasPrefix = serviceMetaExtrasPrefix;
        this.invalidServiceMap = invalidServiceMap;

        int poolSize = Math.max(1, consulHosts == null ? 1 : consulHosts.size());
        AtomicInteger threadCounter = new AtomicInteger(1);
        this.catalogExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "consul-catalog-" + threadCounter.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return t;
        });
    }

    /** Releases the dedicated executor. Safe to call multiple times. */
    public void shutdown() {
        catalogExecutor.shutdown();
        try {
            if (!catalogExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                catalogExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            catalogExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void runCycle() {
        if (!cycleInProgress.compareAndSet(false, true)) {
            log.warn("Consul discovery cycle skipped — previous cycle still in progress");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            invalidServiceMap.clear();
            CycleResult result = fetchAndUnion();
            Map<String, Service> incoming = buildServices(result.reconciledObjects());
            // Drop mismatch state for services that no longer exist, so a deregistered service
            // does not keep a backoff entry alive forever.
            versionMismatches.keySet().retainAll(incoming.keySet());

            // Reconcile FIRST to identify what's actually new or changed. The old
            // ConsulNodeDiscovery did this — fetching OpenAPI for every service every
            // cycle (the previous behavior here) multiplied the cycle's work by the
            // number of services with an open-api endpoint, regardless of whether
            // anything actually changed, and turned every upstream blip into hundreds
            // of timeout waits charged to the scheduler thread.
            ServiceDelta delta = reconcile(incoming, result);

            // Only fetch OpenAPI for services that genuinely need it. Failed fetches
            // are dropped from the delta so the route isn't registered with a stale or
            // missing spec.
            Set<String> failedFetches = prefetchOpenApiForDelta(delta);
            if (!failedFetches.isEmpty()) {
                delta = filterFailedFetches(delta, failedFetches);
            }

            apply(delta);
            maybeFlipReadinessLatch(result);
            emitSummary(result, delta, System.currentTimeMillis() - start);
        } finally {
            cycleInProgress.set(false);
        }
    }

    // -------------------------------------------------------------------------
    // Fetch + union (§7.8 host independence: parallel, bounded, non-blocking)
    // -------------------------------------------------------------------------

    private CycleResult fetchAndUnion() {
        List<CompletableFuture<HostResult>> hostFutures = consulHosts.stream()
                .map(this::fetchHost)
                .toList();

        // Wait slightly longer than the host's OWN internal budget (PER_HOST_TIMEOUT).
        // fetchHostSync is now bound to return within that budget, so under normal
        // degradation the future completes here and we collect a (possibly partial)
        // result. The grace makes abandonment the exception, not the rule.
        long hostWaitMs = PER_HOST_TIMEOUT.toMillis() + HOST_FUTURE_GRACE.toMillis();
        List<HostResult> results = new ArrayList<>(hostFutures.size());
        for (CompletableFuture<HostResult> future : hostFutures) {
            try {
                results.add(future.get(hostWaitMs, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                // Per-host failure is isolated. We synthesize a failed result so other hosts still count.
                // Cancel so the supplyAsync future is completed and not left dangling (note: this does
                // not interrupt an already-running fetchHostSync — that one self-bounds to PER_HOST_TIMEOUT).
                future.cancel(true);
                log.warn("Consul host timed out or failed before returning: {}", e.getMessage());
                results.add(HostResult.failed(null));
            }
        }
        return union(results);
    }

    private CompletableFuture<HostResult> fetchHost(CAPIConfiguration.HostConfig host) {
        return CompletableFuture.supplyAsync(() -> fetchHostSync(host), catalogExecutor);
    }

    private HostResult fetchHostSync(CAPIConfiguration.HostConfig host) {
        // Hard wall-clock budget for the WHOLE host fetch. Previously this method waited
        // PER_REQUEST_TIMEOUT*2 on each of (potentially hundreds of) per-service lookups
        // sequentially, so its runtime was unbounded (sum of timeouts) while the caller
        // only waited PER_HOST_TIMEOUT and then walked away WITHOUT cancelling — leaving a
        // detached task running on catalogExecutor. Across cycles those tasks piled up and
        // spiralled. Bounding the total here keeps the thread free for the next cycle.
        long deadlineNanos = System.nanoTime() + PER_HOST_TIMEOUT.toNanos();

        Set<String> catalogNames;
        try {
            HttpResponse<String> catalogResponse = sendWithGoawayRetry(buildCatalogRequest(host))
                    .get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
            @SuppressWarnings("unchecked")
            Map<String, Object> responseObject = objectMapper.readValue(catalogResponse.body(), Map.class);
            responseObject.remove("consul");
            catalogNames = new HashSet<>(responseObject.keySet());
        } catch (Exception e) {
            log.error("Consul catalog fetch failed for host {}: {}", host.getEndpoint(), e.getMessage());
            return HostResult.failed(host);
        }

        Map<String, CompletableFuture<HttpResponse<String>>> futures = new LinkedHashMap<>();
        for (String name : catalogNames) {
            futures.put(name, sendWithGoawayRetry(buildPerServiceRequest(host, name)));
        }

        Map<String, List<ConsulObject>> perService = new HashMap<>();
        Set<String> emptyFromConsul = new HashSet<>();
        int failed = 0;

        for (Map.Entry<String, CompletableFuture<HttpResponse<String>>> e : futures.entrySet()) {
            String name = e.getKey();
            long budgetMs = remainingMillis(deadlineNanos);
            if (budgetMs <= 0) {
                // Host budget exhausted. Stop waiting and cancel everything still pending so
                // this thread returns NOW instead of grinding through the rest. Abandoned
                // lookups count as failures, which makes result.clean() false and skips route
                // cleanup (§7.1) — we never delete live routes off a partial view.
                int abandoned = cancelPending(futures);
                failed += abandoned;
                log.warn("Consul host {} exceeded its {}ms budget; abandoned {} pending lookup(s) to free the cycle",
                        host.getEndpoint(), PER_HOST_TIMEOUT.toMillis(), abandoned);
                break;
            }
            try {
                HttpResponse<String> response = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                PerServiceOutcome outcome = processServiceByName(name, response.body());
                switch (outcome) {
                    case PerServiceOutcome.Kept k -> perService.put(name, k.entries());
                    case PerServiceOutcome.EmptyFromConsul ignored -> emptyFromConsul.add(name);
                    case PerServiceOutcome.FilteredEmpty ignored -> { /* authoritative: not ours */ }
                    case PerServiceOutcome.Failed f -> {
                        failed++;
                        logPerServiceFailure(name, f.cause());
                    }
                }
            } catch (Exception ex) {
                failed++;
                logPerServiceFailure(name, ex);
            }
        }

        return new HostResult(host, catalogNames, perService, emptyFromConsul, failed, false);
    }

    /** Millis left until {@code deadlineNanos}, never negative. */
    private static long remainingMillis(long deadlineNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    /**
     * Cancels every still-pending lookup and returns how many were cancelled. We stop waiting
     * immediately; the in-flight HTTP requests are additionally bounded by their own
     * {@code .timeout(PER_REQUEST_TIMEOUT)} so any that ignore the cancel still self-terminate
     * within seconds rather than holding the connection for the rest of the (sequential) loop.
     */
    private int cancelPending(Map<String, CompletableFuture<HttpResponse<String>>> futures) {
        int cancelled = 0;
        for (CompletableFuture<HttpResponse<String>> f : futures.values()) {
            if (!f.isDone()) {
                f.cancel(true);
                cancelled++;
            }
        }
        return cancelled;
    }

    private void logPerServiceFailure(String serviceName, Throwable cause) {
        Throwable root = unwrap(cause);
        String msg = root.getMessage();
        if (msg != null && msg.contains("GOAWAY")) {
            // §7.5 — GOAWAY is ALB connection rotation, not an error.
            log.debug("Dropping GOAWAY'd Consul lookup for {}", serviceName);
        } else {
            log.error("Failed to fetch service {}: {}", serviceName, msg);
        }
    }

    private CycleResult union(List<HostResult> hostResults) {
        Set<String> catalogUnion = new HashSet<>();
        Map<String, List<ConsulObject>> mergedPerService = new HashMap<>();
        Set<String> emptyUnion = new HashSet<>();
        int totalFailed = 0;
        int hostsFailed = 0;

        for (HostResult h : hostResults) {
            if (h.catalogFetchFailed()) {
                hostsFailed++;
                continue;
            }
            catalogUnion.addAll(h.catalogNames());
            emptyUnion.addAll(h.emptyFromConsul());
            totalFailed += h.failedServiceLookups();
            for (Map.Entry<String, List<ConsulObject>> e : h.perServiceObjects().entrySet()) {
                mergedPerService.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            }
        }
        return new CycleResult(catalogUnion, mergedPerService, emptyUnion, totalFailed, hostsFailed, hostResults.size());
    }

    // -------------------------------------------------------------------------
    // Per-service parse + filter (instance ownership, state, type default)
    // -------------------------------------------------------------------------

    private sealed interface PerServiceOutcome {
        record Kept(List<ConsulObject> entries) implements PerServiceOutcome {}
        record EmptyFromConsul() implements PerServiceOutcome {}
        record FilteredEmpty() implements PerServiceOutcome {}
        record Failed(Throwable cause) implements PerServiceOutcome {}
    }

    private PerServiceOutcome processServiceByName(String serviceName, String body) {
        List<ConsulObject> parsed;
        try {
            parsed = objectMapper.readValue(body, new TypeReference<List<ConsulObject>>() {});
        } catch (Exception e) {
            return new PerServiceOutcome.Failed(e);
        }
        if (parsed.isEmpty()) {
            return new PerServiceOutcome.EmptyFromConsul();
        }

        List<ConsulObject> kept = new ArrayList<>();
        for (ConsulObject o : parsed) {
            if (!isForThisInstance(o)) continue;
            if (!isPublishable(o)) continue;
            kept.add(o);
        }
        return kept.isEmpty()
                ? new PerServiceOutcome.FilteredEmpty()
                : new PerServiceOutcome.Kept(kept);
    }

    private boolean isForThisInstance(ConsulObject o) {
        boolean hasMultiForThis = serviceUtils.getServiceCapiInstance(o, capiInstanceName) != null;
        boolean hasSingleForThis = o.getServiceMeta() != null
                && o.getServiceMeta().getCapiNamespace() != null
                && o.getServiceMeta().getCapiNamespace().equals(capiInstanceName);
        if (hasMultiForThis || hasSingleForThis) return true;
        boolean declaredForOthers = serviceUtils.isTheServiceRegisteredForOtherInstances(o, capiInstanceName)
                || (o.getServiceMeta() != null && o.getServiceMeta().getCapiNamespace() != null);
        if (declaredForOthers) return false;
        return !strictToInstanceName;
    }

    private boolean isPublishable(ConsulObject o) {
        if (o.getServiceMeta() == null || o.getServiceMeta().getState() == null) return true;
        return o.getServiceMeta().getState().equals(State.PUBLISHED);
    }

    // -------------------------------------------------------------------------
    // Build Service objects (per Q6: ID always "<name>:<group>")
    // -------------------------------------------------------------------------

    private Map<String, Service> buildServices(Map<String, List<ConsulObject>> perService) {
        Map<String, Service> result = new HashMap<>();
        for (Map.Entry<String, List<ConsulObject>> entry : perService.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, Set<Mapping>> byGroup = groupByGroup(entry.getValue());
            for (Map.Entry<String, Set<Mapping>> g : byGroup.entrySet()) {
                Service svc = createServiceObject(serviceName, g.getKey(), g.getValue(), entry.getValue());
                result.put(svc.getId(), svc);
            }
        }
        return result;
    }

    private Map<String, Set<Mapping>> groupByGroup(List<ConsulObject> objects) {
        Map<String, Set<Mapping>> result = new HashMap<>();
        for (ConsulObject o : objects) {
            String group = getGroup(o);
            if (group == null) {
                log.trace("Service {} has no meta.group; skipping", o.getServiceName());
                continue;
            }
            result.computeIfAbsent(group, k -> new HashSet<>()).add(serviceUtils.consulObjectToMapping(o));
        }
        return result;
    }

    private String getGroup(ConsulObject o) {
        return (o.getServiceMeta() != null) ? o.getServiceMeta().getGroup() : null;
    }

    private Service createServiceObject(String serviceName, String group, Set<Mapping> mappings, List<ConsulObject> objects) {
        Service svc = new Service();
        ServiceMeta serviceMeta = findServiceMetaForGroup(group, objects);

        if (serviceMetaExtrasPrefix != null && serviceMeta != null) {
            serviceMeta.getUnknownProperties().forEach((k, v) -> {
                if (k.startsWith(serviceMetaExtrasPrefix)) {
                    serviceMeta.addExtraServiceMeta(k.replace(serviceMetaExtrasPrefix, ""), v);
                }
            });
        }

        if (serviceMeta != null) {
            Map<String, String> multiInstances = new HashMap<>();
            serviceMeta.getUnknownProperties().forEach((k, v) -> {
                if (k.startsWith(ServiceCapiInstanceMapper.SERVICE_CAPI_INSTANCE_PREFIX)) {
                    multiInstances.put(k, v);
                }
            });
            if (!multiInstances.isEmpty()) {
                svc.setServiceCapiInstances(new ServiceCapiInstanceMapper().convert(multiInstances));
            }
        }

        // Q6: ID is always "<name>:<group>" (stable, does not flip with routeGroupFirst)
        svc.setId(serviceName + ":" + group);
        svc.setName(serviceName);
        svc.setRegisteredBy(getClass().getName());
        svc.setMappingList(mappings);
        svc.setServiceMeta(serviceMeta);
        svc.setRoundRobinEnabled(mappings.size() != 1);
        svc.setFailOverEnabled(mappings.size() != 1);

        serviceUtils.validateServiceType(svc);
        applyPerInstanceOverrides(svc);
        setContext(svc);

        return svc;
    }

    private ServiceMeta findServiceMetaForGroup(String group, List<ConsulObject> objects) {
        for (ConsulObject o : objects) {
            if (Objects.equals(getGroup(o), group)) return o.getServiceMeta();
        }
        return null;
    }

    private void applyPerInstanceOverrides(Service svc) {
        if (svc.getServiceCapiInstances() == null || svc.getServiceCapiInstances().getInstances() == null) return;
        ServiceCapiInstances.Instance thisInstance = svc.getServiceCapiInstances().getInstances().get(capiInstanceName);
        if (thisInstance == null) return;

        if (!thisInstance.isAssumeParentSecured()) {
            svc.getServiceMeta().setSecured(thisInstance.isSecured());
        }
        if (!thisInstance.isAssumeParentRouteGroupFirst()) {
            svc.getServiceMeta().setRouteGroupFirst(thisInstance.isRouteGroupFirst());
        }
        if (thisInstance.getOpenApi() != null) {
            svc.getServiceMeta().setOpenApiEndpoint(thisInstance.getOpenApi());
        } else if (thisInstance.isIgnoreOpenApi()) {
            svc.setOpenAPI(null);
        }
        if (thisInstance.getScheme() != null && !thisInstance.getScheme().isEmpty()) {
            svc.getServiceMeta().setScheme(thisInstance.getScheme());
        }
        // Per-instance backend override: repoint every mapping at this instance's
        // ingress. Rebuild the set (hostname/port participate in Mapping.hashCode,
        // so mutating in place would corrupt the HashSet).
        if (thisInstance.getIngress() != null && !thisInstance.getIngress().isEmpty()) {
            Set<Mapping> rebuilt = new HashSet<>();
            for (Mapping m : svc.getMappingList()) {
                serviceUtils.applyIngressToMapping(m, thisInstance.getIngress());
                rebuilt.add(m);
            }
            svc.setMappingList(rebuilt);
            svc.setRoundRobinEnabled(rebuilt.size() != 1);
            svc.setFailOverEnabled(rebuilt.size() != 1);
        }
    }

    private void setContext(Service svc) {
        String group = svc.getServiceMeta().getGroup();
        if (svc.getServiceMeta().isRouteGroupFirst()) {
            svc.setContext("/" + group + "/" + svc.getName());
        } else {
            svc.setContext("/" + svc.getName() + "/" + group);
        }
    }

    // -------------------------------------------------------------------------
    // OpenAPI pre-fetch (parallel)
    // -------------------------------------------------------------------------

    /**
     * Decides which services in this cycle's delta need an OpenAPI fetch and runs
     * them. The rules — matching the pre-rewrite ConsulNodeDiscovery behavior:
     *  - delta.added: needs fetch (new service, no cached spec).
     *  - delta.changed where the open-api endpoint URL itself changed: needs fetch.
     *  - delta.changed where the URL is the same: copy the previously-parsed
     *    OpenAPI from oldSvc to newSvc, no fetch needed.
     * Returns the set of service IDs whose fetch failed — caller drops them from
     * the delta so we don't replace working state with a half-built route.
     */
    private Set<String> prefetchOpenApiForDelta(ServiceDelta delta) {
        Map<String, Service> toFetch = new LinkedHashMap<>();
        for (Service s : delta.added()) {
            toFetch.put(s.getId(), s);
        }
        for (ServiceDelta.ChangedPair p : delta.changed()) {
            String oldEndpoint = p.oldSvc().getServiceMeta() != null
                    ? p.oldSvc().getServiceMeta().getOpenApiEndpoint() : null;
            String newEndpoint = p.newSvc().getServiceMeta() != null
                    ? p.newSvc().getServiceMeta().getOpenApiEndpoint() : null;
            String oldVersion = p.oldSvc().getServiceMeta() != null
                    ? p.oldSvc().getServiceMeta().getVersion() : null;
            String newVersion = p.newSvc().getServiceMeta() != null
                    ? p.newSvc().getServiceMeta().getVersion() : null;
            // A `version` meta bump is the explicit "reload my API definition" signal from
            // service owners. It must force a re-fetch even when the endpoint URL is unchanged
            // — otherwise the cached spec goes stale forever (regressed in 627b6cb, which keyed
            // the reuse decision purely on the URL). Only reuse the cached spec when the URL is
            // unchanged AND the version is unchanged.
            if (Objects.equals(oldEndpoint, newEndpoint)
                    && !serviceUtils.didVersionChange(oldVersion, newVersion)
                    && p.oldSvc().getOpenAPI() != null) {
                p.newSvc().setOpenAPI(p.oldSvc().getOpenAPI());
            } else {
                toFetch.put(p.newSvc().getId(), p.newSvc());
            }
        }
        if (toFetch.isEmpty()) {
            return Set.of();
        }

        // Hold back services that are in mismatch backoff: re-fetching every cycle costs a full
        // download AND parse, and a service whose owner simply forgot to bump info.version would
        // pay that forever. They stay failed (so the delta keeps the previous working state) and
        // stay listed, they are just not re-downloaded yet.
        Set<String> failed = new HashSet<>();
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Service>> it = toFetch.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Service> entry = it.next();
            VersionMismatch mismatch = versionMismatches.get(entry.getKey());
            if (mismatch != null && now.isBefore(mismatch.nextAttemptAt())) {
                recordVersionMismatch(entry.getValue(), mismatch.detail(), mismatch);
                failed.add(entry.getKey());
                it.remove();
            }
        }

        if (!toFetch.isEmpty()) {
            failed.addAll(prefetchOpenApiSpecs(toFetch));
        }
        return failed;
    }

    /**
     * Records (or re-records) a failed {@code match-openapi-version} check.
     *
     * <p>The service is left on its previous spec — the caller adds it to the failed set, the
     * delta drops it, and the cached route keeps serving. Because the cached {@code version} meta
     * is also left untouched, the next cycle still sees a version change and retries: the rollout
     * converges on its own once every pod serves the new spec.
     *
     * <p>{@code detail} carries both versions so a mismatch is diagnosable from
     * {@code /info/invalid-services} alone, and the entry reports how long it has been failing —
     * seconds means a rollout in flight, hours means the owner set the flag without maintaining
     * {@code info.version}.
     */
    private void recordVersionMismatch(Service service, String detail, VersionMismatch previous) {
        Instant now = Instant.now();
        Instant firstSeen = previous != null ? previous.firstSeen() : now;
        int consecutive = previous != null ? previous.consecutive() + 1 : 1;
        Instant nextAttemptAt = consecutive >= MISMATCH_ATTEMPTS_BEFORE_BACKOFF
                ? now.plus(MISMATCH_BACKOFF)
                : now;
        versionMismatches.put(service.getId(),
                new VersionMismatch(firstSeen, consecutive, nextAttemptAt, detail));

        Duration failingFor = Duration.between(firstSeen, now);
        String message = detail + " — keeping the previous spec, failing for "
                + failingFor.toSeconds() + "s over " + consecutive + " attempt(s)";
        invalidServiceMap.put(service.getId(), new InvalidService(
                service.getId(),
                service.getServiceMeta().getGroup(),
                service.getServiceMeta().getOpenApiEndpoint(),
                InvalidService.Reason.OPENAPI_VERSION_MISMATCH,
                message,
                now));

        if (consecutive == 1) {
            log.info("OpenAPI version check failed for {} ({}). Keeping the previous spec; " +
                    "this is expected while a rolling deploy is in flight.", service.getId(), detail);
        } else if (consecutive == MISMATCH_ATTEMPTS_BEFORE_BACKOFF) {
            log.warn("OpenAPI version check still failing for {} after {} attempts ({}). Backing off " +
                    "re-fetch to every {} minutes. If this persists, the service sets " +
                    "match-openapi-version but does not keep info.version in step with its version meta.",
                    service.getId(), consecutive, detail, MISMATCH_BACKOFF.toMinutes());
        }
    }

    /**
     * Returns the IDs of services whose fetch or parse failed. The caller is
     * responsible for evicting them from the delta.
     */
    private Set<String> prefetchOpenApiSpecs(Map<String, Service> services) {
        Set<String> failed = new HashSet<>();
        Map<Service, CompletableFuture<HttpResponse<String>>> futures = new LinkedHashMap<>();
        for (Service svc : services.values()) {
            if (serviceUtils.needsOpenApiFetch(svc)) {
                try {
                    futures.put(svc, httpClient.sendAsync(serviceUtils.buildOpenApiRequest(svc), BodyHandlers.ofString()));
                } catch (Exception e) {
                    invalidServiceMap.put(svc.getId(), new InvalidService(
                            svc.getId(),
                            svc.getServiceMeta().getGroup(),
                            svc.getServiceMeta().getOpenApiEndpoint(),
                            InvalidService.Reason.OPENAPI_FETCH_FAILED,
                            e.getMessage(),
                            Instant.now()));
                    failed.add(svc.getId());
                    log.warn("Failed to build OpenAPI request for {}: {}", svc.getId(), e.getMessage());
                }
            }
        }
        for (Map.Entry<Service, CompletableFuture<HttpResponse<String>>> e : futures.entrySet()) {
            try {
                HttpResponse<String> response = e.getValue().get(PER_REQUEST_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
                if (!serviceUtils.processOpenApiSpec(e.getKey(), response)) {
                    invalidServiceMap.put(e.getKey().getId(), new InvalidService(
                            e.getKey().getId(),
                            e.getKey().getServiceMeta().getGroup(),
                            e.getKey().getServiceMeta().getOpenApiEndpoint(),
                            InvalidService.Reason.OPENAPI_INVALID_SPEC,
                            "Failed to build OpenAPI, invalid spec.",
                            Instant.now()));
                    failed.add(e.getKey().getId());
                } else {
                    // The spec parsed — but is it the one this version announces? Opt-in only.
                    String mismatch = serviceUtils.openApiVersionMismatch(e.getKey());
                    if (mismatch != null) {
                        recordVersionMismatch(e.getKey(), mismatch, versionMismatches.get(e.getKey().getId()));
                        failed.add(e.getKey().getId());
                    } else {
                        versionMismatches.remove(e.getKey().getId());
                    }
                }
            } catch (Exception ex) {
                invalidServiceMap.put(e.getKey().getId(), new InvalidService(
                        e.getKey().getId(),
                        e.getKey().getServiceMeta().getGroup(),
                        e.getKey().getServiceMeta().getOpenApiEndpoint(),
                        InvalidService.Reason.OPENAPI_FETCH_FAILED,
                        ex.getMessage(),
                        Instant.now()));
                log.warn("Failed to fetch OpenAPI spec for {}: {}", e.getKey().getId(), ex.getMessage());
                failed.add(e.getKey().getId());
            }
        }
        return failed;
    }

    /** Removes services whose OpenAPI fetch failed from added/changed lists. The
     *  cached entry (if any) is left in place, so a changed service whose new
     *  spec failed keeps serving with its previous spec until the next cycle. */
    private ServiceDelta filterFailedFetches(ServiceDelta delta, Set<String> failed) {
        List<Service> filteredAdded = delta.added().stream()
                .filter(s -> !failed.contains(s.getId()))
                .toList();
        List<ServiceDelta.ChangedPair> filteredChanged = delta.changed().stream()
                .filter(p -> !failed.contains(p.newSvc().getId()))
                .toList();
        return new ServiceDelta(filteredAdded, filteredChanged, delta.gone());
    }

    // -------------------------------------------------------------------------
    // Reconcile (§7.1 + §7.2 guards)
    // -------------------------------------------------------------------------

    private ServiceDelta reconcile(Map<String, Service> incoming, CycleResult result) {
        List<Service> added = new ArrayList<>();
        List<ServiceDelta.ChangedPair> changed = new ArrayList<>();
        for (Service in : incoming.values()) {
            Service cached = serviceCache.peek(in.getId());
            if (cached == null) {
                added.add(in);
            } else if (serviceUtils.didServiceChange(cached, in)) {
                changed.add(new ServiceDelta.ChangedPair(cached, in));
            }
        }

        if (!result.clean()) {
            // §7.1: partial view of Consul → skip removal phase entirely to preserve live routes.
            log.warn("Consul discovery cycle had {} failed lookup(s) across {} host(s); skipping route cleanup",
                    result.totalFailedLookups() + result.hostsFailed(), result.hostsTotal());
            return new ServiceDelta(added, changed, List.of());
        }

        List<Service> gone = new ArrayList<>();
        for (CacheEntry<String, Service> e : serviceCache.entries()) {
            if (incoming.containsKey(e.getKey())) continue;
            Service cached = serviceCache.get(e.getKey());
            if (cached == null) continue;
            if (result.emptyFromConsul().contains(cached.getName())) {
                // §7.2: Consul returned empty per-service response for this name; preserve.
                log.debug("Preserving {} — Consul returned empty for name {}", e.getKey(), cached.getName());
                continue;
            }
            gone.add(cached);
        }
        return new ServiceDelta(added, changed, gone);
    }

    // -------------------------------------------------------------------------
    // Apply (single-writer: scheduler thread only)
    // -------------------------------------------------------------------------

    private void apply(ServiceDelta delta) {
        for (Service s : delta.added()) {
            serviceCache.put(s.getId(), s);
            for (TransportHandler h : transportHandlers) {
                if (h.supports(s)) h.onAppear(s);
            }
        }
        for (ServiceDelta.ChangedPair p : delta.changed()) {
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
        // All onAppear / onChange / onDisappear have run; each handler now publishes
        // its post-cycle snapshot atomically. Readers (RestGateway) flip from the
        // previous cycle's snapshot to this one in a single volatile-store.
        for (TransportHandler h : transportHandlers) {
            h.afterCycle();
        }
    }

    // -------------------------------------------------------------------------
    // Readiness latch + summary
    // -------------------------------------------------------------------------

    private void maybeFlipReadinessLatch(CycleResult result) {
        if (!connectedToConsul && result.clean()) {
            connectedToConsul = true;
            log.info("Consul readiness latch flipped true — node is ready to serve");
        }
    }

    private void emitSummary(CycleResult result, ServiceDelta delta, long durationMs) {
        log.info("consul-cycle hosts={} hostsFailed={} failedLookups={} added={} changed={} gone={} cleanupSkipped={} durationMs={}",
                result.hostsTotal(),
                result.hostsFailed(),
                result.totalFailedLookups(),
                delta.added().size(),
                delta.changed().size(),
                delta.gone().size(),
                !result.clean(),
                durationMs);
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private HttpRequest buildCatalogRequest(CAPIConfiguration.HostConfig host) {
        URI uri = URI.create(host.getEndpoint() + GET_ALL_SERVICES);
        validatePath(uri);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(PER_REQUEST_TIMEOUT);
        addAuth(b, host);
        return b.build();
    }

    private HttpRequest buildPerServiceRequest(CAPIConfiguration.HostConfig host, String serviceName) {
        URI uri = URI.create(host.getEndpoint() + GET_SERVICE_BY_NAME + serviceName);
        validatePath(uri);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(PER_REQUEST_TIMEOUT);
        addAuth(b, host);
        return b.build();
    }

    private void validatePath(URI uri) {
        if (uri.getPath() != null && uri.getPath().contains("..")) {
            throw new IllegalArgumentException("Path traversal detected in URI path: " + uri.getPath());
        }
    }

    private void addAuth(HttpRequest.Builder builder, CAPIConfiguration.HostConfig host) {
        if (host.getToken() != null && !host.getToken().isEmpty()) {
            builder.header(Constants.AUTHORIZATION_HEADER,
                    Constants.BEARER + host.getToken().replaceAll("(\r\n|\n)", ""));
        }
    }

    private CompletableFuture<HttpResponse<String>> sendWithGoawayRetry(HttpRequest req) {
        return httpClient.sendAsync(req, BodyHandlers.ofString())
                .exceptionallyCompose(ex -> {
                    String msg = unwrap(ex).getMessage();
                    if (msg != null && msg.contains("GOAWAY")) {
                        log.debug("Retrying after GOAWAY for {}", req.uri());
                        return httpClient.sendAsync(req, BodyHandlers.ofString());
                    }
                    return CompletableFuture.failedFuture(ex);
                });
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}