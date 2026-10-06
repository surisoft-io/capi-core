# WebDAV Gateway

CAPI can front WebDAV (RFC 4918) backends on a dedicated listener, separate from REST. Services opt in
with `type: webdav` in their Consul metadata.

> **Status: phase 1.** The listener, both routing modes, all WebDAV verbs, the authorization gate and
> the multi-instance guard work. Two things do not yet: mounting from a GUI client such as Windows
> Explorer or macOS Finder (they cannot send a bearer token — see [Authentication](#authentication)),
> and automatic rewriting of `207` response bodies in path-routing mode (see
> [Routing](#routing)). Disabled by default.

## Enabling

```yaml
capi:
  webdav:
    enabled: true
    port: 8385
    routing: both
```

WebDAV is a **write** protocol. The listener is intended for an internal network behind a reverse
proxy or firewall, not for direct internet exposure.

Unlike the REST listener there is **no context path** — requests go straight to
`http://capi:8385/{service}/{group}/...`.

## Routing

Resolution order is **`Host` first, then path**. A service that declares `webdav-host` is reachable by
that hostname and not by path.

### Host routing — recommended for GUI clients

```yaml
Meta:
  webdav-host: dav-files.corp
```

The client's URL space is the backend's, so nothing is rewritten: `href` values in a `207`, and the
`Destination` header on `COPY`/`MOVE`, are already correct. Needs DNS and a certificate per service.

> **In host mode `root-context` is ignored** and the backend must serve WebDAV at `/`. Prefixing the
> path would reintroduce the mismatch that host routing exists to avoid.

### Path routing

```
http://capi:8385/{service}/{group}/path/to/file
```

CAPI strips `/{service}/{group}` and prepends the backend's `root-context`. Because the backend never
sees that prefix, it emits `href` values that are missing it, and a client following them gets `404`.

Until CAPI rewrites those bodies, **a path-routed backend must be told its own public base URI**. Most
WebDAV servers support this:

| Server | Setting |
|---|---|
| sabre/dav | `$server->setBaseUri('/files/v1/')` |
| Apache `mod_dav` | serve the collection behind a matching `Alias` |
| nginx | matching `location` prefix |

## Registering a service

All examples use the Consul agent API. CAPI picks the service up on its next discovery cycle (default
every few seconds; the demo configs use ~15 s).

### Minimal — path routed, unsecured

```bash
curl -s -X PUT http://localhost:8500/v1/agent/service/register \
  -H 'Content-Type: application/json' \
  -d '{
    "ID": "webdav-files-1",
    "Name": "files",
    "Address": "webdav-backend",
    "Port": 80,
    "Meta": {
      "group": "v1",
      "type": "webdav",
      "scheme": "http",
      "root-context": "/",
      "secured": "false"
    }
  }'
```

Reachable at `http://capi:8385/files/v1/...` — the context is `/{Name}/{group}`.

### Host routed

```bash
curl -s -X PUT http://localhost:8500/v1/agent/service/register \
  -H 'Content-Type: application/json' \
  -d '{
    "ID": "webdav-files-1",
    "Name": "files",
    "Address": "webdav-backend",
    "Port": 80,
    "Meta": {
      "group": "v1",
      "type": "webdav",
      "scheme": "http",
      "secured": "false",
      "webdav-host": "dav-files.corp"
    }
  }'
```

Reachable as `http://dav-files.corp:8385/...`, paths passed through verbatim.

### Secured, with a policy

```bash
curl -s -X PUT http://localhost:8500/v1/agent/service/register \
  -H 'Content-Type: application/json' \
  -d '{
    "ID": "webdav-files-1",
    "Name": "files",
    "Address": "webdav-backend",
    "Port": 80,
    "Meta": {
      "group": "v1",
      "type": "webdav",
      "scheme": "http",
      "root-context": "/",
      "secured": "true",
      "subscriptionGroup": "files-users",
      "opa-rego": "capi/files_policy"
    }
  }'
```

A caller now needs a token whose `subscriptions` claim contains `files-users`, and the policy must
allow it. In phase 1 that means a bearer-capable client (`curl`, `rclone`), not Explorer or Finder.

### Read only

```bash
curl -s -X PUT http://localhost:8500/v1/agent/service/register \
  -H 'Content-Type: application/json' \
  -d '{
    "ID": "webdav-archive-1",
    "Name": "archive",
    "Address": "webdav-backend",
    "Port": 80,
    "Meta": {
      "group": "v1",
      "type": "webdav",
      "scheme": "http",
      "root-context": "/",
      "secured": "false",
      "webdav-read-only": "true"
    }
  }'
```

`PUT`, `DELETE`, `POST`, `MKCOL`, `COPY`, `MOVE`, `LOCK`, `UNLOCK`, `PROPPATCH` and `PATCH` are refused
with `403`; reads pass through.

### Multiple instances

Register each instance separately, same `Name` and `group`, different `ID`:

```bash
for i in 1 2; do
  curl -s -X PUT http://localhost:8500/v1/agent/service/register \
    -H 'Content-Type: application/json' \
    -d "{
      \"ID\": \"webdav-files-$i\",
      \"Name\": \"files\",
      \"Address\": \"webdav-backend-$i\",
      \"Port\": 80,
      \"Meta\": {
        \"group\": \"v1\",
        \"type\": \"webdav\",
        \"scheme\": \"http\",
        \"root-context\": \"/\",
        \"secured\": \"false\",
        \"webdav-shared-state\": \"true\"
      }
    }"
done
```

**`webdav-shared-state` is mandatory above one instance.** Without it CAPI refuses to publish the
service — see [Multiple instances must share state](#multiple-instances-must-share-state).

### Deregister

```bash
curl -s -X PUT http://localhost:8500/v1/agent/service/deregister/webdav-files-1
```

## Multiple instances must share state

CAPI load-balances across a service's instances and **cannot replicate files**. So:

- with unshared **storage**, a `PUT` to one instance is simply absent from the next;
- with unshared **locks**, a `LOCK` taken on one is unknown to the others, so a later `PUT` fails with
  `412`/`423` — which surfaces to the user as file corruption.

Load balancing is therefore wrong for WebDAV regardless of locking: instances with independent storage
are not one service, they are several.

Setting `webdav-shared-state: true` is the owner asserting that every instance shares **both** storage
and the lock database. That second half is the one that bites — `mod_dav`'s `DAVLockDB` and sabre/dav's
file lock backend are both **local by default**, so reads and writes appear to work while locking
silently fails across instances.

A multi-instance service without the assertion is not published and appears at
`/info/invalid-services` with reason `WEBDAV_UNSHARED_MULTI_INSTANCE`. If your instances genuinely have
independent storage, register them as separate services with different `group` values.

## Authentication

**No mainstream GUI WebDAV client can send `Authorization: Bearer`.** Windows WebClient, macOS Finder,
davfs2 and Cyberduck support Basic, Digest and NTLM only; `rclone` is the exception that also supports
bearer tokens.

So in phase 1:

| Client | `secured: false` | `secured: true` |
|---|---|---|
| `curl`, `rclone` | works | works (send the bearer token) |
| Explorer, Finder | works | **not yet** |

Phase 2 adds a Basic envelope: the password field carries a raw JWT, which goes down the normal bearer
validation path. Two prerequisites, both external to CAPI:

- **TLS in front.** Windows gates Basic on transport (`BasicAuthLevel`), and over plain HTTP it refuses
  to send the credential at all.
- **A long-lived token.** A mount lives for days; an access token usually lives minutes, and there is
  no refresh token in a Basic exchange, so CAPI cannot refresh on the client's behalf.

Unlike every other CAPI listener, a missing or invalid credential here returns **`401` with
`WWW-Authenticate`**, not `403` — a `403` does not make a GUI client prompt for credentials. An
authenticated caller who is not permitted still gets `403`.

## Health check

```
GET http://capi:8385/health   ->   200 OK
```

Answered before routing, before the authorization gate and before any guard, so it needs no
credential even when every registered service is `secured`, it ignores the `Host` header, and it
responds before discovery has found anything. Any method works, including `HEAD`. Same path and
semantics as the REST, gRPC and WebSocket listeners.

Three things to get right when pointing a load balancer at it:

- **The path is matched exactly.** `/health/` (trailing slash), `/Health` (case) and `/info/health`
  (that is the admin listener on 8381) all return `404`.
- **It is liveness, not readiness.** A static `200` proves the listener accepts connections. It says
  nothing about Consul, discovery, or whether any WebDAV backend is reachable — a node whose backends
  are all down still reports healthy and stays in rotation.
- **In host-routing mode it shadows a backend `/health` resource.** Because the check runs before
  routing, a host-routed collection containing `/health` at its root is unreachable at that path. Path
  routing is unaffected: there the path is `/{service}/{group}/health`.

## Verifying

```bash
# Is the service routable?
curl -s http://localhost:8381/info/routes | grep -i webdav

# Was it refused?
curl -s http://localhost:8381/info/invalid-services

# Depth observations
curl -s http://localhost:8381/info/metrics | grep capi_webdav
```

The startup log line states the effective settings:

```
WebDAV Gateway started on port 8385 (routing: both, maxRequestSize=unlimited)
```

## Exercising it with curl

```bash
BASE=http://localhost:8385/files/v1

# Capability probe — a real client does this first and reads the DAV header
curl -i -X OPTIONS $BASE/

# Directory listing
curl -i -X PROPFIND $BASE/ -H 'Depth: 1' \
  -H 'Content-Type: application/xml' \
  --data-binary '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>'

# Create a collection, upload, read back
curl -i -X MKCOL $BASE/docs/
curl -i -X PUT   $BASE/docs/hello.txt --data-binary 'hello'
curl -i          $BASE/docs/hello.txt

# Copy / move
curl -i -X COPY $BASE/docs/hello.txt -H "Destination: $BASE/docs/copy.txt"
curl -i -X MOVE $BASE/docs/copy.txt  -H "Destination: $BASE/docs/moved.txt"

# Lock / unlock
curl -i -X LOCK $BASE/docs/hello.txt -H 'Timeout: Second-3600' \
  --data-binary '<?xml version="1.0"?><D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockinfo>'
curl -i -X UNLOCK $BASE/docs/hello.txt -H 'Lock-Token: <opaquelocktoken:...>'

# Delete
curl -i -X DELETE $BASE/docs/moved.txt
```

For compliance testing use `litmus`, the standard WebDAV suite:

```bash
litmus http://localhost:8385/files/v1/
```

## Metadata reference

| Key | Default | Purpose |
|---|---|---|
| `type` | `rest` | Must be `webdav` |
| `group` | — | Required; forms the context `/{name}/{group}` |
| `root-context` | `/` | Backend path prefix. **Ignored in host mode** |
| `scheme` | `http` | `http` or `https` to the backend |
| `secured` | `false` | Require a token in `subscriptionGroup` |
| `subscriptionGroup` | — | Subscription group required when `secured` |
| `opa-rego` | — | Policy consulted on every request |
| `webdav-host` | — | Hostname for host routing; absent means path routing |
| `webdav-shared-state` | `false` | Asserts shared storage **and** lock DB; required above one instance |
| `webdav-read-only` | `false` | Refuse the mutating verbs |
| `response-timeout` | — | Per-service override of the wall-clock budget |

## Configuration reference

| Key | Default | Purpose |
|---|---|---|
| `webdav.enabled` | `false` | Enable the listener |
| `webdav.port` | `8385` | Listener port |
| `webdav.routing` | `both` | `host`, `path`, or `both` (host first) |
| `webdav.maxRequestSize` | `-1` | Largest body in bytes; `-1` unlimited, since a large `PUT` is normal |
| `webdav.maxRequestTime` | `3600000` | Wall-clock budget; a REST-scale timeout would kill an upload |
| `webdav.maxDepth` | `1` | Largest `Depth`; observed only unless enforced |
| `webdav.enforceMaxDepth` | `false` | Refuse a `Depth` above `maxDepth` with `403` |
| `webdav.basicAuth.enabled` | `false` | Phase 2; not yet implemented |
| `webdav.basicAuth.realm` | `CAPI WebDAV` | Realm sent in `WWW-Authenticate` |

`Depth: infinity` is an amplification lever — one small request, unbounded backend work and an
unbounded response — but some sync clients use it legitimately, so it ships **observing only**. Watch
`capi_webdav_depth_total{action="observed"}` on real traffic before setting `enforceMaxDepth: true`.
