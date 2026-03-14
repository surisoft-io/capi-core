#!/bin/sh
set -e

CONSUL="http://consul:8500"

echo "Waiting for Consul..."
until curl -sf "$CONSUL/v1/status/leader" > /dev/null 2>&1; do
  sleep 1
done
echo "Consul is ready."

# Pre-compute SHA-256 hashes for API keys
# Key format: <service>-unlimited, <service>-standard, <service>-limited
UNLIMITED_HASH=$(printf '%s' "$API_KEY_UNLIMITED" | sha256sum | awk '{print $1}')
STANDARD_HASH=$(printf '%s' "$API_KEY_STANDARD" | sha256sum | awk '{print $1}')
LIMITED_HASH=$(printf '%s' "$API_KEY_LIMITED" | sha256sum | awk '{print $1}')

PAYLOAD="[{\"keyHash\":\"$UNLIMITED_HASH\",\"name\":\"unlimited-key\",\"enabled\":true,\"throttleTotalCalls\":-1,\"throttleDuration\":-1},{\"keyHash\":\"$STANDARD_HASH\",\"name\":\"standard-key\",\"enabled\":true,\"throttleTotalCalls\":10,\"throttleDuration\":30000},{\"keyHash\":\"$LIMITED_HASH\",\"name\":\"limited-key\",\"enabled\":true,\"throttleTotalCalls\":3,\"throttleDuration\":30000}]"

# Create API keys for all 5 API-key services
for SVC in inventory-service shipping-service returns-service export-service sync-service; do
  echo "Creating API keys for ${SVC}:v1..."
  curl -sf -X PUT "$CONSUL/v1/kv/capi-api-keys/${SVC}:v1" \
    -d "$PAYLOAD"
  echo " OK"
done

echo ""
echo "API Key setup complete!"
echo "  Services: inventory, shipping, returns, export, sync"
echo "  Keys:"
echo "    API_KEY_UNLIMITED  -> no throttle"
echo "    API_KEY_STANDARD   -> 10 calls / 30s"
echo "    API_KEY_LIMITED    -> 3 calls / 30s"
