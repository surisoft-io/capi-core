"""
Generic backend service for the e2e demo.
A single instance handles all 35 logical services.
Returns contextual mock JSON based on the request path.
"""
import json
import random
import time
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

SERVICE_DATA = {
    # --- Open ---
    "health":       {"status": "healthy", "uptime_seconds": 86400, "version": "2.1.0"},
    "config":       {"environment": "production", "region": "eu-west-1", "features": {"dark_mode": True, "beta_api": False}},
    "search":       {"query": "example", "results": [{"id": 1, "title": "First result", "score": 0.98}, {"id": 2, "title": "Second result", "score": 0.85}]},
    "events":       {"events": [{"id": "evt-101", "type": "user.login", "timestamp": "2026-03-13T10:00:00Z"}, {"id": "evt-102", "type": "order.placed", "timestamp": "2026-03-13T10:05:00Z"}]},
    "webhook":      {"webhook_id": "wh-001", "url": "https://example.com/hook", "active": True, "last_triggered": "2026-03-13T09:55:00Z"},
    # --- OPA ---
    "user":         {"user_id": "u-1001", "username": "jdoe", "email": "jdoe@corp.com", "department": "Engineering"},
    "profile":      {"profile_id": "p-1001", "display_name": "John Doe", "avatar": "/avatars/jdoe.png", "bio": "Software engineer"},
    "catalog":      {"items": [{"sku": "CAT-001", "name": "Widget A", "price": 29.99}, {"sku": "CAT-002", "name": "Widget B", "price": 49.99}]},
    "pricing":      {"plan": "enterprise", "base_price": 999.00, "discount_pct": 15, "final_price": 849.15},
    "order":        {"order_id": "ORD-50001", "status": "processing", "total": 1249.99, "items": 3},
    "payment":      {"payment_id": "PAY-70001", "method": "card", "last4": "4242", "amount": 1249.99, "currency": "USD"},
    "invoice":      {"invoice_id": "INV-90001", "issued": "2026-03-01", "due": "2026-03-31", "total": 4999.00, "status": "pending"},
    "audit":        {"entries": [{"action": "user.create", "actor": "admin", "timestamp": "2026-03-13T08:00:00Z"}, {"action": "role.assign", "actor": "admin", "timestamp": "2026-03-13T08:01:00Z"}]},
    "logging":      {"log_level": "INFO", "entries": 142857, "storage_mb": 512, "retention_days": 90},
    "storage":      {"used_gb": 247.3, "total_gb": 1000, "buckets": 12, "objects": 1584203},
    # --- Subscription-group ---
    "notification": {"notifications": [{"id": "n-1", "type": "alert", "message": "CPU usage high", "read": False}]},
    "preferences":  {"theme": "dark", "language": "en", "timezone": "UTC", "notifications_enabled": True},
    "recommendation": {"recommendations": [{"id": "r-1", "product": "Widget Pro", "score": 0.92}, {"id": "r-2", "product": "Service Plus", "score": 0.87}]},
    "email":        {"queued": 42, "sent_today": 1503, "bounce_rate": 0.02, "provider": "ses"},
    "sms":          {"queued": 7, "sent_today": 389, "delivery_rate": 0.97, "provider": "twilio"},
    "push":         {"active_devices": 28450, "sent_today": 12003, "open_rate": 0.34},
    "chat":         {"active_sessions": 156, "messages_today": 8742, "avg_response_ms": 230},
    "partner-api":  {"partner_id": "PTR-001", "name": "Acme Corp", "tier": "gold", "api_calls_today": 45230},
    "marketplace":  {"listings": 1847, "active_sellers": 312, "transactions_today": 567},
    "integration":  {"connected": 8, "integrations": ["slack", "jira", "github", "datadog", "pagerduty", "confluence", "salesforce", "zendesk"]},
    # --- API-key + throttle ---
    "inventory":    {"warehouse": "WH-EU-01", "sku_count": 4521, "items_in_stock": 287430, "last_sync": "2026-03-13T09:00:00Z"},
    "shipping":     {"shipment_id": "SHP-30001", "carrier": "FedEx", "status": "in_transit", "eta": "2026-03-15"},
    "returns":      {"return_id": "RET-2001", "reason": "defective", "status": "approved", "refund_amount": 49.99},
    "export":       {"job_id": "EXP-401", "format": "csv", "rows": 50000, "status": "completed", "download_url": "/exports/EXP-401.csv"},
    "sync":         {"last_sync": "2026-03-13T09:30:00Z", "records_synced": 12450, "conflicts": 0, "status": "idle"},
    # --- Global throttle ---
    "metrics":      {"cpu_pct": 42.3, "memory_pct": 67.8, "disk_io_mbps": 120, "network_mbps": 850},
    "reporting":    {"reports_generated_today": 234, "avg_generation_ms": 1500, "queue_depth": 3},
    "dashboard":    {"active_dashboards": 18, "widgets": 97, "refresh_interval_s": 30},
    "alerts":       {"active_alerts": 2, "total_today": 14, "acknowledged": 12, "escalated": 1},
    "cache":        {"hit_rate": 0.94, "entries": 250000, "memory_mb": 1024, "evictions_today": 1203},
}

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def _handle(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)

        # Simulate latency: ?delay=500 → sleep 500ms
        delay = int(qs.get("delay", ["0"])[0])
        if 0 < delay <= 30000:
            time.sleep(delay / 1000.0)

        # Extract service name from path: /anything -> first segment
        parts = parsed.path.strip("/").split("/")
        service_name = parts[0] if parts else "health"

        # Match against known services (strip "-service" suffix if present)
        key = service_name.replace("-service", "").replace("-api", "")

        data = SERVICE_DATA.get(key, {"service": service_name, "status": "ok", "timestamp": "2026-03-13T10:00:00Z"})

        # Add some dynamic fields
        response = dict(data)
        response["_service"] = service_name
        response["_timestamp"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        response["_request_id"] = f"req-{random.randint(100000, 999999)}"

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(response, indent=2).encode())

    def log_message(self, format, *args):
        pass  # Suppress request logs

if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", 8080), Handler)
    print("Generic service listening on :8080", flush=True)
    server.serve_forever()
