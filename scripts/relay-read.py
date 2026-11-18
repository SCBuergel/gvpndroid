#!/usr/bin/env python3
"""DEBUG ONLY (developer log relay of debug builds; remove with it before production).

Prints the logs that debug builds of the app sent to the developer's ntfy.sh topic, decrypted
with the developer's key. Reads ~/.config/gvpndroid-secrets/{ntfy-topic,log-x25519.key}
($GVPNDROID_SECRETS overrides the folder). ntfy.sh keeps messages for 12 hours.

Usage: scripts/relay-read.py [SINCE]   SINCE: ntfy's since= (default 1h; e.g. 30m, 12h, all)
       scripts/relay-read.py -f        follow: print new parts as they arrive
"""
import base64, gzip, hashlib, json, os, sys, time, urllib.request

S = os.environ.get("GVPNDROID_SECRETS", os.path.expanduser("~/.config/gvpndroid-secrets"))
TAG = b"gvpndroid-log-v1"


def unseal(body, priv):
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PublicKey
    from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
    blob = base64.b64decode(body.split(" ", 1)[1])
    e, ct = blob[:32], blob[32:]
    shared = priv.exchange(X25519PublicKey.from_public_bytes(e))
    key = hashlib.sha256(TAG + shared + e + priv.public_key().public_bytes_raw()).digest()
    return gzip.decompress(ChaCha20Poly1305(key).decrypt(b"\0" * 12, ct, e)).decode("utf-8", "replace")


def show(m, priv):
    if m.get("event") != "message":
        return
    t = time.strftime("%H:%M:%S", time.localtime(m["time"]))
    body = m.get("message", "")
    try:
        text = unseal(body, priv) if body.startswith(TAG.decode() + " ") else "(not a sealed log part)"
    except Exception as ex:  # a part that is not ours or is damaged: say so, keep going
        text = f"(cannot open: {type(ex).__name__})"
    print(f"=== {t} {m.get('title', '')}\n{text}", flush=True)


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
