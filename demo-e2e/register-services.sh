#!/bin/sh
set -e

CONSUL="http://consul:8500"
ADDR="generic-service"

echo "Waiting for Consul..."
until curl -sf "$CONSUL/v1/status/leader" > /dev/null 2>&1; do
  sleep 1
done
echo "Consul is ready."

register() {
  ID="$1"
  NAME="$2"
  META="$3"
  SVC_TYPE="${4:-rest}"
  echo "  Registering $NAME (type: $SVC_TYPE)..."
  curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
    -H "Content-Type: application/json" \
    -d "{
      \"ID\": \"${ID}\",
      \"Name\": \"${NAME}\",
      \"Port\": 8080,
      \"Address\": \"${ADDR}\",
      \"Meta\": {
        \"group\": \"v1\",
        \"root-context\": \"/${NAME}\",
        \"scheme\": \"http\",
        \"type\": \"${SVC_TYPE}\",
        \"capi-instance\": \"default\"${META}
      }
    }"
}

# ============================================================
# OPEN SERVICES (5) — no security
# ============================================================
echo ""
echo "=== Open Services (5) ==="

register "health-1"   "health-service"   "" "websocket"
register "config-1"   "config-service"   "" "websocket"
register "search-1"   "search-service"   "" "websocket"
register "events-1"   "events-service"   "" "websocket"
register "webhook-1"  "webhook-service"  "" "websocket"

# ============================================================
# OPA-PROTECTED SERVICES (10) — secured + opa-rego
# ============================================================
echo ""
echo "=== OPA-Protected Services (10) ==="

# allow_all: admin, manager, viewer
register "user-1"     "user-service"     ', "opa-rego": "capi/allow_all"'
register "profile-1"  "profile-service"  ', "opa-rego": "capi/allow_all"'
register "catalog-1"  "catalog-service"  ', "opa-rego": "capi/allow_all"'

# manager_or_admin: admin, manager
register "pricing-1"  "pricing-service"  ', "opa-rego": "capi/manager_or_admin"'
register "order-1"    "order-service"    ', "opa-rego": "capi/manager_or_admin"' "websocket"

# admin_only: admin
register "payment-1"  "payment-service"  ', "opa-rego": "capi/admin_only"' "websocket"
register "invoice-1"  "invoice-service"  ', "opa-rego": "capi/admin_only"'
register "audit-1"    "audit-service"    ', "opa-rego": "capi/admin_only"'

# owner_only: admin
register "logging-1"  "logging-service"  ', "opa-rego": "capi/owner_only"'
register "storage-1"  "storage-service"  ', "opa-rego": "capi/owner_only"'

# ============================================================
# SUBSCRIPTION-GROUP SERVICES (10) — secured + subscription-group
# ============================================================
echo ""
echo "=== Subscription-Group Services (10) ==="

register "notification-1"    "notification-service"    ', "secured": "true", "subscription-group": "notifications"'
register "preferences-1"     "preferences-service"     ', "secured": "true", "subscription-group": "notifications"'

register "recommendation-1"  "recommendation-service"  ', "secured": "true", "subscription-group": "recommendations"'

register "email-1"           "email-service"           ', "secured": "true", "subscription-group": "messaging"'
register "sms-1"             "sms-service"             ', "secured": "true", "subscription-group": "messaging"'
register "push-1"            "push-service"            ', "secured": "true", "subscription-group": "messaging"'
register "chat-1"            "chat-service"            ', "secured": "true", "subscription-group": "messaging"'

register "partner-api-1"     "partner-api"             ', "secured": "true", "subscription-group": "partner"'
register "marketplace-1"     "marketplace-service"     ', "secured": "true", "subscription-group": "partner"'
register "integration-1"     "integration-service"     ', "secured": "true", "subscription-group": "partner"'

# ============================================================
# API-KEY + PER-KEY THROTTLE SERVICES (5) — api-key-enabled
# ============================================================
echo ""
echo "=== API-Key Services (5) ==="

register "inventory-1"  "inventory-service"  ', "api-key-enabled": "true"'
register "shipping-1"   "shipping-service"   ', "api-key-enabled": "true"'
register "returns-1"    "returns-service"    ', "api-key-enabled": "true"'
register "export-1"     "export-service"     ', "api-key-enabled": "true"'
register "sync-1"       "sync-service"       ', "api-key-enabled": "true"'

# ============================================================
# GLOBAL THROTTLE SERVICES (5) — throttle + throttleGlobal
# ============================================================
echo ""
echo "=== Global Throttle Services (5) ==="

register "metrics-1"    "metrics-service"    ', "throttle": "true", "throttleGlobal": "true", "throttleTotalCalls": "20", "throttleDuration": "30000"'
register "reporting-1"  "reporting-service"  ', "throttle": "true", "throttleGlobal": "true", "throttleTotalCalls": "15", "throttleDuration": "30000"'
register "dashboard-1"  "dashboard-service"  ', "throttle": "true", "throttleGlobal": "true", "throttleTotalCalls": "10", "throttleDuration": "30000"'
register "alerts-1"     "alerts-service"     ', "throttle": "true", "throttleGlobal": "true", "throttleTotalCalls": "10", "throttleDuration": "30000"'
register "cache-1"      "cache-service"      ', "throttle": "true", "throttleGlobal": "true", "throttleTotalCalls": "30", "throttleDuration": "30000"'

# ============================================================
# WEBSOCKET SERVICES (1) — secured + websocket type
# ============================================================
echo ""
echo "=== WebSocket Services (1) ==="

