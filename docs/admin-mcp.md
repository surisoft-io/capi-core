# Admin MCP Server

A read-only MCP server that lets an AI agent ask operational questions about a CAPI cluster —
*why does this service not route? do all nodes agree? which certificates expire this month? which
node is serving the 5xx?* — instead of an operator stitching `/info/*` calls together with curl.

It is **cluster-wide**: whichever node the agent connects to fans out to every CAPI node of the same
cluster and answers per node, so node-to-node disagreement is visible instead of averaged away.

> **Not the [MCP Gateway](mcp-gateway.md).** The MCP Gateway (`capi.mcp.*`, port `8383`) exposes
> *your backend services* as tools to API consumers. The admin MCP server (`capi.admin.mcp.*`, port
> `8390`) exposes *CAPI itself* to operators. They are independent; enable either, both or neither.

## How it works

```
                     ┌────────────── Consul (admin.mcp.consul) ──────────────┐
                     │  service "capi-admin"  — one entry per CAPI node,      │
                     │  Meta.capi-cluster = instanceName                      │
                     └──────▲───────────────────▲───────────────────▲─────────┘
               register +   │                   │                   │
               TTL heartbeat│                   │                   │
                     ┌──────┴──────┐     ┌──────┴──────┐     ┌──────┴──────┐
  agent ──POST /mcp─►│ node A :8390│────►│ node B :8381│     │ node C :8381│
  (Bearer token)     │  (entry)    │──────────────────────────►            │
                     │  :8381      │◄─── GET /info/* with the caller's token
                     └─────────────┘
```

1. **Each node registers itself** in Consul as service `capi-admin`, with its admin address and port,
   and keeps the registration alive with a TTL heartbeat.
2. An agent connects to **any** node's `admin.mcp.port` (`8390`) and calls a tool.
3. That node looks up the passing `capi-admin` entries **of its own cluster** (same
   `capi.instanceName`), then calls each node's Admin API (`/info/*` on `adminPort`, itself included)
   **in parallel, forwarding the caller's token**.
4. It folds the answers into one result, listing every node that did not answer.

Nothing is stored centrally and there is no leader: every node is an equally good entry point.

## Requirements

| Requirement | Why |
|---|---|
| `admin.protected: true` + `admin.group` | The admin MCP reuses the admin authorization. **CAPI refuses to start** with `admin.mcp.enabled: true` on an unprotected admin listener. |
| `oauth2.enabled: true` | Tokens are validated with the same key set as the admin API. |
| `admin.mcp.consul.endpoint` | The Consul used to register and find peers. **Required** — there is no fallback to `consulHosts`. |
| A Consul token with `service:write` on `capi-admin` | For registration. Read-only catalog tokens stay read-only. |
| Nodes can reach each other's `adminPort` | The fan-out calls peers directly. |
| TLS on the admin listener (recommended) | The caller's token is forwarded node to node. On plain HTTP it crosses the network in clear. |

## Configuration

```yaml
capi:
  instanceName: default          # cluster identity: nodes with the same instanceName are one cluster
  adminPort: 8381
  oauth2:
    enabled: true
    keys:
      - https://idp.example.com/realms/ops/protocol/openid-connect/certs
  admin:
    protected: true
    group: capi-admin
    mcp:
      enabled: true
      port: 8390
      authorizationServer: https://idp.example.com/realms/ops
      consul:
        endpoint: https://consul.example.com:8500
        token: <service:write on capi-admin>
        meta: []
      advertiseAddress:
      peerTlsName:
      nodeTimeoutMs: 5000
      maxConcurrentCalls: 4
      history:
        intervalSeconds: 15
        retentionMinutes: 60
```

