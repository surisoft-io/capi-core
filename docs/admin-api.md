# Admin API

The Admin API runs on a dedicated port (default `8381`) and provides health checks, metrics, route inspection, and OpenAPI spec retrieval. It is separate from the main gateway traffic.

## Discovery Endpoint

```bash
curl http://localhost:8381/info
```

Returns links to all available admin endpoints:

```json
{
  "_links": {
    "metrics": { "href": "http://localhost:8381/info/metrics" },
    "health": { "href": "http://localhost:8381/info/health" },
    "capi": { "href": "http://localhost:8381/info/capi" },
    "routes": { "href": "http://localhost:8381/info/routes" },
    "invalid-services": { "href": "http://localhost:8381/info/invalid-services" },
    "openapi": { "href": "http://localhost:8381/info/openapi/{serviceId}" },
    "truststore": { "href": "http://localhost:8381/info/truststore" },
    "spec-compliance": { "href": "http://localhost:8381/info/spec-compliance" },
    "wsroutes": { "href": "http://localhost:8381/info/wsroutes" },
    "mcp": { "href": "http://localhost:8381/info/mcp" },
    "mcp-tools": { "href": "http://localhost:8381/info/mcp/tools" },
    "mcp-sessions": { "href": "http://localhost:8381/info/mcp/sessions" }
  }
}
```

## Endpoints

### Health

```bash
curl http://localhost:8381/info/health
```

Returns `200` with `{"status":"UP"}` when connected to Consul, or `503` with `{"status":"DOWN"}` otherwise.

Used as the Kubernetes **liveness probe** in the Helm chart.

There is also a readiness probe on the REST port:

```bash
curl http://localhost:8380/health
```

### Metrics

```bash
curl http://localhost:8381/info/metrics
```

Returns Prometheus-formatted metrics (`text/plain`). Configure your Prometheus scrape target to point at this endpoint.

CAPI tracks per-route request counters and standard JVM metrics.

