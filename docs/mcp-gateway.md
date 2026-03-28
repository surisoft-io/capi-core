# MCP Gateway Capabilities into CAPI

## Status

Experimental

## Context

CAPI currently supports routing and governance for REST, SSE, and WebSocket traffic. Services self-register in Consul, and CAPI periodically queries the Consul catalog to discover services, read metadata, and dynamically create routes based on predefined rules.

The goal is for CAPI to expose internal services to Large Language Model (LLM) clients and agents using the Model Context Protocol (MCP). MCP enables LLMs to discover and invoke enterprise capabilities ("tools") in a structured, governed, and secure manner.

The challenge is to introduce MCP capabilities into CAPI without creating a parallel gateway, without breaking existing routing models, and without introducing shared memory or transport-level state.

## Decision

CAPI will be extended to act as an MCP Gateway by reusing its existing architectural primitives:

- Consul-based service discovery
- Undertow for HTTP, SSE, and WebSocket transport
- Hazelcast for distributed state
- Open Policy Agent (OPA) for authorization

MCP will be implemented as a logical interaction model layered on top of existing transports, not as a new transport type.

## Architecture Overview

### Logical Model

- MCP clients (LLMs, agents, tools) interact with CAPI using MCP semantics.
- CAPI exposes a single MCP endpoint that speaks the MCP wire protocol (JSON-RPC 2.0 over Streamable HTTP).
- MCP calls are authenticated and authorized using the existing CAPI security pipeline.
- MCP sessions provide logical state (distributed cache).

### Physical Model

- The MCP endpoint is exposed on the Undertow listener that already supports SSE and WebSocket traffic.
- This listener supports all content types, including `application/json` and `text/event-stream`.
- MCP clients see a single MCP endpoint and are not exposed to port-level separation.

## MCP Wire Protocol

CAPI implements the [MCP Streamable HTTP transport](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http) to ensure compatibility with standard MCP clients (Claude Desktop, Cursor, IDEs, custom agents).

### Single Endpoint

CAPI exposes a single `/mcp` endpoint. All MCP interactions are JSON-RPC 2.0 messages sent to this endpoint. CAPI parses the `method` field from the JSON-RPC message and routes internally.

### Supported JSON-RPC Methods

| Method | Description |
|---|---|
| `initialize` | Create an MCP session. Returns server capabilities and a `Mcp-Session-Id` header. |
| `tools/list` | Returns the aggregated tool catalog from all MCP-enabled Consul services. |
| `tools/call` | Invokes a tool. CAPI resolves the tool name to a backend service and forwards the request. |
| `ping` | Health check. Returns a JSON-RPC success response. |

### Response Format

- Standard tool calls return `application/json` with a JSON-RPC result.
- Streaming tool calls return `text/event-stream` when the tool declares streaming capability and the client sends `Accept: text/event-stream`.

### Session Header

Sessions are identified by the `Mcp-Session-Id` HTTP header, as defined in the MCP specification. Clients must include this header in all requests after `initialize`.

## MCP Session Management

MCP introduces the concept of a logical session to support multi-step interactions and streaming workflows.

- Sessions are explicitly created via the `initialize` JSON-RPC method.
- Each session is identified by a `Mcp-Session-Id`.
- Session metadata is stored in Hazelcast as a distributed, TTL-based cache entry.
- Sessions are logically stateful but physically stateless at the HTTP layer.

### Session Characteristics

- TTL-based expiration (default 30 minutes)
- Sliding expiration on successful MCP calls
- Explicit client-driven invalidation via a `close` notification
- Sessions are bound to the transport-level identity (JWT)
- CAPI rejects session creation if the client is not authorized (before allocating Hazelcast state)

## Authorization Model

The existing CAPI authorization pipeline is reused without replacement:

1. Incoming request carries a Bearer token
2. Token validation (signature, expiration)
3. Authorization decision via OPA (Rego)
4. Route execution or rejection (401 / 403)

For MCP, the OPA input is extended with additional context:

- MCP client identity (e.g. `claude-desktop`)
- MCP session metadata
- Tool name and tool metadata

