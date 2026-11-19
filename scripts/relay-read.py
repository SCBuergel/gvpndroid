#!/usr/bin/env python3
"""DEBUG ONLY (developer log relay of debug builds; remove with it before production).

Prints the logs that debug builds of the app sent to the developer's ntfy.sh topic, decrypted
with the developer's key. Reads ~/.config/gvpndroid-secrets/{ntfy-topic,log-x25519.key}
($GVPNDROID_SECRETS overrides the folder). ntfy.sh keeps messages for 12 hours and attachments (backlogs) for 3.

Usage: scripts/relay-read.py [SINCE]   SINCE: ntfy's since= (default 1h; e.g. 30m, 12h, all)
       scripts/relay-read.py -f        follow: print new parts as they arrive, reconnecting
"""
import base64, hashlib, http.client, json, os, re, sys, time, urllib.parse, urllib.request, zlib

S = os.environ.get("GVPNDROID_SECRETS", os.path.expanduser("~/.config/gvpndroid-secrets"))
TAG = b"gvpndroid-log-v1"
MAX_LOG = 20_000_000   # a log part never unpacks to more; anyone can post to the topic
MAX_ATT, ATT_SECS = 3_000_000, 60   # an attachment larger or slower than this is skipped
FOLLOW_SECS = 120   # -f read timeout: ntfy sends a keepalive about every 45 s, so silence means a dead stream


def gunzip(z):
    d = zlib.decompressobj(wbits=31)
    out = d.decompress(z, MAX_LOG)
    if d.unconsumed_tail:
        raise ValueError(f"unpacks to more than {MAX_LOG} bytes")
    return out


def safe(s):
    """Text from whoever posted to the topic, without terminal control sequences."""
    return re.sub(r"[\x00-\x08\x0b-\x1f\x7f-\x9f]", "?", s)


def unseal(body, priv):
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PublicKey
    from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
    blob = base64.b64decode(body.split(" ", 1)[1])
    e, ct = blob[:32], blob[32:]
    shared = priv.exchange(X25519PublicKey.from_public_bytes(e))
    key = hashlib.sha256(TAG + shared + e + priv.public_key().public_bytes_raw()).digest()
    return gunzip(ChaCha20Poly1305(key).decrypt(b"\0" * 12, ct, e)).decode("utf-8", "replace")


def ntfy_file(url):
    """Only ntfy's own uploads (https://ntfy.sh/file/<id>.<ext>): anyone can post any link here."""
    try:
        u = urllib.parse.urlsplit(url)
    except ValueError:
        return False
    return (u.scheme == "https" and u.netloc == "ntfy.sh" and not u.query and not u.fragment
            and re.fullmatch(r"/file/[A-Za-z0-9]+(\.[A-Za-z0-9]+)?", u.path) is not None)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None  # urllib then raises HTTPError for the 3xx


def fetch(url, secs=ATT_SECS):
    """The body, read within secs in all (a stream never ends); ValueError if over MAX_ATT bytes."""
    end, out = time.monotonic() + secs, b""
    with urllib.request.build_opener(_NoRedirect).open(url, timeout=20) as r:
        while chunk := r.read1(65536):
            out += chunk
            if len(out) > MAX_ATT:
                raise ValueError(f"over {MAX_ATT} bytes")
            if time.monotonic() > end:
                raise TimeoutError(f"not done in {secs} s")
    return out


def show(m, priv):
    if m.get("event") != "message":
        return
    t = time.strftime("%H:%M:%S", time.localtime(m["time"]))
    body = m.get("message", "")
    att = m.get("attachment") or {}
    note = None
    if att.get("url"):  # a backlog sent as one attachment (ntfy keeps it 3 hours)
        url = str(att["url"])
        if not ntfy_file(url):
            note = f"(attachment link not an ntfy.sh file, not fetched: {url[:60]})"
        else:
            try:
                body = fetch(url).decode("ascii", "replace").strip()
            except Exception as ex:
                note = f"(attachment not readable: {type(ex).__name__}; it may have expired)"
    try:
        text = note or (unseal(body, priv) if body.startswith(TAG.decode() + " ") else "(not a sealed log part)")
    except Exception as ex:  # a part that is not ours or is damaged: say so, keep going
        text = f"(cannot open: {type(ex).__name__})"
    print(f"=== {t} {safe(m.get('title', ''))}\n{safe(text)}", flush=True)


def follow(topic, since, priv, base="https://ntfy.sh", sleep=time.sleep):
    """-f: on a dead, failed or closed stream, says so and reconnects after the last message seen."""
    delay = 2
    while True:
        try:
            with urllib.request.urlopen(f"{base}/{topic}/json?since={urllib.parse.quote(since, safe='')}", timeout=FOLLOW_SECS) as r:
                for line in r:
                    m = json.loads(line)
                    if m.get("event") in ("message", "keepalive"):
                        delay = 2  # the stream works again
                    show(m, priv)
                    if m.get("event") == "message" and m.get("id"):
                        since = str(m["id"])
            why = "stream closed"
        except (OSError, ValueError, http.client.HTTPException) as ex:  # URLError, HTTPError, timeouts: OSError; a cut chunk: IncompleteRead
            why = type(ex).__name__
        print(f"relay-read: {why}; reconnecting in {delay} s", file=sys.stderr, flush=True)
        sleep(delay)
        delay = min(delay * 2, 60)


def main():
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    topic = open(f"{S}/ntfy-topic").read().strip()
    priv = X25519PrivateKey.from_private_bytes(base64.b64decode(open(f"{S}/log-x25519.key").read()))
    following = "-f" in sys.argv[1:]
    args = [a for a in sys.argv[1:] if a != "-f"]
    since = args[0] if args else ("10m" if following else "1h")
    if following:
        follow(topic, since, priv)
        return
    with urllib.request.urlopen(f"https://ntfy.sh/{topic}/json?since={since}&poll=1", timeout=30) as r:
        for line in r:
            show(json.loads(line), priv)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
