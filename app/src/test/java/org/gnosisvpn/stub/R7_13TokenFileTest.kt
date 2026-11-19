package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * run.sh and app.sh arm the deletion deadline with the token file the run uses, as an absolute path
 * (the deadline runs outside this environment), and down.sh uses the one run.sh recorded (finding R7-13).
 * Fake python3: droplet.py logs its DO_TOKEN_FILE, the deadline logs its argv and refuses to arm,
 * which ends the script through its teardown.
 */
class R7_13TokenFileTest {
    @get:Rule val tmp = TemporaryFolder()
    private val secret = "SECRET-TOKEN-VALUE"

    private inner class Rig {
        val repo = tmp.newFolder("repo"); val home = tmp.newFolder("home"); val bin = tmp.newFolder("bin")
        val log = File(tmp.root, "calls.log"); val st = File(home, ".local/state/gvpndroid-do")
        val tok = File(home, "tok dir/do-team").apply { parentFile.mkdirs(); writeText(secret) }
        init {
            File(repo, "scripts/do-build").mkdirs()
            for (s in listOf("run.sh", "app.sh", "lib.sh", "down.sh")) File("../scripts/do-build/$s").copyTo(File(repo, "scripts/do-build/$s"))
            st.mkdirs(); File(st, "id_ed25519").writeText("k"); File(st, "id_ed25519.pub").writeText("k")
            File(bin, "python3").apply {
                writeText("""
                    #!/bin/bash
                    [ "${'$'}1" = -I ] && shift
                    case "${'$'}1" in
                    */droplet.py) echo "droplet ${'$'}2 DO_TOKEN_FILE=${'$'}DO_TOKEN_FILE" >> "${'$'}LOG"; [ "${'$'}2" = create ] && printf '4242\n1.2.3.4 0.5\n'; exit 0 ;;
                    *) shift; printf 'deadline' >> "${'$'}LOG"; printf ' [%s]' "${'$'}@" >> "${'$'}LOG"; echo >> "${'$'}LOG"; [ "${'$'}1" = start ] && exit 1; exit 0 ;;
                    esac
                """.trimIndent() + "\n")
                setExecutable(true)
            }
        }
        fun run(script: String, tokenEnv: String?): String {
            val p = ProcessBuilder("bash", "scripts/do-build/$script").directory(repo).redirectErrorStream(true)
            p.environment().apply {
                put("HOME", home.path); put("PATH", "${bin.path}:${get("PATH")}"); put("LOG", log.path); put("DEADLINE", "/fake/deadline")
                if (tokenEnv == null) remove("DO_TOKEN_FILE") else put("DO_TOKEN_FILE", tokenEnv)
            }
            val proc = p.start(); val out = proc.inputStream.readBytes().decodeToString(); proc.waitFor()
            return out
        }
        fun noSecretAnywhere() {
            assertFalse(log.readText().contains(secret))
            st.walk().filter { it.isFile }.forEach { assertFalse(it.path, it.readText().contains(secret)) }
        }
    }

    private fun assertArmedWith(r: Rig, path: String, out: String) {
        val calls = r.log.readLines()
        val start = calls.single { it.startsWith("deadline [start]") }
        assertTrue(out + calls, start.endsWith(" [--] [env] [DO_TOKEN_FILE=$path] [python3] [-I] [${r.repo.canonicalPath}/scripts/do-build/droplet.py] [delete] [4242]"))
        assertEquals(out + calls, listOf("droplet create DO_TOKEN_FILE=$path", "droplet delete DO_TOKEN_FILE=$path"), calls.filter { it.startsWith("droplet") })
    }

    @Test fun runShArmsTheDeadlineWithTheRunsTokenFile() {
        val r = Rig(); val out = r.run("run.sh", "~/tok dir/do-team")
        assertArmedWith(r, r.tok.canonicalPath, out); r.noSecretAnywhere()
    }

    @Test fun appShArmsTheDeadlineWithTheRunsTokenFile() {
        val r = Rig(); val out = r.run("app.sh", r.tok.path)
        assertArmedWith(r, r.tok.canonicalPath, out); r.noSecretAnywhere()
    }

    @Test fun theDefaultTokenFileIsPassedAbsolute() {
        val r = Rig(); File(r.home, "do-api-token").writeText(secret); val out = r.run("run.sh", null)
        assertArmedWith(r, File(r.home, "do-api-token").canonicalPath, out)
    }

    @Test fun downShUsesTheRecordedTokenFile() {
        val r = Rig()
        // the record as run.sh's KEEP teardown writes it (printf %q keeps the space)
        val rec = ProcessBuilder("bash", "-c", "printf 'id=%s\\nname=%s\\ntoken_file=%q\\n' 4242 n1 \"$1\"", "_", r.tok.canonicalPath).start()
        File(r.st, "current").writeBytes(rec.inputStream.readBytes()); rec.waitFor()
        val out = r.run("down.sh", null)
        assertEquals(out, listOf("droplet delete DO_TOKEN_FILE=${r.tok.canonicalPath}"), r.log.readLines().filter { it.startsWith("droplet") })
    }
}
