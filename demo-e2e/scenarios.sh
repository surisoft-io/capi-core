#!/bin/bash
# End-to-end Consul discovery scenarios against the rewritten ConsulCatalogService.
# Requires docker-compose stack to be up. Uses localhost ports (8500 Consul, 8380 REST, 8381 Admin).

set -u

CONSUL="http://localhost:8500"
CAPI_REST="http://localhost:8380"
CAPI_ADMIN="http://localhost:8381"
CYCLE_WAIT=14   # one cycle = 10s; we wait 14s for safety margin
PASS=0
FAIL=0

ok()   { echo "  [PASS] $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
note() { echo "  [NOTE] $1"; }
hdr()  { echo ""; echo "=== $1 ==="; }

register_svc() {
    # $1=id $2=name $3=group $4=port $5=extra_meta (JSON fragment, may be empty)
    local id="$1" name="$2" group="$3" port="$4" extra="${5:-}"
    curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
        -H "Content-Type: application/json" \
        -d "{\"ID\":\"$id\",\"Name\":\"$name\",\"Port\":$port,\"Address\":\"generic-service\",\"Meta\":{\"group\":\"$group\",\"root-context\":\"/$name\",\"scheme\":\"http\",\"type\":\"rest\",\"capi-instance\":\"default\"$extra}}" \
        > /dev/null 2>&1
}

deregister_svc() {
    curl -sf -X PUT "$CONSUL/v1/agent/service/deregister/$1" > /dev/null 2>&1
}

# Raw agent register — lets us omit capi-instance entirely (for strict-mode test)
register_svc_raw() {
    # $1=ID $2=name $3=group $4=extra_meta (JSON fragment; leading comma required if present)
    local id="$1" name="$2" group="$3" extra="${4:-}"
    curl -sf -X PUT "$CONSUL/v1/agent/service/register" \
        -H "Content-Type: application/json" \
        -d "{\"ID\":\"$id\",\"Name\":\"$name\",\"Port\":8080,\"Address\":\"generic-service\",\"Meta\":{\"group\":\"$group\",\"root-context\":\"/$name\",\"scheme\":\"http\",\"type\":\"rest\"$extra}}" \
        > /dev/null 2>&1
}

# Restart CAPI container and wait until healthy.
restart_capi_and_wait() {
    docker restart demo-e2e-capi > /dev/null 2>&1
    for i in $(seq 1 60); do
        if [ "$(admin_health_code)" = "200" ]; then return 0; fi
        sleep 2
    done
    return 1
}

route_count() {
    curl -sf "$CAPI_ADMIN/info/routes" 2>/dev/null | grep -oE '"id"\s*:' | wc -l | tr -d ' '
}

route_exists() {
    # $1 = route context path or cache id substring to look for
    curl -sf "$CAPI_ADMIN/info/routes" 2>/dev/null | grep -q "$1"
}

health_code() {
    curl -s -o /dev/null -w "%{http_code}" "$CAPI_REST/api/info/health" 2>/dev/null
}

admin_health_code() {
    curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/health" 2>/dev/null
}

rest_call_code() {
    curl -s -o /dev/null -w "%{http_code}" "$CAPI_REST/api/$1/v1" 2>/dev/null
}

capi_log_tail() {
    # print last N lines of CAPI container logs
    docker logs --tail "$1" demo-e2e-capi 2>&1
}

wait_cycle() {
    echo "  (waiting ${CYCLE_WAIT}s for next discovery cycle...)"
    sleep "$CYCLE_WAIT"
}

# ------------------------------------------------------------------------------
hdr "Scenario 0: stack readiness"
# ------------------------------------------------------------------------------

attempt=0
until [ "$(admin_health_code)" = "200" ] || [ $attempt -ge 60 ]; do
    sleep 2; attempt=$((attempt+1))
done
if [ "$(admin_health_code)" = "200" ]; then
    ok "admin /info/health is 200"
else
    fail "admin /info/health never reached 200 (got $(admin_health_code))"
    echo "--- capi log tail ---"
    capi_log_tail 50
    exit 1
fi

baseline_routes=$(route_count)
note "Baseline route count: $baseline_routes"
if [ "$baseline_routes" -gt 10 ]; then
    ok "Baseline has >10 routes (got $baseline_routes) — initial discovery worked"
else
    fail "Baseline route count too low: $baseline_routes"
fi

# ------------------------------------------------------------------------------
hdr "Scenario 1: new service registration propagates within one cycle"
# ------------------------------------------------------------------------------

register_svc "scenario-new-1" "scenario-new" "dev" 8080 ""
wait_cycle
if route_exists "scenario-new:dev"; then
    ok "New service scenario-new:dev appeared in /info/routes"
else
    fail "New service did not appear after one cycle"
    note "/info/routes payload:"
    curl -sf "$CAPI_ADMIN/info/routes" | head -c 1000
fi

# ------------------------------------------------------------------------------
hdr "Scenario 2: service deregistration removes route within one cycle"
# ------------------------------------------------------------------------------

deregister_svc "scenario-new-1"
wait_cycle
if route_exists "scenario-new:dev"; then
    fail "Route scenario-new:dev still present after deregistration"
else
    ok "Route removed after deregistration"
fi

# ------------------------------------------------------------------------------
hdr "Scenario 3: multi-group service → two independent cache entries"
# ------------------------------------------------------------------------------

register_svc "multigroup-v1-1" "multigroup" "v1" 8080 ""
register_svc "multigroup-v2-1" "multigroup" "v2" 8080 ""
wait_cycle
if route_exists "multigroup:v1" && route_exists "multigroup:v2"; then
    ok "Both multigroup:v1 and multigroup:v2 present"
else
    fail "Multi-group split failed"
fi
# Deregister v1 only, v2 must survive
deregister_svc "multigroup-v1-1"
wait_cycle
if ! route_exists "multigroup:v1" && route_exists "multigroup:v2"; then
    ok "Partial dereg: only multigroup:v1 removed; multigroup:v2 intact"
else
    fail "Partial dereg did not behave correctly"
fi
deregister_svc "multigroup-v2-1"

# ------------------------------------------------------------------------------
hdr "Scenario 4: routeGroupFirst flip updates context, keeps cache id stable"
# ------------------------------------------------------------------------------

# Pre-flight: clear any stale flipme from previous run
deregister_svc "flipme-1"
sleep 2

# Register with default (routeGroupFirst absent → false) → path should be /api/flipme/dev/*
register_svc "flipme-1" "flipme" "dev" 8080 ""
wait_cycle
before_404_at_new=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_REST/api/dev/flipme/" 2>/dev/null)
before_200_at_old=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_REST/api/flipme/dev/" 2>/dev/null)
note "Before flip: /api/flipme/dev -> $before_200_at_old , /api/dev/flipme -> $before_404_at_new"
if [ "$before_200_at_old" != "404" ] && [ "$before_404_at_new" = "404" ]; then
    ok "Pre-flip routing: only /api/flipme/dev path is active (routeGroupFirst=false)"
else
    fail "Pre-flip routing unexpected: flipme/dev=$before_200_at_old dev/flipme=$before_404_at_new"
fi
id_before_stable=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/flipme:dev" 2>/dev/null)
id_before_flipped=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/dev:flipme" 2>/dev/null)
note "Cache id before flip: flipme:dev -> $id_before_stable ; dev:flipme -> $id_before_flipped"
if [ "$id_before_stable" = "200" ] && [ "$id_before_flipped" = "404" ]; then
    ok "Cache id is 'flipme:dev' (not 'dev:flipme') before flip"
else
    fail "Pre-flip cache id layout unexpected"
fi

# Re-register with route-group-first=true → new path should be /api/dev/flipme/*
deregister_svc "flipme-1"
sleep 2
register_svc "flipme-1" "flipme" "dev" 8080 ', "route-group-first": "true"'
wait_cycle
after_404_at_old=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_REST/api/flipme/dev/" 2>/dev/null)
after_200_at_new=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_REST/api/dev/flipme/" 2>/dev/null)
note "After flip: /api/flipme/dev -> $after_404_at_old , /api/dev/flipme -> $after_200_at_new"
if [ "$after_404_at_old" = "404" ] && [ "$after_200_at_new" != "404" ]; then
    ok "Post-flip routing: /api/dev/flipme is active, /api/flipme/dev is gone"
else
    fail "Post-flip routing unexpected: flipme/dev=$after_404_at_old dev/flipme=$after_200_at_new"
fi

id_after_stable=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/flipme:dev" 2>/dev/null)
id_after_flipped=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/dev:flipme" 2>/dev/null)
note "Cache id after flip: flipme:dev -> $id_after_stable ; dev:flipme -> $id_after_flipped"
if [ "$id_after_stable" = "200" ] && [ "$id_after_flipped" = "404" ]; then
    ok "Cache id remained 'flipme:dev' after flip (Q6: stable cache key)"
else
    fail "Post-flip cache id layout unexpected (before_stable=$id_before_stable after_stable=$id_after_stable after_flipped=$id_after_flipped)"
fi
# Verify the cached service's context actually flipped
ctx_after=$(curl -sf "$CAPI_ADMIN/info/routes/flipme:dev" | grep -oE '"context":"[^"]*"' | head -1)
if echo "$ctx_after" | grep -q '/dev/flipme'; then
    ok "Service context in cache flipped to /dev/flipme (§5.2)"
else
    fail "Service context did not flip as expected: $ctx_after"
fi
deregister_svc "flipme-1"

# ------------------------------------------------------------------------------
hdr "Scenario 5: Consul outage — routes preserved, /health stays 200"
# ------------------------------------------------------------------------------

register_svc "outage-test-1" "outage-test" "dev" 8080 ""
wait_cycle
if route_exists "outage-test:dev"; then
    ok "outage-test:dev registered"
else
    fail "outage-test:dev never appeared"
fi

route_count_before=$(route_count)
note "Stopping Consul container..."
docker stop demo-e2e-consul > /dev/null 2>&1

echo "  (waiting 30s with Consul down to observe 3 failed cycles...)"
sleep 30

admin_h=$(admin_health_code)
if [ "$admin_h" = "200" ]; then
    ok "admin /info/health still 200 during outage"
else
    fail "admin /info/health returned $admin_h during outage (should be 200 — §7.3)"
fi
rest_h=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_REST/api/health-service/v1/" 2>/dev/null)
if [ "$rest_h" != "404" ] && [ "$rest_h" != "503" ]; then
    ok "REST gateway still routing (/api/health-service/v1 -> $rest_h) during outage"
else
    fail "REST gateway not routing during outage (/api/health-service/v1 -> $rest_h)"
fi

route_count_during=$(route_count)
if [ "$route_count_during" = "$route_count_before" ]; then
    ok "Route count unchanged during outage ($route_count_during) — §7.1 holds"
else
    fail "Route count drifted during outage: before=$route_count_before during=$route_count_during"
fi

# Check CAPI logs for absence of "REST client removed" bursts during outage
recent_removed=$(capi_log_tail 200 | grep -c "REST client removed" | head -1 | tr -dc '0-9')
recent_removed=${recent_removed:-0}
if [ "$recent_removed" = "0" ]; then
    ok "No 'REST client removed' log lines during outage"
else
    fail "Saw $recent_removed 'REST client removed' lines during outage"
    note "Sample:"
    capi_log_tail 200 | grep "REST client removed" | head -5
fi

# Check logs for the expected consul-cycle reports with host failures OR the WARN about skipping cleanup.
# Either signal indicates §7.1 is firing.
summary_with_fail=$(capi_log_tail 400 | grep -c "consul-cycle.*hostsFailed=[1-9]" | head -1 | tr -dc '0-9')
summary_with_fail=${summary_with_fail:-0}
warn_skipped=$(capi_log_tail 400 | grep -c "skipping route cleanup" | head -1 | tr -dc '0-9')
warn_skipped=${warn_skipped:-0}
if [ "$summary_with_fail" != "0" ] || [ "$warn_skipped" != "0" ]; then
    ok "§7.1 signals in logs: failed-cycles=$summary_with_fail skip-cleanup=$warn_skipped"
else
    note "No §7.1 signals in logs (check manually: docker logs demo-e2e-capi | tail -50)"
fi

# ------------------------------------------------------------------------------
hdr "Scenario 6: Consul recovery — routes still intact, new registrations flow"
# ------------------------------------------------------------------------------

docker start demo-e2e-consul > /dev/null 2>&1
echo "  (waiting for consul to become healthy again...)"
attempt=0
until curl -sf "$CONSUL/v1/status/leader" > /dev/null 2>&1 || [ $attempt -ge 30 ]; do
    sleep 2; attempt=$((attempt+1))
done
# Consul agent restart loses services registered via /v1/agent/service/register (ephemeral).
# Re-register what we need for post-recovery checks.
wait_cycle
wait_cycle
# After recovery, services registered via agent are gone because agent lost state.
# That means the cleanup phase (now running on a clean cycle) would legitimately remove those routes.
# The key guarantee we already validated is: during the outage, no route was removed.
register_svc "recovery-check-1" "recovery-check" "dev" 8080 ""
wait_cycle
if route_exists "recovery-check:dev"; then
    ok "Post-recovery: new service registrations flowing again"
else
    fail "Post-recovery: new service did not register"
fi
deregister_svc "recovery-check-1"

# ------------------------------------------------------------------------------
hdr "Scenario 7: readiness latch is sticky — still true"
# ------------------------------------------------------------------------------

# Verify the one-way latch was not reset by the outage
if [ "$(admin_health_code)" = "200" ]; then
    ok "Readiness latch still true after outage+recovery — §7.3 one-way"
else
    fail "Readiness latch reset after outage — §7.3 VIOLATED"
fi

# ------------------------------------------------------------------------------
hdr "Scenario 8: instance ownership — single capiNamespace filter"
# ------------------------------------------------------------------------------
# CAPI runs with instanceName=default. A service tagged capi-instance=other must NOT be registered.

# 8a. service owned by another instance → must be IGNORED
register_svc "mi-other-1" "mi-other" "dev" 8080 ''
# Overwrite capi-instance to "other-instance" by re-registering with that value
curl -sf -X PUT "$CONSUL/v1/agent/service/register" -H "Content-Type: application/json" \
  -d '{"ID":"mi-other-1","Name":"mi-other","Port":8080,"Address":"generic-service","Meta":{"group":"dev","root-context":"/mi-other","scheme":"http","type":"rest","capi-instance":"other-instance"}}' >/dev/null 2>&1
wait_cycle
if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/mi-other:dev" | grep -q 404; then
    ok "Service tagged capi-instance=other-instance is NOT in our cache"
else
    fail "§4.2 ownership leak: service for other instance is in our cache"
fi
deregister_svc "mi-other-1"

# 8b. service owned by THIS instance (capi-instance=default) → must be registered
register_svc "mi-default-1" "mi-default" "dev" 8080 ''
wait_cycle
if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/mi-default:dev" | grep -q 200; then
    ok "Service tagged capi-instance=default IS in our cache"
else
    fail "Service for this instance was rejected"
fi
deregister_svc "mi-default-1"

# ------------------------------------------------------------------------------
hdr "Scenario 9: instance ownership — multi (capi-instance-<name>) meta"
# ------------------------------------------------------------------------------
# Meta with "capi-instance-<name>" keys maps to ServiceCapiInstances.

# Multi-instance meta format is FLAT: capi-instance-<instanceName>-<propertyName>: <value>
# (not a JSON blob — see ServiceCapiInstanceMapper). Supported properties:
# secured, scheme, open-api, route-group-first, ignore-open-api

# 9a. only capi-instance-other-instance-* declared → must be IGNORED
curl -sf -X PUT "$CONSUL/v1/agent/service/register" -H "Content-Type: application/json" \
  -d '{"ID":"mi-multi-a-1","Name":"mi-multi-a","Port":8080,"Address":"generic-service","Meta":{"group":"dev","root-context":"/mi-multi-a","scheme":"http","type":"rest","capi-instance-other-instance-secured":"false","capi-instance-other-instance-scheme":"http"}}' >/dev/null 2>&1
wait_cycle
if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/mi-multi-a:dev" | grep -q 404; then
    ok "Service with only capi-instance-other-* meta is NOT registered here"
else
    fail "§4.2: service for other instance (multi-meta) leaked into cache"
fi
curl -sf -X PUT "$CONSUL/v1/agent/service/deregister/mi-multi-a-1" >/dev/null 2>&1

# 9b. capi-instance-default-* declared → must be registered (multi meta match)
curl -sf -X PUT "$CONSUL/v1/agent/service/register" -H "Content-Type: application/json" \
  -d '{"ID":"mi-multi-b-1","Name":"mi-multi-b","Port":8080,"Address":"generic-service","Meta":{"group":"dev","root-context":"/mi-multi-b","scheme":"http","type":"rest","capi-instance-default-secured":"false","capi-instance-default-scheme":"http"}}' >/dev/null 2>&1
wait_cycle
if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/mi-multi-b:dev" | grep -q 200; then
    ok "Service with capi-instance-default-* multi-meta IS registered here"
else
    fail "§4.2: service for this instance (multi-meta) was rejected"
fi
curl -sf -X PUT "$CONSUL/v1/agent/service/deregister/mi-multi-b-1" >/dev/null 2>&1

# 9c. BOTH capi-instance-default-* and capi-instance-other-* declared → registered here
curl -sf -X PUT "$CONSUL/v1/agent/service/register" -H "Content-Type: application/json" \
  -d '{"ID":"mi-multi-c-1","Name":"mi-multi-c","Port":8080,"Address":"generic-service","Meta":{"group":"dev","root-context":"/mi-multi-c","scheme":"http","type":"rest","capi-instance-default-secured":"false","capi-instance-other-instance-secured":"true"}}' >/dev/null 2>&1
wait_cycle
if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/mi-multi-c:dev" | grep -q 200; then
    ok "Service shared between default and other-instance IS registered here"
else
    fail "§4.2: shared multi-instance service rejected"
fi
curl -sf -X PUT "$CONSUL/v1/agent/service/deregister/mi-multi-c-1" >/dev/null 2>&1

# ------------------------------------------------------------------------------
hdr "Scenario 10: per-instance override flips routeGroupFirst for just this instance"
# ------------------------------------------------------------------------------
# Register with routeGroupFirst=false at root meta, but capi-instance-default has routeGroupFirst=true.
# Post-override, context should be /dev/mi-override, cache id still "mi-override:dev".

curl -sf -X PUT "$CONSUL/v1/agent/service/register" -H "Content-Type: application/json" \
  -d '{"ID":"mi-override-1","Name":"mi-override","Port":8080,"Address":"generic-service","Meta":{"group":"dev","root-context":"/mi-override","scheme":"http","type":"rest","route-group-first":"false","capi-instance-default-secured":"false","capi-instance-default-route-group-first":"true"}}' >/dev/null 2>&1
wait_cycle

id_present=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/mi-override:dev")
flipped_miss=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/dev:mi-override")
if [ "$id_present" = "200" ] && [ "$flipped_miss" = "404" ]; then
    ok "Cache id stable 'mi-override:dev' even with per-instance routeGroupFirst override"
else
    fail "Per-instance override broke cache id stability (present=$id_present flipped=$flipped_miss)"
fi

ctx=$(curl -sf "$CAPI_ADMIN/info/routes/mi-override:dev" | grep -oE '"context":"[^"]*"' | head -1)
note "Context with override: $ctx"
if echo "$ctx" | grep -q '/dev/mi-override'; then
    ok "Per-instance override flipped context to /dev/mi-override"
else
    fail "Per-instance override did NOT flip context: $ctx"
fi

# Confirm routing works at the overridden path
route_200=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_REST/api/dev/mi-override/")
route_old=$(curl -s -o /dev/null -w "%{http_code}" "$CAPI_REST/api/mi-override/dev/")
if [ "$route_200" != "404" ] && [ "$route_old" = "404" ]; then
    ok "Overridden path /api/dev/mi-override is live; base path /api/mi-override/dev is not"
else
    fail "Overridden routing unexpected: overridden=$route_200 base=$route_old"
fi
curl -sf -X PUT "$CONSUL/v1/agent/service/deregister/mi-override-1" >/dev/null 2>&1

# ------------------------------------------------------------------------------
hdr "Scenario 11: strictToInstanceName=true — untagged services are rejected"
# ------------------------------------------------------------------------------
# Swap config to strict=true, restart CAPI, verify filter behavior, restore.

CONFIG=/Users/rodrigo/projects/capi-core/demo-e2e/capi-config.yaml
cp "$CONFIG" "$CONFIG.bak"
sed -i.tmp 's/strictToInstanceName: false/strictToInstanceName: true/' "$CONFIG"
rm -f "$CONFIG.tmp"
note "Restarting CAPI with strictToInstanceName=true..."
if restart_capi_and_wait; then
    ok "CAPI restarted with strict mode"
else
    fail "CAPI failed to restart"
    mv "$CONFIG.bak" "$CONFIG"
    exit 1
fi

# 11a. register a service with NO capi-instance meta → must be REJECTED under strict
register_svc_raw "strict-untagged-1" "strict-untagged" "dev" ""
wait_cycle
if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/strict-untagged:dev" | grep -q 404; then
    ok "Untagged service rejected in strict mode"
else
    fail "§4.2 / Q5: untagged service leaked into cache under strict mode"
fi
deregister_svc "strict-untagged-1"

# 11b. tagged service with capi-instance=default → must be ACCEPTED under strict
register_svc "strict-tagged-1" "strict-tagged" "dev" 8080 ""
wait_cycle
if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/strict-tagged:dev" | grep -q 200; then
    ok "Tagged service for this instance accepted in strict mode"
else
    fail "§4.2: tagged service rejected under strict mode"
fi
deregister_svc "strict-tagged-1"

# Restore config
mv "$CONFIG.bak" "$CONFIG"
note "Restoring CAPI with strictToInstanceName=false..."
if restart_capi_and_wait; then
    ok "CAPI restored to non-strict mode"
else
    fail "CAPI failed to restart after restore"
fi

# ------------------------------------------------------------------------------
hdr "Scenario 12: multi-host Consul union (§7.8 + Q2)"
# ------------------------------------------------------------------------------
# Bring up a SECOND Consul, reconfigure CAPI to know both, verify services from
# both hosts are discovered AND that same-name/same-group with different backend
# addresses merge into one Service via Set<Mapping> dedup.

CONSUL2_NAME="demo-e2e-consul2"
docker rm -f "$CONSUL2_NAME" > /dev/null 2>&1 || true
docker run -d --name "$CONSUL2_NAME" --network demo-e2e_default -p 8502:8500 \
    hashicorp/consul:1.22 agent -dev -client=0.0.0.0 > /dev/null 2>&1
echo "  (waiting for consul2 to become healthy...)"
for i in $(seq 1 30); do
    if curl -sf "http://localhost:8502/v1/status/leader" > /dev/null 2>&1; then break; fi
    sleep 1
done

# Patch capi-config to list BOTH hosts (in-place; under the existing consulHosts block)
cp "$CONFIG" "$CONFIG.bak"
python3 - <<EOF
import re, io
p = "$CONFIG"
s = open(p).read()
s = s.replace(
    "consulHosts:\n    - endpoint: http://consul:8500\n      token:",
    "consulHosts:\n    - endpoint: http://consul:8500\n      token:\n    - endpoint: http://$CONSUL2_NAME:8500\n      token:"
)
open(p, "w").write(s)
EOF
note "Restarting CAPI with two Consul hosts..."
if ! restart_capi_and_wait; then
    fail "CAPI failed to restart"
    mv "$CONFIG.bak" "$CONFIG"
    docker rm -f "$CONSUL2_NAME" > /dev/null 2>&1
    exit 1
fi

# 12a. Service only on consul #1 → appears in cache
register_svc "mh-one-1" "mh-one" "dev" 8080 ""
# 12b. Service only on consul #2 → appears in cache (proves union)
curl -sf -X PUT "http://localhost:8502/v1/agent/service/register" -H "Content-Type: application/json" \
    -d '{"ID":"mh-two-1","Name":"mh-two","Port":8080,"Address":"generic-service","Meta":{"group":"dev","root-context":"/mh-two","scheme":"http","type":"rest","capi-instance":"default"}}' > /dev/null 2>&1
wait_cycle

if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/mh-one:dev" | grep -q 200; then
    ok "Service from consul #1 is in cache"
else
    fail "Service from consul #1 missing"
fi
if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/mh-two:dev" | grep -q 200; then
    ok "Service from consul #2 is in cache — Q2 union across hosts works"
else
    fail "Service from consul #2 missing — Q2 broken"
fi

# 12c. Same Name+group on BOTH Consuls, different backend addresses → one Service, two mappings
register_svc "mh-merge-1" "mh-merge" "dev" 8080 ""
curl -sf -X PUT "http://localhost:8502/v1/agent/service/register" -H "Content-Type: application/json" \
    -d '{"ID":"mh-merge-2","Name":"mh-merge","Port":8080,"Address":"weather-service","Meta":{"group":"dev","root-context":"/mh-merge","scheme":"http","type":"rest","capi-instance":"default"}}' > /dev/null 2>&1
wait_cycle
merge_body=$(curl -sf "$CAPI_ADMIN/info/routes/mh-merge:dev")
backends=$(echo "$merge_body" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('mappingList',[])))")
note "mh-merge mappingList size: $backends"
if [ "$backends" = "2" ]; then
    ok "Union merged same service across hosts into two mappings"
