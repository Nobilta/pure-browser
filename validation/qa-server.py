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
import struct
import zlib

ROOT = Path(__file__).resolve().parent
EVENTS = defaultdict(lambda: deque(maxlen=600))
LOCK = threading.Lock()
FILTER_MODE = 'valid'

def png_chunk(kind, payload):
    return struct.pack('!I', len(payload)) + kind + payload + struct.pack('!I', zlib.crc32(kind + payload))

IMAGE = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('!IIBBBBB', 160, 96, 8, 2, 0, 0, 0))
         + png_chunk(b'IDAT', zlib.compress(b''.join(
             b'\0' + bytes([40, 130, 90] if y < 48 else [230, 190, 70]) * 160 for y in range(96))))
         + png_chunk(b'IEND', b''))

class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, *args):
        pass

    def do_POST(self):
        global FILTER_MODE
        parsed = urlparse(self.path)
        if parsed.path == '/__filter-mode':
            mode = parse_qs(parsed.query).get('value', ['valid'])[0]
            if mode not in ('valid', 'updated', 'invalid', 'offline'):
                self.send_error(400); return
            FILTER_MODE = mode
            self.send_response(204); self.end_headers(); return
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
        if parsed.path == '/__auth':
            if self.headers.get('Authorization') != 'Basic dGVzdDpwdXJl':
                self.send_response(401)
                self.send_header('WWW-Authenticate', 'Basic realm="Pure fixture"')
                self.send_header('Content-Length', '0'); self.end_headers(); return
            body = b'<h1>Authentication completed</h1>'
            self.send_response(200); self.send_header('Content-Type', 'text/html')
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body); return
        if parsed.path == '/filter-marker.js':
            with LOCK:
                EVENTS['filter-requests'].append({'kind': 'marker', 'receivedAt': time.time()})
            body = b'window.__pureFilterMarker=true;'
            self.send_response(200); self.send_header('Content-Type', 'application/javascript')
            self.send_header('Cache-Control', 'no-store'); self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body); return
        if parsed.path == '/__filter-list':
            mode = FILTER_MODE
            etag = '"pure-' + mode + '"'
            with LOCK:
                EVENTS['filter-requests'].append({'kind': 'list', 'mode': mode,
                    'validator': self.headers.get('If-None-Match'), 'receivedAt': time.time()})
            if mode == 'offline':
                self.send_error(503); return
            if mode == 'invalid':
                body = b'<html><body>Captive portal</body></html>'
                content_type = 'text/html'
            else:
                if self.headers.get('If-None-Match') == etag:
                    self.send_response(304); self.send_header('ETag', etag); self.end_headers(); return
                body = ('[Adblock Plus 2.0]\n! Pure regression ' + mode + '\n'
                        '||127.0.0.1:8875/filter-marker.js\n127.0.0.1###pure-filter-unit\n'
                        + ('||pure-update-fixture.invalid^\n' if mode == 'updated' else '')).encode()
                content_type = 'text/plain'
            self.send_response(200); self.send_header('Content-Type', content_type)
            self.send_header('ETag', etag); self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body); return
        if parsed.path == '/productivity-fixture.html':
            with LOCK:
                EVENTS['context-requests'].append({'path': self.path, 'receivedAt': time.time()})
        if parsed.path == '/context-image.png':
            self.send_response(200)
            self.send_header('Content-Type', 'image/png')
            self.send_header('Content-Length', str(len(IMAGE)))
            self.end_headers()
            self.wfile.write(IMAGE)
            return
        if parsed.path in ('/download-test.bin', '/download-resume.bin'):
            slow = parsed.path == '/download-resume.bin'
            size = (48 if slow else 6) * 1024 * 1024
            match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
            start, end = (int(match[1]), min(int(match[2]) if match[2] else size - 1, size - 1)) if match else (0, size - 1)
            if start > end or start >= size:
                self.send_response(416); self.end_headers(); return
            with LOCK:
                EVENTS['download-requests'].append({'start': start, 'end': end, 'receivedAt': time.time(),
                    'path': parsed.path, 'ifRange': self.headers.get('If-Range')})
            self.send_response(206 if match else 200)
            self.send_header('Content-Type', 'application/octet-stream')
            filename = 'pure-resume-test.bin' if slow else 'pure-range-test.bin'
            self.send_header('Content-Disposition', 'attachment; filename="' + filename + '"')
            self.send_header('Content-Length', str(end - start + 1))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('ETag', '"pure-range-fixture-v1"')
            if match:
                self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.end_headers()
            block = bytes(range(256)) * 256
            try:
                for position in range(start, end + 1, len(block)):
                    count = min(len(block), end - position + 1)
                    offset = position % 256
                    self.wfile.write((block + block)[offset:offset + count])
                    self.wfile.flush()
                    time.sleep(.5 if slow else .03)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
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
