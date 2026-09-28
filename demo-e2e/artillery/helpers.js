'use strict';

/**
 * Shared processor functions for the CAPI Artillery suites.
 *
 * These run inside Artillery's worker process (Node 18+), so the global
 * `fetch` is available. No external deps required.
 *
 * Env overrides (all optional — defaults match demo-e2e):
 *   KC_URL    Keycloak base, including the /keycloak context path
 *             default: http://localhost:8080/keycloak
 *   KC_REALM  default: e2e-demo
 */

const KC_URL = process.env.KC_URL || 'http://localhost:8080/keycloak';
const KC_REALM = process.env.KC_REALM || 'e2e-demo';

const TOKEN_ENDPOINT =
  `${KC_URL}/realms/${KC_REALM}/protocol/openid-connect/token`;

// Resource-owner-password clients from setup-keycloak.sh.
// Secrets are read from env so we don't hard-code them; the demo .env exports
// PREMIUM_CLIENT_SECRET / BASIC_CLIENT_SECRET.
const CLIENTS = {
  premium: {
    client_id: 'client-premium',
    client_secret: process.env.PREMIUM_CLIENT_SECRET || 'changeme',
  },
  basic: {
    client_id: 'client-basic',
    client_secret: process.env.BASIC_CLIENT_SECRET || 'changeme',
  },
};

// Demo users (setup-keycloak.sh): alice=admin, bob=manager, charlie=viewer.
const USERS = {
  alice: { username: 'alice', password: process.env.ALICE_PASSWORD || 'changeme' },
  bob: { username: 'bob', password: process.env.BOB_PASSWORD || 'changeme' },
  charlie: { username: 'charlie', password: process.env.CHARLIE_PASSWORD || 'changeme' },
};

// Simple in-worker token cache keyed by client+user, so we don't hammer
// Keycloak with one token request per virtual user at ramp-up.
const _cache = new Map();

async function fetchToken(clientKey, userKey) {
  const cacheKey = `${clientKey}:${userKey}`;
  const hit = _cache.get(cacheKey);
  // Reuse if it still has >30s of life left.
  if (hit && hit.expiresAt - Date.now() > 30_000) {
    return hit.token;
  }

  const client = CLIENTS[clientKey];
  const user = USERS[userKey];
  if (!client || !user) {
    throw new Error(`Unknown client/user combo: ${clientKey}/${userKey}`);
  }

  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: client.client_id,
    client_secret: client.client_secret,
    username: user.username,
    password: user.password,
    scope: 'openid',
  });

  const res = await fetch(TOKEN_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });

  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Token fetch failed (${res.status}): ${text.slice(0, 200)}`);
  }

  const json = await res.json();
  const token = json.access_token;
  const ttlMs = (json.expires_in || 60) * 1000;
  _cache.set(cacheKey, { token, expiresAt: Date.now() + ttlMs });
  return token;
}

// ---- Artillery beforeScenario hooks (context, events, done) ----------------
// Each sets context.vars.token to a JWT for the named identity. Routes that
// reference {{ token }} in an Authorization header pick it up automatically.

function tokenAliceAdmin(context, events, done) {
  fetchToken('premium', 'alice')
    .then((t) => { context.vars.token = t; done(); })
    .catch((err) => done(err));
}

function tokenBobManager(context, events, done) {
  fetchToken('premium', 'bob')
    .then((t) => { context.vars.token = t; done(); })
    .catch((err) => done(err));
}

function tokenCharlieViewer(context, events, done) {
  fetchToken('premium', 'charlie')
    .then((t) => { context.vars.token = t; done(); })
    .catch((err) => done(err));
}

// Basic client: only subscribed to "notifications". Useful for asserting
// subscription-gated routes reject it.
function tokenBasicClient(context, events, done) {
  fetchToken('basic', 'alice')
    .then((t) => { context.vars.token = t; done(); })
    .catch((err) => done(err));
}

// ---- Assertion / logging helpers -------------------------------------------

// afterResponse hook: record how long a streaming connection actually stayed
// open before the gateway closed it. Used by the SSE timeout scenario to make
// the wall-clock cutoff visible in the run summary.
function recordStreamDuration(requestParams, response, context, events, done) {
  const started = context.vars._streamStart;
  if (started) {
    const heldMs = Date.now() - started;
    events.emit('histogram', 'capi.sse.held_ms', heldMs);
  }
  done();
}

function markStreamStart(requestParams, context, events, done) {
  context.vars._streamStart = Date.now();
  done();
}

module.exports = {
  tokenAliceAdmin,
  tokenBobManager,
  tokenCharlieViewer,
  tokenBasicClient,
  recordStreamDuration,
  markStreamStart,
};