else
    fail "Expected 2 mappings from union, got $backends"
fi

# 12d. SAME Name+group+address on both → Set<Mapping> dedup → one mapping
register_svc "mh-dedup-1" "mh-dedup" "dev" 8080 ""
curl -sf -X PUT "http://localhost:8502/v1/agent/service/register" -H "Content-Type: application/json" \
    -d '{"ID":"mh-dedup-2","Name":"mh-dedup","Port":8080,"Address":"generic-service","Meta":{"group":"dev","root-context":"/mh-dedup","scheme":"http","type":"rest","capi-instance":"default"}}' > /dev/null 2>&1
wait_cycle
dedup_body=$(curl -sf "$CAPI_ADMIN/info/routes/mh-dedup:dev")
dedup_n=$(echo "$dedup_body" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('mappingList',[])))")
note "mh-dedup mappingList size: $dedup_n"
if [ "$dedup_n" = "1" ]; then
    ok "Identical backend on both hosts deduplicated to one mapping (Set<Mapping> semantics)"
else
    fail "Expected 1 mapping after dedup, got $dedup_n"
fi

# Cleanup scenario 12
deregister_svc "mh-one-1"
deregister_svc "mh-merge-1"
deregister_svc "mh-dedup-1"
curl -sf -X PUT "http://localhost:8502/v1/agent/service/deregister/mh-two-1"  >/dev/null 2>&1
curl -sf -X PUT "http://localhost:8502/v1/agent/service/deregister/mh-merge-2" >/dev/null 2>&1
curl -sf -X PUT "http://localhost:8502/v1/agent/service/deregister/mh-dedup-2" >/dev/null 2>&1

