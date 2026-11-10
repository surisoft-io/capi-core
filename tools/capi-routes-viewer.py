#!/usr/bin/env python3
"""
Local helper for capi-routes-viewer.html: serves the page and forwards its calls to each node's
admin API (routes, invalid services, per-service detail and the trust store).

The admin API sends no CORS headers, so a browser page cannot call it directly. This helper runs on
your machine, listens on 127.0.0.1 only, and forwards to a fixed set of paths — it is not a general
proxy. Reads: /info/routes, /info/invalid-services, /info/truststore, /info/capi, /info/health, and /info/routes/<id> and
/info/openapi/<id> for one service. Writes, the trust store only: PUT /info/truststore (one PEM
certificate) and DELETE /info/truststore/<alias>.

POST /discover lists the nodes of one CAPI instance from the capi-admin registrations that the admin
MCP writes to its Consul (admin.mcp.consul). The Consul token is used for that one read and never
written or logged. It also performs the client-credentials grant for the page (POST /token): the
issuer's token endpoint is found by OIDC discovery, and the secret is used for that one request and
never written or logged. Tokens are sent per request from the page and never written anywhere.
Standard library only — nothing to install.

    python3 tools/capi-routes-viewer.py            # then open http://127.0.0.1:8399
    python3 tools/capi-routes-viewer.py --port 9000
    python3 tools/capi-routes-viewer.py --ca-file internal-ca.pem
    python3 tools/capi-routes-viewer.py --insecure  # skip TLS verification (self-signed nodes)
"""
import argparse
import re
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ALLOWED_PATHS = ("/info/routes", "/info/invalid-services", "/info/truststore", "/info/capi", "/info/health")
# Per-service detail: the cached service (meta) and the spec CAPI loaded for it.
SERVICE_PATH = re.compile(r"^/info/(routes|openapi)/[A-Za-z0-9._~-]+:[A-Za-z0-9._~-]+$")
TRUSTSTORE = "/info/truststore"
# One percent-encoded alias (encodeURIComponent output); checked again after decoding.
TRUSTSTORE_ALIAS = re.compile(r"^/info/truststore/[A-Za-z0-9._~%!'()*-]+$")
MAX_PEM = 16 * 1024


def allowed(method, path, body):
    """The only calls the page may forward. Returns an error message, or None when allowed."""
    if method == "GET":
        return None if path in ALLOWED_PATHS or SERVICE_PATH.match(path) else "Path not allowed"
    if method == "PUT":
        if path != TRUSTSTORE:
            return "PUT is only allowed on /info/truststore"
        if not body or len(body) > MAX_PEM or body.count("BEGIN CERTIFICATE") != 1:
            return "PUT body must be exactly one PEM certificate"
        return None
    if method == "DELETE":
        if not TRUSTSTORE_ALIAS.match(path):
            return "DELETE is only allowed on /info/truststore/<alias>"
        alias = urllib.parse.unquote(path[len(TRUSTSTORE) + 1:])
        if alias in (".", "..") or any(c in alias for c in "/\\") or any(ord(c) < 32 or ord(c) == 127 for c in alias):
            return "Invalid alias"
        return None
    return "Method not allowed"
PAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "capi-routes-viewer.html")
MAX_BODY = 64 * 1024


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # surface the 3xx so post_form can decide


def post_form(url, form, ssl_context, timeout, max_redirects=3):
    """POST a form and return (json body, final url). Redirects are followed by POSTing again.

    urllib's default handler turns a POST into a body-less GET on 301/302/303, which a token endpoint
    answers with 405 — typically discovery advertising http:// behind an ingress that redirects to
    https://. The credentials are only re-sent to the same host, never across hosts."""
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ssl_context), _NoRedirect)
    host = urllib.parse.urlparse(url).hostname
    for _ in range(max_redirects + 1):
        req = urllib.request.Request(url, data=form, method="POST",
                                     headers={"Content-Type": "application/x-www-form-urlencoded",
                                              "Accept": "application/json"})
        try:
            with opener.open(req, timeout=timeout) as resp:
                return json.loads(resp.read()), url
        except urllib.error.HTTPError as e:
            location = e.headers.get("Location") if 300 <= e.code < 400 else None
            if not location:
                raise
            target = urllib.parse.urljoin(url, location)
            if urllib.parse.urlparse(target).hostname != host:
                raise ValueError(f"redirected to another host ({target}); not re-sending the client secret there")
            url = target
    raise ValueError(f"too many redirects (last: {url})")


