#!/usr/bin/env python3
"""relay-read.py -f reconnects after a closed, silent or failing stream, from the last message
seen, so no part is lost or shown twice. Run: python3 scripts/test_r6_2_relay_follow.py"""
import http.server, importlib.util, io, os, threading, time, unittest, urllib.parse
from contextlib import redirect_stderr

spec = importlib.util.spec_from_file_location("relay_read", os.path.join(os.path.dirname(__file__), "relay-read.py"))
rr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rr)

seen = []  # the since= of each connection


def msg(i):
    return f'{{"event":"message","id":"{i}","time":0}}\n'.encode()


class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        since = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)["since"][0]
        seen.append(since)
        n = len(seen)
        if n == 3:  # the server fails
            self.send_response(503); self.end_headers()
            return
        self.send_response(200); self.end_headers()
        if n == 1:  # one part, a keepalive, then a clean close (a redeploy)
            self.wfile.write(msg("a1") + b'{"event":"keepalive","id":"k1","time":0}\n')
        elif n == 2:  # one part, then silence: a dead connection
            self.wfile.write(msg("a2")); self.wfile.flush(); time.sleep(3)
        else:
            self.wfile.write(msg("a3"))


class T(unittest.TestCase):
    def test_reconnects_from_the_last_message(self):
        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        srv.daemon_threads = True
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        shown, delays = [], []
        rr.show = lambda m, priv: shown.append(m["id"])
        rr.FOLLOW_SECS = 1

        def sleep(d):
            delays.append(d)
            if len(delays) == 4:
                raise KeyboardInterrupt

        err = io.StringIO()
        with redirect_stderr(err), self.assertRaises(KeyboardInterrupt):
            rr.follow("topic", "10m", None, base=f"http://127.0.0.1:{srv.server_port}", sleep=sleep)
        srv.shutdown()
        self.assertEqual(seen, ["10m", "a1", "a2", "a2"])  # a keepalive id is not a resume point
        self.assertEqual(shown, ["a1", "k1", "a2", "a3"])  # show() itself skips non-messages
        notes = err.getvalue().splitlines()
        self.assertEqual(len(notes), 4)
        self.assertIn("stream closed", notes[0])
        self.assertIn("TimeoutError", notes[1])
        self.assertIn("HTTPError", notes[2])
        self.assertTrue(all("topic" not in n for n in notes))
        self.assertEqual(delays, [2, 2, 4, 2])  # backs off while failing, resets once data flows


if __name__ == "__main__":
    unittest.main()
