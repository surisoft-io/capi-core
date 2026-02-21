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
    "openapi": { "href": "http://localhost:8381/info/openapi/{serviceId}" },
    "truststore": { "href": "http://localhost:8381/info/truststore" },
    "wsroutes": { "href": "http://localhost:8381/info/wsroutes" }
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

CAPI tracks per-route request counters and standard JVM/Camel metrics.

### CAPI Instance Info

```bash
curl http://localhost:8381/info/capi
```

Returns instance configuration and runtime information:
- CAPI version
- Instance name
- Uptime
- Camel version
- Number of active routes
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

### OpenAPI

Retrieve the OpenAPI spec for a service (the service must have `expose-open-api-definition: true` in its Consul metadata):

```bash
curl http://localhost:8381/info/openapi/order-service:v1
```

Returns the OpenAPI JSON spec with the server URL rewritten to point at the CAPI gateway.

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