| Metric | Labels | Meaning |
|---|---|---|
| `capi_requests_total` | `service`, `method`, `status`, `status_group` | Requests per route |
| `capi_request_duration` | `service`, `method` | Request latency |
| `capi_dot_segment_requests_total` | `service`, `action` | Requests whose path contained a `.`/`..` segment. `action` is `observed` (forwarded — `rest.rejectDotSegments` off) or `rejected` (400). See [Security — Path handling](security.md#path-handling). |

> **Changed in 2.23 — the `method` label is now bounded.** Undertow accepts arbitrary HTTP method
> tokens, and Micrometer keeps one time series per distinct label value for the life of the process,
> so tagging with the raw method let an unauthenticated caller grow the registry without limit. Any
> method outside `GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE` is now recorded as `OTHER`.
> A dashboard or alert that filters on an unusual method label needs updating.

### CAPI Instance Info

```bash
curl http://localhost:8381/info/capi
```

Returns instance configuration and runtime information:
- CAPI version
- Instance name
- Uptime
- Number of active routes
- `invalidServiceCount` — services this instance could not register in the last discovery cycle (see [Invalid Services](#invalid-services))
- OAuth2 configuration (enabled, JWKS endpoints)
- Consul hosts
- Tracing configuration
- REST context path

### Routes

List all managed routes:

```bash
curl http://localhost:8381/info/routes
```

Get a specific route by service ID:

```bash
curl http://localhost:8381/info/routes/order-service:v1
```

Returns the full service object including mappings (upstream instances), metadata, load balancing configuration, and OpenAPI status.

### Invalid Services

Services CAPI discovered in Consul but could **not** register in the most recent discovery
cycle:

```bash
curl http://localhost:8381/info/invalid-services
```

```json
[
  {
    "serviceId": "order-service:v1",
    "group": "v1",
    "openApiEndpoint": "http://order-service:8080/openapi.json",
    "reason": "OPENAPI_VERSION_MISMATCH",
    "detail": "meta version 2.0.1, spec info.version 2.0.0 — keeping the previous spec, failing for 47s over 2 attempt(s)",
    "detectedAt": "2026-09-04T11:22:33Z"
  }
]
```

| `reason` | Meaning |
|---|---|
| `OPENAPI_FETCH_FAILED` | The spec could not be retrieved — timeout, connection refused, malformed URL. |
| `OPENAPI_INVALID_SPEC` | Retrieved, but not parseable as OpenAPI, or the endpoint returned a non-200. |
| `OPENAPI_VERSION_MISMATCH` | Parsed, but `info.version` did not match the `version` metadata on a service that set `match-openapi-version`. See [Consul metadata](consul-metadata.md#12-guard-against-stale-specs-during-a-rolling-deploy). |

Two things to know when reading this list:

- **It is rebuilt every cycle.** The registry is cleared at the start of each discovery pass, so
  it always reflects the latest cycle — an entry that disappears has recovered.
- **Listed does not mean unrouted.** A service that previously registered successfully keeps
  serving its last good definition; the entry means *this cycle's update was not applied*.
  Services intentionally filtered out — not declared for this CAPI instance, or not in
  `PUBLISHED` state — are never listed here.

For `OPENAPI_VERSION_MISMATCH`, read the age in `detail`: seconds means a rolling deploy is in
flight and will converge on its own; hours means the flag is set but the spec's `info.version`
is not being maintained.

The count alone is available as `invalidServiceCount` on `GET /info/capi`, for dashboards.

### OpenAPI (diagnostics)

Inspect the OpenAPI spec CAPI has cached for a service:

```bash
curl http://localhost:8381/info/openapi/order-service:v1
```

Returns the JSON spec with `servers[0].url` rewritten to point at the gateway, or `404` if CAPI has no cached spec for that service ID.

This endpoint is **operator-facing**: it serves the spec regardless of `expose-open-api-definition` and does not authenticate the caller. Use it for diagnostics from inside the cluster.

For the consumer-facing variant — which respects `expose-open-api-definition`, optionally requires a Bearer token via `secure-open-api-definition`, and lives on the main gateway port — see [`GET /definitions/openapi/<service-id>`](consul-metadata.md#6-expose-openapi-spec).

### Spec compliance

```bash
curl http://localhost:8381/info/spec-compliance
```

Where every discovered service stands against the `capi.matchOpenApiSpec` rule (`info.title` must
equal the Consul service name; `info.version` must be set). Verdicts are computed on every discovery
cycle **whether or not enforcement is enabled**, so an estate can be measured before the switch is
turned on.

```json
{
  "summary": {
    "enforcing": false,
    "compliant": 38,
    "nonCompliant": 2,
    "exempt": 1,
    "notApplicable": 12,
    "safeToEnable": false
  },
  "compliant":     [ { "serviceId": "orders:v1", "serviceName": "orders", "group": "v1", "verdict": "COMPLIANT", "evaluatedAt": "..." } ],
  "nonCompliant":  [ { "serviceId": "billing:v1", "detail": "spec info.title 'Billing API' does not identify service 'billing'", "...": "..." } ],
  "exempt":        [ { "serviceId": "legacy:v1", "detail": "...", "...": "..." } ],
  "notApplicable": [ { "serviceId": "echo:v1", "verdict": "NOT_APPLICABLE", "...": "..." } ]
}
```

| Verdict | Meaning |
|---|---|
| `compliant` | spec identifies the service |
| `nonCompliant` | mismatch — **would be blocked** if `matchOpenApiSpec.enabled` were true |
| `exempt` | mismatch, but named in `matchOpenApiSpec.exempt`, so it keeps routing |
| `notApplicable` | no `open-api` meta; the rule does not apply |

`safeToEnable` is true when `nonCompliant` is empty — the signal that flipping the switch will not
strand anything. Exempt services deliberately do not block readiness.

Returns `404` when Consul discovery is not enabled. Once enforcing, blocked services also appear in
[`/info/invalid-services`](#invalid-services) with reason `OPENAPI_IDENTITY_MISMATCH`.

### Truststore

```bash
curl http://localhost:8381/info/truststore
```

Lists all certificates in the custom truststore — alias, subject DN, issuer DN and validity window. Returns `404` if the truststore is not enabled.

Add a certificate (PEM in the request body):

```bash
curl -X PUT http://localhost:8381/info/truststore \
     --data-binary @my-backend.pem
```

The alias is derived from the certificate CN, lowercased, with anything outside `[a-z0-9._-]` replaced by `_`. Returns `409` if that alias is already present.

Remove a certificate by alias — use the `alias` exactly as `GET /info/truststore` reports it:

```bash
curl -X DELETE http://localhost:8381/info/truststore/my-backend
```

| Status | Meaning |
| --- | --- |
| `200` | Certificate removed |
| `404` | Truststore not enabled, Consul KV not configured, or no certificate with that alias |
| `500` | Truststore could not be pushed back to Consul KV |

Both verbs require an **authenticated** admin listener (`admin.protected: true`). On a listener left
unauthenticated they return `403` regardless of `trustStore.enabled`: adding a certificate here
changes what every CAPI instance in the cluster trusts for backend TLS, so it is not something an
unauthenticated caller may do. Reads (`GET /info/truststore`) are unaffected.

Both `PUT` and `DELETE` write the updated JKS to Consul KV (`capi-trust-store`). Every CAPI instance picks the change up on its next KV poll, which rebuilds the `SSLContext`, the `CapiTrustManager` and the REST/WebSocket/gRPC client handlers — so the change is cluster-wide, not local to the node you called. They require the Consul KV store to be configured; with `trustStore.enabled` off, or no KV store, both return `404`.

`/info/truststore` accepts `GET` and `PUT` only, and `/info/truststore/{alias}` accepts `DELETE` only; anything else returns `405` with an `Allow` header.

### WebSocket Routes

```bash
curl http://localhost:8381/info/wsroutes
```

Lists active WebSocket and SSE route connections. Returns `404` if the WebSocket gateway is not enabled.