OPA policies remain centralized and declarative. MCP does not introduce a new authorization engine.

## Tool Model

### Definition

In CAPI, an MCP tool is a logical abstraction mapped to a backend service via Undertow's HTTP proxy. Tools are not implemented by services directly and do not require services to be MCP-aware.

### Discovery and Aggregation

Tools are discovered dynamically via Consul service metadata. CAPI extends its existing discovery pipeline to register MCP tools when services declare MCP-related metadata.

CAPI aggregates tools from all Consul services with `mcp-enabled=true` into a unified catalog. When an MCP client calls `tools/list`, CAPI returns the combined tool list from all registered services. The `mcp-toolPrefix` field prevents name collisions across services (e.g. service `orders` with prefix `orders` exposes `orders.get`, `orders.create`).

### Tool Routing

Routing is tool-centric, not client-centric:

```
MCP Client -> JSON-RPC tools/call {name: "orders.get", arguments: {...}}
  -> CAPI parses JSON-RPC method + tool name
  -> Consul lookup: which service exposes "orders.get"?
  -> Forward request to the backend service via Undertow proxy
  -> Wrap service response in JSON-RPC result format
  -> Return to MCP client
```

The requested tool name determines the backend service. MCP client identity influences policy and behavior, not routing.

### Tool Schema

Each tool must declare a description and input schema so that LLM clients know how to call it. Tool schemas are provided via Consul metadata or derived from the service's OpenAPI definition (CAPI already supports OpenAPI retrieval via the Admin Gateway).

## Consul Metadata Extension

Existing Consul metadata remains unchanged:

- `type` = `rest` | `sse` | `websocket`

New MCP-specific metadata keys are introduced:

| Key | Description |
|---|---|
| `mcp-enabled` | Whether the service exposes MCP tools |
| `mcp-tools` | List of tool names |
| `mcp-tools-{name}-description` | Human-readable tool description (used by LLMs for tool selection) |
| `mcp-tools-{name}-inputSchema` | JSON Schema defining the tool's input parameters |
| `mcp-streaming` | List of tools that may emit SSE events |
| `mcp-category` | Semantic classification used as OPA input |
| `mcp-timeout` | Execution timeout budget |
| `mcp-toolPrefix` | (Optional) Namespace prefix for exposed tool names |

If a service has an OpenAPI definition and does not provide explicit `inputSchema` metadata, CAPI may derive the tool schema from the OpenAPI spec.

This approach avoids a separate MCP registry and keeps service registration simple and backward compatible.

## Streaming (SSE) Model

MCP does not mandate streaming for all calls.

- Default MCP calls use standard JSON request/response semantics.
- Streaming is opt-in and tool-driven.
- Streaming responses are delivered over the same `/mcp` endpoint using `text/event-stream`.

Streaming behavior is enabled only when:

1. The tool declares streaming capability (`mcp-streaming`)
2. The client explicitly requests streaming (e.g. `Accept: text/event-stream`)

Undertow manages the SSE connection lifecycle; tools are stateless request handlers.

## Scope

### In Scope

- MCP Streamable HTTP transport (JSON-RPC 2.0)
- `initialize`, `tools/list`, `tools/call`, `ping` methods
- Tool discovery and aggregation from Consul
- Session management via Hazelcast
- Authorization via existing JWT + OPA pipeline
- SSE streaming for tools that declare it

### Out of Scope (Initial Implementation)

The following are explicitly out of scope:

- **MCP Resources** (`resources/list`, `resources/read`) — may be added in a future iteration
- **MCP Prompts** (`prompts/list`, `prompts/get`) — may be added in a future iteration
- Hosting or executing LLM models
- Prompt management or prompt engineering
- Agent reasoning or orchestration logic
- Tool composition inside the gateway

These concerns remain client-side or in dedicated agent runtimes.

## Consequences

### Positive

- Reuses existing CAPI architecture and operational knowledge
- No new discovery, security, or state-management systems
- Clean separation of concerns
- Scales horizontally and safely
- Compatible with REST, SSE, and WebSocket workloads
- Standard MCP clients can connect without custom adapters

### Trade-offs

