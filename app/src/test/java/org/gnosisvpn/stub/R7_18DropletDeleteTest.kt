package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** scripts/do-build/droplet.py delete with a fake API: a transient DELETE failure is retried, others are not (finding R7-18). */
class R7_18DropletDeleteTest {
    @get:Rule val tmp = TemporaryFolder()

    /** delete("42") with call() answering DELETEs from [replies] in turn (then 204) and GETs with 404; exit, stdout, stderr, DELETEs sent. */
    private data class Run(val code: Int, val out: String, val err: String, val sent: Int)
    private fun delete(vararg replies: Int): Run {
        File(tmp.root, "token").writeText("t")
        val py = """
import sys, time
sys.path.insert(0, sys.argv[1])
import droplet
time.sleep = lambda s: None
replies = [${replies.joinToString()}]
sent = []
def call(method, path, body=None, timeout=60):
    assert timeout <= 20, timeout
    if method == "DELETE":
        sent.append(path)
        st = replies.pop(0) if replies else 204
        return st, ({"error": "timed out"} if st == 0 else {})
    return 404, {}
droplet.call = call
try:
    droplet.delete("42")
finally:
    print("sent", len(sent), file=sys.stderr)
"""
        val p = ProcessBuilder("python3", "-I", "-c", py, File("../scripts/do-build").absolutePath)
        p.environment()["DO_TOKEN_FILE"] = File(tmp.root, "token").path
        p.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val sent = Regex("sent (\\d+)").find(err)!!.groupValues[1].toInt()
        return Run(proc.waitFor(), out, err, sent)
    }

    @Test fun aServiceErrorIsRetriedAndTheDropletVerifiedGone() {
        val (code, out, err, sent) = delete(503)
        assertEquals(err, 0, code); assertTrue(out, out.contains("droplet 42 verified gone")); assertEquals(2, sent)
    }

    @Test fun aDroppedConnectionAndARateLimitAreRetried() {
        val (code, out, err, sent) = delete(0, 429)
        assertEquals(err, 0, code); assertTrue(out, out.contains("verified gone")); assertEquals(3, sent)
    }

    @Test fun anAuthErrorFailsAtOnce() {
        val (code, out, err, sent) = delete(403)
        assertNotEquals(0, code); assertEquals("", out); assertTrue(err, err.contains("delete 42 failed: 403")); assertEquals(1, sent)
    }

    @Test fun aServiceThatStaysDownFailsAfterTheLastTry() {
        val (code, _, err, sent) = delete(503, 503, 503, 503, 503)
        assertNotEquals(0, code); assertTrue(err, err.contains("delete 42 failed: 503")); assertEquals(4, sent)
    }
}
