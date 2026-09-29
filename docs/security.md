# Security

CAPI supports two complementary authorization mechanisms: **OAuth2/OIDC** for token validation and **OPA (Open Policy Agent)** for fine-grained policy decisions. Both can be used independently or together.

## OAuth2 / OIDC

When a service declares `"secured": "true"` in its Consul metadata, CAPI validates the Bearer token on every request before proxying to the backend.

### Configuration

```yaml
capi:
  oauth2:
    enabled: true
    cookieName: ""
    keys:
      - http://keycloak:8080/realms/capi/protocol/openid-connect/certs
      - http://auth0.example.com/.well-known/jwks.json
```

| Field | Description |
|-------|-------------|
| `enabled` | Enable OAuth2 token validation globally. |
| `cookieName` | Optional. When set, CAPI also looks for the token in a cookie with this name (useful for browser-based clients). |
| `keys` | List of JWKS (JSON Web Key Set) endpoints. CAPI fetches public keys from each endpoint at startup and uses them to verify JWT signatures. Multiple providers are supported. |

### How It Works

1. Client sends a request with `Authorization: Bearer <token>` header
2. CAPI validates the JWT signature against the configured JWKS endpoints
3. CAPI checks token expiration
4. If valid, the request is proxied to the backend
5. If invalid or missing, CAPI returns `401 Unauthorized`

### Token Sources

CAPI looks for tokens in this order:
1. `Authorization: Bearer <token>` header
2. `access_token` query parameter — unless `oauth2.allowQueryParameterToken: false`
3. Cookie (if `cookieName` is configured)

> **A token in a URL** ends up in access logs, browser history and `Referer` headers along the way.
> CAPI now **strips `access_token` from the query string before forwarding**, so it no longer reaches
> the backend's own logs — CAPI has already re-emitted it as `Authorization: Bearer …`. Set
> `oauth2.allowQueryParameterToken: false` to stop accepting it entirely; `capi_query_token_requests_total{service}`
> tells you who still relies on it first.
>
> An `access_token` **request header** was also honoured until 2026-09-29. It was never a documented
> source and is no longer accepted.

### Example

```bash
# Get a token from your OIDC provider
TOKEN=$(curl -s -X POST http://keycloak:8080/realms/capi/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=my-client" \
  -d "client_secret=my-secret" | jq -r .access_token)

# Call a secured service through CAPI
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8380/api/order-service/v1/orders
```

## OPA (Open Policy Agent)

OPA provides fine-grained, policy-based authorization. CAPI sends request context to OPA, which evaluates a Rego policy and returns an allow/deny decision.

### Configuration

```yaml
capi:
  opa:
    enabled: true
    wasmBundleUrl: http://opa-bundle-server/bundle.tar.gz
    wasmBundleToken:               # optional; sent as Authorization: Bearer <token>
    wasmBundlePollIntervalSeconds: 60
    wasmPoolSize: 10
```

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `false` | Enable OPA authorization globally. |
| `wasmBundleUrl` | — | URL of the `.tar.gz` Wasm bundle. Re-fetched on ETag change. |
| `wasmBundleToken` | — | Optional bearer token for the bundle server. |
| `wasmBundlePollIntervalSeconds` | `60` | How often the bundle is polled. |
| `wasmPoolSize` | `10` | Size of the shared policy-instance pool. |

> **There is no `endpoint` key and no HTTP OPA mode.** Earlier revisions of this page documented
> `opa.endpoint: http://opa:8181` and an "OPA HTTP fallback". Neither exists: CAPI evaluates Rego
> compiled to WebAssembly, in-process, only. Setting `endpoint` makes CAPI **fail to start** —
> unknown configuration properties are rejected, not ignored.

### Per-Service Policies

Each service can declare its own OPA policy via the `opa-rego` metadata key:

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

CAPI evaluates the `capi/order_policy` entrypoint from the loaded Wasm bundle. The bundle must
declare an entrypoint at `<opa-rego>/allow`; a service naming a policy the bundle does not declare is
rejected with `403 Unknown policy`.

### OPA Input

CAPI sends the following input to OPA:

```json
{
  "input": {
    "path": "/api/order-service/v1/orders/123",
    "method": "GET",
    "token": {
      "sub": "user-id",
      "azp": "client-id",
      "realm_access": { "roles": ["admin"] }
    }
  }
}
```

### Example Rego Policy

```rego
package capi.order_policy

default allow = false

allow {
    input.method == "GET"
}

allow {
    input.method == "POST"
    input.token.realm_access.roles[_] == "admin"
}
```

This policy allows all GET requests but restricts POST to users with the `admin` role.

### Evaluation model

