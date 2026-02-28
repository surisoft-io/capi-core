<p align="center">
  <img src="docs/capi-logo-white.svg" alt="CAPI" width="20%"/>
</p>

[![CAPI](https://github.com/surisoft-io/capi-core/actions/workflows/main.yaml/badge.svg)](https://github.com/surisoft-io/capi-core/actions/workflows/main.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
![Docker Image Version (latest by date)](https://img.shields.io/docker/v/surisoft/capi-core)

<h5 align="center">
  <br>
  <a href="https://github.com/surisoft-io/capi-core/issues/new?assignees=&labels=use+case&template=use_case.md&title=%5BUSECASE%5D+">
    <img src="https://dummyimage.com/1000x80/000000/ffffff.png&text=If+you+are+using+CAPI,+please+let+us+know+by+clicking+here" alt="Share your use case with us">
  </a>
  <br>
</h5>

# CAPI Gateway
## _Light Apache Camel API Gateway_

CAPI is a lightweight API Gateway and load balancer powered by Apache Camel dynamic routes. Services register themselves in HashiCorp Consul, and CAPI automatically discovers them, creates routes, and applies security, throttling, and observability policies — no database required.

## Features

* REST, WebSocket, and SSE gateway with dynamic routing
* Service discovery via HashiCorp Consul (automatic route creation and removal)
* OAuth2 / OIDC token validation (multi-provider)
* Fine-grained authorization via OPA (Open Policy Agent)
* Distributed throttling (Hazelcast, with Kubernetes discovery)
* Load balancing (Round Robin) and Failover
* Distributed tracing (OpenTelemetry / OTLP)
* Prometheus metrics
* OpenAPI spec aggregation from upstream services
* TLS termination and custom truststore management
* CORS management
* Admin API with health, metrics, and route inspection
* Multi-instance support (route targeting per CAPI instance)
* Reverse proxy headers (`X-Forwarded-*`)
* **[Experimental]** MCP Gateway — expose services as MCP tools for LLM agents (JSON-RPC 2.0 over Streamable HTTP)
* **[Experimental]** gRPC Gateway — transparent HTTP/2 reverse proxy for gRPC services with header-based routing

## REST-to-MCP Bridging

CAPI's MCP Gateway turns **any existing REST API into an MCP tool** — no code changes on your backends.

Register a service in Consul with MCP metadata, and LLM agents (Claude Desktop, Cursor, custom agents) can discover and invoke it immediately. Your REST services don't need to know anything about MCP, JSON-RPC, or tool schemas. CAPI handles the translation:

```
LLM Agent                          CAPI                          Your REST API
                                                                 (unchanged)
  tools/list  ──────────────►  reads Consul metadata
              ◄──────────────  returns tool catalog

  tools/call  ──────────────►  extracts arguments
              {"name":"..."}   POST /endpoint  ──────────────►  handles request
                               wraps response  ◄──────────────  returns JSON
              ◄──────────────  MCP-formatted result
```

CAPI also supports **real MCP Server backends** — services that already speak JSON-RPC 2.0. Register them with `mcp-type: server` and CAPI discovers their tools automatically via `tools/list`, then proxies `tools/call` requests transparently. Both REST and MCP Server backends are aggregated under one unified MCP endpoint.

Unlike dedicated MCP gateways that require all backends to be MCP Servers, CAPI bridges the gap between existing REST infrastructure and LLM-native protocols. See the [MCP Gateway docs](docs/mcp-gateway.md) and the [demo](demo/) for a working example with both backend types.

## Quickstart

CAPI requires the `CAPI_CONFIG_FILE` environment variable pointing to a valid configuration file.

### Running from JAR

```bash
CAPI_CONFIG_FILE=config/config.yaml java -jar capi-core.jar
```

### Running with Docker

```bash
docker run -p 8380:8380 -p 8381:8381 \
  -v $(pwd)/config/config.yaml:/capi/config/config.yaml \
  -e CAPI_CONFIG_FILE=/capi/config/config.yaml \
  surisoft/capi-core
```

### Running with Helm (Kubernetes)

A Helm chart is available in [`helm/capi-core/`](helm/capi-core/).

```bash
helm install capi-core helm/capi-core

# With custom values
helm install capi-core helm/capi-core -f my-values.yaml

# Enable SSL and truststore
helm install capi-core helm/capi-core \
  --set capi.ssl.enabled=true \
  --set capi.ssl.keystoreBase64=<base64-encoded-keystore> \
  --set capi.ssl.password=changeit
```

See [`helm/capi-core/values.yaml`](helm/capi-core/values.yaml) for all available configuration options.

## Ports

| Port | Description | Config key |
|------|-------------|------------|
| 8380 | REST API gateway | `capi.rest.port` |
| 8381 | Admin / metrics | `capi.adminPort` |
| 8382 | WebSocket gateway | `capi.websocket.port` |
| 8383 | MCP Gateway (experimental) | `capi.mcp.port` |
| 8384 | gRPC Gateway (experimental) | `capi.grpc.port` |

## Running Modes

The `runningMode` field controls which types of services CAPI will proxy:

| Mode | Description |
|------|-------------|
| `full` | Proxies REST, WebSocket, and SSE services (default) |
| `websocket` | Only proxies WebSocket services |
| `sse` | Only proxies SSE services |

## Documentation

| Document | Description |
|----------|-------------|
| [Service Registration](docs/consul-metadata.md) | How to register services in Consul and configure routing, security, and throttling via metadata |
| [Security](docs/security.md) | OAuth2/OIDC and OPA authorization configuration |
| [Admin API](docs/admin-api.md) | Admin endpoints reference (health, metrics, routes, OpenAPI) |
| [Configuration Reference](docs/configuration.md) | Complete YAML configuration reference with all fields |
| [MCP Gateway (Experimental)](docs/mcp-gateway.md) | MCP Gateway for LLM tool integration via JSON-RPC 2.0 |
| [gRPC Gateway (Experimental)](docs/grpc-gateway.md) | Transparent gRPC reverse proxy with header-based routing |