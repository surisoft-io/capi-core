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

### Host routing

```yaml
Meta:
  webdav-host: dav-files.corp
```

The client's URL space is the backend's, so nothing is rewritten: `href` values in a `207`, and the
`Destination` header on `COPY`/`MOVE`, are already correct.

**`webdav-host` is a hostname pointing at CAPI, not at the backend.** It is a virtual host: CAPI routes
on the `Host` header of the incoming request and still reaches the backend through the `Address` and
`Port` in the Consul registration. Those two are independent.

```
Word  ──►  dav-files.corp:8385        DNS A record → CAPI
             │ Host: dav-files.corp
             ▼
           CAPI :8385                 matches Host against webdav-host
             │
             ▼
           webdav-backend:80          from Consul Address/Port
```

Matching is case-insensitive and the port is ignored, so `dav-files.corp:8385` matches
`webdav-host: dav-files.corp`. A hosts-file entry is enough to try it.

The cost — and the reason path routing exists at all — is **one DNS name per service**, all resolving
to the same CAPI. If you terminate TLS in front (needed for Word to send Basic auth), the certificate
must cover every one of those names, so a SAN or wildcard certificate rather than one per service.

> **In host mode `root-context` is ignored** and the backend must serve WebDAV at `/`. Prefixing the
> path would reintroduce the mismatch that host routing exists to avoid.

### Path routing

```
http://capi:8385/{service}/{group}/path/to/file
```

CAPI strips `/{service}/{group}` and prepends the backend's `root-context`. Because the backend never
sees that prefix, it emits `href` values that are missing it, and a client following them gets `404`.

