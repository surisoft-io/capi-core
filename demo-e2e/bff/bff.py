"""
BFF (Backend-For-Frontend) for the e2e demo.
Handles OIDC auth code flow, session management, and API proxying.
"""
import os
import json
import uuid
import time
import urllib.request
import urllib.parse
import urllib.error
import base64

from flask import Flask, redirect, request, jsonify, make_response, session

app = Flask(__name__)
app.secret_key = os.environ["SECRET_KEY"]

KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://keycloak:8080")
KEYCLOAK_EXTERNAL_URL = os.environ.get("KEYCLOAK_EXTERNAL_URL", "http://localhost:9000/keycloak")
REALM = os.environ.get("REALM", "e2e-demo")
CLIENT_ID = os.environ.get("CLIENT_ID", "demo-bff")
CLIENT_SECRET = os.environ["CLIENT_SECRET"]
CAPI_URL = os.environ.get("CAPI_URL", "http://capi:8380")
CALLBACK_URL = os.environ.get("CALLBACK_URL", "http://localhost:9000/bff/callback")

AUTH_ENDPOINT = f"{KEYCLOAK_EXTERNAL_URL}/realms/{REALM}/protocol/openid-connect/auth"
TOKEN_ENDPOINT = f"{KEYCLOAK_URL}/keycloak/realms/{REALM}/protocol/openid-connect/token"
LOGOUT_ENDPOINT = f"{KEYCLOAK_EXTERNAL_URL}/realms/{REALM}/protocol/openid-connect/logout"


def decode_jwt_payload(token):
    """Decode JWT payload without verification (for display only)."""
    parts = token.split(".")
    if len(parts) != 3:
        return {}
    payload = parts[1]
    # Add padding
    payload += "=" * (4 - len(payload) % 4)
    try:
        return json.loads(base64.urlsafe_b64decode(payload))
    except Exception:
        return {}


@app.route("/bff/login")
def login():
    """Redirect to Keycloak authorization endpoint."""
    state = str(uuid.uuid4())
    session["oauth_state"] = state
    params = {
        "client_id": CLIENT_ID,
        "response_type": "code",
        "scope": "openid",
        "redirect_uri": CALLBACK_URL,
        "state": state,
    }
    hint = request.args.get("hint")
    if hint:
        params["login_hint"] = hint
    return redirect(f"{AUTH_ENDPOINT}?{urllib.parse.urlencode(params)}")


@app.route("/bff/login/<username>")
def login_direct(username):
    """Direct login via resource owner password grant (for demo convenience)."""
    passwords = {
        "alice": os.environ.get("ALICE_PASSWORD", "alice123"),
        "bob": os.environ.get("BOB_PASSWORD", "bob123"),
        "charlie": os.environ.get("CHARLIE_PASSWORD", "charlie123"),
    }
    password = passwords.get(username)
    if not password:
        return jsonify({"error": f"Unknown user: {username}"}), 400

    data = urllib.parse.urlencode({
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "grant_type": "password",
        "username": username,
        "password": password,
        "scope": "openid",
    }).encode()

    req = urllib.request.Request(TOKEN_ENDPOINT, data=data,
                                headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req) as resp:
            tokens = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        return jsonify({"error": "Token exchange failed", "detail": body}), 502

    session["access_token"] = tokens["access_token"]
    session["refresh_token"] = tokens.get("refresh_token", "")
    session["id_token"] = tokens.get("id_token", "")
    session["username"] = username

    return jsonify({"status": "ok", "username": username})


@app.route("/bff/callback")
def callback():
    """Handle Keycloak callback — exchange code for tokens."""
    code = request.args.get("code")
    state = request.args.get("state")

    if not code:
        return jsonify({"error": "Missing authorization code"}), 400

    # Exchange code for tokens
    data = urllib.parse.urlencode({
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": CALLBACK_URL,
    }).encode()

    req = urllib.request.Request(TOKEN_ENDPOINT, data=data,
                                headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req) as resp:
            tokens = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        return jsonify({"error": "Token exchange failed", "detail": body}), 502

    session["access_token"] = tokens["access_token"]
    session["refresh_token"] = tokens.get("refresh_token", "")
    session["id_token"] = tokens.get("id_token", "")

    payload = decode_jwt_payload(tokens["access_token"])
    session["username"] = payload.get("preferred_username", "unknown")

    # Redirect back to the UI
    return redirect("/")


