"""Dummy weather forecast service for CAPI MCP Gateway demo."""
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import random

CONDITIONS = ["Sunny", "Partly Cloudy", "Cloudy", "Rainy", "Thunderstorm", "Windy", "Foggy", "Snowy"]

CITY_BASELINES = {
    "lisbon": (18, 65), "london": (12, 75), "tokyo": (16, 60),
    "new york": (14, 55), "sydney": (22, 50), "paris": (13, 70),
    "berlin": (10, 68), "dubai": (35, 30), "moscow": (5, 72),
    "bertolandia": (20, 30), "sapancio-filio": (20, 40)
}

# Minimal OpenAPI 3.0 spec served at GET /openapi.json so the service can be
# registered with mcp-from-openapi=true and exercise CAPI's auto-promotion.
OPENAPI_SPEC = {
    "openapi": "3.0.1",
    "info": {"title": "weather", "version": "1.0.0"},
    "paths": {
        "/forecast": {
            "post": {
                "operationId": "getForecast",
                "summary": "Get weather forecast for a city",
                "requestBody": {
                    "required": True,
                    "content": {
                        "application/json": {
                            "schema": {
                                "type": "object",
                                "properties": {
                                    "city": {"type": "string", "description": "City name"}
                                },
                                "required": ["city"]
                            }
                        }
                    }
                },
                "responses": {"200": {"description": "OK"}}
            }
        },
        "/cities": {
            "get": {
                "operationId": "listCities",
                "summary": "List supported cities",
                "responses": {"200": {"description": "OK"}}
            }
        }
    }
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/openapi.json":
            payload = json.dumps(OPENAPI_SPEC).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if self.path == "/cities":
            payload = json.dumps({"cities": sorted(CITY_BASELINES.keys())}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length)) if length > 0 else {}
        city = body.get("city", "Unknown")

        base_temp, base_humidity = CITY_BASELINES.get(city.lower(), (20, 55))
        response = {
            "city": city,
            "temperature_celsius": base_temp + random.randint(-5, 5),
            "humidity_percent": base_humidity + random.randint(-10, 10),
            "condition": random.choice(CONDITIONS),
            "wind_speed_kmh": random.randint(5, 40),
            "forecast": [
                {"day": f"+{i}d", "high": base_temp + random.randint(-3, 6), "low": base_temp + random.randint(-8, -1)}
                for i in range(1, 4)
            ],
        }

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(response).encode())

    def log_message(self, fmt, *args):
        print(f"[weather] {args[0]}")


if __name__ == "__main__":
    print("Weather service starting on :8080")
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
