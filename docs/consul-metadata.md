# Service Registration and Consul Metadata

This guide walks through registering a service with CAPI via Consul, from the simplest case to advanced configurations.

## How CAPI Discovers Services

CAPI polls the Consul catalog at a configurable interval (`consulCatalogDiscoverInterval`, default 60s). For each service it finds, it reads the service metadata (`ServiceMeta`) and automatically creates, updates, or removes routes.

Services do **not** need any CAPI-specific code. CAPI reads metadata from Consul and builds routes accordingly.

## User Journey

### 1. Register a Simple REST Service

The only mandatory metadata CAPI needs is `group`. Optionally, set `root-context` to specify the backend path prefix (defaults to `/`).

Register a service in Consul via the HTTP API:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "order-service",
    "Port": 8080,
    "Address": "10.0.0.10",
    "Meta": {
      "group": "v1",
      "root-context": "/orders",
      "scheme": "http"
    }
  }'
```

CAPI creates a route at:
```
GET http://<capi-host>:8380/api/order-service/v1/orders/**
```

The route ID becomes `order-service:v1`. Requests to CAPI are proxied to `http://10.0.0.10:8080/orders/**`.

Verify the route was created:

```bash
curl http://localhost:8381/info/routes
```

### 2. Add Multiple Instances (Load Balancing)

Register more instances of the same service with the same `Name` and `group`:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "order-service-2",
    "Name": "order-service",
    "Port": 8080,
    "Address": "10.0.0.11",
    "Meta": {
      "group": "v1",
      "root-context": "/orders",
      "scheme": "http"
    }
  }'
```

CAPI automatically enables **Round Robin load balancing** and **Failover** when more than one instance is registered.

### 3. Secure with OAuth2

Add `secured: true` to require a valid Bearer token on every request:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "order-service",
    "Port": 8080,
    "Address": "10.0.0.10",
    "Meta": {
      "group": "v1",
      "root-context": "/orders",
      "scheme": "http",
      "secured": "true"
    }
  }'
```

CAPI validates the token against the JWKS endpoints configured in `capi.oauth2.keys`. See [Security](security.md) for details.

Test the secured endpoint:

```bash
# Without token - returns 401
curl -i http://localhost:8380/api/order-service/v1/orders

# With token - proxied to backend
curl -H "Authorization: Bearer <token>" \
  http://localhost:8380/api/order-service/v1/orders
```

### 4. Add OPA Authorization

For fine-grained authorization (e.g. role-based access), add an OPA Rego policy:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "order-service",
    "Port": 8080,
    "Address": "10.0.0.10",
    "Meta": {
      "group": "v1",
      "root-context": "/orders",
      "scheme": "http",
      "secured": "true",
      "opa-rego": "capi/order_policy"
    }
  }'
```

CAPI sends the request context to OPA at the configured endpoint and enforces the decision. See [Security](security.md) for policy details.

### 5. Enable Throttling

Rate-limit a service by declaring throttle metadata:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "order-service",
    "Port": 8080,
    "Address": "10.0.0.10",
    "Meta": {
      "group": "v1",
      "root-context": "/orders",
      "scheme": "http",
      "throttle": "true",
      "throttleTotalCalls": "100",
      "throttleDuration": "60000"
    }
  }'
```

This allows 100 calls per 60 seconds. Throttle state is distributed across all CAPI instances via Hazelcast. CAPI must have `capi.throttle.enabled: true` in its configuration.

### 6. Expose OpenAPI Spec

If your service has an OpenAPI endpoint, CAPI can fetch it at registration time and re-publish it to API consumers:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "order-service",
    "Port": 8080,
    "Address": "10.0.0.10",
    "Meta": {
      "group": "v1",
      "root-context": "/orders",
      "scheme": "http",
      "open-api": "http://10.0.0.10:8080/v3/api-docs",
      "expose-open-api-definition": "true"
    }
  }'
```

Consumers retrieve the spec on the main gateway port (default `8380`), outside the configured `contextPath`:

```bash
curl http://localhost:8380/definitions/openapi/order-service:v1
```

CAPI returns the upstream spec with `servers[0].url` rewritten to point at the gateway and `info.title` / `info.description` replaced with CAPI-generated values. Without `expose-open-api-definition: "true"`, the endpoint returns `404`.

Add `"secure-open-api-definition": "true"` to require a Bearer token whose claims include the service's `subscription-group`. Missing or unauthorized tokens also return `404` (the endpoint is silent about whether the service exists when secured).

> Operators can also inspect any cached spec — regardless of the opt-in flag — via the admin-port endpoint at `http://localhost:8381/info/openapi/<service-id>`. That endpoint is unauthenticated and intended for diagnostics, not for client traffic.

