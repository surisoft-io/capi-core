## ⚠️ Breaking change — CORS is now deny-by-default

**If a browser application calls CAPI cross-origin, it will stop working after this upgrade until its
origin is allowlisted.**

`capi.corsEnabled` was previously *reporting-only* — it appeared in `/info/capi` but gated nothing. A
configuration reading `corsEnabled: false` was still answering **every** request with
`Access-Control-Allow-Origin` reflecting whatever `Origin` the caller sent, together with
`Access-Control-Allow-Credentials: true`. That combination lets any website a user visits read their
authenticated responses from the gateway.

It is now enforced, and origins must be declared:

```yaml
capi:
  corsEnabled: true          # master switch — false sends no CORS header at all
  allowedOrigins:
    - https://your-app.example.com
```

Per service, via Consul ServiceMeta — this **replaces** the gateway-wide list for that service rather
than adding to it:

```json
{ "Meta": { "allowed-origins": "https://team-app.example.com" } }
```

`allowedOrigins: ["*"]` sends the literal wildcard and, per the Fetch standard, never
`Allow-Credentials`. A client using cookies or `credentials: "include"` therefore needs its origin
listed explicitly — there is no configuration that grants any origin *and* credentials, because that
is the vulnerability being fixed.

**This failure is invisible from the gateway.** CAPI returns `200`; the browser silently drops the
response. It surfaces only in a client's browser console, never in CAPI logs, metrics or access logs.
Check your configuration before upgrading rather than waiting for a signal.

`allowed-origins` in ServiceMeta was documented previously but never actually read. It now is.

---

## Security

- **CORS origin reflection with credentials** — see above.
- **Trust-store writes now require an authenticated admin listener.** `PUT /info/truststore` and
  `DELETE /info/truststore/{alias}` return `403` unless `admin.protected` is on, regardless of any
  other setting. A certificate added there is pushed to Consul KV and reloaded by every instance in
  the cluster, so on an unauthenticated listener it was a fleet-wide man-in-the-middle primitive.
  Reads are unaffected.
- **Bounded metric labels.** The HTTP method was used as a Micrometer tag, and Undertow accepts
  arbitrary method tokens, so an unauthenticated caller could grow the metrics registry without
  limit. Methods outside `GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE` now record as `OTHER`.
  *A dashboard or alert filtering on an unusual method label needs updating.*
- **Undertow 2.3.18 → 2.3.21.Final**, clearing CVE-2025-12543 (Host header validation),
  CVE-2025-9784, CVE-2024-4027 and CVE-2024-3884. Note the Host fix is strict: a malformed `Host`
  now gets `400`, and an absolute-form request URI whose host disagrees with the `Host` header is
  rejected. Origin-form requests — what `proxy_pass` and every normal client send — are unaffected.
- **`admin.protected` is now a nullable boolean**, so "absent" and "explicitly false" are different
  states. An absent `admin:` block keeps the previous behaviour (unprotected, with a startup
  warning) so an upgrade cannot crash-loop; an explicit `protected: true` with no `group` now fails
  startup rather than silently rejecting every admin call.

## Fixed

- **OPA Wasm policy pool no longer returns spurious 403s under concurrency.** The pool returned
  "denied by policy" whenever in-flight requests exceeded `opa.wasmPoolSize`; it now grows on demand
  up to 4× that size.
- **OPA Wasm pool no longer drains permanently.** An evaluation that threw cost its instance for
  good, so repeated failures shrank the pool until every OPA-protected service returned `403` until
  the next bundle reload. Discarded instances are now replaced.

## New — all opt-in, all default to off

- **`GET /info/spec-compliance`** reports whether each service's OpenAPI spec identifies it
  (`info.title` == Consul service name, `info.version` present), as `compliant` / `nonCompliant` /
  `exempt` / `notApplicable`, plus `safeToEnable`. Verdicts are computed every discovery cycle
  whether or not enforcement is on, and nothing is logged while it is off — so an estate can be
  measured before anything changes.
- **`capi.matchOpenApiSpec.enabled`** turns that report into enforcement: a non-compliant service is
  held out of routing and listed in `/info/invalid-services` as `OPENAPI_IDENTITY_MISMATCH`.
  `matchOpenApiSpec.exempt` lists service names that keep routing regardless.
- **`capi.rest.rejectDotSegments`** refuses a request whose path contains a `.` or `..` **segment**
  with `400`. While off, such requests are forwarded exactly as before and only counted, via
  `capi_dot_segment_requests_total{action="observed"}` — watch that on real traffic before enabling.
  The check is segment-wise, so ordinary paths such as `/range/1..10` or `/report..pdf` are
  unaffected.
- **`DELETE /info/truststore/{alias}`** removes a certificate by alias. Previously the endpoint only
  supported read and add, and a `DELETE` silently returned the listing with `200`. Unsupported
  methods on the trust-store endpoints now return `405` with an `Allow` header.

## Also

- CI now runs the test suite before building and publishing. It previously built with `-DskipTests`,
  so a release could be cut without a single test running.
- Documentation corrected: `opa.endpoint` and an "OPA HTTP fallback" mode were documented but have
  never existed — CAPI evaluates Rego compiled to WebAssembly, in-process, only. Setting `endpoint`
  makes CAPI fail to start, because unknown configuration properties are rejected rather than
  ignored.

## Upgrading

1. If you have browser clients, set `corsEnabled: true` and declare their origins — gateway-wide or
   per service. This is the only change that can break a working deployment.
2. Review any dashboard filtering on the `method` metric label.
3. Everything else defaults to off and requires no action.