mv "$CONFIG.bak" "$CONFIG"
note "Restoring single-host config..."
restart_capi_and_wait > /dev/null
docker rm -f "$CONSUL2_NAME" > /dev/null 2>&1
ok "Stack restored (single Consul host)"

# ------------------------------------------------------------------------------
hdr "Scenario 13: §7.2 empty-from-Consul preservation"
# ------------------------------------------------------------------------------
# Simulate via catalog-register on a phantom node, then deregister ONLY the service
# from that node. For a window of time after deregister-service, the catalog listing
# may still show the service (brief convergence) while /v1/catalog/service/<name>
# returns []. If CAPI removed the cache entry in that window, §7.2 is broken.
#
# This is inherently race-sensitive. We use the catalog API directly (not agent) so
# we control node lifecycle independently from service lifecycle.

PHANTOM_NODE="phantom-node-e2e"
# Register service via catalog (not agent) on a fake node
curl -sf -X PUT "$CONSUL/v1/catalog/register" -H "Content-Type: application/json" \
    -d "{\"Datacenter\":\"dc1\",\"Node\":\"$PHANTOM_NODE\",\"Address\":\"10.250.0.1\",\"Service\":{\"ID\":\"phantom-svc-1\",\"Service\":\"phantom-svc\",\"Port\":8080,\"Meta\":{\"group\":\"dev\",\"root-context\":\"/phantom-svc\",\"scheme\":\"http\",\"type\":\"rest\",\"capi-instance\":\"default\"}}}" > /dev/null 2>&1
