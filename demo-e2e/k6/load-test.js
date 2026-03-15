/**
 * CAPI Gateway — k6 load test
 *
 * Tests all protocol/security types: open, OPA, subscription, API key,
 * throttle, MCP, and gRPC (via BFF bridge).
 *
 * Usage:
 *   # Local
 *   k6 run --env BASE_URL=http://localhost load-test.js
 *
 *   # Remote
 *   k6 run --env BASE_URL=https://your-domain.eu load-test.js
 *
 *   # Custom load profile
 *   k6 run --env BASE_URL=http://localhost --env MAX_VUS=200 load-test.js
 *
 *   # Single scenario only
 *   k6 run --env BASE_URL=http://localhost --env SCENARIO=open load-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const BASE    = __ENV.BASE_URL || 'http://localhost';
const MAX_VUS = parseInt(__ENV.MAX_VUS || '100');
const SCENARIO_FILTER = __ENV.SCENARIO || 'all';

// Auth — set via environment or use defaults for the demo
const ALICE_USER     = __ENV.ALICE_USER     || 'alice';
const ALICE_PASSWORD = __ENV.ALICE_PASSWORD || 'changeme';
const API_KEY        = __ENV.API_KEY        || '8815ce2c-2d93-41e2-af08-89e48196467f';
const BFF_CLIENT_ID  = __ENV.BFF_CLIENT_ID  || 'demo-bff';
const BFF_SECRET     = __ENV.BFF_SECRET     || 'changeme';
const KC_REALM       = __ENV.KC_REALM       || 'e2e-demo';

// ---------------------------------------------------------------------------
// Custom metrics
// ---------------------------------------------------------------------------

const errorRate     = new Rate('errors');
const latencyOpen   = new Trend('latency_open', true);
const latencyOpa    = new Trend('latency_opa', true);
const latencySub    = new Trend('latency_subscription', true);
const latencyApiKey = new Trend('latency_apikey', true);
const latencyMcp    = new Trend('latency_mcp', true);
const latencyGrpc   = new Trend('latency_grpc', true);

// ---------------------------------------------------------------------------
// Load profile
// ---------------------------------------------------------------------------

function buildStages() {
  return [
    { duration: '30s', target: Math.round(MAX_VUS * 0.2) },   // warm-up
    { duration: '2m',  target: Math.round(MAX_VUS * 0.5) },   // ramp
    { duration: '5m',  target: MAX_VUS },                      // sustained peak
    { duration: '1m',  target: Math.round(MAX_VUS * 0.3) },   // cool-down
    { duration: '30s', target: 0 },                            // drain
  ];
}

export const options = {
  stages: buildStages(),
  thresholds: {
    http_req_duration: ['p(95)<2000'],  // 95% of requests under 2s
    errors:            ['rate<0.1'],     // error rate below 10%
  },
};

// ---------------------------------------------------------------------------
// Token helper
// ---------------------------------------------------------------------------

let cachedToken = null;
let tokenExpiry = 0;

function getToken() {
  if (cachedToken && Date.now() < tokenExpiry) return cachedToken;

  const kcUrl = `${BASE}/keycloak/realms/${KC_REALM}/protocol/openid-connect/token`;
  const res = http.post(kcUrl, {
    client_id:     BFF_CLIENT_ID,
    client_secret: BFF_SECRET,
    grant_type:    'password',
    username:      ALICE_USER,
    password:      ALICE_PASSWORD,
    scope:         'openid',
  });

  if (res.status === 200) {
    const body = JSON.parse(res.body);
    cachedToken = body.access_token;
    tokenExpiry = Date.now() + (body.expires_in - 30) * 1000;
    return cachedToken;
  }
  return null;
}

function authHeaders() {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

// ---------------------------------------------------------------------------
// Service lists
// ---------------------------------------------------------------------------

const OPEN_SERVICES    = ['health-service', 'config-service', 'search-service', 'events-service', 'webhook-service'];
const OPA_SERVICES     = ['user-service', 'profile-service', 'catalog-service', 'pricing-service', 'order-service'];
const SUB_SERVICES     = ['notification-service', 'recommendation-service', 'email-service', 'partner-api'];
const APIKEY_SERVICES  = ['inventory-service', 'shipping-service', 'returns-service'];
const THROTTLE_SERVICES = ['metrics-service', 'reporting-service', 'dashboard-service'];

function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }

// ---------------------------------------------------------------------------
// Test scenarios
// ---------------------------------------------------------------------------

function testOpen() {
  const svc = pick(OPEN_SERVICES);
  const res = http.get(`${BASE}/api/${svc}/v1`);
  latencyOpen.add(res.timings.duration);
  errorRate.add(res.status !== 200);
  check(res, { 'open: 200': (r) => r.status === 200 });
}

function testOpa() {
  const svc = pick(OPA_SERVICES);
  const res = http.get(`${BASE}/api/${svc}/v1`, { headers: authHeaders() });
  latencyOpa.add(res.timings.duration);
  errorRate.add(res.status !== 200);
  check(res, { 'opa: 200': (r) => r.status === 200 });
}

function testSubscription() {
  const svc = pick(SUB_SERVICES);
  const res = http.get(`${BASE}/api/${svc}/v1`, { headers: authHeaders() });
  latencySub.add(res.timings.duration);
  errorRate.add(res.status !== 200);
  check(res, { 'sub: 200': (r) => r.status === 200 });
}

function testApiKey() {
  if (!API_KEY) return;
  const svc = pick(APIKEY_SERVICES);
  const res = http.get(`${BASE}/api/${svc}/v1`, {
    headers: { Authorization: `ApiKey ${API_KEY}` },
  });
  latencyApiKey.add(res.timings.duration);
  errorRate.add(res.status !== 200 && res.status !== 429);
  check(res, { 'apikey: 200 or 429': (r) => r.status === 200 || r.status === 429 });
}

function testMcp() {
  const token = getToken();
  const headers = {
    'Content-Type': 'application/json',
  };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  // Initialize
  const initRes = http.post(`${BASE}/mcp`, JSON.stringify({
    jsonrpc: '2.0', method: 'initialize', id: 1,
  }), { headers });

  if (initRes.status !== 200) {
    errorRate.add(true);
    return;
  }

  const sessionId = initRes.headers['Mcp-Session-Id'];
  if (!sessionId) {
    errorRate.add(true);
    return;
  }

  headers['Mcp-Session-Id'] = sessionId;

  // Call a tool
  const callRes = http.post(`${BASE}/mcp`, JSON.stringify({
    jsonrpc: '2.0', method: 'tools/call', id: 2,
    params: { name: 'weather_forecast', arguments: { city: 'Lisbon' } },
  }), { headers });

  latencyMcp.add(callRes.timings.duration);
  errorRate.add(callRes.status !== 200);
  check(callRes, { 'mcp: 200': (r) => r.status === 200 });
}

function testGrpc() {
  const res = http.post(`${BASE}/bff/grpc`, JSON.stringify({
    service: 'greeter/dev',
    name: 'k6-load-test',
  }), { headers: { 'Content-Type': 'application/json' } });

  latencyGrpc.add(res.timings.duration);
  errorRate.add(res.status !== 200);
  check(res, { 'grpc: 200': (r) => r.status === 200 });
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

const scenarios = {
  open:         testOpen,
  opa:          testOpa,
  subscription: testSubscription,
  apikey:       testApiKey,
  mcp:          testMcp,
  grpc:         testGrpc,
};

const scenarioWeights = [
  { fn: testOpen,         weight: 30 },
  { fn: testOpa,          weight: 25 },
  { fn: testSubscription, weight: 20 },
  { fn: testApiKey,       weight: 10 },
  { fn: testMcp,          weight: 10 },
  { fn: testGrpc,         weight: 5 },
];

export default function () {
  if (SCENARIO_FILTER !== 'all' && scenarios[SCENARIO_FILTER]) {
    scenarios[SCENARIO_FILTER]();
  } else {
    // Weighted random selection
    const total = scenarioWeights.reduce((s, w) => s + w.weight, 0);
    let r = Math.random() * total;
    for (const sw of scenarioWeights) {
      r -= sw.weight;
      if (r <= 0) { sw.fn(); break; }
    }
  }
  sleep(0.1 + Math.random() * 0.3); // 100-400ms think time
}