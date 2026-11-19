#!/usr/bin/env python3
"""DEBUG ONLY (developer log relay of debug builds; remove with it before production).

Prints the logs that debug builds of the app sent to the developer's ntfy.sh topic, decrypted
with the developer's key. Reads ~/.config/gvpndroid-secrets/{ntfy-topic,log-x25519.key}
($GVPNDROID_SECRETS overrides the folder). ntfy.sh keeps messages for 12 hours and attachments (backlogs) for 3.

Usage: scripts/relay-read.py [SINCE]   SINCE: ntfy's since= (default 1h; e.g. 30m, 12h, all)
       scripts/relay-read.py -f        follow: print new parts as they arrive
"""
import base64, hashlib, json, os, re, sys, time, urllib.request, zlib

S = os.environ.get("GVPNDROID_SECRETS", os.path.expanduser("~/.config/gvpndroid-secrets"))
TAG = b"gvpndroid-log-v1"
MAX_LOG = 20_000_000   # a log part never unpacks to more; anyone can post to the topic


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


def show(m, priv):
    if m.get("event") != "message":
        return
    t = time.strftime("%H:%M:%S", time.localtime(m["time"]))
    body = m.get("message", "")
    att = m.get("attachment") or {}
    note = None
    if att.get("url"):  # a backlog sent as one attachment (ntfy keeps it 3 hours)
        url = att["url"]
        # The topic is in every debug APK, so anyone can post here: fetch only ntfy's own files.
        if not url.startswith("https://ntfy.sh/file/"):
            note = f"(attachment link not from ntfy.sh, not fetched: {url[:60]})"
        else:
            try:
                with urllib.request.urlopen(url, timeout=60) as r:
                    body = r.read(3_000_000).decode("ascii", "replace").strip()
            except Exception as ex:
                note = f"(attachment not readable: {type(ex).__name__}; it may have expired)"
    try:
        text = note or (unseal(body, priv) if body.startswith(TAG.decode() + " ") else "(not a sealed log part)")
    except Exception as ex:  # a part that is not ours or is damaged: say so, keep going
        text = f"(cannot open: {type(ex).__name__})"
    print(f"=== {t} {safe(m.get('title', ''))}\n{safe(text)}", flush=True)


def main():
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
    topic = open(f"{S}/ntfy-topic").read().strip()
    priv = X25519PrivateKey.from_private_bytes(base64.b64decode(open(f"{S}/log-x25519.key").read()))
    follow = "-f" in sys.argv[1:]
    args = [a for a in sys.argv[1:] if a != "-f"]
    since = args[0] if args else ("10m" if follow else "1h")
    url = f"https://ntfy.sh/{topic}/json?since={since}" + ("" if follow else "&poll=1")
    with urllib.request.urlopen(url, timeout=None if follow else 30) as r:
        for line in r:
            show(json.loads(line), priv)


if __name__ == "__main__":
    main()