> **Office clients probe the server root, and in path mode that returns `404`.** `/` has only one
> segment, so CAPI matches no service and answers `OPTIONS /` and `PROPFIND /` with `404`. The
> per-file requests are unaffected — `DAV`, `MS-Author-Via` and `Lock-Token` pass through and `LOCK`
> returns its token.
>
> **In practice Word works in path routing anyway**, confirmed against a real deployment. If Word
> opens a document read-only and offers to force editing, that is almost always **transport or
> security zone, not routing** — see [Word opens read-only](#word-opens-a-document-read-only). Serve
> WebDAV over **HTTPS on a real hostname with a valid certificate** before suspecting anything else.

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

**A session id carried in the URL path.** That is the only supported mechanism, and the reason is
worth stating because it looks unusual.

A document is opened by handing the client a URL and nothing else — the `ms-word:` handoff has no
header slot, and the client inherits nothing from the page that launched it. So the credential has to
be in the URL. But a query string survives exactly one request: a WebDAV client addresses a parent
collection by truncating the filename, and discards the query with it. A **path segment** survives
that truncation.

```
handed to the client   /s/<session-id>/files/v1/webdav/doc.docx
derived by the client  /s/<session-id>/files/v1/webdav/          ← id still there
```

CAPI resolves the id, strips `/s/<id>` and forwards the real path, so the backend never sees it.

### Minting a session

```bash
curl -s -X POST https://capi/.capi/ticket -H "Authorization: Bearer <jwt>"
```

```json
{"ticket":"NHaG4bcDNKEgeWzqfXObhw","expiresIn":60,
 "session":"lDI7_2ZvpLaSMnORFFwyjA","sessionPath":"/s/lDI7_2ZvpLaSMnORFFwyjA","sessionExpiresIn":3600}
```

`POST` only, authenticated with an ordinary bearer token which is **validated before anything is
issued** — otherwise a dead token yields a healthy-looking ticket that fails much later, far from the
cause. Build the document URL with `sessionPath` and hand it to the client.

Two credentials come back:

| | Reusable | Lives in | Use |
|---|---|---|---|
| `session` | yes | the path | every request, including derived URLs |
| `ticket` | **no**, single use | `?ticket=` | the one request whose URL you authored |

The ticket is there for clients that do keep state; for Office, use the session.

> **What was tried and does not work with Office.** A cookie — Word returns no `Cookie` header at
> all, not even the load balancer's. `X-MS-CookieUri` — ignored, despite Word advertising
> `X-MS-CookieUri-Requested: t`. `WWW-Authenticate: Bearer realm=…, authorization_uri=…` — that is
> the shape of Microsoft's Azure AD challenge, so Word signs in to Microsoft and then probes the
> origin instead of the requested path. A JWT in the URL — the resulting `ms-word:` URI is long
> enough that Word opens and hangs. All four were confirmed against Word 16 on macOS.

### Behind a load balancer

```yaml
capi:
  webdav:
    distributedSessions: true
```

**Required with more than one CAPI node.** A ticket is minted by your web application and redeemed by
the document client — two callers with no shared affinity — so with per-process stores the two halves
land on different nodes and the ticket is simply unknown. Shares both stores over Hazelcast, reusing
the Kubernetes discovery settings from `capi.throttle`.

Single use still holds across the cluster: redemption is an atomic `IMap.remove`, so two nodes cannot
both redeem the same ticket.

### Session lifetime

Keep `sessionTtlSeconds` at or **below** the access token's own lifetime. The session holds the token
captured at mint time, so a session that outlives its token keeps resolving to an expired one and the
request is refused with a `403` an hour into editing, with nothing to explain it.

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

## Troubleshooting

### A browser shows 404 — is the route broken?

Probably not. A browser sends `GET`, and `GET` on a WebDAV *collection* is not a WebDAV operation —
it is an ordinary request for a directory, answered entirely by the backend. `mod_dav` with no
`DirectoryIndex` and no `mod_autoindex` returns `404` or `403`; with autoindex it returns an HTML
listing; sabre/dav returns its own browser page. **A browser 404 says nothing about CAPI's routing.**

Tell the two apart by the body:

| | Status | Body | Backend headers |
|---|---|---|---|
| **CAPI found no route** | `404` | **empty** | none |
| **Backend answered** | anything | present | `Server:` etc. |

```bash
curl -i http://localhost:8385/files/v1/
```

A body and a `Server` header mean the route works and the backend simply does not serve directory
`GET`s. A zero-length `404` means CAPI matched no service — check the path is `/{Name}/{group}` with
**no** `/capi` or `/api` prefix (this listener has no context path), and check `/info/routes`.

### The real smoke test

```bash
curl -i -X OPTIONS http://localhost:8385/files/v1/
```

`200` with a `DAV: 1,2` header proves the whole chain: CAPI routed, and the backend genuinely speaks
WebDAV. If `DAV:` is absent, the backend is not a WebDAV server on that path — CAPI never synthesises
that header, so it is always the backend's own answer.

To check with a browser at all, request a known **file** (`/files/v1/hello.txt`), not the collection
root.

### Word opens a document read-only

Word shows a warning bar and only edits if you force it. **Check transport and security zone before
anything else** — in a real deployment this turned out to be exactly that, not WebDAV at all:

1. **Serve over HTTPS on a real DNS name with a valid certificate.** This is the fix that worked.
   Plain HTTP on a bare IP fails for two independent reasons: Windows gates Basic auth on transport
   (`BasicAuthLevel` defaults to SSL-only, so credentials are never sent over HTTP), and a bare IP
   generally lands in the Internet zone, which puts Word into Protected View.
2. Only if that is already true, check the backend advertises locking:

   ```bash
   curl -i -X OPTIONS https://dav.example.corp/path/
   ```

   `DAV:` must contain `2`, and `MS-Author-Via: DAV` should be present. Without class 2 Word cannot
   take a write lock and will not enter normal edit mode.

Forcing past the warning makes Word edit **without holding a lock**, so two users can open the same
document and the second save silently overwrites the first. Worth fixing properly rather than training
users to click through it.

### Other things that look like auth failures but are not

| Symptom | Cause |
|---|---|
| `404` on a service you registered | Multi-instance without `webdav-shared-state: true` — it was never published. See `/info/invalid-services`. |
| `403` on writes | `webdav-read-only: true`, or the backend itself refuses (`mod_dav` needs `Dav On` and a writable filesystem). |
| `401` on an unsecured service | `opa-rego` is set: the policy gate is independent of `secured`, and a policy needs a token to decide on. |
| `400` on an odd path | The dot-segment guard rejected a `.` or `..` path segment. |
| Listing works, navigation 404s | Path mode without the backend knowing its public base URI — see [Routing](#routing). |
| `504` on a large upload | `maxRequestTime` is wall-clock and covers the upload, not just the response. |

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
| `webdav.port` | `8385` | Listener port. No context path — requests go to `/{service}/{group}/...` |
| `webdav.routing` | `both` | `host`, `path`, or `both` (host first) |
| `webdav.maxRequestSize` | `-1` | Largest body in bytes; `-1` unlimited, since a large `PUT` is normal |
| `webdav.maxRequestTime` | `3600000` | Wall-clock budget; a REST-scale timeout would kill an upload |
| `webdav.maxDepth` | `1` | Largest `Depth`; observed only unless enforced |
| `webdav.enforceMaxDepth` | `false` | Refuse a `Depth` above `maxDepth` with `403` |
| `webdav.sessionPathPrefix` | `/s/` | Path segment carrying the session id. Blank disables it |
| `webdav.sessionTtlSeconds` | `3600` | Session lifetime; keep at or below the token's own lifetime |
| `webdav.maxSessions` | `10000` | Concurrent sessions; cluster-wide when distributed |
| `webdav.distributedSessions` | `false` | Share tickets and sessions over Hazelcast. Required on more than one node |
| `webdav.ticket.enabled` | `false` | Enable the minting endpoint |
| `webdav.ticket.path` | `/.capi/ticket` | Where sessions are minted. `POST` only |
| `webdav.ticket.ttlSeconds` | `60` | Ticket lifetime; it only has to survive the handoff |
| `webdav.ticket.maxOutstanding` | `10000` | Unredeemed ticket cap |

`Depth: infinity` is an amplification lever — one small request, unbounded backend work and an
unbounded response — but some sync clients use it legitimately, so it ships **observing only**. Watch
`capi_webdav_depth_total{action="observed"}` before setting `enforceMaxDepth: true`.