echo "  Registering websocket-service..."
curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "websocket-1",
    "Name": "websocket-service",
    "Port": 9090,
    "Address": "websocket-service",
    "Meta": {
      "group": "v1",
      "root-context": "/",
      "scheme": "http",
      "type": "websocket",
      "secured": "true",
      "subscription-group": "notifications",
      "capi-instance": "default"
    }
  }'

# ============================================================
# SSE SERVICES (1) — secured + sse type
# ============================================================
echo ""
echo "=== SSE Services (1) ==="

echo "  Registering sse-service..."
curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "sse-1",
    "Name": "sse-service",
    "Port": 9091,
    "Address": "sse-service",
    "Meta": {
      "group": "v1",
      "scheme": "http",
      "type": "sse",
      "secured": "true",
      "subscription-group": "notifications",
      "capi-instance": "default"
    }
  }'

# ============================================================
# MCP SERVICES (4) — MCP-enabled REST + MCP Server backends
# ============================================================
echo ""
echo "=== MCP Services (4) ==="

echo "  Registering weather (MCP REST)..."
curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "weather-1",
    "Name": "weather",
    "Port": 8080,
    "Address": "weather-service",
    "Meta": {
      "group": "v1",
      "root-context": "/",
      "scheme": "http",
      "type": "rest",
      "capi-instance": "default",
      "secured": "false",
      "mcp-enabled": "true",
      "mcp-toolPrefix": "weather",
      "mcp-category": "utilities",
      "mcp-tools": "forecast",
      "mcp-tools-forecast-description": "Get current weather and 3-day forecast for a city. Returns temperature, humidity, wind speed, and conditions.",
      "mcp-tools-forecast-inputSchema": "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"City name (e.g. Lisbon, London, Tokyo)\"}},\"required\":[\"city\"]}"
    }
  }'

echo "  Registering inventory (MCP REST)..."
curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "inventory-mcp-1",
    "Name": "inventory-mcp",
    "Port": 8080,
    "Address": "inventory-mcp-service",
    "Meta": {
      "group": "v1",
      "root-context": "/",
      "scheme": "http",
      "type": "rest",
      "capi-instance": "default",
      "secured": "false",
      "mcp-enabled": "true",
      "mcp-toolPrefix": "inventory",
      "mcp-category": "commerce",
      "mcp-tools": "lookup",
      "mcp-tools-lookup-description": "Check product availability, stock quantity, price, and warehouse location. Available products: laptop, headphones, keyboard, monitor, mouse, webcam, chair, desk.",
      "mcp-tools-lookup-inputSchema": "{\"type\":\"object\",\"properties\":{\"product\":{\"type\":\"string\",\"description\":\"Product name to look up\"}},\"required\":[\"product\"]}"
    }
  }'

echo "  Registering translate (MCP REST)..."
curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "translate-1",
    "Name": "translate",
    "Port": 8080,
    "Address": "translate-service",
    "Meta": {
      "group": "v1",
      "root-context": "/",
      "scheme": "http",
      "type": "rest",
      "capi-instance": "default",
      "secured": "false",
      "mcp-enabled": "true",
      "mcp-toolPrefix": "translate",
      "mcp-category": "language",
      "mcp-tools": "text",
      "mcp-tools-text-description": "Translate text to a target language. Supported languages: spanish, french, german, portuguese, japanese, italian.",
      "mcp-tools-text-inputSchema": "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"description\":\"Text to translate\"},\"target_language\":{\"type\":\"string\",\"description\":\"Target language (spanish, french, german, portuguese, japanese, italian)\"}},\"required\":[\"text\",\"target_language\"]}"
    }
  }'

echo "  Registering math-mcp (MCP Server)..."
curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "math-mcp-1",
    "Name": "math-mcp",
    "Port": 8080,
    "Address": "math-mcp-server",
    "Meta": {
      "group": "v1",
      "root-context": "/",
      "scheme": "http",
      "type": "rest",
      "capi-instance": "default",
      "secured": "false",
      "mcp-enabled": "true",
      "mcp-type": "server",
      "mcp-toolPrefix": "math",
      "mcp-category": "utilities"
    }
  }'

# ============================================================
# gRPC SERVICES (1) — gRPC type, routed via x-capi-service header
# ============================================================
echo ""
echo "=== gRPC Services (1) ==="

echo "  Registering greeter (gRPC)..."
curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "greeter-grpc-1",
    "Name": "greeter",
    "Port": 50051,
    "Address": "greeter-grpc",
    "Meta": {
      "group": "dev",
      "root-context": "/",
      "scheme": "http",
      "type": "grpc",
      "capi-instance": "default",
      "secured": "false"
    }
  }'

echo ""
echo "All 42 services registered!"
echo "  Open:               5 (health, config, search, events, webhook)"
echo "  OPA-protected:     10 (allow_all=3, manager_or_admin=2, admin_only=3, owner_only=2)"
echo "  Subscription-group: 10 (notifications=2, recommendations=1, messaging=4, partner=3)"
echo "  API-key + throttle:  5 (inventory, shipping, returns, export, sync)"
echo "  Global throttle:     5 (metrics, reporting, dashboard, alerts, cache)"
echo "  WebSocket:           1 (websocket-service)"
echo "  SSE:                 1 (sse-service)"
echo "  MCP REST:            3 (weather, inventory, translate)"
echo "  MCP Server:          1 (math-mcp — auto-discovered tools)"
echo "  gRPC:                1 (greeter — HTTP/2 via x-capi-service header)"
