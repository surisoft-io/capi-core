#!/bin/sh
set -e

CONSUL="http://consul:8500"

echo "Waiting for Consul..."
until curl -sf "$CONSUL/v1/status/leader" > /dev/null 2>&1; do
  sleep 1
done
echo "Consul is ready."

# --- Weather Service ---
echo "Registering weather service..."
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
echo " OK"

# --- Inventory Service ---
echo "Registering inventory service..."
curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
  -H "Content-Type: application/json" \
  -d '{
    "ID": "inventory-1",
    "Name": "inventory",
    "Port": 8080,
    "Address": "inventory-service",
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
echo " OK"

# --- Translation Service ---
echo "Registering translation service..."
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
echo " OK"

# --- Math MCP Server ---
# This is a real MCP Server backend (mcp-type: server).
# No mcp-tools metadata needed — CAPI discovers tools via tools/list.
echo "Registering math MCP Server..."
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
echo " OK"

# --- Greeter gRPC Service ---
# Registered with type=grpc so CAPI routes it through the gRPC gateway.
echo "Registering greeter gRPC service..."
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
echo " OK"

echo ""
echo "All services registered (3 REST + 1 MCP Server + 1 gRPC). CAPI will discover them within 5 seconds."
