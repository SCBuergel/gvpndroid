#!/usr/bin/env python3
"""Create, inspect and delete one DigitalOcean build droplet. Python 3 stdlib only.

  droplet.py create NAME SIZE REGION PUBKEY_FILE   -> prints the droplet id, waits for an IPv4
  droplet.py ip ID
  droplet.py delete ID     idempotent: a droplet already gone counts as success; verifies 404
  droplet.py list          droplets on the account

The API token is read from $DO_TOKEN_FILE (default ~/do-api-token) and never printed.
The SSH key goes in through cloud-init, because the token may not manage account SSH keys.
"""
import http.client, json, os, sys, time, urllib.error, urllib.request

API = "https://api.digitalocean.com/v2"
DELETE_TRIES = 4
TOKEN = open(os.path.expanduser(os.environ.get("DO_TOKEN_FILE", "~/do-api-token"))).read().strip()


def call(method, path, body=None, timeout=60):
    req = urllib.request.Request(API + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            t = r.read().decode()
            return r.status, json.loads(t) if t else {}
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try:
            return e.code, json.loads(t)
        except ValueError:
            return e.code, {"raw": t[:300]}
    # Timeout, reset, DNS, a reply cut short or garbled: status 0, never a traceback.
    except (urllib.error.URLError, OSError, http.client.HTTPException, ValueError) as e:
        return 0, {"error": str(e)}


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


def find_by_name(name, tries=6):
    """The id of the droplet named exactly NAME (this run's unique name), polled briefly since a
    droplet created by a POST whose reply was lost may show up late; None if there is none.
    Exits loudly if the lookup itself never completes."""
    answered = False
    for i in range(tries):
        st, js = call("GET", "/droplets?tag_name=gvpndroid-build&per_page=200")
        if st == 200:
            answered = True
            ids = [d["id"] for d in js.get("droplets", []) if d.get("name") == name]
            if ids:
                return ids[0]
        if i < tries - 1:
            time.sleep(10)
    if not answered:
        sys.exit(f"!!! could not check whether droplet {name} exists: check https://cloud.digitalocean.com/droplets !!!")
    return None


def create(name, size, region, pubkey_file):
    pub = open(pubkey_file).read().strip()
    st, js = call("POST", "/droplets", {"name": name, "region": region, "size": size,
                                        "image": "ubuntu-24-04-x64", "user_data": user_data(pub),
                                        "tags": ["gvpndroid-build"]})
    did = js.get("droplet", {}).get("id") if st == 202 else None
    if did is None:
        # The reply may be lost after DigitalOcean created it: never POST again, look it up by name.
        print(f"create: no droplet id ({st} {js}); looking for {name}", file=sys.stderr)
        did = find_by_name(name)
        if did is None:
            sys.exit(f"create failed: {st} {js}")
    print(did, flush=True)
    for _ in range(90):
        st, js = call("GET", f"/droplets/{did}")
        if st == 200 and js["droplet"]["status"] == "active" and ipv4(js["droplet"]):
            print(ipv4(js["droplet"]), js["droplet"]["size"]["price_hourly"], flush=True)
            return
        time.sleep(5)
    sys.exit(f"droplet {did} did not become active")


def delete(did):
    # DELETE is idempotent (404 = already gone): a timeout, reset, 429 or 5xx is tried again; any other
    # failure ends it at once. Short calls (20 s) and 90 s of verification keep one run within the
    # deadline helper's 120 s per attempt in the usual case.
    for i in range(DELETE_TRIES):
        st, js = call("DELETE", f"/droplets/{did}", timeout=20)
        if st in (204, 404) or not (st == 0 or st == 429 or st >= 500):
            break
        if i < DELETE_TRIES - 1:
            print(f"delete {did}: {st}, trying again", file=sys.stderr)
            time.sleep(5)
    if st not in (204, 404):
        sys.exit(f"delete {did} failed: {st} {js}")
    for _ in range(18):
        st, _ = call("GET", f"/droplets/{did}", timeout=20)
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
