<img src="https://capi.surisoft.io/capi-4.4.04.png" alt="CAPI" width="20%"/>

[![CAPI](https://github.com/surisoft-io/capi-core/actions/workflows/main.yaml/badge.svg)](https://github.com/surisoft-io/capi-core/actions/workflows/main.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=surisoft-io_capi-core&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=surisoft-io_capi-core)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=surisoft-io_capi-core&metric=coverage)](https://sonarcloud.io/summary/new_code?id=surisoft-io_capi-core)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
![Docker Image Version (latest by date)](https://img.shields.io/docker/v/surisoft/capi-core)

<h5 align="center">
  <br>
  <a href="https://github.com/surisoft-io/capi-core/issues/new?assignees=&labels=use+case&template=use_case.md&title=%5BUSECASE%5D+">
    <img src="https://dummyimage.com/1000x80/15273c/ffffff.png&text=If+you+are+using+CAPI,+please+let+us+know+by+clicking+here" alt="Share your use case with us">
  </a>
  <br>
</h5>

# CAPI Gateway Documentation
## _Light Apache Camel API Gateway_

## Features
* Light API Gateway / Load Balancer powered by Apache Camel dynamic routes
* Protect your services using OAuth2 or OPA (Open Policy Agent)
* Distributed tracing (OpenTelemetry)
* Metrics (Prometheus)
* Route management and metrics
* Load balancing (Round Robin)
* Failover (with and without Round Robin)
* Certificate Manager
* No database needed — CAPI uses HashiCorp Consul for service discovery
* Websocket Gateway
* SSE Gateway
* Distributed throttling (Hazelcast, with Kubernetes discovery support)

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
| 8382 | Websocket gateway | `capi.websocket.port` |

## Running Modes

The `runningMode` field controls which types of services CAPI will proxy:

| Mode | Description |
|------|-------------|
| `full` | Proxies REST, Websocket, and SSE services (default) |
| `websocket` | Only proxies Websocket services |
| `sse` | Only proxies SSE services |

## Configuration Reference

CAPI is configured via a YAML file. Below is a complete example with all available fields:

```yaml
capi:
  version: 1.0.0
  instanceName: default
  strictToInstanceName: true
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
  websocket:
    enabled: false
    port: 8382
    listeningAddress: 0.0.0.0
    contextPath: /api/*
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
  accessLogs:
    enabled: false
    tenant: capi
    service: capi
    destination: localhost:5444
  throttle:
    enabled: false
    kubernetesNamespace:
    kubernetesServiceName:
```

### Config Fields

| Field | Description |
|-------|-------------|
| `instanceName` | Name of this CAPI instance (used for multi-instance setups) |
| `strictToInstanceName` | When `true`, only routes tagged for this instance are proxied |
| `publicEndpoint` | The externally-reachable URL of this gateway |
| `runningMode` | Service types to proxy: `full`, `websocket`, or `sse` |
| `reverseProxyHost` | Override the host header sent to upstream services |
| `consulCatalogDiscoverInterval` | Interval (ms) for polling Consul for service changes |
| `consulHosts` | List of Consul agent endpoints for service discovery |
| `consulStore` | Optional Consul KV store for persisting route configuration |
| `oauth2` | OAuth2/OIDC protection — provide JWKS endpoint(s) |
| `opa` | Open Policy Agent integration for fine-grained authorization |
| `traces` | OpenTelemetry tracing configuration (OTLP HTTP endpoint) |
| `loggingTraces` | Structured logging traces forwarding |
| `accessLogs` | Access log forwarding |
| `throttle` | Rate-limiting via distributed Hazelcast cache |

## Throttling

CAPI supports distributed rate-limiting using a Hazelcast cluster. When enabled, throttle state is shared across all CAPI instances.

```yaml
capi:
  throttle:
    enabled: true
```

### Kubernetes Discovery

By default, Hazelcast uses multicast to discover cluster members. This works for standalone JARs and Docker containers on the same network, but **not in Kubernetes** where multicast is typically blocked.

To run throttling in Kubernetes, set `kubernetesServiceName` to the name of the Kubernetes `Service` that fronts your CAPI pods. Hazelcast will then use the Kubernetes API to discover members instead of multicast.

```yaml
capi:
  throttle:
    enabled: true
    kubernetesServiceName: capi-core
    kubernetesNamespace: default
```

| Field | Description |
|-------|-------------|
| `kubernetesServiceName` | K8s Service name for Hazelcast pod discovery. If empty, multicast is used. |
| `kubernetesNamespace` | K8s namespace to query. Optional — defaults to the pod's own namespace. |

When deploying with the Helm chart, the required `ServiceAccount`, `Role`, and `RoleBinding` are created automatically:

```bash
helm install capi-core helm/capi-core \
  --set capi.throttle.enabled=true \
  --set capi.throttle.kubernetesServiceName=capi-core \
  --set capi.throttle.kubernetesNamespace=default
```

## Health Endpoints

| Endpoint | Port | Purpose |
|----------|------|---------|
| `GET /health` | REST (8380) | Readiness — checks Consul connectivity |
| `GET /info/health` | Admin (8381) | Liveness — confirms the process is running |

## Admin Endpoints

All admin endpoints are served on the admin port (default `8381`):

| Endpoint | Description |
|----------|-------------|
| `GET /info/health` | Health check |
| `GET /info/metrics` | Prometheus metrics |
| `GET /info/capi` | General CAPI instance info |
| `GET /info/routes` | List all managed routes |
| `GET /info/routes/{id}` | Get a specific route by ID |
| `GET /info/openapi/{id}` | OpenAPI spec for a route |
| `GET /info/truststore` | Truststore info |
| `GET /info/wsroutes` | List active Websocket routes |