@app.route("/bff/me")
def me():
    """Return decoded JWT info for the current session."""
    token = session.get("access_token")
    if not token:
        return jsonify({"authenticated": False}), 401

    payload = decode_jwt_payload(token)
    return jsonify({
        "authenticated": True,
        "username": session.get("username", "unknown"),
        "roles": payload.get("realm_access", {}).get("roles", []),
        "subscriptions": payload.get("subscriptions", []),
        "azp": payload.get("azp", ""),
        "exp": payload.get("exp", 0),
    })


@app.route("/bff/token")
def token():
    """Return the raw access token (for the UI to make direct API calls)."""
    t = session.get("access_token")
    if not t:
        return jsonify({"error": "Not authenticated"}), 401
    return jsonify({"access_token": t})


@app.route("/bff/config")
def config():
    """Return client-side config (API keys for testing)."""
    return jsonify({
        "apiKeys": {
            "unlimited": os.environ.get("API_KEY_UNLIMITED", ""),
            "standard": os.environ.get("API_KEY_STANDARD", ""),
            "limited": os.environ.get("API_KEY_LIMITED", ""),
        }
    })


@app.route("/bff/gateway-config")
def gateway_config():
    """Return the CAPI gateway config YAML for display in the UI."""
    config_path = os.environ.get("CAPI_CONFIG_FILE", "/capi/config/config.yaml")
    try:
        with open(config_path, "r") as f:
            return f.read(), 200, {"Content-Type": "text/plain; charset=utf-8"}
    except FileNotFoundError:
        return "Config file not found", 404


@app.route("/bff/api/<path:path>", methods=["GET", "POST", "PUT", "DELETE", "PATCH"])
def proxy(path):
    """Proxy requests to CAPI with Bearer token from session."""
    token = session.get("access_token")
    if not token:
        return jsonify({"error": "Not authenticated"}), 401

    url = f"{CAPI_URL}/api/{path}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": request.content_type or "application/json",
    }

    body = request.get_data() or None
    req = urllib.request.Request(url, data=body, headers=headers, method=request.method)
    try:
        with urllib.request.urlopen(req) as resp:
            resp_body = resp.read()
            response = make_response(resp_body, resp.status)
            response.headers["Content-Type"] = resp.headers.get("Content-Type", "application/json")
            return response
    except urllib.error.HTTPError as e:
        resp_body = e.read()
        response = make_response(resp_body, e.code)
        response.headers["Content-Type"] = e.headers.get("Content-Type", "application/json")
        return response


@app.route("/bff/grpc", methods=["POST"])
def grpc_proxy():
    """Proxy gRPC calls through CAPI's gRPC gateway for browser testing."""
    data = request.get_json()
    if not data:
        return jsonify({"error": "JSON body required"}), 400

    service = data.get("service", "greeter/dev")
    name = data.get("name", "World")
    grpc_host = os.environ.get("CAPI_GRPC_URL", "capi:8384")

    try:
        import grpc
        import greeter_pb2
        import greeter_pb2_grpc

        channel = grpc.insecure_channel(grpc_host)
        stub = greeter_pb2_grpc.GreeterStub(channel)
        metadata = [("x-capi-service", service)]
        response = stub.SayHello(
            greeter_pb2.HelloRequest(name=name),
            metadata=metadata,
            timeout=10,
        )
        channel.close()
        return jsonify({"message": response.message, "service": service})
    except Exception as e:
        return jsonify({"error": str(e), "service": service}), 502


@app.route("/bff/logout")
def logout():
    """Clear session and redirect to Keycloak logout."""
    id_token = session.get("id_token", "")
    session.clear()
    if id_token:
        params = urllib.parse.urlencode({
            "id_token_hint": id_token,
            "post_logout_redirect_uri": CALLBACK_URL.rsplit("/bff/callback", 1)[0],
        })
        return redirect(f"{LOGOUT_ENDPOINT}?{params}")
    return redirect("/")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