Rego is compiled to WebAssembly and evaluated **in-process**, on the XNIO I/O thread: sub-millisecond,
no network call, no thread dispatch. This keeps the whole request path — accept to proxy handoff —
free of blocking I/O, matching every other pre-proxy check (JWT validation, API key lookup, throttle
counters).

Compile your Rego to a `.wasm` bundle and serve it from a bundle server; point `wasmBundleUrl` at it.
Services then name their policy with the `opa-rego` metadata key. Instances are drawn from one shared
pool (`wasmPoolSize`) that grows under load and self-heals if an evaluation fails.

### Authorization Flow

1. Client sends a request with a Bearer token
2. CAPI extracts the token — **no token is `403`**, the OPA path does not skip authentication
3. If the policy engine has no bundle loaded yet, CAPI returns **`503` with `Retry-After: 1`**
   rather than falling back to a weaker check
4. A service naming a policy the bundle does not declare is rejected with **`403 Unknown policy`**
5. CAPI **verifies the JWT signature** before trusting any decoded claim, and returns `403 Invalid
   token signature` if it does not verify
6. The policy is evaluated in-process and returns allow/deny
7. Allowed requests are proxied; denied requests get **`403 Access denied by policy`**

## SSL / TLS

### Gateway SSL

CAPI can terminate TLS on all its listeners:

```yaml
capi:
  ssl:
    enabled: true
    keyStoreType: PKCS12
    path: /capi/certs/keystore.p12
    password: changeit
```

When using the Helm chart, provide the keystore as a base64-encoded value:

```bash
helm install capi-core helm/capi-core \
  --set capi.ssl.enabled=true \
  --set capi.ssl.keystoreBase64=$(base64 -i keystore.p12) \
  --set capi.ssl.password=changeit
```

### Custom Truststore

To connect to upstream services with self-signed or internal CA certificates:

```yaml
capi:
  trustStore:
    enabled: true
    path: /capi/certs/truststore.jks
    password: changeit
```

Inspect the loaded certificates via the Admin API:

```bash
curl http://localhost:8381/info/truststore
```

