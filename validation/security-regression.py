#!/usr/bin/env python3
"""Verify certificate-warning UI on a signed APK using a local, untrusted TLS fixture."""
import argparse
import http.server
import importlib.util
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ux", ROOT / "emulator-ux.py")
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise SystemExit("This regression is for local emulators only")
    ux.ADB = ["adb", "-s", args.serial]
    sdk = ux.adb("shell", "getprop", "ro.build.version.sdk")
    output = ROOT / "results"
    output.mkdir(exist_ok=True)
    stem = output / ("api" + sdk + "-security")
    apk = ux.adb("shell", "pm", "path", ux.PACKAGE).partition(":")[2].strip()
    result = {"serial": args.serial, "sdk": sdk,
              "apkSha256": ux.adb("shell", "sha256sum", apk).split()[0],
              "checks": [], "error": None}

    def record(message):
        result["checks"].append(message)
        print("PASS:", message, flush=True)

    def snapshot(name):
        target = Path(str(stem) + "-" + name)
        target.with_suffix(".xml").write_text(ux.nodes()[1], encoding="utf-8")
        target.with_suffix(".png").write_bytes(subprocess.check_output(ux.ADB + ["exec-out", "screencap", "-p"]))

    def loaded():
        # A provider may prompt again after a reload. Both paths must retain the warning.
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            root, _ = ux.nodes()
            if ux.match(root, "Continue anyway (unsafe)") is not None:
                ux.tap("Continue anyway (unsafe)")
            if ux.match(root, "Pure TLS fixture loaded") is not None:
                return
            time.sleep(.2)
        raise AssertionError("Local TLS fixture did not load after explicit override")

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body = b"<!doctype html><meta name='viewport' content='width=device-width'><title>Pure TLS fixture</title><h1>Pure TLS fixture loaded</h1><p>Local test certificate only.</p>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    try:
        with tempfile.TemporaryDirectory(prefix="pure-browser-tls-fixture-") as directory:
            cert, key = Path(directory) / "cert.pem", Path(directory) / "key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                            "-keyout", str(key), "-out", str(cert), "-days", "1", "-subj", "/CN=localhost"],
                           check=True, capture_output=True)
            server = http.server.ThreadingHTTPServer(("127.0.0.1", 8877), Handler)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(str(cert), str(key))
            server.socket = context.wrap_socket(server.socket, server_side=True)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                ux.adb("reverse", "tcp:8877", "tcp:8877")
                ux.adb("shell", "am", "force-stop", ux.PACKAGE)
                ux.launch("https://127.0.0.1:8877/first")
                ux.tap("Continue anyway (unsafe)", timeout=20)
                loaded()
                ux.expect("SSL certificate verification failed")
                snapshot("overridden")
                record("explicit TLS override keeps a certificate-warning indicator")
                ux.tap("SSL certificate verification failed")
                ux.expect("Dangerous connection")
                ux.expect("Secure connection", present=False)
                snapshot("details")
                ux.adb("shell", "input", "keyevent", "4")
                record("site information reports the certificate error instead of a secure connection")
                ux.tap("Refresh")
                loaded()
                ux.expect("SSL certificate verification failed")
                record("reload preserves the warning even when WebView caches the SSL override")
                ux.launch("about:blank")
                ux.launch("https://127.0.0.1:8877/second")
                loaded()
                ux.expect("SSL certificate verification failed")
                snapshot("revisited")
                record("returning to another path on the same origin preserves the warning")
            finally:
                server.shutdown()
                server.server_close()
                ux.adb("reverse", "--remove", "tcp:8877")
    except Exception as error:
        result["error"] = str(error)
        snapshot("failure")
        raise
    finally:
        stem.with_suffix(".json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