wait_cycle

if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/phantom-svc:dev" | grep -q 200; then
    ok "Phantom service registered via catalog is in cache"
else
    fail "Catalog-register did not flow into cache"
fi

# Deregister ONLY the service from the node (keep the node itself)
curl -sf -X PUT "$CONSUL/v1/catalog/deregister" -H "Content-Type: application/json" \
    -d "{\"Datacenter\":\"dc1\",\"Node\":\"$PHANTOM_NODE\",\"ServiceID\":\"phantom-svc-1\"}" > /dev/null 2>&1

# Check whether /v1/catalog/service/phantom-svc returns [] while catalog listing may still show it.
# If both list and per-service return empty, this is authoritative removal — not the §7.2 scenario.
# If list still shows phantom-svc AND per-service returns [], §7.2 applies and CAPI MUST preserve.
# Give Consul a brief moment, then poll once.
sleep 2
persvc=$(curl -sf "$CONSUL/v1/catalog/service/phantom-svc")
list=$(curl -sf "$CONSUL/v1/catalog/services" | python3 -c "import json,sys; print('yes' if 'phantom-svc' in json.load(sys.stdin) else 'no')")
note "After dereg: /v1/catalog/services shows phantom-svc? $list ; per-service body='${persvc}'"

if [ "$list" = "yes" ] && [ "$persvc" = "[]" ]; then
    # Perfect §7.2 condition — CAPI must preserve
    wait_cycle
    if curl -s -o /dev/null -w "%{http_code}" "$CAPI_ADMIN/info/routes/phantom-svc:dev" | grep -q 200; then
        ok "§7.2: phantom-svc preserved while catalog lists it but per-service returns []"
    else
        fail "§7.2 VIOLATED: cache entry removed on empty per-service response"
    fi