### 7. Register a WebSocket Service

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "chat-service",
    "Port": 9090,
    "Address": "10.0.0.20",
    "Meta": {
      "group": "v1",
      "root-context": "/chat",
      "scheme": "ws",
      "type": "websocket"
    }
  }'
```

CAPI routes WebSocket connections through the WebSocket port (default 8382). CAPI must have `capi.websocket.enabled: true`.

### 8. Register an SSE Service

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "notifications",
    "Port": 9091,
    "Address": "10.0.0.30",
    "Meta": {
      "group": "v1",
      "root-context": "/events",
      "scheme": "http",
      "type": "sse"
    }
  }'
```

SSE services are handled on the WebSocket port. CAPI must have `capi.websocket.enabled: true` and `runningMode` set to `full` or `sse`.

### 9. Target a Specific CAPI Instance

When running multiple CAPI instances with different `instanceName` values, target a service to a specific instance:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "order-service",
    "Port": 8080,
    "Address": "10.0.0.10",
    "Meta": {
      "group": "v1",
      "root-context": "/orders",
      "scheme": "http",
      "capi-instance": "production"
    }
  }'
```

Only the CAPI instance with `instanceName: production` will create a route for this service. If `strictToInstanceName: true`, services without a `capi-instance` declaration are ignored.

#### Multi-Instance Targeting

A single service can target multiple CAPI instances with different settings per instance:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "order-service",
    "Port": 8080,
    "Address": "10.0.0.10",
    "Meta": {
      "group": "v1",
      "root-context": "/orders",
      "scheme": "http",
      "capi-instance-production": "{\"secured\": true, \"routeGroupFirst\": false}",
      "capi-instance-staging": "{\"secured\": false, \"routeGroupFirst\": false}"
    }
  }'
```

### 10. Control Route Path Order

By default, the route path is `/<service-name>/<group>`. To reverse it to `/<group>/<service-name>`:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "order-service",
    "Port": 8080,
    "Address": "10.0.0.10",
    "Meta": {
      "group": "v1",
      "root-context": "/orders",
      "scheme": "http",
      "route-group-first": "true"
    }
  }'