| Field | Default | Description |
|---|---|---|
| `admin.mcp.enabled` | `false` | Start the admin MCP listener, register this node in Consul and keep the metrics history. |
| `admin.mcp.port` | `8390` | Listening port of the MCP endpoint. |
| `admin.mcp.authorizationServer` | — | The **one** issuer allowed to authorize agents. See [Authorization](#authorization). |
| `admin.mcp.consul.endpoint` | — | **Required.** The Consul this node registers in and discovers its peers from. |
| `admin.mcp.consul.token` | — | ACL token, sent as `Authorization: Bearer`. Needs `service:write` (and read) on `capi-admin` only. |
| `admin.mcp.consul.meta` | `[]` | Extra metadata added to the registration. A map, or a list of single `key: value` entries. See [Extra registration metadata](#extra-registration-metadata). |
| `admin.mcp.advertiseAddress` | this host's address | The address **other nodes** use to reach this node's `adminPort`. The default (the resolved host address — a pod IP on Kubernetes) is usually right. Set it behind NAT or Docker port mappings. |
| `admin.mcp.peerTlsName` | — | HTTPS admin listeners only. The DNS name in the admin certificate, checked instead of the peer's IP. See [TLS between nodes](#tls-between-nodes). |
| `admin.mcp.nodeTimeoutMs` | `5000` | Per-node timeout of the fan-out. A node slower than this is reported as unreachable. |
| `admin.mcp.maxConcurrentCalls` | `4` | `tools/call` requests in flight per node. Past the limit a call is refused with JSON-RPC `-32000`, not queued. |
| `admin.mcp.history.intervalSeconds` | `15` | How often each node samples its own metrics for the [metrics tools](#metrics-history). |
| `admin.mcp.history.retentionMinutes` | `60` | How much history each node keeps in memory. |

### Why a dedicated Consul

CAPI can read several Consul catalogs (`consulHosts`), possibly owned by other teams. It never
writes into them, and with several of them it could not know which one to register in. So the
admin MCP is given exactly one Consul, explicitly. It may well be the same server as one of the
catalogs — it is just never guessed.

### Extra registration metadata

When Consul is fronted by something that validates registrations (and answers `422` when required
metadata is missing), add what it needs:

```yaml
admin:
  mcp:
    consul:
      meta:
        - owner: platform
        - environment: dev
```

A plain map (`meta: { owner: platform }`) works too. Empty keys and duplicates fail startup.
`capi-cluster`, `capi-version`, `admin-scheme` and `mcp-port` are set by CAPI; using one of them
fails startup rather than silently overriding it.

> ⚠️ **Do not add `group` until every CAPI that reads this Consul as a catalog runs a version with
> the admin MCP.** Older versions do not know `capi-admin` is reserved, and `group` is what makes a
> service routable — they would publish a node's admin API on their data plane.

## Node registration

Each node registers:

```json
{
  "ID":      "capi-admin-<instanceName>-<hostname>",
  "Name":    "capi-admin",
  "Address": "<advertiseAddress>",
  "Port":    8381,
  "Meta": {
    "capi-cluster": "<instanceName>",
    "capi-version": "<CAPI version>",
    "admin-scheme": "http | https",
    "mcp-port":     "8390"
  },
  "Check": { "TTL": "30s", "DeregisterCriticalServiceAfter": "2m" }
}
```

- The heartbeat runs every 10 s on its own thread. If Consul lost the registration (an agent
  restart, for example), the node registers again on the next tick.
- On a graceful shutdown the node deregisters. A killed node drops out of discovery after 30 s and
  out of the catalog after 2 minutes.
- Peers are discovered with `GET /v1/health/service/capi-admin?passing=true` and filtered on
  `capi-cluster` — so several CAPI clusters can share one Consul. Cluster identity is the exact
  `instanceName` string.
- The node that receives the call is always included, even if Consul is unreachable, so a tool
  still answers for at least that node.

### `capi-admin` is a reserved service name

CAPI drops the `capi-admin` service from every catalog it reads, exactly as it drops `consul`. A
node's admin API is therefore never routed on the gateway, never fetched for an OpenAPI spec and
never reported as an invalid service. **Do not register a backend under that name** — CAPI will
not route it.

## Authorization

Every request carries `Authorization: Bearer <jwt>` and is checked on its own — there are no
sessions:

1. The token is validated with the `oauth2` key set and must carry `admin.group` in its
   `subscriptions` claim — the same check as the [Admin API](configuration.md#admin-listener).
2. If `admin.mcp.authorizationServer` is set, the token's `iss` must equal it (a trailing slash is
   ignored).

| Response | When |
|---|---|
| `401` + `WWW-Authenticate` | No token, or a token that does not validate |
| `403` | Valid token without the admin group, or from another issuer |

**Use client credentials.** An agent authenticates as a confidential client whose service account
carries the admin group. One client per person or team keeps the audit trail useful.

**Why one authorization server.** CAPI trusts every issuer in `oauth2.keys`; any of them could
mint a token carrying the admin group. Agents get cluster-wide read access, so they are authorized
by exactly one issuer. Set it to the issuer **as it appears in the token** — the URL agents fetch
tokens from — not an internal hostname CAPI happens to use to reach the IdP. When unset, any
trusted issuer is accepted and CAPI logs a warning at startup.

**Discovery (RFC 9728).** `GET /.well-known/oauth-protected-resource` on the MCP port is served
without authentication and lists `authorizationServer` (or, when unset, the issuers derived from
`oauth2.keys`). The `401` points to it via `WWW-Authenticate: Bearer resource_metadata="…"`.

**Token forwarding.** The entry node forwards the caller's token to its peers' Admin API. Peers are
the same resource server, so each re-checks the caller, and no node holds standing credentials for
the others. The issuer check above is enforced by the entry node.

## TLS between nodes

Peers are dialled by the address they registered — usually an IP. Standard HTTPS hostname
verification accepts an IP only when the certificate carries that exact IP as a subject
alternative name, so a certificate issued to a DNS name **never** matches, even when it is in the
trust store. The symptom is every node, including the local one, reported as unreachable.

Set `admin.mcp.peerTlsName` to the DNS name in the admin certificate shared by the nodes:

```yaml
admin:
  mcp:
    peerTlsName: capi-admin.example.com    # or a name covered by *.example.com
```

The fan-out then:

1. validates the certificate chain against CAPI's trust store (or the JVM default), as before;
2. requires a **DNS subject alternative name** covering `peerTlsName` — exact, or a single-label
   `*.` wildcard. The CN is never consulted.

Both checks happen in the TLS handshake, so the token is never sent to a peer that fails them.
Trust-store reloads are picked up without a restart. Without `peerTlsName`, an HTTPS admin
listener and an IP advertise address produce a startup warning.

## Connecting an agent

### MCP clients with HTTP + OAuth support

Point the client at `https://<any-node>:8390/mcp` with a bearer token. For example, with Claude Code:

```bash
claude mcp add --transport http capi-admin https://capi-node.example.com:8390/mcp \
  --header "Authorization: Bearer $TOKEN"
```

### Clients that only launch a local process

Some clients can only start a command and talk MCP over stdin/stdout, or cannot refresh a
client-credentials token. `tools/capi-admin-mcp-bridge.py` (Python standard library, nothing to
install) bridges stdio to the HTTP endpoint and fetches and refreshes the token itself:

```json
{
  "mcpServers": {
    "capi-admin": {
      "command": "python3",
      "args": ["/path/to/capi-admin-mcp-bridge.py"],
      "env": {
        "CAPI_MCP_URL": "https://capi-node.example.com:8390/mcp",
        "CAPI_TOKEN_URL": "https://idp.example.com/realms/ops/protocol/openid-connect/token",
        "CAPI_CLIENT_ID": "capi-ops-agent",
        "CAPI_CLIENT_SECRET": "<from your secret store>",
        "CAPI_CA_FILE": "/path/to/internal-ca.pem"
      }
    }
  }
}
```

| Variable | Required | Meaning |
|---|---|---|
| `CAPI_MCP_URL` | yes | The admin MCP endpoint |
| `CAPI_TOKEN_URL` | yes | The token endpoint of `authorizationServer` |
| `CAPI_CLIENT_ID` / `CAPI_CLIENT_SECRET` | yes | Client-credentials client |
| `CAPI_SCOPE` | no | `scope` parameter of the token request |
| `CAPI_CA_FILE` | no | PEM bundle to trust (internal CA) |
| `CAPI_TIMEOUT` | no | Per-request timeout in seconds (default 30) |

Logs go to stderr; stdout carries only MCP messages.

### curl

```bash
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=capi-ops-agent \
  -d client_secret=$SECRET https://idp.example.com/realms/ops/protocol/openid-connect/token | jq -r .access_token)

curl -s https://capi-node.example.com:8390/mcp \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "MCP-Protocol-Version: 2025-11-25" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"capi_check_alignment","arguments":{}}}' | jq .result.structuredContent
```

## Tools

All tools are read-only (`readOnlyHint: true`). Every result is returned as `structuredContent` and
as the same JSON in a text block, and every result says which nodes answered:

```json
"nodes": {
  "total": 3,
  "answered": 2,
  "unreachable": [ { "node": "capi-admin-default-capi-2", "error": "timeout" } ]
}
```

A node that did not answer is reported, never silently skipped.

### Cluster and routing

| Tool | Arguments | Answers |
|---|---|---|
| `capi_list_nodes` | — | Every node with version, uptime, health, route count and invalid-service count. Start here. |
| `capi_check_alignment` | `limit` | Do all nodes run the same CAPI version, use the same JWKS endpoints **and have the same signing keys loaded**, read the same Consul hosts, and expose the same services (counts per transport and the exact REST service ids)? |
| `capi_explain_service` | `serviceId` (required) | Per node: is the service routed, to which endpoints, is it invalid and why, its spec-compliance verdict — and whether the nodes agree. |
| `capi_compare_routes` | `limit` | Only the differences: routes missing on some nodes, or routed to different endpoints. |
| `capi_invalid_services` | `limit` | Services CAPI refused to route, merged across nodes, with the nodes reporting each. |
| `capi_spec_compliance_summary` | — | Per-node counts of spec-identity verdicts and whether enforcement is on. |

`capi_check_alignment` returns one verdict per check — `capiVersion`, `jwksEndpoints`,
`jwksKeysLoaded`, `consulHosts`, `serviceCounts`, `restServices` — each `true`, `false`, or **`null`
when fewer than two nodes could be compared** (a single answering node is not "aligned"). Nodes
running a version that does not report a field are listed under `unknownOn`.

> **Signing keys are loaded once, at startup.** After the identity provider rotates its keys, a node
> started before the rotation does not know the new key and rejects every token signed with it — on
> the gateway as well as on the admin API. That is exactly what `jwksKeysLoaded` catches. Because the
> fan-out uses the caller's token, those nodes typically show up as unreachable with a `403`;
> the result then names them and the key id under `tokenRejectedByPeers`. Restart them to reload
> the keys.

### Trust store

| Tool | Arguments | Answers |
|---|---|---|
| `capi_truststore_expiring` | `days` (30), `recentDays` (30) | Certificates expired or expiring within `days`, each with `status`: **replaced** (a certificate valid beyond the window shares its CN or a DNS name — listed under `replacedBy`) or **not replaced**. Also `recentlyAdded`: aliases added to the store in the last `recentDays`. |
| `capi_truststore_find` | `query` (required), `limit` | Every certificate, valid or expired, whose alias, subject, issuer or DNS name contains `query`. |

"Added" is the date the alias entered the trust store, not the certificate's issue date. Only
**JKS** trust stores record it; a PKCS12 store reports `addedAt: null`.

### Metrics history

| Tool | Arguments | Answers |
|---|---|---|
| `capi_traffic` | `minutes` (15), `limit` (10) | Requests/s, 4xx/5xx, 5xx ratio and latency per node and cluster-wide; how load is shared (flags imbalance); top services by 5xx, latency and traffic; 5xx spikes. |
| `capi_service_health` | `serviceId` (required), `minutes` | One service on every node, with a per-interval series; nodes that served it no traffic. |
| `capi_node_health` | `minutes` | Heap, threads, CPU and open file descriptors per node; outliers against the other nodes; threads or file descriptors growing within the window. |
| `capi_security_events` | `minutes` | Blocked paths, dot-segment requests, tokens in query strings, WebSocket origin checks and WebDAV depth — split into observed and rejected, per node and cluster-wide. |

These read each node's **in-memory** metrics history — no Prometheus needed. With
`admin.mcp.enabled`, every node samples its own metrics every `history.intervalSeconds` and keeps
`history.retentionMinutes` of per-interval deltas (only services that had traffic are stored).
`minutes` is therefore bounded by the retention. The history starts empty on restart: the first
sample is only a baseline, so a node needs one interval before it reports anything.

> Requests to an unknown route are answered `404` before they reach the metrics, so a service CAPI
> refused to route shows **no traffic** rather than 404s. Use `capi_explain_service` for those.

The same data is available per node on the Admin API:
[`GET /info/metrics/history`](admin-api.md#metrics-history).

### Deliberately not exposed

Anything that changes state, `/info/jvm/profile` (runs a profiler), the raw OpenAPI specs (large,
and author-controlled text), and the raw Prometheus dump.

## Protocol

- `POST /mcp` — JSON-RPC 2.0 over streamable HTTP, plain JSON responses (no SSE).
- **Stateless on every revision.** Serves `2026-07-28`, `2025-11-25`, `2025-06-18` and `2025-03-26`.
  `initialize` is answered and the version negotiated, but no `Mcp-Session-Id` is issued, so clients
  that perform the handshake work unchanged. `2026-07-28` clients skip it and call `server/discover`.
- Methods: `server/discover`, `initialize`, `ping`, `tools/list`, `tools/call`. Anything else returns
  `-32601`. Notifications get `202 Accepted`.
- JSON-RPC errors are returned with HTTP `200`; authentication failures are `401`/`403`. A request
  body over 64 KiB is refused.

| Code | Meaning |
|---|---|
| `-32602` | Unknown tool or invalid argument |
| `-32000` | `maxConcurrentCalls` reached on this node — retry, or use another node |
| `-32603` | Internal error |

The listener is non-blocking: requests are handled on its own I/O threads, peers are called
asynchronously, and the data-plane listeners are not involved.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| CAPI refuses to start | `admin.protected` is not `true`, or `admin.mcp.consul.endpoint` is missing. The log names which. |
| Node never appears in Consul | Registration rejected — check the log for the Consul status code. `403`: the token lacks `service:write` on `capi-admin`. `422`: a validating proxy wants extra metadata — set `admin.mcp.consul.meta`. |
| Every node, even the local one, unreachable over HTTPS | Peers are dialled by IP and the certificate is issued to a DNS name. Set `peerTlsName`. |
| Only the local node answers | Peers cannot reach each other's `adminPort`, or registered an address that does not route — check `advertiseAddress`. |
| Some nodes unreachable with `403` | Those nodes reject the token — usually signing keys loaded before a key rotation. See `capi_check_alignment`. |
| `403` for every call | The token is from an issuer other than `authorizationServer`, or lacks `admin.group`. Decode it and compare `iss` and `subscriptions`. |
| Metrics tools return nothing | The node restarted less than one `intervalSeconds` ago, or the window had no traffic. |
| A node registered in another cluster's results | Two clusters use the same `instanceName`. |
