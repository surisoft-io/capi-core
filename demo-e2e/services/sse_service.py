"""
SSE (Server-Sent Events) backend service for the e2e demo.
Streams system-like events every 2 seconds.
"""
import json
import random
import time

try:
    from flask import Flask, Response
except ImportError:
    import subprocess
    subprocess.check_call(["pip", "install", "--quiet", "flask"])
    from flask import Flask, Response

app = Flask(__name__)

EVENT_TYPES = [
    {"type": "cpu", "gen": lambda: {"usage_pct": round(random.uniform(10, 95), 1), "cores_active": random.randint(2, 8)}},
    {"type": "memory", "gen": lambda: {"used_gb": round(random.uniform(4, 14), 1), "total_gb": 16, "swap_mb": random.randint(0, 512)}},
    {"type": "disk_io", "gen": lambda: {"read_mbps": round(random.uniform(10, 200), 1), "write_mbps": round(random.uniform(5, 150), 1)}},
    {"type": "network", "gen": lambda: {"in_mbps": round(random.uniform(50, 900), 1), "out_mbps": round(random.uniform(20, 500), 1), "connections": random.randint(100, 5000)}},
    {"type": "request", "gen": lambda: {"method": random.choice(["GET", "POST", "PUT", "DELETE"]), "path": random.choice(["/api/users", "/api/orders", "/api/products", "/api/health"]), "status": random.choice([200, 200, 200, 201, 304, 400, 404, 500]), "latency_ms": random.randint(1, 800)}},
    {"type": "alert", "gen": lambda: {"severity": random.choice(["info", "warning", "critical"]), "message": random.choice(["High CPU usage detected", "Memory threshold exceeded", "Disk space low", "Service latency spike", "Connection pool exhausted", "Certificate expiring soon"])}},
    {"type": "deployment", "gen": lambda: {"service": random.choice(["api-gateway", "auth-service", "order-service", "payment-service"]), "version": f"v{random.randint(1,5)}.{random.randint(0,9)}.{random.randint(0,99)}", "status": random.choice(["rolling", "completed", "rollback"])}},
]


def generate_events():
    seq = 0
    while True:
        seq += 1
        evt = random.choice(EVENT_TYPES)
        data = {
            "seq": seq,
            "event_type": evt["type"],
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            **evt["gen"](),
        }
        yield f"event: {evt['type']}\ndata: {json.dumps(data)}\n\n"
        time.sleep(2)


@app.route("/stream")
def stream():
    return Response(generate_events(), mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache", "Connection": "keep-alive"})


if __name__ == "__main__":
    print("SSE service listening on :9091", flush=True)
    app.run(host="0.0.0.0", port=9091, threaded=True)
