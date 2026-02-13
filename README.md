<img src="https://capi.surisoft.io/capi-4.4.04.png" alt="CAPI" width="20%"/>

[![CAPI](https://github.com/surisoft-io/capi-core/actions/workflows/main.yaml/badge.svg)](https://github.com/surisoft-io/capi-core/actions/workflows/main.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
![Docker Image Version (latest by date)](https://img.shields.io/docker/v/surisoft/capi-core)

<h5 align="center">
  <br>
  <a href="https://github.com/surisoft-io/capi-lb/issues/new?assignees=&labels=use+case&template=use_case.md&title=%5BUSECASE%5D+">
    <img src="https://dummyimage.com/1000x80/15273c/ffffff.png&text=If+you+are+using+CAPI,+please+let+us+know+by+clicking+here" alt="Share your use case with us">
  </a>
  <br>
</h5>

# CAPI Gateway Documentation
## _Light Apache Camel API Gateway_

## Supports:
* Light API Gateway / Load Balancer powered by Apache Camel dynamics routes.
* Protect your Services using OAUTH2 or OPA (Open Policy Agent).
* Distributed tracing system (OpenTelemetry)
* Metrics (Prometheus)
* Metrics for Route management.
* Load Balancer (Round robin)
* Failover (With and without Round Robin)
* Certificate Manager
* No DB is needed, CAPI uses Hashicorp Consul for service discovery
* Websocket Gateway
* SSE Gateway
* gRPC Gateway

### CAPI Config File Example
```yaml
  capi:
  version: 1.0.0
  instanceName: default
  runningMode: full
  adminPort: 8381
  rest:
    enabled: true
    port: 8380
    listeningAddress: 0.0.0.0
    contextPath: /api
    connectionRequestTimeout: 5000
    requestTimeout: 5000
    responseTimeout: 120000
  websocket:
    enabled: true
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
    path: /some/path
    encoded:
    password:
  consulHosts:
    - endpoint: http://localhost:8500
      token:
  oauth2:
    enabled: true
    cookieName:
    keys:
      - http://localhost:8080/realms/capi/protocol/openid-connect/certs
  traces:
    enabled: true
    serviceName: capi
    endpoint: http://localhost:4318
    extraMetadataPrefix: ec-
  corsEnabled: true
  allowedHeaders:
    - Origin
    - Accept
    - X-Requested-With
    - Content-Type
    - Access-Control-Request-Method
    - Authorization
```

### Metrics Endpoint
CAPI Metrics are available on http://localhost:8381/metrics
* Get statistics about the routes. `/metrics/routes`
* Get General info. `/metrics/capi`
* Certificate Management 

