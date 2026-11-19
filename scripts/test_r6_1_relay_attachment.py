#!/usr/bin/env python3
"""relay-read.py fetches only ntfy.sh/file/<id> links, follows no redirect and gives up on a
stream or oversize body. Run: python3 scripts/test_r6_1_relay_attachment.py"""
import http.server, importlib.util, io, os, threading, time, unittest, urllib.error
from contextlib import redirect_stdout

spec = importlib.util.spec_from_file_location("relay_read", os.path.join(os.path.dirname(__file__), "relay-read.py"))
rr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rr)


class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/redirect":
            self.send_response(302); self.send_header("Location", "/ok"); self.end_headers()
            return
        self.send_response(200); self.end_headers()
        if self.path == "/ok":
            self.wfile.write(b"gvpndroid-log-v1 AAAA")
        elif self.path == "/big":
            self.wfile.write(b"x" * (rr.MAX_ATT + 1))
        elif self.path == "/stream":  # like ntfy's /<topic>/json: keepalives, never EOF
            try:
                for _ in range(100):
                    self.wfile.write(b'{"event":"keepalive"}\n'); self.wfile.flush(); time.sleep(0.2)
            except OSError:
                pass


class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        cls.srv.daemon_threads = True
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.srv.server_port}"

    def test_only_ntfy_files(self):
        for ok in ["https://ntfy.sh/file/AbC123xyz.sealed", "https://ntfy.sh/file/AbC123xyz"]:
            self.assertTrue(rr.ntfy_file(ok), ok)
        for bad in ["https://ntfy.sh/file/../topic/json", "https://ntfy.sh/file/./x", "https://ntfy.sh/file/%2e%2e",
                    "https://ntfy.sh/file/a/b", "https://ntfy.sh/file/a?x=1", "https://ntfy.sh/file/a#f",
                    "http://ntfy.sh/file/a", "https://ntfy.sh:8443/file/a", "https://u@ntfy.sh/file/a",
                    "https://ntfy.sh.evil.com/file/a", "https://evil.com/file/a", "https://[x/file/a", ""]:
            self.assertFalse(rr.ntfy_file(bad), bad)

    def test_fetch(self):
        self.assertEqual(rr.fetch(self.base + "/ok"), b"gvpndroid-log-v1 AAAA")
        with self.assertRaises(urllib.error.HTTPError):
            rr.fetch(self.base + "/redirect")
        with self.assertRaises(ValueError):
            rr.fetch(self.base + "/big")
        t = time.monotonic()
        with self.assertRaises(TimeoutError):
            rr.fetch(self.base + "/stream", secs=1)
        self.assertLess(time.monotonic() - t, 5)

    def test_show_rejects_without_fetching(self):
        f, rr.fetch = rr.fetch, lambda *a, **k: self.fail("fetched")
        self.addCleanup(setattr, rr, "fetch", f)
        out = io.StringIO()
        with redirect_stdout(out):
            rr.show({"event": "message", "time": 0, "attachment": {"url": "https://ntfy.sh/file/../t/json\x1b[2J"}}, None)
        self.assertIn("not fetched: https://ntfy.sh/file/../t/json?[2J", out.getvalue())


if __name__ == "__main__":
    unittest.main()
