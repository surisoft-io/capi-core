# Configuration Reference

CAPI is configured via a YAML file. Set the `CAPI_CONFIG_FILE` environment variable to point to it.

## Complete Configuration

```yaml
capi:
  version: 1.0.0
  instanceName: default
  strictToInstanceName: false
  publicEndpoint: http://localhost:8380/api/
  runningMode: full
  adminPort: 8381
  reverseProxyHost:

  admin:
    protected: true
    group: capi-admin

  matchOpenApiSpec:
    enabled: false
    exempt: []

  apiKeyStore:
    enabled: false

  rest:
    enabled: true
    port: 8380
    listeningAddress: 0.0.0.0
    contextPath: /api
    rejectDotSegments: false
    connectionRequestTimeout: 5000
    requestTimeout: 5000
    responseTimeout: 120000
    proxyPoolSize: 200
    proxyMaxPoolSize: 500

  websocket:
    enabled: false
    port: 8382
    listeningAddress: 0.0.0.0
    contextPath: /capi/*

  grpc:
    enabled: false
    port: 8384

  ssl:
    enabled: false
    keyStoreType: PKCS12
    path:
    password:

  trustStore:
    enabled: false
    path:
    encoded:
    password:

  consulCatalogDiscoverInterval: 60000
  consulHosts:
    - endpoint: http://localhost:8500
      token:
  consulStore:
    enabled: false
    endpoint: http://localhost:8500
    token:

  oauth2:
    enabled: false
    cookieName:
    keys:
      - http://localhost:8080/realms/capi/protocol/openid-connect/certs

  opa:
    enabled: false
    wasmBundleUrl:
    wasmBundleToken:
    wasmBundlePollIntervalSeconds: 60
    wasmPoolSize: 10

  traces:
    enabled: false
    serviceName: capi
    endpoint: http://localhost:4318
    extraMetadataPrefix:

  corsEnabled: false
  allowedOrigins: []
  allowedHeaders:
    - Origin
    - Accept
    - X-Requested-With
    - Content-Type
    - Access-Control-Request-Method
    - Authorization

  loggingTraces:
    enabled: false
    tenant: capi
    appName: capi
    appEnvironment: dev
    destination: localhost:5444
    filePath: /var/log/capi/capi.log

  accessLogs:
    enabled: false
    tenant: capi
    service: capi
    destination: localhost:5444
    filePath: /var/log/capi/capi-access.log

  throttle:
    enabled: false
    kubernetesNamespace:
    kubernetesServiceName:
```

## Fields

### General

