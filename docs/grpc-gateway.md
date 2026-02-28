# gRPC Gateway (Experimental)

CAPI's gRPC Gateway is a transparent HTTP/2 reverse proxy for gRPC services. It listens on a dedicated port and routes requests to backend gRPC services discovered via Consul.

## How it works

Unlike REST routing (which uses URL path prefixes), the gRPC Gateway uses **header-based routing**. Standard gRPC clients always construct paths as `/<package.Service>/<Method>` and do not support arbitrary path prefixes. The `x-capi-service` header tells CAPI which backend to forward to, and the gRPC path is forwarded as-is.

```
Client                              CAPI gRPC Gateway                    Backend
                                    (port 8390)
  ──── HTTP/2 ────────────────►
  Header: x-capi-service: svc/grp
  Path:   /pkg.Service/Method       lookup svc/grp in Consul
                                     ──── HTTP/2 (h2c-prior) ────►
                                     Path: /pkg.Service/Method          handles request
                                     ◄──── response ──────────────
  ◄──── response ──────────────
```

## Configuration

Enable the gRPC Gateway in your CAPI config:

```yaml
capi:
  grpc:
    enabled: true
    port: 8390
```

## Registering a gRPC service in Consul

Register your gRPC service with `type: grpc` in the metadata:

```bash
curl -X PUT http://localhost:8500/v1/agent/service/register \
  -H "Content-Type: application/json" \
  -d '{
    "Name": "my-service",
    "ID": "my-service-1",
    "Port": 50051,
    "Address": "my-grpc-host",
    "Meta": {
      "group": "dev",
      "root-context": "/",
      "scheme": "http",
      "type": "grpc",
      "capi-instance": "default",
      "secured": "false"
    }
  }'
```

| Meta field | Description |
|------------|-------------|
| `type` | Must be `grpc` |
| `group` | Service group (e.g., `dev`, `prod`). Combined with service name for routing. |
| `scheme` | `http` for plaintext gRPC (h2c). `https` for TLS-encrypted gRPC. |
| `root-context` | Typically `/` for gRPC services |
| `secured` | Set to `true` to require OAuth2 token validation |

## Client usage

Clients route requests by setting the `x-capi-service` header to `<serviceName>/<group>`:

```bash
grpcurl -plaintext \
  -H "x-capi-service: my-service/dev" \
  -import-path /path/to/protos -proto my_service.proto \
  -d '{"field": "value"}' \
  localhost:8390 mypackage.MyService/MyMethod
```

### Error responses

| Status code | Meaning |
|-------------|---------|
| 400 | Missing `x-capi-service` header |
| 404 | Service not found (check service name and group) |
| 503 | Backend unavailable (service registered but not reachable) |

### Health check

The gateway exposes a health endpoint:

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8390/health
# 200
```

## Limitations

- **Server reflection**: gRPC server reflection uses bidirectional streaming, which Undertow's reverse proxy does not support. Clients must provide proto files directly (e.g., `grpcurl -proto`).
- **Streaming RPCs**: Bidirectional streaming RPCs are not supported. Unary and server-streaming RPCs work correctly.
- **Load balancing**: Backends are load-balanced across all registered instances using Undertow's built-in round-robin.

## Transport details

For plaintext gRPC backends (`scheme: http`), CAPI connects using HTTP/2 prior knowledge (`h2c-prior`), which is required by the gRPC protocol. For TLS backends (`scheme: https`), standard ALPN negotiation is used.