else
    note "Consul converged too fast to exercise §7.2 window (list=$list persvc=$persvc)"
    note "Code path is still guarded — unit-level reasoning covers it. Skipping assertion."
fi

# Cleanup the phantom node entirely
curl -sf -X PUT "$CONSUL/v1/catalog/deregister" -H "Content-Type: application/json" \
    -d "{\"Datacenter\":\"dc1\",\"Node\":\"$PHANTOM_NODE\"}" > /dev/null 2>&1

# ------------------------------------------------------------------------------
hdr "Scenario 14: OpenAPI gate on routeGroupFirst=true service (Q6 regression test)"
# ------------------------------------------------------------------------------
# Regression for: cache id is "<name>:<group>" (stable), but context flips to
# "/<group>/<name>" when routeGroupFirst=true. Any callsite that does
# serviceCache.get(contextToRole(restClient.getServiceId())) misses the cache
# → checkOpenApi returns "Call not allowed" even for valid POSTs.
# Caught in dev with cc-signature-backend (/api/cc-int/cc-signature-backend/...).

# Serve the test OpenAPI spec on the host; CAPI reaches host via host.docker.internal
( python3 -m http.server 9997 --bind 127.0.0.1 --directory /Users/rodrigo/projects/capi-core/demo-e2e > /tmp/openapi-srv.log 2>&1 & echo $! > /tmp/openapi-srv.pid )
sleep 1

