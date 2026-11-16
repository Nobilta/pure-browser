#!/usr/bin/env python3
"""FTP and HLS fixture servers for the FTP/HLS download regression.

Two listeners, because the browser needs two protocols: a passive-mode read-only FTP server for
the single-stream transport, and an HTTP server whose playlist and segments drive the merge.
Every payload is generated from its own path, so the regression recomputes the same bytes instead
of keeping media in the repository.

    python3 validation/ftp-hls-server.py            # FTP 2121 (+2122 passive), HTTP 8878
    adb reverse tcp:2121 tcp:2121
    adb reverse tcp:2122 tcp:2122
    adb reverse tcp:8878 tcp:8878
"""
import argparse
import socket
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FIXTURE = ROOT / 'ftp-hls-fixture.html'

FTP_FILE = 'pure-ftp.bin'
SEGMENTS = {'one.ts': 64 * 1024, 'two.ts': 96 * 1024}
FTP_SIZE = 128 * 1024


def payload(name, size=None):
    """The bytes a fixture serves, derived from its name so both sides can predict them."""
    seed = zlib.crc32(name.encode()) & 0xFF
    length = size if size is not None else (SEGMENTS.get(name) or FTP_SIZE)
    return bytes((seed + 13 * index) % 256 for index in range(length))


def playlist(segments):
    lines = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-TARGETDURATION:4']
    lines += [f'#EXTINF:4.0,\n{name}' for name in segments]
    lines += ['#EXT-X-ENDLIST', '']
    return '\n'.join(lines)


class HttpHandler(BaseHTTPRequestHandler):
    ftp_port = 2121

    def log_message(self, *args):
        pass

    def reply(self, body, content_type, attachment=None):
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        if attachment:
            self.send_header('Content-Disposition', f'attachment; filename="{attachment}"')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split('?')[0]
        if path == '/ftp-hls-fixture.html':
            self.reply(
                FIXTURE.read_text(encoding='utf-8').replace(
                    '{{ftp}}', f'ftp://127.0.0.1:{self.ftp_port}/pub/{FTP_FILE}',
                ).encode('utf-8'),
                'text/html; charset=utf-8',
            )
        elif path == '/hls/playlist.m3u8':
            # An attachment forces the WebView to hand the response to the download flow instead
            # of trying to play a playlist it cannot render.
            self.reply(playlist(SEGMENTS).encode(), 'application/vnd.apple.mpegurl', 'playlist.m3u8')
        elif path == '/hls/master.m3u8':
            master = (
                '#EXTM3U\n'
                '#EXT-X-STREAM-INF:BANDWIDTH=400000\nplaylist.m3u8\n'
                '#EXT-X-STREAM-INF:BANDWIDTH=1600000\nplaylist.m3u8\n'
            )
            self.reply(master.encode(), 'application/vnd.apple.mpegurl', 'master.m3u8')
        elif path.startswith('/hls/') and path[5:] in SEGMENTS:
            self.reply(payload(path[5:]), 'video/mp2t')
        else:
            self.send_error(404)


class FtpSession(threading.Thread):
    """One passive-mode control connection; a real client opens exactly one per task.

    The passive port is fixed rather than ephemeral: the device reaches this host through
    `adb reverse`, which forwards only the ports that were reversed, so the data connection has
    to listen on one of them.
    """

    def __init__(self, control, files, passive_port):
        super().__init__(daemon=True)
        self.control = control
        self.files = files
        self.passive_port = passive_port
        self.passive = None
        self.offset = 0

    def reply(self, line):
        self.control.sendall((line + '\r\n').encode())

    def run(self):
        reader = self.control.makefile('rb')
        try:
            self.reply('220 pure ftp fixture')
            for raw in reader:
                line = raw.decode('utf-8', 'replace').strip()
                command, _, argument = line.partition(' ')
                command = command.upper()
                if command == 'USER':
                    self.reply('331 password required')
                elif command == 'PASS':
                    self.reply('230 logged in')
                elif command == 'TYPE':
                    self.reply('200 binary mode')
                elif command == 'SIZE':
                    size = self.files.get(argument)
                    self.reply(f'213 {size}' if size is not None else '550 no such file')
                elif command == 'REST':
                    self.offset = int(argument or 0)
                    self.reply(f'350 restarting at {self.offset}')
                elif command == 'PASV':
                    if self.passive is None:
                        self.passive = socket.socket()
                        self.passive.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                        self.passive.bind(('127.0.0.1', self.passive_port))
                        self.passive.listen(1)
                    port = self.passive_port
                    self.reply(f'227 Entering Passive Mode (127,0,0,1,{port // 256},{port % 256})')
                elif command == 'RETR':
                    if argument not in self.files or self.passive is None:
                        self.reply('550 no such file')
                        continue
                    self.reply('150 opening data connection')
                    self.passive.settimeout(5)
                    data, _ = self.passive.accept()
                    with data:
                        data.sendall(payload(argument.rsplit('/', 1)[-1]))
                    self.offset = 0
                    self.reply('226 transfer complete')
                elif command == 'QUIT':
                    self.reply('221 bye')
                    return
                else:
                    self.reply('502 not implemented')
        except (ConnectionError, socket.timeout):
            pass
        finally:
            self.control.close()


def serve_ftp(port, passive_port, stop):
    files = {'/pub/' + FTP_FILE: FTP_SIZE}
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('127.0.0.1', port))
    server.listen(4)
    server.settimeout(0.5)
    while not stop.is_set():
        try:
            control, _ = server.accept()
        except socket.timeout:
            continue
        FtpSession(control, files, passive_port).start()
    server.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ftp-port', type=int, default=2121)
    parser.add_argument('--passive-port', type=int, default=2122)
    parser.add_argument('--http-port', type=int, default=8878)
    args = parser.parse_args()

    HttpHandler.ftp_port = args.ftp_port
    stop = threading.Event()
    threading.Thread(
        target=serve_ftp, args=(args.ftp_port, args.passive_port, stop), daemon=True,
    ).start()
    http = ThreadingHTTPServer(('127.0.0.1', args.http_port), HttpHandler)
    print(f'FTP on 127.0.0.1:{args.ftp_port}, HTTP on 127.0.0.1:{args.http_port}', flush=True)
    try:
        http.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        time.sleep(0.2)


if __name__ == '__main__':
    main()
