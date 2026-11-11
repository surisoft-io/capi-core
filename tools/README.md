# Tools

Operator tools for running CAPI. Both use the Python 3 standard library only — nothing to install.

| Tool | What it is for |
|---|---|
| [`capi-routes-viewer.py`](#capi-routes-viewer) + `.html` | A local web page that compares the nodes of a CAPI instance: configuration, routes, operations and their security, invalid services and the trust store |
| [`capi-admin-mcp-bridge.py`](#capi-admin-mcp-bridge) | Connects an MCP client that can only launch a local process to the [admin MCP server](../docs/admin-mcp.md) |

---

## capi-routes-viewer

A page for answering "do all my nodes agree?". It reads each node's [admin API](../docs/admin-api.md)
(port 8381 by default) and lays the answers side by side, marking every value that differs.

```bash
python3 tools/capi-routes-viewer.py                      # then open http://127.0.0.1:8399
python3 tools/capi-routes-viewer.py --ca-file internal-ca.pem
python3 tools/capi-routes-viewer.py --insecure           # self-signed nodes: skip TLS verification
python3 tools/capi-routes-viewer.py --port 9000 --timeout 20
```

| Option | Default | Meaning |
|---|---|---|
| `--port` | `8399` | Local port of the page |
| `--ca-file` | — | PEM bundle to trust for `https` nodes, the identity provider and Consul |
| `--insecure` | off | Skip TLS certificate verification |
| `--timeout` | `10` | Per-call timeout in seconds |

### Why a helper script

The admin API sends no CORS headers, so a page opened in a browser cannot call it directly. The
script serves the page and forwards its calls. It is deliberately not a general proxy:

- It listens on `127.0.0.1` only, and refuses calls coming from any other page open in the browser.
- It forwards only to a fixed set of admin paths. Reads: `/info/capi`, `/info/health`,
  `/info/routes`, `/info/invalid-services`, `/info/truststore`, and `/info/routes/<id>` and
  `/info/openapi/<id>` for one service. Writes, the trust store only: `PUT /info/truststore` (exactly
  one PEM certificate) and `DELETE /info/truststore/<alias>`. Every write is logged on the console.
- Secrets — the client secret, the access token, the Consul token — are used per request and never
  written or logged by the script, nor stored by the page.

### CAPI instances

Nodes are grouped into named **instances**, one tab each; switch between them with the tabs. An
instance is created by hand (name + the base URL of each node's admin port) or discovered:

**Discover instance** reads the `capi-admin` registrations that each node's admin MCP writes to its
Consul (`admin.mcp.consul`). Give the Consul URL, a Consul token with read access to the
`capi-admin` service, and the CAPI instance name — the nodes' `capi.instanceName`, or `default`. Every
node of that instance is listed with its version and health; nodes failing their check are shown
but not selected. If no node matches, the other instance names registered in that Consul are listed.
Discovery only finds nodes with `admin.mcp` enabled, and lists the address each node registered —
a pod or private IP is not reachable from a laptop without a VPN or port-forward.

Instances are kept in the browser's local storage. **Export** downloads them as JSON (names, node
URLs, Consul URL — never a token) to hand to a colleague, who loads it with **Import**.

The page follows the system's light or dark setting; the button next to **Authenticate** switches
between them, and the choice is remembered in the browser.

### Authentication

If the admin listener is protected (`admin.protected: true`), click **Authenticate** and enter the
issuer, client id and secret of a client-credentials client the admin API accepts. The script finds
the token endpoint through OIDC discovery and fetches the token; the header then shows the client
(`azp`) and when the token expires, and the token is renewed shortly before it does. **Paste a token
instead** takes an existing bearer token. Only the issuer and client id are remembered.

### Views

| Button | Shows |
|---|---|
| **Node info** | Every setting from `/info/capi`, one column per node, plus `/info/health`. Flags mixed CAPI versions, a node that is not `UP`, route and service counts that differ, and JWKS key ids a node has not loaded (a missed key rotation). Start time and uptime are shown, not compared |
| **List routes** | Every REST route and its endpoints, and on how many nodes it is present and identical. Click a route for its operations |
| **Invalid services** | Services a node refused to route, with the reason, and on which nodes |
| **Truststore** | Every certificate by alias, with its subject, expiry (expired in red, under 30 days in amber) and how many nodes hold the same certificate |

**Operations of a route** fetches the route's metadata and the OpenAPI spec CAPI loaded for it from
every node, then lists each operation with the security its spec declares and what CAPI's access gate
does with it — anonymous, token, policy (`opa-rego`) or subscription group, or refused because the
operation needs security and the route has no rule. Each column shows whether all nodes agree.

### Changing the trust store

In the **Truststore** view, paste a PEM certificate or choose a file, pick the node to send it
through, and click **Add**; a file holding several certificates is added one at a time. Each row has
a **Delete** button, which asks for a second click to confirm.

A change goes to one node, which writes the trust store to Consul KV; every node, that one
included, applies it on its next Consul KV sync. The page polls all nodes until they agree, for up to
two minutes. The alias is the certificate's CN, lower-cased, and cannot be chosen. Writes require
`admin.protected: true` and the Consul KV store — see [Trust store](../docs/admin-api.md).

---

## capi-admin-mcp-bridge

Bridges stdin/stdout to the admin MCP server's HTTP endpoint (`https://<node>:8390/mcp`), and
fetches and refreshes a client-credentials token on its own. Use it with MCP clients that can only
launch a local process, or that cannot refresh a client-credentials token themselves; clients with
HTTP and OAuth support can connect to the endpoint directly.

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
| `CAPI_MCP_URL` | yes | The admin MCP endpoint of any node |
| `CAPI_TOKEN_URL` | yes | Token endpoint of the issuer set in `admin.mcp.authorizationServer` |
| `CAPI_CLIENT_ID` / `CAPI_CLIENT_SECRET` | yes | Client-credentials client |
| `CAPI_SCOPE` | no | `scope` parameter of the token request |
| `CAPI_CA_FILE` | no | PEM bundle to trust (internal CA) |
| `CAPI_TIMEOUT` | no | Per-request timeout in seconds (default 30) |

Keep the secret in the client's environment or secret store, never in a committed file.

How it behaves:

- The token is refreshed 30 seconds before it expires. A `401` from CAPI triggers one forced refresh
  and one retry.
- Each message is sent on its own thread, so a slow `tools/call` does not hold up other messages.
- The protocol version negotiated in `initialize` is sent as `MCP-Protocol-Version` on later requests.
- Every request gets a JSON-RPC answer, even when the token endpoint or CAPI fails, so the client
  never waits forever.
- Logs go to stderr; stdout carries only MCP messages.

See [Admin MCP](../docs/admin-mcp.md) for the tools the server offers and how to configure it.
