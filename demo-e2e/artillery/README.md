# Artillery suites for CAPI

These complement the k6 throughput baseline (`helm/capi-load-test.js`). They
target two areas k6 doesn't cover well for a gateway:

| Suite | What it proves | Engine |
|-------|----------------|--------|
| `websocket-timeout.yml` | WS connection-scaling load (many concurrent sockets) | `ws` |
| `ws-idle-probe.js` | The actual WS idle-timeout *measurement* (Artillery can't — see Findings) | `ws` lib |
| `sse-timeout.yml` | Long-lived SSE streams hit the ~180s wall-clock cutoff | `http` |
| `auth-combinations.yml` | Functional regression gate: every auth mode + combinations are enforced (wrong identity rejected), incl. the dual apiKey+secured **bypass-bug** guard | `http` |

## Findings (verified against real CAPI, demo-e2e, 2026-06)

These came out of actually running the suites; the configs encode them:

- **API-key header is `Authorization: ApiKey <key>`** (200). `X-API-Key` is rejected (403). The k6 script (`helm/capi-load-test.js`) uses `X-API-Key` — that's wrong against this build.
- **Throttled API keys return `403`, not `429`.** A 3/30s key gives 3×200 then 403. (k6 also wrongly assumes 429.)
- **Unknown API key → 403** (no fall-back to open access).
- **Secured + OPA route with no token → 403** (empty roles denied by OPA), not 401.
- **SSE timeout is a hard wall-clock ~180s** — the 2s backend events do NOT reset it. CAPI closes via TCP **RST** (Artillery logs `ECONNRESET` + marks the vuser failed; that reset *is* the expected timeout, see `sse-timeout.yml`).
- **WebSocket is NOT closed at 180s when frames flow.** An idle-but-PONG-responsive WS connection (backend pings every 3s) stays open indefinitely (verified to 235s). Measure this with `ws-idle-probe.js`, not the Artillery suite — Artillery's ws engine can't see a close that happens during a `think`.
- **Artillery's `ws` engine does not template `connect` headers** (`{{ }}` and `$processEnvironment` are sent verbatim → 403). `run-ws-timeout.sh` injects a literal token via a placeholder.

Keep using k6 for raw RPS/saturation baselining — Artillery's per-VU overhead
is higher. These are for protocol-shape and correctness-under-load coverage.

## Install

```bash
npm i -g artillery artillery-plugin-expect artillery-plugin-ensure \
         artillery-plugin-metrics-by-endpoint
# or prefix each run with: npx artillery@latest ...

# ws-idle-probe.js needs the ws library:
npm i ws
```

## Prereqs

demo-e2e stack up, services registered, Keycloak seeded:

```bash
cd demo-e2e
docker compose up -d
./register-services.sh
./setup-keycloak.sh
```

## Run

```bash
cd demo-e2e/artillery

artillery run auth-combinations.yml      # functional gate (exit != 0 on failure)
./run-ws-timeout.sh                       # WS connection-scaling load (mints token, ~200s)
./run-ws-timeout.sh --quick               # ~4 conns, 8s — just verifies the handshake
node ws-idle-probe.js                      # measure the WS idle timeout (auto-PONG, ~235s)
artillery run sse-timeout.yml            # SSE ~180s wall-clock close (expect ECONNRESET)
```

> `websocket-timeout.yml` can't be run directly with a templated token (the ws
> engine ignores header templating) — use `run-ws-timeout.sh`, which injects a
> real token into the `__WS_TOKEN__` placeholder.

Override host(s) for EKS/remote ALBs:

```bash
artillery run -t ws://my-ws-alb:8382 websocket-timeout.yml
artillery run -t http://my-rest-alb:8380 auth-combinations.yml
```

## Config (env vars, all optional — defaults match demo-e2e)

| Var | Default | Used for |
|-----|---------|----------|
| `KC_URL` | `http://localhost:8080/keycloak` | Keycloak base (incl. context path) |
| `KC_REALM` | `e2e-demo` | realm |
| `PREMIUM_CLIENT_SECRET` | `changeme` | `client-premium` resource-owner grant |
| `BASIC_CLIENT_SECRET` | `changeme` | `client-basic` grant |
| `ALICE_PASSWORD` / `BOB_PASSWORD` / `CHARLIE_PASSWORD` | `changeme` | demo users |
| `DUAL_PATH` | _(unset)_ | path of a dual apiKey+secured route (see below) |

## Two things to reconcile before trusting results

1. **API-key header.** These configs use `Authorization: ApiKey <key>`
   (matches `Constants.java:63` + demo curl examples). The k6 script uses
   `X-API-Key`. They disagree — confirm which your build accepts and fix one.

2. **Dual apiKey+secured route.** demo-e2e ships no route that is *both*
   key-enabled and OAuth-secured, so the bypass-bug regression scenarios in
   `auth-combinations.yml` are templates with `weight: 0`. To enable: register
   a route that keeps `secured: true` **and** has a `capi-api-keys/<svc>:<grp>`
   entry, then `export DUAL_PATH=/api/<that-route>/...` and bump their weights.
   The asserted contract: no creds -> 401, key-only -> 401, bearer -> 200.