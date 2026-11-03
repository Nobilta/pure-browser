#!/usr/bin/env python3
"""Local fixture server for the developer-tools regression (ports 8875 and 8876)."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import threading
import time

ROOT = Path(__file__).resolve().parent


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status, body, content_type="text/plain; charset=utf-8", extra=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for name, value in (extra or []):
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/devtools-fixture.html") or self.path == "/":
            body = (ROOT / "devtools-fixture.html").read_text(encoding="utf-8")
            if "long=1" in self.path:
                rows = "".join("<p>Long server line %d — the text source must page through every line.</p>" % i
                               for i in range(1500))
                body = body.replace("<script>", "<main>" + rows + "</main><script>", 1)
            self._send(200, body, "text/html; charset=utf-8", [
                ("Set-Cookie", "doc_cookie=visible; Path=/; SameSite=Lax"),
                ("Set-Cookie", "sid=httponly-value; Path=/; HttpOnly; SameSite=Lax"),
                ("Set-Cookie", "secure_cookie=yes; Path=/; Secure; SameSite=None"),
            ])
            return
        if self.path.startswith("/api/frame"):
            self._send(200, "<!doctype html><p id='frame-text'>cross origin frame</p>", "text/html; charset=utf-8")
            return
        if self.path.startswith("/api/echo"):
            payload = {"ok": True, "query": self.path.split("?", 1)[1] if "?" in self.path else "",
                       "method": "GET"}
            self._send(200, json.dumps(payload), "application/json; charset=utf-8")
            return
        if self.path.startswith("/api/html"):
            body = "<!doctype html><h1>Captured HTML</h1><script>document.title='BAD'</script>"
            self._send(200, body, "text/html; charset=utf-8")
            return
        if self.path.startswith("/api/big"):
            self._send(200, "x" * 300000, "text/plain; charset=utf-8")
            return
        if self.path.startswith("/api/stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            for i in range(30):
                try:
                    self.wfile.write(("data: tick %d\n\n" % i).encode())
                    self.wfile.flush()
                except Exception:
                    break
                time.sleep(0.1)
            return
        if self.path.startswith("/api/cross"):
            self._send(200, json.dumps({"cross": True}), "application/json; charset=utf-8")
            return
        self._send(404, "not found")

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length)
        if self.path.startswith("/api/echo"):
            self._send(200, json.dumps({"ok": True, "method": "POST",
                                        "body": body.decode("utf-8", "replace"),
                                        "query": self.path.split("?", 1)[1] if "?" in self.path else ""}),
                       "application/json; charset=utf-8")
            return
        self._send(404, "not found")


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 8875), Handler)
    cross = ThreadingHTTPServer(("127.0.0.1", 8876), Handler)
    threading.Thread(target=cross.serve_forever, daemon=True).start()
    print("fixture server on 8875/8876", flush=True)
    server.serve_forever()
