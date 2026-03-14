#!/bin/sh
set -e

KEYCLOAK="http://keycloak:8080/keycloak"
REALM="e2e-demo"
EXTERNAL_URL="${EXTERNAL_URL:-http://localhost}"
KC_ADMIN_USER="${KC_ADMIN_USER:?KC_ADMIN_USER is required}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:?KC_ADMIN_PASSWORD is required}"
BFF_CLIENT_SECRET="${BFF_CLIENT_SECRET:?BFF_CLIENT_SECRET is required}"
PREMIUM_CLIENT_SECRET="${PREMIUM_CLIENT_SECRET:?PREMIUM_CLIENT_SECRET is required}"
BASIC_CLIENT_SECRET="${BASIC_CLIENT_SECRET:?BASIC_CLIENT_SECRET is required}"
ALICE_PASSWORD="${ALICE_PASSWORD:?ALICE_PASSWORD is required}"
BOB_PASSWORD="${BOB_PASSWORD:?BOB_PASSWORD is required}"
CHARLIE_PASSWORD="${CHARLIE_PASSWORD:?CHARLIE_PASSWORD is required}"

# Install jq
apk add --no-cache jq > /dev/null 2>&1

echo "Waiting for Keycloak..."
ATTEMPTS=0
until curl -sf "$KEYCLOAK/realms/master" > /dev/null 2>&1; do
  ATTEMPTS=$((ATTEMPTS + 1))
  if [ "$ATTEMPTS" -ge 90 ]; then
    echo "Keycloak did not become ready after 3 minutes, giving up."
    exit 1
  fi
  sleep 2
done
echo "Keycloak is ready."

# --- Get admin token ---
echo "Getting admin token..."
ADMIN_TOKEN=$(curl -sf -X POST "$KEYCLOAK/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=$KC_ADMIN_USER" \
  -d "password=$KC_ADMIN_PASSWORD" \
  -d "grant_type=password" | jq -r '.access_token')

AUTH="Authorization: Bearer $ADMIN_TOKEN"

# --- Create realm ---
echo "Creating realm '$REALM'..."
curl -sf -X POST "$KEYCLOAK/admin/realms" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{\"realm\": \"$REALM\", \"enabled\": true}" || true
echo " OK"

# --- Create client: demo-bff (confidential, auth code flow) ---
echo "Creating client 'demo-bff'..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/clients" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "demo-bff",
    "enabled": true,
    "protocol": "openid-connect",
    "publicClient": false,
    "secret": "'"$BFF_CLIENT_SECRET"'",
    "directAccessGrantsEnabled": true,
    "standardFlowEnabled": true,
    "serviceAccountsEnabled": false,
    "redirectUris": ["'"$EXTERNAL_URL"'/bff/callback", "http://nginx/bff/callback"],
    "webOrigins": ["'"$EXTERNAL_URL"'"],
    "attributes": {"post.logout.redirect.uris": "'"$EXTERNAL_URL"'##http://nginx"}
  }' || true
echo " OK"

# --- Create client: client-premium (subscription: all groups) ---
echo "Creating client 'client-premium'..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/clients" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "client-premium",
    "enabled": true,
    "protocol": "openid-connect",
    "publicClient": false,
    "secret": "'"$PREMIUM_CLIENT_SECRET"'",
    "directAccessGrantsEnabled": true,
    "standardFlowEnabled": false,
    "serviceAccountsEnabled": false
  }' || true
echo " OK"

# --- Create client: client-basic (subscription: notifications only) ---
echo "Creating client 'client-basic'..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/clients" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "client-basic",
    "enabled": true,
    "protocol": "openid-connect",
    "publicClient": false,
    "secret": "'"$BASIC_CLIENT_SECRET"'",
    "directAccessGrantsEnabled": true,
    "standardFlowEnabled": false,
    "serviceAccountsEnabled": false
  }' || true
echo " OK"

# --- Get internal client IDs ---
BFF_ID=$(curl -sf "$KEYCLOAK/admin/realms/$REALM/clients?clientId=demo-bff" \
  -H "$AUTH" | jq -r '.[0].id')
PREMIUM_ID=$(curl -sf "$KEYCLOAK/admin/realms/$REALM/clients?clientId=client-premium" \
  -H "$AUTH" | jq -r '.[0].id')
BASIC_ID=$(curl -sf "$KEYCLOAK/admin/realms/$REALM/clients?clientId=client-basic" \
  -H "$AUTH" | jq -r '.[0].id')

# --- Add subscription claim mappers ---