- Introduces session state (logical) into the gateway
- Requires careful observability and rate limiting
- Tool metadata quality becomes critical (descriptions and schemas directly affect LLM tool selection)
- Services must declare MCP metadata in Consul for their tools to be discoverable

---

## Implementation Details

### Status

Implemented

### Dedicated Port

The MCP Gateway runs on a dedicated Undertow server on port **8383** (configurable), following the same standalone-server pattern used by AdminGateway and WebsocketGateway. This keeps MCP traffic isolated from the REST gateway port.

### Configuration

Enable the MCP Gateway in `config.yaml`:

```yaml
capi:
  mcp:
    enabled: true
    port: 8383
    sessionTtl: 1800000      # 30 minutes (milliseconds)
    toolCallTimeout: 30000   # 30 seconds (milliseconds)
```

All fields have sensible defaults. When `enabled: false` (the default), no MCP listener is started.

### Admin Endpoints

When the MCP Gateway is enabled, the Admin API (default port 8381) exposes:

| Endpoint | Description |
|---|---|
| `GET /info/mcp` | MCP status: enabled, port, tool count, active sessions |
| `GET /info/mcp/tools` | Full tool catalog as JSON |
| `GET /info/mcp/sessions` | Active session count |

---

## Usage Guide

### Registering a Service as an MCP Tool Provider

Register your service in Consul with `mcp-*` metadata tags. The service itself does not need to know about MCP — CAPI translates MCP tool calls into REST calls against the service.

```json
{
  "ID": "order-service-1",
  "Name": "order-service",
  "Address": "10.0.1.50",
  "Port": 8080,
  "Meta": {
    "scheme": "http",
    "root-context": "/api",
    "mcp-enabled": "true",
    "mcp-toolPrefix": "orders",
    "mcp-tools": "get,create,search",
    "mcp-tools-get-description": "Get an order by ID",
    "mcp-tools-get-inputSchema": "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}",
    "mcp-tools-create-description": "Create a new order",
    "mcp-tools-create-inputSchema": "{\"type\":\"object\",\"properties\":{\"product\":{\"type\":\"string\"},\"quantity\":{\"type\":\"integer\"}}}",
    "mcp-tools-search-description": "Search orders by criteria",
    "mcp-tools-search-inputSchema": "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
    "mcp-category": "commerce",
    "mcp-timeout": "10000",
    "mcp-streaming": "search"
  }
}
```

With `mcp-toolPrefix: "orders"`, the tools are exposed as `orders.get`, `orders.create`, and `orders.search`. When an agent calls `orders.get` with `{"orderId": "12345"}`, CAPI POSTs `{"orderId": "12345"}` to `http://10.0.1.50:8080/api`.

### Connecting Claude Desktop or Cursor

Add CAPI as an MCP server in your client configuration:

**Claude Desktop** (`claude_desktop_config.json`):

Claude Desktop only supports local stdio servers in its config file. Use the `mcp-remote` npm package to bridge stdio to CAPI's HTTP endpoint:

```json
{
  "mcpServers": {
    "capi": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8383/mcp"]
    }
  }
}
```