def build_context(args):
    if args.insecure:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    return ssl.create_default_context(cafile=args.ca_file) if args.ca_file else ssl.create_default_context()


def make_handler(ssl_context, timeout):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *a):
            sys.stderr.write("[capi-routes-viewer] " + (fmt % a) + "\n")

        def _send(self, status, content_type, body):
            data = body if isinstance(body, bytes) else body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def _json(self, obj):
            self._send(200, "application/json", json.dumps(obj))

        def do_GET(self):
            if self.path in ("/", "/index.html"):
                with open(PAGE, "rb") as f:
                    self._send(200, "text/html; charset=utf-8", f.read())
            else:
                self._send(404, "text/plain", "Not found")

        def do_POST(self):
            if self.path not in ("/proxy", "/token", "/discover"):
                self._send(404, "text/plain", "Not found")
                return
            # Same-origin only: refuse calls from other pages open in the browser.
            origin = self.headers.get("Origin")
            if origin and urllib.parse.urlparse(origin).netloc != self.headers.get("Host"):
                self._send(403, "text/plain", "Cross-origin call refused")
                return
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY:
                self._send(400, "text/plain", "Bad request")
                return
            if self.path == "/token":
                self._token(length)
                return
            if self.path == "/discover":
                self._discover(length)
                return
            try:
                req = json.loads(self.rfile.read(length))
                node = str(req.get("node", "")).strip().rstrip("/")
                token = str(req.get("token") or "").strip()
                path = str(req.get("path") or ALLOWED_PATHS[0])
                method = str(req.get("method") or "GET").upper()
                body = req.get("body")
                body = str(body) if body is not None else None
            except (ValueError, AttributeError):
                self._send(400, "text/plain", "Bad request")
                return

            parsed = urllib.parse.urlparse(node)
            if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.path not in ("", "/") \
                    or parsed.query or parsed.fragment:
                self._json({"error": "Node must be a base URL like http://host:8381"})
                return

            refused = allowed(method, path, body)
            if refused:
                self._json({"error": refused})
                return
            url = f"{parsed.scheme}://{parsed.netloc}{path}"
            headers = {"Accept": "application/json"}
            if token:
                headers["Authorization"] = "Bearer " + token
            data = None
            if method == "PUT":
                data = body.encode("utf-8")
                headers["Content-Type"] = "application/x-pem-file"
            if method != "GET":
                self.log_message("%s %s%s", method, parsed.netloc, path)
            try:
                with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method),
                                            timeout=timeout, context=ssl_context) as resp:
                    self._json({"status": resp.status, "body": resp.read().decode("utf-8", "replace")})
            except urllib.error.HTTPError as e:
                self._json({"status": e.code, "body": e.read().decode("utf-8", "replace")})
            except Exception as e:  # connection refused, DNS, TLS, timeout
                self._json({"error": f"{type(e).__name__}: {getattr(e, 'reason', e)}"})

        def _discover(self, length):
            """The capi-admin registrations in one Consul: every node of the requested CAPI instance
            (meta capi-cluster), with its health, plus the names of the other instances registered there."""
            try:
                req = json.loads(self.rfile.read(length))
                consul = str(req.get("consul", "")).strip().rstrip("/")
                token = str(req.get("token") or "").strip()
                cluster = str(req.get("cluster") or "").strip() or "default"
            except (ValueError, AttributeError):
                self._send(400, "text/plain", "Bad request")
                return
            parsed = urllib.parse.urlparse(consul)
            if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.path not in ("", "/") \
                    or parsed.query or parsed.fragment:
                self._json({"error": "Consul must be a base URL like https://consul.example.com:8500"})
                return
            # Not ?passing: a node failing its TTL check is shown, unselected, rather than hidden.
            url = f"{parsed.scheme}://{parsed.netloc}/v1/health/service/capi-admin"
            headers = {"Accept": "application/json"}
            if token:
                headers["Authorization"] = "Bearer " + token   # as CAPI itself calls Consul
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers=headers),
                                            timeout=timeout, context=ssl_context) as resp:
                    entries = json.loads(resp.read())
            except urllib.error.HTTPError as e:
                self._json({"error": f"Consul answered HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}"})
                return
            except Exception as e:  # DNS, TLS, timeout, bad JSON
                self._json({"error": f"{url}: {type(e).__name__}: {getattr(e, 'reason', e)}"})
                return
            nodes, clusters = [], {}
            for entry in entries if isinstance(entries, list) else []:
                svc = entry.get("Service") or {}
                meta = svc.get("Meta") or {}
                name = meta.get("capi-cluster") or "default"
                clusters[name] = clusters.get(name, 0) + 1
                if name != cluster:
                    continue
                address = svc.get("Address") or (entry.get("Node") or {}).get("Address") or ""
                port = svc.get("Port") or 0
                if not address or not port:
                    continue
                scheme = "https" if meta.get("admin-scheme") == "https" else "http"
                host = f"[{address}]" if ":" in address else address
                checks = entry.get("Checks") or []
                status = "passing" if all(c.get("Status") == "passing" for c in checks) else \
                    next((c.get("Status") for c in checks if c.get("Status") == "critical"), "warning")
                nodes.append({"id": svc.get("ID"), "url": f"{scheme}://{host}:{port}", "status": status,
                              "version": meta.get("capi-version"),
                              "consulNode": (entry.get("Node") or {}).get("Node")})
            nodes.sort(key=lambda n: (n["status"] != "passing", n["url"]))
            self._json({"cluster": cluster, "nodes": nodes, "clusters": clusters})

        def _token(self, length):
            """Client-credentials grant against the issuer's token endpoint (found by OIDC discovery).
            The secret is used for this one request and never stored or logged."""
            try:
                req = json.loads(self.rfile.read(length))
                issuer = str(req.get("issuer", "")).strip().rstrip("/")
                client_id = str(req.get("clientId", "")).strip()
                secret = str(req.get("clientSecret", ""))
            except (ValueError, AttributeError):
                self._send(400, "text/plain", "Bad request")
                return
            parsed = urllib.parse.urlparse(issuer)
            if parsed.scheme not in ("http", "https") or not parsed.netloc or not client_id:
                self._json({"error": "Issuer must be an http(s) URL and the client id is required"})
                return
            step, url = "discovery", f"{issuer}/.well-known/openid-configuration"
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}),
                                            timeout=timeout, context=ssl_context) as resp:
                    token_endpoint = json.loads(resp.read()).get("token_endpoint")
                if not token_endpoint:
                    self._json({"error": f"No token_endpoint in {url}"})
                    return
                step, url = "token request", token_endpoint
                form = urllib.parse.urlencode({"grant_type": "client_credentials",
                                               "client_id": client_id, "client_secret": secret}).encode()
                body, url = post_form(token_endpoint, form, ssl_context, timeout)
                if not body.get("access_token"):
                    self._json({"error": f"{url} returned no access_token"})
                    return
                self._json({"access_token": body["access_token"], "expires_in": body.get("expires_in")})
            except urllib.error.HTTPError as e:
                detail = e.read().decode("utf-8", "replace")
                try:
                    j = json.loads(detail)
                    detail = j.get("error_description") or j.get("error") or detail
                except ValueError:
                    pass
                self._json({"error": f"{step} failed: HTTP {e.code} from {e.url or url}: {detail[:300]}"})
            except Exception as e:  # DNS, TLS, timeout, bad JSON, refused redirect
                self._json({"error": f"{step} failed at {url}: {type(e).__name__}: {getattr(e, 'reason', e)}"})

    return Handler


def main():
    p = argparse.ArgumentParser(description="Serve capi-routes-viewer.html and forward its calls to the CAPI admin API.")
    p.add_argument("--port", type=int, default=8399)
    p.add_argument("--ca-file", help="PEM bundle to trust for https nodes (internal CA)")
    p.add_argument("--insecure", action="store_true", help="skip TLS certificate verification")
    p.add_argument("--timeout", type=float, default=10.0, help="per-node timeout in seconds")
    args = p.parse_args()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(build_context(args), args.timeout))
    print(f"CAPI routes viewer on http://127.0.0.1:{args.port}  (Ctrl+C to stop)", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
