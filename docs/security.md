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
2. `access_token` query parameter
3. Cookie (if `cookieName` is configured)

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
    endpoint: http://opa:8181
```

| Field | Description |
|-------|-------------|
| `enabled` | Enable OPA authorization globally. |
| `endpoint` | OPA server endpoint. |

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

CAPI queries OPA at `http://opa:8181/v1/data/capi/order_policy` with the request context.

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

### Authorization Flow

1. Client sends a request with a Bearer token
2. CAPI validates the JWT (OAuth2)
3. CAPI sends the request context to OPA
4. OPA evaluates the Rego policy and returns `{ "result": { "allow": true/false } }`
5. If allowed, the request is proxied
6. If denied, CAPI returns `403 Forbidden`

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

## CORS

CAPI supports CORS header management for browser-based clients:

```yaml
capi:
  corsEnabled: true
  allowedHeaders:
    - Origin
    - Accept
    - X-Requested-With
    - Content-Type
    - Access-Control-Request-Method
    - Authorization
```

When enabled, CAPI automatically sets:
- `Access-Control-Allow-Credentials: true`
- `Access-Control-Allow-Methods: GET, POST, DELETE, PUT, PATCH`
- `Access-Control-Max-Age: 86400`

Per-service origin restrictions can be set via the `allowed-origins` metadata key in Consul.
