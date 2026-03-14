#!/bin/bash
#
# Interactive MCP Gateway demo script.
# Walks through initialize -> tools/list -> tools/call for each service.
#
# Requirements: curl, jq
# Usage: ./test-mcp.sh [host:port]
#
set -e

BASE="${1:-http://localhost}/mcp"
BOLD="\033[1m"
DIM="\033[2m"
GREEN="\033[32m"
CYAN="\033[36m"
RESET="\033[0m"

pause() {
  echo ""
  echo -e "${DIM}Press Enter to continue...${RESET}"
  read -r
}

echo -e "${BOLD}================================================${RESET}"
echo -e "${BOLD}  CAPI MCP Gateway Demo (REST + MCP Server)${RESET}"
echo -e "${BOLD}================================================${RESET}"
echo ""
echo -e "Endpoint: ${CYAN}${BASE}${RESET}"
echo ""

# --- Step 1: Initialize ---
echo -e "${BOLD}[1/7] Initialize session${RESET}"
echo -e "${DIM}POST ${BASE}${RESET}"
echo -e "${DIM}Method: initialize${RESET}"
echo ""

INIT_RESPONSE=$(curl -s -w "\n%{http_code}" -D /tmp/mcp-headers "$BASE" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","id":1}')

HTTP_CODE=$(echo "$INIT_RESPONSE" | tail -1)
INIT_BODY=$(echo "$INIT_RESPONSE" | sed '$d')

SESSION_ID=$(grep -i "mcp-session-id" /tmp/mcp-headers | tr -d '\r' | awk '{print $2}')

echo -e "${GREEN}HTTP ${HTTP_CODE}${RESET}"
echo -e "Session ID: ${CYAN}${SESSION_ID}${RESET}"
echo "$INIT_BODY" | jq .

pause

# --- Step 2: Ping ---
echo -e "${BOLD}[2/7] Ping${RESET}"
echo -e "${DIM}Method: ping${RESET}"
echo ""

curl -s "$BASE" \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"ping","id":2}' | jq .

pause

# --- Step 3: List tools ---
echo -e "${BOLD}[3/7] List available tools${RESET}"
echo -e "${DIM}Method: tools/list${RESET}"
echo ""

TOOLS=$(curl -s "$BASE" \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":3}')

echo "$TOOLS" | jq .

TOOL_COUNT=$(echo "$TOOLS" | jq '.result.tools | length')
echo ""
echo -e "Discovered ${GREEN}${TOOL_COUNT}${RESET} tools (REST backends + MCP Server backends)."

pause

# --- Step 4: Call weather_forecast (REST backend) ---
echo -e "${BOLD}[4/7] Call tool: weather_forecast  ${DIM}(REST backend)${RESET}"
echo -e "${DIM}Method: tools/call${RESET}"
echo -e "${DIM}Arguments: {\"city\": \"Lisbon\"}${RESET}"
echo ""

curl -s "$BASE" \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "id": 4,
    "params": {
      "name": "weather_forecast",
      "arguments": {"city": "Lisbon"}
    }
  }' | jq .

pause

# --- Step 5: Call inventory_lookup (REST backend) ---
echo -e "${BOLD}[5/7] Call tool: inventory_lookup  ${DIM}(REST backend)${RESET}"
echo -e "${DIM}Method: tools/call${RESET}"
echo -e "${DIM}Arguments: {\"product\": \"laptop\"}${RESET}"
echo ""

curl -s "$BASE" \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "id": 5,
    "params": {
      "name": "inventory_lookup",
      "arguments": {"product": "laptop"}
    }
  }' | jq .

pause

# --- Step 6: Call math_calculate (MCP Server backend) ---
echo -e "${BOLD}[6/7] Call tool: math_calculate  ${CYAN}(MCP Server backend)${RESET}"
echo -e "${DIM}Method: tools/call${RESET}"
echo -e "${DIM}Arguments: {\"expression\": \"sqrt(144) + 2**3\"}${RESET}"
echo -e "${DIM}This tool was discovered automatically from the MCP Server's tools/list.${RESET}"
echo -e "${DIM}CAPI forwards as JSON-RPC to the backend (not plain REST).${RESET}"
echo ""

curl -s "$BASE" \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "id": 6,
    "params": {
      "name": "math_calculate",
      "arguments": {"expression": "sqrt(144) + 2**3"}
    }
  }' | jq .

pause

# --- Step 7: Call math_convert (MCP Server backend) ---
echo -e "${BOLD}[7/7] Call tool: math_convert  ${CYAN}(MCP Server backend)${RESET}"
echo -e "${DIM}Method: tools/call${RESET}"
echo -e "${DIM}Arguments: {\"value\": 100, \"from_unit\": \"km\", \"to_unit\": \"miles\"}${RESET}"
echo ""

curl -s "$BASE" \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "id": 7,
    "params": {
      "name": "math_convert",
      "arguments": {"value": 100, "from_unit": "km", "to_unit": "miles"}
    }
  }' | jq .

echo ""
echo -e "${BOLD}================================================${RESET}"
echo -e "${BOLD}  Demo complete${RESET}"
echo -e "${BOLD}================================================${RESET}"
echo ""
echo "Try it yourself:"
echo ""
echo "  # REST backend — translate text"
echo "  curl -s $BASE \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -H 'Mcp-Session-Id: $SESSION_ID' \\"
echo "    -d '{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":10,\"params\":{\"name\":\"translate_text\",\"arguments\":{\"text\":\"hello\",\"target_language\":\"portuguese\"}}}' | jq ."
echo ""
echo "  # MCP Server backend — statistics"
echo "  curl -s $BASE \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -H 'Mcp-Session-Id: $SESSION_ID' \\"
echo "    -d '{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":11,\"params\":{\"name\":\"math_statistics\",\"arguments\":{\"numbers\":[10,20,30,40,50]}}}' | jq ."
echo ""
echo "  # Check admin API"
echo "  curl -s http://localhost:8381/info/mcp | jq ."
echo "  curl -s http://localhost:8381/info/mcp/tools | jq ."
echo ""
echo "  # Consul catalog"
echo "  curl -s http://localhost:8500/v1/agent/services | jq ."
