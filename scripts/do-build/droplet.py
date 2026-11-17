#!/usr/bin/env python3
"""Create, inspect and delete one DigitalOcean build droplet. Python 3 stdlib only.

  droplet.py create NAME SIZE REGION PUBKEY_FILE   -> prints the droplet id, waits for an IPv4
  droplet.py ip ID
  droplet.py delete ID     idempotent: a droplet already gone counts as success; verifies 404
  droplet.py list          droplets on the account

The API token is read from $DO_TOKEN_FILE (default ~/do-api-token) and never printed.
The SSH key goes in through cloud-init, because the token may not manage account SSH keys.
"""
import json, os, sys, time, urllib.error, urllib.request

API = "https://api.digitalocean.com/v2"
TOKEN = open(os.path.expanduser(os.environ.get("DO_TOKEN_FILE", "~/do-api-token"))).read().strip()


def call(method, path, body=None):
    req = urllib.request.Request(API + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read().decode()
            return r.status, json.loads(t) if t else {}
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try:
            return e.code, json.loads(t)
        except ValueError:
            return e.code, {"raw": t[:300]}


def user_data(pubkey):
    # Without an account SSH key DigitalOcean expires the root password, which blocks key logins.
    return f"""#cloud-config
chpasswd: {{expire: false}}
bootcmd:
  - [chage, -M, "99999", -d, "{time.strftime('%Y-%m-%d')}", root]
users:
  - name: root
    ssh_authorized_keys: ["{pubkey}"]
runcmd:
  - [chage, -M, "99999", -d, "{time.strftime('%Y-%m-%d')}", root]
"""


def ipv4(d):
    return next((n["ip_address"] for n in d["networks"]["v4"] if n["type"] == "public"), None)


def create(name, size, region, pubkey_file):
    pub = open(pubkey_file).read().strip()
    st, js = call("POST", "/droplets", {"name": name, "region": region, "size": size,
                                        "image": "ubuntu-24-04-x64", "user_data": user_data(pub),
                                        "tags": ["gvpndroid-build"]})
    if st != 202:
        sys.exit(f"create failed: {st} {js}")
    did = js["droplet"]["id"]
    print(did, flush=True)
    for _ in range(90):
        st, js = call("GET", f"/droplets/{did}")
        if st == 200 and js["droplet"]["status"] == "active" and ipv4(js["droplet"]):
            print(ipv4(js["droplet"]), js["droplet"]["size"]["price_hourly"], flush=True)
            return
        time.sleep(5)
    sys.exit(f"droplet {did} did not become active")


def delete(did):
    st, js = call("DELETE", f"/droplets/{did}")
    if st not in (204, 404):
        sys.exit(f"delete {did} failed: {st} {js}")
    for _ in range(24):
        st, _ = call("GET", f"/droplets/{did}")
        if st == 404:
            print(f"droplet {did} verified gone")
            return
        time.sleep(5)
    sys.exit(f"!!! droplet {did} NOT verified gone: check https://cloud.digitalocean.com/droplets !!!")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "create" and len(sys.argv) == 6:
        create(*sys.argv[2:6])
    elif cmd == "ip" and len(sys.argv) == 3:
        st, js = call("GET", f"/droplets/{sys.argv[2]}")
        print(ipv4(js["droplet"]) if st == 200 else f"status {st}")
    elif cmd == "delete" and len(sys.argv) == 3:
        delete(sys.argv[2])
    elif cmd == "list":
        st, js = call("GET", "/droplets?per_page=200")
        for d in js.get("droplets", []):
            print(d["id"], d["name"], d["size_slug"], d["status"], ipv4(d))
    else:
        sys.exit(__doc__)
