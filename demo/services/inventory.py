"""Dummy product inventory service for CAPI MCP Gateway demo."""
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import random

CATALOG = {
    "laptop":     {"category": "Electronics",  "price": 999.99,  "warehouse": "EU-West"},
    "headphones": {"category": "Electronics",  "price": 149.99,  "warehouse": "EU-West"},
    "keyboard":   {"category": "Peripherals",  "price": 79.99,   "warehouse": "US-East"},
    "monitor":    {"category": "Electronics",  "price": 449.99,  "warehouse": "US-East"},
    "mouse":      {"category": "Peripherals",  "price": 39.99,   "warehouse": "EU-West"},
    "webcam":     {"category": "Peripherals",  "price": 89.99,   "warehouse": "AP-South"},
    "chair":      {"category": "Furniture",    "price": 299.99,  "warehouse": "EU-West"},
    "desk":       {"category": "Furniture",    "price": 399.99,  "warehouse": "US-East"},
}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length)) if length > 0 else {}
        product = body.get("product", "").lower()

        if product in CATALOG:
            info = CATALOG[product]
            response = {
                "product": product,
                "in_stock": random.choice([True, True, True, False]),
                "quantity": random.randint(0, 500),
                "price_usd": info["price"],
                "category": info["category"],
                "warehouse": info["warehouse"],
                "last_restocked": f"2026-02-{random.randint(1, 22):02d}",
            }
        else:
            response = {
                "product": product,
                "in_stock": False,
                "quantity": 0,
                "message": f"Product '{product}' not found. Available: {', '.join(sorted(CATALOG.keys()))}",
            }

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(response).encode())

    def log_message(self, fmt, *args):
        print(f"[inventory] {args[0]}")


if __name__ == "__main__":
    print("Inventory service starting on :8080")
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
