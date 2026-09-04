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

### Truststore

```bash
curl http://localhost:8381/info/truststore
```

Lists all certificates in the custom truststore. Returns `404` if the truststore is not enabled.

### WebSocket Routes

```bash
curl http://localhost:8381/info/wsroutes
```

Lists active WebSocket and SSE route connections. Returns `404` if the WebSocket gateway is not enabled.
