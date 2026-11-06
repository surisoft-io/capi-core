#!/usr/bin/env python3
"""
stdio <-> HTTPS bridge for CAPI's admin MCP server, with OAuth2 client-credentials.

For MCP clients that can only launch a local process (command/args/env), or that cannot refresh a
client-credentials token themselves. The client talks MCP over stdin/stdout to this process; each
JSON-RPC message is POSTed to the admin MCP endpoint with a bearer token this bridge obtains and
refreshes on its own. Standard library only — nothing to install.

Configuration (environment variables — keep the secret out of any file you commit):
    CAPI_MCP_URL        https://capi-node.example.com:8390/mcp            (required)
    CAPI_TOKEN_URL      https://idp.example.com/realms/ops/protocol/openid-connect/token  (required)
    CAPI_CLIENT_ID      client-credentials client id                         (required)
    CAPI_CLIENT_SECRET  its secret                                           (required)
    CAPI_SCOPE          optional scope parameter for the token request
    CAPI_CA_FILE        optional PEM bundle to trust (internal CA)
    CAPI_TIMEOUT        per-request timeout in seconds (default 30)

Logs go to stderr; stdout carries only MCP messages.
"""
import json
import os
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

PROTOCOL_HEADER = "MCP-Protocol-Version"


def log(message):
    print(f"[capi-admin-mcp-bridge] {message}", file=sys.stderr, flush=True)


class TokenError(Exception):
    """The token endpoint refused or failed — distinct from an error returned by CAPI itself."""


def require(name):
    value = os.environ.get(name, "").strip()
    if not value:
        log(f"missing required environment variable {name}")
        sys.exit(2)
    return value


class Bridge:
    def __init__(self):
        self.url = require("CAPI_MCP_URL")
        self.token_url = require("CAPI_TOKEN_URL")
        self.client_id = require("CAPI_CLIENT_ID")
        self.client_secret = require("CAPI_CLIENT_SECRET")
        self.scope = os.environ.get("CAPI_SCOPE", "").strip()
        self.timeout = float(os.environ.get("CAPI_TIMEOUT", "30"))
        ca_file = os.environ.get("CAPI_CA_FILE", "").strip()
        self.ssl_context = ssl.create_default_context(cafile=ca_file or None)
        self.token = None
        self.token_expires_at = 0.0
        self.protocol_version = None   # learned from the initialize result, then sent as a header
        self.lock = threading.Lock()   # one token refresh at a time
        self.out_lock = threading.Lock()

    # ---------------------------------------------------------------- token

    def access_token(self, force=False):
        with self.lock:
            # Refresh 30 s early so a token never expires between fetch and use.
            if force or self.token is None or time.time() > self.token_expires_at - 30:
                form = {"grant_type": "client_credentials",
                        "client_id": self.client_id, "client_secret": self.client_secret}
                if self.scope:
                    form["scope"] = self.scope
                request = urllib.request.Request(
                    self.token_url, data=urllib.parse.urlencode(form).encode(),
                    headers={"Content-Type": "application/x-www-form-urlencoded"})
                try:
                    with urllib.request.urlopen(request, timeout=self.timeout, context=self.ssl_context) as response:
                        body = json.loads(response.read())
                except urllib.error.HTTPError as e:
                    raise TokenError(f"token endpoint returned HTTP {e.code}: "
                                     f"{e.read().decode(errors='replace')[:200]}") from None
                except (urllib.error.URLError, OSError, ValueError) as e:
                    raise TokenError(f"token endpoint unreachable: {e}") from None
                self.token = body["access_token"]
                self.token_expires_at = time.time() + float(body.get("expires_in", 60))
                log(f"token refreshed, valid {int(body.get('expires_in', 60))}s")
            return self.token

    # ---------------------------------------------------------------- relay

    def post(self, raw, token):
        headers = {"Content-Type": "application/json",
                   "Accept": "application/json, text/event-stream",
                   "Authorization": f"Bearer {token}"}
        if self.protocol_version:
            headers[PROTOCOL_HEADER] = self.protocol_version
        request = urllib.request.Request(self.url, data=raw.encode(), headers=headers, method="POST")
        return urllib.request.urlopen(request, timeout=self.timeout, context=self.ssl_context)

    def relay(self, raw):
        try:
            message = json.loads(raw)
        except json.JSONDecodeError:
            self.emit({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})
            return
        message_id = message.get("id") if isinstance(message, dict) else None
        try:
            try:
                response = self.post(raw, self.access_token())
            except urllib.error.HTTPError as e:
                if e.code != 401:
                    raise
                # Revoked or clock-skewed token: fetch a fresh one and retry exactly once.
                response = self.post(raw, self.access_token(force=True))
            with response:
                status = response.status
                content_type = response.headers.get("Content-Type", "")
                body = response.read().decode()
            if status == 202 or not body.strip():
                return   # notification acknowledged; nothing goes back to the client
            if "text/event-stream" in content_type:
                for line in body.splitlines():
                    if line.startswith("data:"):
                        self.forward(line[5:].strip(), message)
            else:
                self.forward(body, message)
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            log(f"HTTP {e.code} from server: {detail}")
            if message_id is not None:
                # Relay only a real JSON-RPC body; anything else becomes one, so the client never
                # hangs and never receives a non-MCP line on stdout.
                try:
                    parsed = json.loads(detail)
                except (json.JSONDecodeError, ValueError):
                    parsed = None
                if isinstance(parsed, dict) and parsed.get("jsonrpc") == "2.0":
                    parsed["id"] = message_id
                    self.emit(parsed)
                else:
                    self.emit({"jsonrpc": "2.0", "id": message_id,
                               "error": {"code": -32000, "message": f"HTTP {e.code} from CAPI admin MCP"}})
        except TokenError as e:
            log(str(e))
            if message_id is not None:
                self.emit({"jsonrpc": "2.0", "id": message_id,
                           "error": {"code": -32000, "message": f"Bridge could not get a token: {e}"}})
        except Exception as e:  # network, TLS, token endpoint down...
            log(f"request failed: {e!r}")
            if message_id is not None:
                self.emit({"jsonrpc": "2.0", "id": message_id,
                           "error": {"code": -32000, "message": f"Bridge could not reach CAPI admin MCP: {e}"}})

    def forward(self, payload, request):
        data = json.loads(payload)
        # Remember the negotiated revision so later requests carry MCP-Protocol-Version.
        if isinstance(request, dict) and request.get("method") == "initialize":
            version = (data.get("result") or {}).get("protocolVersion")
            if version:
                self.protocol_version = version
        self.emit(data)

    def emit(self, data):
        with self.out_lock:
            sys.stdout.write(json.dumps(data, separators=(",", ":")) + "\n")
            sys.stdout.flush()

    def run(self):
        log(f"bridging stdio <-> {self.url}")
        in_flight = []
        for line in sys.stdin:
            if line.strip():
                # One thread per message: a slow tools/call must not block a ping or a cancel.
                worker = threading.Thread(target=self.relay, args=(line,), daemon=True)
                worker.start()
                in_flight = [t for t in in_flight if t.is_alive()] + [worker]
        # stdin closed: let requests already sent finish, so their answers are not lost.
        for worker in in_flight:
            worker.join(timeout=self.timeout)


if __name__ == "__main__":
    Bridge().run()