> Requires Node.js. The `npx -y` flag auto-installs `mcp-remote` on first use. If authorization is enabled, pass headers via `mcp-remote` flags — see the [mcp-remote docs](https://www.npmjs.com/package/mcp-remote).

The MCP client handles the full session lifecycle automatically: `initialize` (gets session ID) -> `tools/list` (discovers tools) -> `tools/call` (invokes tools as the LLM decides).

### curl Walkthrough

```bash
# 1. Initialize — creates a session, returns capabilities
curl -s -D- -X POST http://localhost:8383/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","method":"initialize","id":1}'

# Response headers include: Mcp-Session-Id: <uuid>
# Response body:
# {
#   "jsonrpc": "2.0",
#   "result": {
#     "protocolVersion": "2025-03-26",
#     "capabilities": { "tools": { "listChanged": false } },
#     "serverInfo": { "name": "CAPI MCP Gateway", "version": "1.0.0" }
#   },
#   "id": 1
# }

# 2. List tools — returns all tools from MCP-enabled Consul services
curl -s -X POST http://localhost:8383/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":2}'

# 3. Call a tool — CAPI forwards to the backend service
curl -s -X POST http://localhost:8383/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":3,"params":{"name":"orders.get","arguments":{"orderId":"12345"}}}'

# Response:
# {
#   "jsonrpc": "2.0",
#   "result": {
#     "content": [{ "type": "text", "text": "{\"id\":\"12345\",\"status\":\"shipped\"}" }]
#   },
#   "id": 3
# }

# 4. Streaming tool call (SSE) — for tools that declare streaming
curl -N -X POST http://localhost:8383/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -H "Mcp-Session-Id: <session-id>" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":4,"params":{"name":"orders.search","arguments":{"query":"pending"}}}'

# 5. Ping — simple health check over JSON-RPC
curl -s -X POST http://localhost:8383/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"ping","id":5}'

# 6. Health endpoint (plain HTTP, no JSON-RPC)
curl http://localhost:8383/mcp/health

# 7. Admin introspection
curl http://localhost:8381/info/mcp
curl http://localhost:8381/info/mcp/tools
```

### Python Agent Integration

```python
import requests

BASE = "http://localhost:8383/mcp"
HEADERS = {"Content-Type": "application/json"}

def jsonrpc(method, params=None, req_id=1, extra_headers=None):
    body = {"jsonrpc": "2.0", "method": method, "id": req_id}
    if params:
        body["params"] = params
    h = {**HEADERS, **(extra_headers or {})}
    return requests.post(BASE, json=body, headers=h)

# Initialize
resp = jsonrpc("initialize", extra_headers={"Authorization": "Bearer <token>"})
session_id = resp.headers["Mcp-Session-Id"]
session_headers = {"Mcp-Session-Id": session_id}
print(f"Session: {session_id}")

# Discover tools
resp = jsonrpc("tools/list", req_id=2, extra_headers=session_headers)
tools = resp.json()["result"]["tools"]
for t in tools:
    print(f"  {t['name']}: {t['description']}")

# Call a tool
resp = jsonrpc("tools/call", req_id=3, extra_headers=session_headers,
               params={"name": "orders.get", "arguments": {"orderId": "12345"}})
content = resp.json()["result"]["content"][0]["text"]
print(f"Result: {content}")
```

### LLM Agent Loop Pattern

A typical agent bridges LLM function-calling with MCP tool invocation:

```python
def agent_loop(user_message, session_id):
    # 1. Discover tools from CAPI MCP
    tools = mcp_list_tools(session_id)

    # 2. Present tools to the LLM as function definitions
    llm_response = llm.chat(
        messages=[{"role": "user", "content": user_message}],
        tools=[{
            "type": "function",
            "function": {
                "name": t["name"],
                "description": t["description"],
                "parameters": t["inputSchema"]
            }
        } for t in tools]
    )

    # 3. If the LLM decided to call a tool, forward to CAPI
    if llm_response.tool_calls:
        for call in llm_response.tool_calls:
            result = mcp_call_tool(session_id, call.name, call.arguments)
            # 4. Feed tool result back to LLM for the final answer
            return llm.chat(messages=[
                {"role": "user", "content": user_message},
                {"role": "assistant", "tool_calls": [call]},
                {"role": "tool", "content": result, "tool_call_id": call.id}
            ])

    return llm_response
```

### Error Handling

All errors are returned as standard JSON-RPC error responses:

| Code | Meaning | When |
|---|---|---|
| `-32700` | Parse error | Request body is not valid JSON |
| `-32600` | Invalid request | Missing `jsonrpc: "2.0"` or missing `method` |
| `-32601` | Method not found | Unknown JSON-RPC method |
| `-32602` | Invalid params | Missing tool name, tool not found |
| `-32603` | Internal error | Backend timeout, connection failure |
| `-32000` | Auth error | Missing/invalid token, OPA policy denied |

Example error response:

```json
{
  "jsonrpc": "2.0",
  "error": {
    "code": -32602,
    "message": "Tool not found: orders.delete"
  },
  "id": 3
}