# demo-bff: all subscription groups (used by logged-in users)
echo "Adding claim mappers to demo-bff..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/clients/$BFF_ID/protocol-mappers/models" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "subscriptions",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-hardcoded-claim-mapper",
    "config": {
      "claim.name": "subscriptions",
      "claim.value": "[\"notifications\",\"recommendations\",\"messaging\",\"partner\"]",
      "jsonType.label": "JSON",
      "id.token.claim": "true",
      "access.token.claim": "true",
      "userinfo.token.claim": "false"
    }
  }' || true
echo " OK"

# client-premium: all subscription groups
echo "Adding claim mappers to client-premium..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/clients/$PREMIUM_ID/protocol-mappers/models" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "subscriptions",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-hardcoded-claim-mapper",
    "config": {
      "claim.name": "subscriptions",
      "claim.value": "[\"notifications\",\"recommendations\",\"messaging\",\"partner\"]",
      "jsonType.label": "JSON",
      "id.token.claim": "true",
      "access.token.claim": "true",
      "userinfo.token.claim": "false"
    }
  }' || true
echo " OK"

# client-basic: notifications only
echo "Adding claim mappers to client-basic..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/clients/$BASIC_ID/protocol-mappers/models" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "subscriptions",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-hardcoded-claim-mapper",
    "config": {
      "claim.name": "subscriptions",
      "claim.value": "[\"notifications\"]",
      "jsonType.label": "JSON",
      "id.token.claim": "true",
      "access.token.claim": "true",
      "userinfo.token.claim": "false"
    }
  }' || true
echo " OK"

# --- Create realm roles ---
echo "Creating realm roles..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/roles" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"name": "admin"}' || true

curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/roles" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"name": "manager"}' || true

curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/roles" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"name": "viewer"}' || true
echo " OK"

# --- Get role IDs ---
ADMIN_ROLE=$(curl -sf "$KEYCLOAK/admin/realms/$REALM/roles/admin" \
  -H "$AUTH" | jq -c '{id: .id, name: .name}')
MANAGER_ROLE=$(curl -sf "$KEYCLOAK/admin/realms/$REALM/roles/manager" \
  -H "$AUTH" | jq -c '{id: .id, name: .name}')
VIEWER_ROLE=$(curl -sf "$KEYCLOAK/admin/realms/$REALM/roles/viewer" \
  -H "$AUTH" | jq -c '{id: .id, name: .name}')

# --- Create user: alice (admin) ---
echo "Creating user 'alice' (admin)..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/users" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "alice",
    "firstName": "Alice",
    "lastName": "Admin",
    "email": "alice@e2e-demo.com",
    "enabled": true,
    "emailVerified": true,
    "requiredActions": [],
    "credentials": [{"type": "password", "value": "'"$ALICE_PASSWORD"'", "temporary": false}]
  }' || true

ALICE_ID=$(curl -sf "$KEYCLOAK/admin/realms/$REALM/users?username=alice" \
  -H "$AUTH" | jq -r '.[0].id')

curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/users/$ALICE_ID/role-mappings/realm" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "[$ADMIN_ROLE]"
echo " OK"

# --- Create user: bob (manager) ---
echo "Creating user 'bob' (manager)..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/users" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "bob",
    "firstName": "Bob",
    "lastName": "Manager",
    "email": "bob@e2e-demo.com",
    "enabled": true,
    "emailVerified": true,
    "requiredActions": [],
    "credentials": [{"type": "password", "value": "'"$BOB_PASSWORD"'", "temporary": false}]
  }' || true

BOB_ID=$(curl -sf "$KEYCLOAK/admin/realms/$REALM/users?username=bob" \
  -H "$AUTH" | jq -r '.[0].id')

curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/users/$BOB_ID/role-mappings/realm" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "[$MANAGER_ROLE]"
echo " OK"

# --- Create user: charlie (viewer) ---
echo "Creating user 'charlie' (viewer)..."
curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/users" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "charlie",
    "firstName": "Charlie",
    "lastName": "Viewer",
    "email": "charlie@e2e-demo.com",
    "enabled": true,
    "emailVerified": true,
    "requiredActions": [],
    "credentials": [{"type": "password", "value": "'"$CHARLIE_PASSWORD"'", "temporary": false}]
  }' || true

CHARLIE_ID=$(curl -sf "$KEYCLOAK/admin/realms/$REALM/users?username=charlie" \
  -H "$AUTH" | jq -r '.[0].id')

curl -sf -X POST "$KEYCLOAK/admin/realms/$REALM/users/$CHARLIE_ID/role-mappings/realm" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "[$VIEWER_ROLE]"
echo " OK"

echo ""
echo "Keycloak setup complete!"
echo "  Realm:   $REALM"
echo "  Clients: demo-bff (all subs), client-premium (all subs), client-basic (notifications only)"
echo "  Roles:   admin, manager, viewer"
echo "  Users:   alice (admin), bob (manager), charlie (viewer)"
