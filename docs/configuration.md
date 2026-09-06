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

  rest:
    enabled: true
    port: 8380
    listeningAddress: 0.0.0.0
    contextPath: /api
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
    endpoint: http://localhost:8181

  traces:
    enabled: false
    serviceName: capi
    endpoint: http://localhost:4318
    extraMetadataPrefix:

  corsEnabled: false
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
| `adminPort` | `8381` | Port for the Admin API (health, metrics, routes). |
| `reverseProxyHost` | — | Override the `X-Forwarded-Host` header sent to upstream services. |

### REST

| Field | Default | Description |
|-------|---------|-------------|
| `rest.enabled` | `true` | Enable the REST gateway. |
| `rest.port` | `8380` | Listening port. |
| `rest.listeningAddress` | `0.0.0.0` | Bind address. |
| `rest.contextPath` | `/api` | Base path for all REST routes. |
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
| `opa.endpoint` | — | OPA server endpoint (e.g. `http://opa:8181`). |

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
| `corsEnabled` | `false` | Enable CORS header management. |
| `allowedHeaders` | — | List of allowed request headers. |

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