# Register a service with route-group-first=true AND an OpenAPI pointing at our spec
OPENAPI_URL="http://host.docker.internal:9997/test-openapi.json"
curl -sf -X PUT "$CONSUL/v1/agent/service/register" -H "Content-Type: application/json" -d "{
    \"ID\": \"q6regression-1\",
    \"Name\": \"q6regression\",
    \"Port\": 8080,
    \"Address\": \"generic-service\",
    \"Meta\": {
      \"group\": \"grp\",
      \"root-context\": \"/\",
      \"scheme\": \"http\",
      \"type\": \"rest\",
      \"capi-instance\": \"default\",
      \"route-group-first\": \"true\",
      \"open-api\": \"${OPENAPI_URL}\"
    }
}" >/dev/null 2>&1
wait_cycle

# Verify the service is registered and has OpenAPI loaded
capi_svc=$(curl -sf "$CAPI_ADMIN/info/routes/q6regression:grp")
if [ -z "$capi_svc" ]; then
    fail "q6regression:grp not in cache (is the stack up? check service)"
else
    ok "q6regression:grp registered in cache (routeGroupFirst=true means context flipped)"
fi

# Send a POST to the flipped path /api/grp/q6regression/api/content/resolution WITH a dummy bearer.
# Pre-fix behavior: 400 "Call not allowed" (serviceCache.get missed because cache id != contextToRole(context)).
# Post-fix behavior: 401 "Invalid authentication" (cache hits; JWT validation fails because token is dummy).
resp=$(curl -s -o /tmp/q6_body.txt -w "%{http_code}" \
    -X POST "$CAPI_REST/api/grp/q6regression/api/content/resolution" \
    -H "Authorization: Bearer not-a-real-jwt-but-triggers-the-check")
body=$(cat /tmp/q6_body.txt)
note "POST response: status=$resp body=$body"

if echo "$body" | grep -q "Call not allowed"; then
    fail "§Q6 regression: checkOpenApi returned 'Call not allowed' — cache lookup miss on routeGroupFirst=true service"
else
    ok "checkOpenApi reached cache successfully for routeGroupFirst=true service (no 'Call not allowed')"
fi

# Cleanup
curl -sf -X PUT "$CONSUL/v1/agent/service/deregister/q6regression-1" >/dev/null 2>&1
if [ -f /tmp/openapi-srv.pid ]; then
    kill "$(cat /tmp/openapi-srv.pid)" 2>/dev/null
    rm -f /tmp/openapi-srv.pid
fi

# ------------------------------------------------------------------------------
hdr "Summary"
# ------------------------------------------------------------------------------
echo ""
echo "  Passed: $PASS"
echo "  Failed: $FAIL"
echo ""
if [ "$FAIL" -eq 0 ]; then
    echo "  All scenarios passed."
    exit 0
else
    echo "  Some scenarios failed."
    exit 1
fi