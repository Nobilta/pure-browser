#!/usr/bin/env python3
"""Local fixture server with byte ranges and bounded playback telemetry on two origins."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from collections import defaultdict, deque
import json
import mimetypes
import re
import threading
import time

ROOT = Path(__file__).resolve().parent
EVENTS = defaultdict(lambda: deque(maxlen=600))
LOCK = threading.Lock()

class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, *args):
        pass

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path != '/__state':
            self.send_error(404); return
        length = int(self.headers.get('Content-Length', '0'))
        if length > 16384:
            self.send_error(413); return
        value = json.loads(self.rfile.read(length))
        value['receivedAt'] = time.time()
        case = parse_qs(parsed.query).get('case', ['default'])[0][:80]
        with LOCK:
            EVENTS[case].append(value)
        self.send_response(204); self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == '/__state':
            case = parse_qs(parsed.query).get('case', ['default'])[0][:80]
            with LOCK:
                body = json.dumps(list(EVENTS[case])).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body); return
        file = Path(self.translate_path(self.path))
        match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
        if match and file.is_file() and file.suffix in ['.mp4', '.webm', '.ts']:
            size = file.stat().st_size
            start = int(match[1]); end = min(int(match[2]) if match[2] else size - 1, size - 1)
            if start >= size or start > end:
                self.send_response(416); self.send_header('Content-Range', f'bytes */{size}'); self.end_headers(); return
            self.send_response(206)
            self.send_header('Content-Type', mimetypes.guess_type(file.name)[0] or 'application/octet-stream')
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.send_header('Content-Length', str(end-start+1))
            self.end_headers()
            with file.open('rb') as stream:
                stream.seek(start); self.wfile.write(stream.read(end-start+1))
            return
        super().do_GET()

if __name__ == '__main__':
    secondary = ThreadingHTTPServer(('127.0.0.1', 8876), Handler)
    threading.Thread(target=secondary.serve_forever, daemon=True).start()
    print('Fixtures available on loopback ports 8875 and 8876', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 8875), Handler).serve_forever()