| Field | Default | Description |
|-------|---------|-------------|
| `version` | — | CAPI version identifier (informational). |
| `instanceName` | — (`default` in the shipped config) | Name of this CAPI instance, matched against the `capi-instance` service metadata. **Must not contain a hyphen** — per-instance override keys are split at the first hyphen, so a hyphenated name silently voids every `capi-instance-<name>-<property>` override. See [Consul metadata](consul-metadata.md#multi-instance-targeting). |
| `strictToInstanceName` | `false` | Controls services that declare **no** instance metadata: `true` ignores them, `false` routes them on every instance. Has no effect on services that declare `capi-instance` or a `capi-instance-<name>-<property>` key — those are always matched by name. |
| `publicEndpoint` | — | The externally-reachable URL of this gateway. Used for OpenAPI spec URL rewriting. |
| `runningMode` | `full` | Service types to proxy: `full` (REST + WebSocket + SSE), `websocket`, or `sse`. |
| `adminPort` | `8381` | Port for the Admin API (health, metrics, routes). Bound on `0.0.0.0`. See [Admin listener](#admin-listener) for authentication. |
| `reverseProxyHost` | — | Override the `X-Forwarded-Host` header sent to upstream services. |

### Admin listener

Authentication on the `adminPort` listener.

```yaml
capi:
  admin:
    protected: true
    group: capi-admin
```

| Field | Default | Description |
|-------|---------|-------------|
| `admin.protected` | *unset* | Require a bearer token on every admin endpoint except `/info/health`. |
| `admin.group` | — | Subscription group the token must carry. **Required when `protected: true`.** |

`protected` is a nullable boolean, so "absent" and "explicitly false" are different states:

| Config | Behaviour |
|---|---|
| no `admin:` block | Unprotected, with a startup `WARN`. Pre-2.23 behaviour, so an upgrade never crash-loops. |
| `protected: true` + `group` | Protected — token required. |
| `protected: true`, no `group` | **CAPI refuses to start**, naming `admin.group`. Serving the listener open would contradict the stated intent. |
| `protected: false` | Unprotected, with a startup `WARN`. |

- The token is validated by the same `oauth2` key set as the data plane, and checked against the
  **`subscriptions` claim** — roles / `realm_access` are not consulted. So `oauth2.enabled` must be
  true for the check to ever pass.
- `/info/health` is the only exempt path: a liveness probe cannot carry a token, and a 401 there
  would pull the instance out of rotation. **`/info/metrics` is not exempt** — a Prometheus scrape
  must send a token.
- Codes: **401** for a missing or unparseable token, **403** for a valid token in the wrong group.
- **Trust-store writes ignore the opt-out.** `PUT /info/truststore` and
  `DELETE /info/truststore/{alias}` return **403** on an unprotected listener whatever `protected`
  says: a certificate added there is pushed to Consul KV and reloaded by every instance in the
  cluster, so it is a fleet-wide MITM primitive. Reads are unaffected. See
  [Admin API — Truststore](admin-api.md#truststore).
- Enforced by one wrapper around the path handler, not per endpoint, so a new admin endpoint is
  protected by default.

### OpenAPI spec identity

Governance check binding a fetched spec to the service that declared it.

```yaml
capi:
  matchOpenApiSpec:
    enabled: false
    exempt:
      - legacy-service
```

| Field | Default | Description |
|-------|---------|-------------|
| `matchOpenApiSpec.enabled` | `false` | Hold a service out of routing when its spec does not identify it. |
| `matchOpenApiSpec.exempt` | — | Consul service **names** that keep routing while enforcing. |

The rule: the fetched spec's `info.title` must equal the Consul service **name** exactly (trimmed, no
case or separator folding) and `info.version` must be non-blank. Matched on name rather than
`name:group`, since one spec legitimately describes an API registered into several groups.

**The verdict is computed on every discovery cycle whether or not `enabled` is true**, and published
at [`GET /info/spec-compliance`](admin-api.md#spec-compliance). That is the migration instrument:
watch `nonCompliant` reach zero (`safeToEnable: true`), then turn the switch on. Nothing is logged
while it is off, so enabling the report cannot make an existing deployment noisier.

While enforcing, a non-compliant service is listed in `/info/invalid-services` with reason
`OPENAPI_IDENTITY_MISMATCH` — the same handling as an unparseable spec. It is deliberately not routed
spec-less, which would silently disable its operation-security gate.

`exempt` is operator config rather than ServiceMeta on purpose: an exemption a service owner could
grant themselves is not governance. Exempt services stay listed with their mismatch, so the debt
remains visible, and they do not block `safeToEnable`.

Distinct from the per-service `match-openapi-version` metadata key, which guards the rolling-deploy
race (a new pod bumps `version` while the load balancer still serves the spec from an old pod). This
checks identity; that checks staleness. Both can be on.

### API key store

```yaml
capi:
  apiKeyStore:
    enabled: false
```

| Field | Default | Description |
|-------|---------|-------------|
| `apiKeyStore.enabled` | `false` | Enable the API-key gate, with keys held in Consul KV. Services opt in with the `api-key` metadata key. |

### REST

| Field | Default | Description |
|-------|---------|-------------|
| `rest.enabled` | `true` | Enable the REST gateway. |
| `rest.port` | `8380` | Listening port. |
| `rest.listeningAddress` | `0.0.0.0` | Bind address. |
| `rest.contextPath` | `/api` | Base path for all REST routes. |
| `rest.rejectDotSegments` | `false` | Refuse a request whose path contains a `.` or `..` **segment** with `400`. While false the request is forwarded exactly as before and only counted, via `capi_dot_segment_requests_total{action="observed"}` — watch that stay at zero on real traffic, then turn it on. Segment-wise, so ordinary paths like `/range/1..10` or `/report..pdf` are unaffected. See [Security](security.md#path-handling). |
| `rest.connectionRequestTimeout` | `5000` | Time (ms) to obtain a connection from the pool. |
| `rest.requestTimeout` | `5000` | Total request timeout (ms). |
| `rest.responseTimeout` | `120000` | Time (ms) to wait for a response from the backend. |
| `rest.proxyPoolSize` | `200` | Core size of the proxy connection pool. Controls how many concurrent backend connections can be maintained. |
| `rest.proxyMaxPoolSize` | `500` | Maximum proxy connection pool size. |

### WebSocket

| Field | Default | Description |
|-------|---------|-------------|
| `websocket.enabled` | `false` | Enable the WebSocket/SSE gateway. |
| `websocket.port` | `8382` | Listening port. |
| `websocket.listeningAddress` | `0.0.0.0` | Bind address. |
| `websocket.contextPath` | `/api/*` | Path pattern for WebSocket routes. |
| `websocket.enforceOriginCheck` | `false` | Refuse a WebSocket upgrade whose `Origin` is not allowlisted, guarding cross-site WebSocket hijacking. Shares the CORS allowlist (`allowedOrigins` + per-service `allowed-origins`). A request with **no** `Origin` is always allowed, since browsers always send one and its absence means a non-browser client. While false the upgrade proceeds and is only counted, via `capi_websocket_origin_total{action="observed"}`. See [Security](security.md#websocket-origins). |

### gRPC

| Field | Default | Description |
|-------|---------|-------------|
| `grpc.enabled` | `false` | Enable the gRPC Gateway (HTTP/2 reverse proxy). |
| `grpc.port` | `8384` | Listening port. |

See [gRPC Gateway](grpc-gateway.md) for details on header-based routing and service registration.

### SSL

| Field | Default | Description |
|-------|---------|-------------|
| `ssl.enabled` | `false` | Enable TLS termination on all listeners. |
| `ssl.keyStoreType` | `PKCS12` | Keystore format. |
| `ssl.path` | — | Path to the keystore file. |
| `ssl.password` | — | Keystore password. |

### Truststore

| Field | Default | Description |
|-------|---------|-------------|
| `trustStore.enabled` | `false` | Enable a custom truststore for upstream connections. |
| `trustStore.path` | — | Path to the truststore file. |
| `trustStore.encoded` | — | Base64-encoded truststore content (alternative to path). |
| `trustStore.password` | — | Truststore password. |

### Consul

| Field | Default | Description |
|-------|---------|-------------|
| `consulCatalogDiscoverInterval` | `60000` | Interval (ms) between Consul catalog polls. |
| `consulHosts[].endpoint` | — | Consul agent HTTP endpoint (e.g. `http://consul:8500`). Multiple hosts supported. |
| `consulHosts[].token` | — | Consul ACL token for this host. |
| `consulStore.enabled` | `false` | Enable Consul KV store integration. |
| `consulStore.endpoint` | — | Consul KV store endpoint. |
| `consulStore.token` | — | Consul KV store token. |

### OAuth2

| Field | Default | Description |
|-------|---------|-------------|
| `oauth2.enabled` | `false` | Enable OAuth2/OIDC token validation. |
| `oauth2.cookieName` | — | Cookie name to extract tokens from (for browser clients). |
| `oauth2.keys` | — | List of JWKS endpoint URLs. Multiple providers supported. |

See [Security](security.md) for details.

### OPA

| Field | Default | Description |
|-------|---------|-------------|
| `opa.enabled` | `false` | Enable OPA authorization. |
| `opa.wasmBundleUrl` | — | URL of the `.tar.gz` Wasm policy bundle, re-fetched on ETag change. |
| `opa.wasmBundleToken` | — | Optional bearer token for the bundle server. |
| `opa.wasmBundlePollIntervalSeconds` | `60` | Bundle poll interval. |
| `opa.wasmPoolSize` | `10` | Size of the shared policy-instance pool. |

> **`opa.endpoint` does not exist.** This page previously listed it. CAPI evaluates Rego compiled to
> WebAssembly in-process and has no HTTP OPA mode; setting `endpoint` makes CAPI **fail to start**,
> because unknown configuration properties are rejected rather than ignored.

See [Security](security.md) for details.

### Tracing

| Field | Default | Description |
|-------|---------|-------------|
| `traces.enabled` | `false` | Enable OpenTelemetry distributed tracing. |
| `traces.serviceName` | `capi` | Service name reported in traces. |
| `traces.endpoint` | — | OTLP HTTP endpoint (e.g. `http://otel-collector:4318`). |
| `traces.extraMetadataPrefix` | — | Prefix for extracting extra service metadata from Consul into trace attributes. |

### CORS

| Field | Default | Description |
|-------|---------|-------------|
| `corsEnabled` | `false` | **Master switch.** While false CAPI sends no CORS header at all, and both `allowedOrigins` and any per-service `allowed-origins` metadata are ignored. |
| `allowedOrigins` | — (**deny all**) | Gateway-wide fallback list of origins allowed CORS headers. Exact match on scheme+host+port. `["*"]` sends the literal wildcard and never credentials. Applies only to services that declare no `allowed-origins` of their own. |
| `allowedHeaders` | — | List of allowed request headers (preflight `Access-Control-Allow-Headers`). |

> **Changed in 2.23.** `corsEnabled` was previously reporting-only — it appeared in `/info/capi` but
> gated nothing, so a config reading `corsEnabled: false` still served
> `Access-Control-Allow-Origin` reflecting whatever `Origin` the caller sent, together with
> `Access-Control-Allow-Credentials: true`. It is now enforced, and origins must be allowlisted.
> A browser client calling CAPI cross-origin will stop working until its origin is listed.

See [Security](security.md) for details.

### Logging

| Field | Default | Description |
|-------|---------|-------------|
| `loggingTraces.enabled` | `false` | Enable structured log forwarding. |
| `loggingTraces.tenant` | — | Tenant identifier in logs. |
| `loggingTraces.appName` | — | Application name in logs. |
| `loggingTraces.appEnvironment` | — | Environment label (dev, staging, prod). |
| `loggingTraces.destination` | — | Remote log destination (host:port). |
| `loggingTraces.filePath` | — | Path for rolling app log file (e.g. `/var/log/capi/capi.log`). Rotation: 100MB per file, 30 days, 3GB cap. |
| `accessLogs.enabled` | `false` | Enable access log forwarding. |
| `accessLogs.tenant` | — | Tenant identifier. |
| `accessLogs.service` | — | Service identifier. |
| `accessLogs.destination` | — | Remote log destination (host:port). |
| `accessLogs.filePath` | — | Path for rolling access log file (e.g. `/var/log/capi/capi-access.log`). Rotation: 100MB per file, 30 days, 3GB cap. |

### Throttling

| Field | Default | Description |
|-------|---------|-------------|
| `throttle.enabled` | `false` | Enable distributed rate limiting via Hazelcast. |
| `throttle.kubernetesServiceName` | — | Kubernetes Service name for Hazelcast pod discovery. If empty, multicast is used. |
| `throttle.kubernetesNamespace` | — | Kubernetes namespace for Hazelcast discovery. Defaults to the pod's own namespace. |

Per-service throttle settings are configured via Consul metadata. See [Service Registration](consul-metadata.md) for details.

### MCP

| Field | Default | Description |
|-------|---------|-------------|
| `mcp.enabled` | `false` | Enable the MCP Gateway. |
| `mcp.port` | `8383` | Listening port. |
| `mcp.sessionTtl` | `1800000` | MCP session TTL (ms). Sessions are evicted on inactivity. **Only applies to `2025-03-26` clients** — protocol revision `2026-07-28` is stateless and mints no sessions. |
| `mcp.maxRequestSize` | `1048576` | Largest JSON-RPC request body, in bytes. Enforced by Undertow before the handler runs. The body is buffered whole **before the request is authenticated**, so leaving it unbounded let a single unauthenticated POST exhaust the heap. |
| `mcp.maxSessions` | `10000` | Cap on concurrent sessions. Sessions live for `sessionTtl`, so without a cap a client can mint them faster than they expire; past the cap `initialize` returns `503`. |
| `mcp.toolCallTimeout` | `30000` | Per-tool-call backend timeout (ms). Overridable per tool via Consul metadata. |
| `mcp.circuitBreakerCooldownMs` | `30000` | Cooldown (ms) before re-trying a failed backend in the per-tool load balancer. |
| `mcp.mcpServerDiscoveryTimeoutMs` | `10000` | Timeout (ms) for the JSON-RPC `initialize` + `tools/list` probe used to discover tools from upstream MCP servers. |
| `mcp.authorizationServers` | — (derived) | Issuer URLs advertised as `authorization_servers` in the RFC 9728 protected-resource metadata served at `/.well-known/oauth-protected-resource` on the MCP port. When unset, derived from `oauth2.keys` by trimming the usual JWKS suffixes (covers Keycloak, Okta, Entra and plain `/.well-known/jwks.json`). Set explicitly if your provider uses a different layout. |
| `mcp.observability.genAi.enabled` | `false` | Emit OpenTelemetry GenAI semconv spans (`gen_ai.system=mcp`, `gen_ai.operation.name`, `gen_ai.tool.name`, `capi.outcome`, …) for every MCP request. Requires `traces.enabled: true`. See [MCP Gateway → Observability](mcp-gateway.md#observability-opentelemetry-genai) for the full attribute list and span model. |

See [MCP Gateway](mcp-gateway.md) for the full design, wire protocol and Consul metadata extensions.

## Ports Summary

| Port | Description | Config Key |
|------|-------------|------------|
| 8380 | REST gateway | `capi.rest.port` |
| 8381 | Admin API | `capi.adminPort` |
| 8382 | WebSocket/SSE gateway | `capi.websocket.port` |
| 8383 | MCP Gateway | `capi.mcp.port` |
| 8384 | gRPC Gateway | `capi.grpc.port` |

## Reverse Proxy Headers

CAPI automatically sets the following headers on proxied requests:

| Header | Value |
|--------|-------|
| `X-Forwarded-Host` | Original client host (or `reverseProxyHost` if configured) |
| `X-Forwarded-Prefix` | Service context path prefix |
| `X-Forwarded-For` | Client IP address |
| `X-Forwarded-Proto` | Protocol scheme |
| `X-Forwarded-Server` | CAPI server hostname |
| `X-Forwarded-Port` | CAPI server port |
