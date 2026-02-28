"""Dummy translation service for CAPI MCP Gateway demo."""
from http.server import HTTPServer, BaseHTTPRequestHandler
import json

GREETINGS = {
    "spanish": {"hello": "hola", "goodbye": "adios", "thank you": "gracias", "please": "por favor"},
    "french":  {"hello": "bonjour", "goodbye": "au revoir", "thank you": "merci", "please": "s'il vous plait"},
    "german":  {"hello": "hallo", "goodbye": "auf wiedersehen", "thank you": "danke", "please": "bitte"},
    "portuguese": {"hello": "ola", "goodbye": "adeus", "thank you": "obrigado", "please": "por favor"},
    "japanese": {"hello": "konnichiwa", "goodbye": "sayonara", "thank you": "arigatou", "please": "onegaishimasu"},
    "italian": {"hello": "ciao", "goodbye": "arrivederci", "thank you": "grazie", "please": "per favore"},
}

SUPPORTED_LANGUAGES = list(GREETINGS.keys())


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length)) if length > 0 else {}
        text = body.get("text", "")
        target = body.get("target_language", "spanish").lower()

        if target not in GREETINGS:
            response = {
                "original": text,
                "target_language": target,
                "translated": None,
                "error": f"Unsupported language. Supported: {', '.join(SUPPORTED_LANGUAGES)}",
            }
        else:
            lookup = GREETINGS[target]
            translated = lookup.get(text.lower(), f"[{target}] {text}")
            response = {
                "original": text,
                "target_language": target,
                "translated": translated,
                "confidence": 0.95 if text.lower() in lookup else 0.72,
            }

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(response).encode())

    def log_message(self, fmt, *args):
        print(f"[translate] {args[0]}")


if __name__ == "__main__":
    print("Translation service starting on :8080")
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
