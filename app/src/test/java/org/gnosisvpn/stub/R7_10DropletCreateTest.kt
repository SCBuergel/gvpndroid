package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** scripts/do-build/droplet.py create with a fake API: a lost create reply still yields the droplet's id. */
class R7_10DropletCreateTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Runs create("gvpndroid-app-1", ...) with call() replaced by [fakeCall] (Python); exit code and output. */
    private fun create(fakeCall: String): Triple<Int, String, String> {
        File(tmp.root, "token").writeText("t")
        File(tmp.root, "key.pub").writeText("ssh-ed25519 AAAA test")
        val py = """
import sys, time
sys.path.insert(0, sys.argv[1])
import droplet
time.sleep = lambda s: None
posts = []
$fakeCall
droplet.call = call
droplet.create("gvpndroid-app-1", "s-1vcpu-1gb", "fra1", sys.argv[2])
assert len(posts) == 1, posts
"""
        val p = ProcessBuilder("python3", "-I", "-c", py, File("../scripts/do-build").absolutePath, File(tmp.root, "key.pub").path)
        p.environment()["DO_TOKEN_FILE"] = File(tmp.root, "token").path
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        return Triple(proc.waitFor(), out, err)
    }

    private val active = """{"droplet": {"status": "active", "size": {"price_hourly": 0.01}, "networks": {"v4": [{"type": "public", "ip_address": "192.0.2.7"}]}}}"""

    @Test fun aTimedOutCreateFindsTheDropletByItsExactName() {
        val (code, out, err) = create("""
def call(method, path, body=None):
    if method == "POST":
        posts.append(path); return 0, {"error": "timed out"}
    if path.startswith("/droplets?tag_name="):
        return 200, {"droplets": [{"id": 41, "name": "gvpndroid-app-10"}, {"id": 42, "name": "gvpndroid-app-1"}]}
    if path == "/droplets/42":
        return 200, $active
    return 404, {}
""")
        assertEquals(err, 0, code)
        assertEquals(listOf("42", "192.0.2.7 0.01"), out.trim().lines())
    }

    @Test fun noDropletUnderTheNameFailsWithoutAnId() {
        val (code, out, _) = create("""
def call(method, path, body=None):
    if method == "POST":
        posts.append(path); return 502, {"raw": "bad gateway"}
    return 200, {"droplets": [{"id": 41, "name": "gvpndroid-app-10"}]}
""")
        assertNotEquals(0, code)
        assertEquals("", out)
    }

    @Test fun aLookupThatNeverAnswersSaysWhereToLook() {
        val (code, out, err) = create("""
def call(method, path, body=None):
    if method == "POST":
        posts.append(path)
    return 0, {"error": "connection reset"}
""")
        assertNotEquals(0, code)
        assertEquals("", out)
        assertTrue(err, err.contains("gvpndroid-app-1") && err.contains("cloud.digitalocean.com"))
    }

    @Test fun aNetworkErrorIsAStatusNotATraceback() {
        val py = """
import sys, http.client, urllib.request
sys.path.insert(0, sys.argv[1])
import droplet
for e in [TimeoutError("timed out"), ConnectionResetError(), http.client.IncompleteRead(b"")]:
    def boom(*a, **k): raise e
    urllib.request.urlopen = boom
    print(droplet.call("GET", "/droplets/1")[0])
"""
        File(tmp.root, "token").writeText("t")
        val p = ProcessBuilder("python3", "-I", "-c", py, File("../scripts/do-build").absolutePath).redirectErrorStream(true)
        p.environment()["DO_TOKEN_FILE"] = File(tmp.root, "token").path
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(out, 0, proc.waitFor())
        assertEquals(listOf("0", "0", "0"), out.trim().lines())
    }
}