```

This creates a route at `/api/v1/order-service/orders/**` instead of `/api/order-service/v1/orders/**`.

### 11. Deregister a Service

```bash
curl -X PUT http://localhost:8500/v1/agent/service/deregister/order-service
```

CAPI automatically detects the removal on the next discovery cycle and removes the route.

## Complete Metadata Reference

### Required Fields

| Key | Description | Example |
|-----|-------------|---------|
| `group` | **(Mandatory)** Route group identifier. Combined with service name to form the route ID and route path (e.g. `order-service:v1`). Services registered without a `group` are ignored by CAPI. | `v1` |

### Routing

| Key | Default | Description |
|-----|---------|-------------|
| `root-context` | `/` | Backend path prefix. Requests are forwarded to this path on the upstream service. If not specified, CAPI forwards to `/`. |
| `scheme` | `http` | Protocol to use when connecting to the upstream service (`http`, `https`, `ws`, `wss`). |
| `type` | `rest` | Service type: `rest`, `websocket`, or `sse`. |
| `route-group-first` | `false` | When `true`, route path becomes `/<group>/<service>` instead of `/<service>/<group>`. |
| `keep-group` | `false` | When `true`, preserves the group segment in the path forwarded to the backend. |
| `state` | `PUBLISHED` | Service state. Only `PUBLISHED` services are routed. Set to other values to disable without deregistering. |
| `version` | — | Service version metadata (informational). |

### Security

| Key | Default | Description |
|-----|---------|-------------|
| `secured` | `false` | When `true`, requires a valid OAuth2/OIDC Bearer token. |
| `opa-rego` | — | OPA Rego policy path for fine-grained authorization (e.g. `capi/order_policy`). |
| `allowed-origins` | — | Comma-separated list of allowed CORS origins for this service. |

### Throttling

| Key | Default | Description |
|-----|---------|-------------|
| `throttle` | `false` | Enable rate limiting for this service. |
| `throttleTotalCalls` | `-1` | Maximum number of calls allowed in the time window. |
| `throttleDuration` | `-1` | Time window in milliseconds. |
| `throttleGlobal` | `false` | When `true`, throttle state is shared across all CAPI instances. |
| `rateLimit` | `false` | Enable rate limiting flag. |

### OpenAPI

| Key | Default | Description |
|-----|---------|-------------|
| `open-api` | — | URL of the service's OpenAPI spec endpoint. Fetched at registration time, parsed, and cached in memory. |
| `expose-open-api-definition` | `false` | When `true`, the cached spec is published to consumers at `GET /definitions/openapi/<service-id>` on the main gateway port (outside `contextPath`). When `false`, that endpoint returns `404`. |
| `secure-open-api-definition` | `false` | When `true`, the public endpoint above requires a Bearer token whose claims include this service's `subscription-group`. Unauthorized requests get `404`, not `401`. |

### MCP

| Key | Default | Description |
|-----|---------|-------------|
| `mcp-enabled` | `false` | Expose the service as an MCP tool provider via tag-defined tools (`mcp-tools`). |
| `mcp-tools` | — | Comma-separated list of tool names (used with `mcp-enabled`). |
| `mcp-tools-{name}-description` | — | Human-readable description for the tool, surfaced to LLM clients. |
| `mcp-tools-{name}-inputSchema` | `{"type":"object"}` | JSON Schema describing the tool's input. |
| `mcp-tools-{name}-timeout` | — | Per-tool override of the global `mcp-timeout`. |
| `mcp-toolPrefix` | — | Prefix applied to all exposed tool names from this service. |
| `mcp-streaming` | — | Comma-separated list of tool names that may emit SSE events. |
| `mcp-category` | — | Semantic classification, used as input to OPA policy. |
| `mcp-timeout` | — | Default tool execution timeout (ms) for this service. |
| `mcp-type` | `rest` | `rest` for synthetic tools or `server` to passthrough to an upstream MCP server. |
| `mcp-from-openapi` | `false` | When `true`, every operation in the service's `open-api` spec is auto-promoted to an MCP tool. Can be combined with `mcp-enabled` to selectively override entries. |
| `mcp-promote-include` | — | Comma-separated `operationId`s to include when auto-promoting. Empty means all. |
| `mcp-promote-exclude` | — | Comma-separated `operationId`s to exclude when auto-promoting. Applied after `mcp-promote-include`. |
| `mcp-tools-{name}-signature` | — | Base64-encoded signature over the canonical manifest of this tool. Verified against the public key identified by `mcp-tools-{name}-keyid`. Only consulted when `capi.mcp.signing.mode` is `warn` or `enforce`. |
| `mcp-tools-{name}-keyid` | — | Identifier of the signing public key (matches a Consul KV entry under `capi-mcp-trust-keys/<keyid>`). |
| `mcp-required-signed` | `false` | When `true` (and signing is in `enforce` mode), unsigned tools from this service are dropped. In `warn` mode they pass with a log line. |

See [MCP Gateway](mcp-gateway.md) for the full design, the `tools/list` / `tools/call` wire protocol, and worked examples (including OpenAPI auto-promotion and signed manifests).

### Instance Targeting

| Key | Default | Description |
|-----|---------|-------------|
| `capi-instance` | — | Target a specific CAPI instance by `instanceName`. |
| `namespace` | — | Alternative field for instance targeting (same as `capi-instance`). |
| `capi-instance-<name>` | — | JSON object with per-instance overrides. See multi-instance targeting above. |

### Timeouts

| Key | Default | Description |
|-----|---------|-------------|
| `response-timeout` | `-1` (uses global) | Maximum time in milliseconds to wait for the backend service to respond. When set to a value greater than `0`, overrides the global `rest.responseTimeout` from CAPI configuration. Use this to set shorter timeouts on fast services or longer timeouts on services that are expected to be slow. |

### Observability

| Key | Default | Description |
|-----|---------|-------------|
| `X-B3-TraceId` | `false` | When `true`, includes B3 trace IDs in error responses. |
| `ingress` | — | Ingress metadata (informational). |

### WebSocket / SSE

| Key | Default | Description |
|-----|---------|-------------|
| `subscription-group` | — | Subscription group for SSE/WebSocket services. |
| `allow-subscriptions` | `false` | Enable subscription functionality. |
