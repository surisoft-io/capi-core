#!/usr/bin/env python3
"""File upload service — accepts multipart/form-data, saves files to /uploads."""

import os
import json
import uuid
import time
import re
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

UPLOAD_DIR = os.environ.get("UPLOAD_DIR", "/uploads")
MAX_FILE_SIZE = int(os.environ.get("MAX_FILE_SIZE", 500 * 1024 * 1024))  # 500MB default

os.makedirs(UPLOAD_DIR, exist_ok=True)


def parse_multipart(body, boundary):
    """Parse multipart/form-data body manually (no cgi module needed)."""
    parts = body.split(b"--" + boundary.encode())
    files = []
    for part in parts:
        if part in (b"", b"--\r\n", b"--"):
            continue
        part = part.strip(b"\r\n")
        if b"\r\n\r\n" not in part:
            continue
        header_block, file_data = part.split(b"\r\n\r\n", 1)
        # Strip trailing \r\n from file data
        if file_data.endswith(b"\r\n"):
            file_data = file_data[:-2]
        headers_text = header_block.decode("utf-8", errors="replace")
        match = re.search(r'filename="([^"]+)"', headers_text)
        if match:
            files.append({"filename": match.group(1), "data": file_data})
    return files


class UploadHandler(BaseHTTPRequestHandler):

    def do_GET(self):
        """List uploaded files."""
        try:
            files = []
            for fname in sorted(os.listdir(UPLOAD_DIR)):
                fpath = os.path.join(UPLOAD_DIR, fname)
                if os.path.isfile(fpath):
                    stat = os.stat(fpath)
                    files.append({
                        "name": fname,
                        "size": stat.st_size,
                        "uploaded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(stat.st_mtime)),
                    })
            self._json_response(200, {"files": files, "count": len(files)})
        except Exception as e:
            self._json_response(500, {"error": str(e)})

    def do_POST(self):
        """Handle multipart file upload."""
        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self._json_response(400, {"error": "Content-Type must be multipart/form-data"})
            return

        content_length = int(self.headers.get("Content-Length", 0))
        if content_length > MAX_FILE_SIZE:
            self._json_response(413, {"error": f"File too large. Max size: {MAX_FILE_SIZE} bytes"})
            return

        # Extract boundary from Content-Type
        match = re.search(r"boundary=(.+)", content_type)
        if not match:
            self._json_response(400, {"error": "No boundary found in Content-Type"})
            return
        boundary = match.group(1).strip()

        try:
            body = self.rfile.read(content_length)
            files = parse_multipart(body, boundary)

            if not files:
                self._json_response(400, {"error": "No file found in request"})
                return

            saved = []
            for f in files:
                saved.append(self._save_file(f["filename"], f["data"]))

            self._json_response(200, {"uploaded": saved, "count": len(saved)})

        except Exception as e:
            self._json_response(500, {"error": str(e)})

    def do_DELETE(self):
        """Delete a file by name (path: /filename)."""
        filename = self.path.lstrip("/").split("/")[-1]
        if not filename:
            self._json_response(400, {"error": "Filename required"})
            return
        fpath = os.path.join(UPLOAD_DIR, os.path.basename(filename))
        if not os.path.isfile(fpath):
            self._json_response(404, {"error": f"File not found: {filename}"})
            return
        try:
            os.remove(fpath)
            self._json_response(200, {"deleted": filename})
        except Exception as e:
            self._json_response(500, {"error": str(e)})

    def _save_file(self, original_name, data):
        original_name = os.path.basename(original_name)
        file_id = uuid.uuid4().hex[:8]
        safe_name = f"{file_id}_{original_name}"
        fpath = os.path.join(UPLOAD_DIR, safe_name)

        with open(fpath, "wb") as f:
            f.write(data)

        return {
            "id": file_id,
            "original_name": original_name,
            "saved_as": safe_name,
            "size": len(data),
            "uploaded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }

    def _json_response(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        pass  # Suppress default logging


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    server = ThreadingHTTPServer(("0.0.0.0", port), UploadHandler)
    print(f"Upload service listening on port {port}, saving to {UPLOAD_DIR}")
    server.serve_forever()
