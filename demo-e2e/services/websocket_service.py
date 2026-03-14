"""
WebSocket backend service for the e2e demo.
Accepts WebSocket connections and sends periodic pings with system-like metrics.
"""
import asyncio
import json
import random
import time
import hashlib

try:
    import websockets
except ImportError:
    import subprocess
    subprocess.check_call(["pip", "install", "--quiet", "websockets"])
    import websockets

CONNECTED = set()


async def handler(websocket):
    CONNECTED.add(websocket)
    client_id = hashlib.md5(str(id(websocket)).encode()).hexdigest()[:8]
    print(f"Client {client_id} connected ({len(CONNECTED)} total)", flush=True)

    try:
        # Send welcome message
        await websocket.send(json.dumps({
            "type": "connected",
            "client_id": client_id,
            "message": "WebSocket connection established",
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }))

        # Send pings every 3 seconds
        while True:
            ping = {
                "type": "ping",
                "client_id": client_id,
                "seq": int(time.time() * 1000) % 1000000,
                "cpu_pct": round(random.uniform(10, 85), 1),
                "memory_pct": round(random.uniform(40, 90), 1),
                "active_connections": len(CONNECTED),
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            }
            await websocket.send(json.dumps(ping))
            await asyncio.sleep(3)
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        CONNECTED.discard(websocket)
        print(f"Client {client_id} disconnected ({len(CONNECTED)} total)", flush=True)


async def main():
    print("WebSocket service listening on :9090", flush=True)
    async with websockets.serve(handler, "0.0.0.0", 9090):
        await asyncio.Future()  # Run forever

if __name__ == "__main__":
    asyncio.run(main())