When the truststore is backed by Consul KV you can also add and remove certificates at runtime — `PUT /info/truststore` with a PEM body, and `DELETE /info/truststore/{alias}`. See [Admin API — Truststore](admin-api.md#truststore).

## Path handling

CAPI binds the authorization decision to the service prefix in the path (`/api/<service>/<group>`),
then strips that prefix and forwards the remainder to the backend. A `.` or `..` **segment** in the
remainder can therefore escape the `root-context` the service was registered with, while the request
has already been authorized as that service.

```yaml
capi:
  rest:
    rejectDotSegments: false   # true = refuse such paths with 400
```

- Only a **whole segment** equal to `.` or `..` is refused. Ordinary paths containing dots —
  `/range/1..10`, `/files/report..pdf`, `/a..b`, `/v1.2.3/x`, `/.hidden` — are unaffected. A
  substring test would reject all of those, which is why the check is segment-wise.
- Percent-encoded forms are covered by the same rule: Undertow decodes `%2e%2e`, `%2E%2E` and mixed
  `.%2e` into `..` before any handler runs. Double-encoded `%252e%252e` arrives as the literal
  segment `%2e%2e` and is not treated as a dot-segment — reaching `..` from there requires a backend
  that percent-decodes twice, which is a defect in that backend.
- **Off by default**, because refusing a request an existing deployment forwards today is a behaviour
  change. While off, such requests are forwarded unchanged and only counted:

  ```
  capi_dot_segment_requests_total{service="...",action="observed"}
  ```

  Watch that stay at zero on real traffic, then set `rejectDotSegments: true` and the same paths
  return `400` (counted with `action="rejected"`). The check runs before any authorization decision.

> **Encoded slashes (`%2F`) are not currently rejected.** Undertow does not decode them, so
> `/foo%2F..%2F..%2Fadmin` reaches the backend intact and is treated as a single path segment by the
> OpenAPI operation gate. A backend that decodes `%2F` will see a different path from the one CAPI
> validated. Tomcat and Spring reject encoded slashes by default; if yours does not, treat this as an
> open item.

## OpenAPI spec endpoints

The `open-api` service metadata key is the one URL a service owner supplies that CAPI then requests
itself — using the Consul HTTP client, which carries CAPI's trust store. Endpoints are checked before
they are fetched:

```yaml
capi:
  openApi:
    allowLocalSpecEndpoints: false
```

| Target | Allowed |
|---|---|
| Public addresses | yes |
| **Private / RFC 1918** (`10.x`, `172.16-31.x`, `192.168.x`) | **yes** — this is where a spec normally lives |
| **Loopback** (`127.0.0.0/8`, `::1`, `localhost`) | no, unless `allowLocalSpecEndpoints: true` |
| **Link-local** (`169.254.0.0/16`, `fe80::/10`) | **never** — the cloud metadata endpoints |

Deliberately *not* a blanket private-range block. CAPI's legitimate backends are on RFC 1918
addresses, so refusing them would break the feature rather than secure it. What is refused is the two
ranges that are never a valid spec host: cloud metadata, and loopback — which from the gateway means
CAPI's own admin port or a co-located Consul agent, neither of which the service describing itself
could legitimately point at.

The host is **resolved** before comparison, so `2130706433` and `127.1` are caught along with the
dotted form, as is a hostname that resolves to a blocked address. Note that Java does not parse octal
dotted-quads: `0177.0.0.1` resolves to `177.0.0.1`, an ordinary public address, and is allowed —
which matches what the HTTP client would connect to.

## Kubernetes deployment

The rendered CAPI configuration embeds the Consul token, the OPA bundle token and the keystore /
truststore passwords, so the Helm chart writes it to a **Secret**, not a ConfigMap.

A ConfigMap would be the wrong object for it: anything holding `get`/`list` on the namespace can read
one, it is not encrypted at rest by default, and `kubectl describe configmap` prints it in full. Until
2026-09-29 the chart used a ConfigMap, so those credentials were readable by every workload in the
namespace. **If you deployed a chart before that, rotate the Consul and OPA tokens.**

The Secret mounts exactly as the ConfigMap did, so no other change is needed.

## gRPC

The gRPC listener routes by the `x-capi-service` header. A service registered with `secured: "true"`
now requires a valid token whose `subscriptions` claim contains its `subscriptionGroup`, matching the
REST and WebSocket gateways. Until 2026-09-29 this listener dispatched straight to the backend, so any
client that could reach the port could invoke RPC methods on internal services.

Refusals use gRPC semantics rather than HTTP status codes, because a gRPC client reads `grpc-status`
and would surface a bare 401 as an opaque transport error:

| Situation | Response |
|---|---|
| No token on a `secured` service | HTTP 200, `grpc-status: 16` (UNAUTHENTICATED) |
| Token not in the subscription group | HTTP 200, `grpc-status: 7` (PERMISSION_DENIED) |
| `oauth2` not configured, service `secured` | HTTP 200, `grpc-status: 16` — refused, not passed through |

Services without `secured` are unaffected and still route without a token.

> Note this listener still has **no OPA, throttle or request-time watchdog**. Only the subscription
> gate is applied. Treat the gRPC port as internal.

## CORS

CORS is controlled by two keys. `corsEnabled` is the **master switch**: while it is false CAPI sends
no CORS header at all, and both `allowedOrigins` and any per-service `allowed-origins` metadata are
ignored. With it true, **an origin must be allowlisted to receive any CORS header** — an empty list
denies every origin:

```yaml
capi:
  corsEnabled: true
  allowedOrigins:
    - https://app.example.com
  allowedHeaders:
    - Origin
    - Accept
    - X-Requested-With
    - Content-Type
    - Access-Control-Request-Method
    - Authorization
```

With `corsEnabled: true` and the request's `Origin` on the allowlist, CAPI sets:
- `Access-Control-Allow-Origin: <that origin>`
- `Access-Control-Allow-Credentials: true`
- `Vary: Origin`

And on a preflight, regardless of origin:
- `Access-Control-Allow-Methods: GET, POST, DELETE, PUT, PATCH`
- `Access-Control-Max-Age: 86400`

With `allowedOrigins: ["*"]`, `Access-Control-Allow-Origin: *` is sent and
`Access-Control-Allow-Credentials` is **never** sent — the pair is invalid per the Fetch standard,
so cookie-authenticated browser clients need their origin listed explicitly.

`corsEnabled: false` was previously **reporting-only** — it appeared in `/info/capi` but gated
nothing, so a config reading `corsEnabled: false` could still serve CORS headers. It is now enforced.

### Per-service origins (preferred)

CORS origins belong to whoever owns the service, so they travel with the Consul registration:

```json
{ "Meta": { "allowed-origins": "https://team-a-app.example,https://team-a-admin.example" } }
```

- A service that declares `allowed-origins` uses **exactly that list** — it replaces the gateway
  default rather than adding to it.
- A service that declares nothing **inherits** `capi.allowedOrigins`.
- One service's origins never apply to another, so listing an origin for your service does not grant
  it access to anyone else's.
- Requires `capi.corsEnabled: true`; the master switch overrides per-service origins.
- Changing the list is a re-registration, not a CAPI redeploy — but remember CAPI only picks up
  changed metadata when the `version` meta is bumped (see `capi-service-discovery`).

Use `capi.allowedOrigins` for an origin that genuinely spans the whole gateway, such as a shared
developer portal, and the ServiceMeta key for everything else.

> **Note:** `allowed-origins` was present in `ServiceMeta` but read by nothing until 2026-09-25 —
> registrations that set it had no effect before then.
