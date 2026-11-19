package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * scripts/do-build/build-remote.sh on a kept droplet (finding R7-8): a changed patch is applied to the
 * pristine client, not stacked or skipped; an unchanged one leaves the tree alone; one that fails to
 * apply stops the build with no library. Real git, fake cargo.
 */
class R7_8PatchReapplyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sh(dir: File, cmd: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val p = ProcessBuilder("bash", "-c", cmd).directory(dir).redirectErrorStream(true)
        p.environment().apply { putAll(env); put("GIT_CONFIG_GLOBAL", "/dev/null") }
        val proc = p.start()
        val out = proc.inputStream.readBytes().decodeToString()
        return proc.waitFor() to out
    }
    private fun ok(dir: File, cmd: String) = sh(dir, cmd).also { assertEquals(it.second, 0, it.first) }

    @Test
    fun aChangedPatchReplacesTheOldOne() {
        val src = tmp.newFolder("src"); val up = tmp.newFolder("upload"); val out = tmp.newFolder("out")
        val home = tmp.newFolder("home"); val bin = tmp.newFolder("bin")
        File(home, ".cargo").mkdirs(); File(home, ".cargo/env").writeText("")
        // cargo ndk ... -o OUT build: writes the library; cargo test: passes
        File(bin, "cargo").apply {
            writeText("#!/bin/bash\nif [ \"\$1\" = ndk ]; then mkdir -p \"\$OUT/x86_64\"; echo lib > \"\$OUT/x86_64/libgnosisvpn.so\"; fi\n")
            setExecutable(true)
        }
        val git = "git -c user.name=t -c user.email=t@t"
        ok(src, "$git init -q . && printf 'target/\\n' > .gitignore && echo base > a.txt && git add -A && $git commit -qm base")
        // two versions of the patch, each made against the pristine tree
        ok(src, "echo v1 > a.txt && echo n > new1.txt && git add -N new1.txt && git diff > ../p1 && git reset -q && git checkout -q -- . && rm new1.txt")
        ok(src, "echo v2 > a.txt && echo n > new2.txt && git add -N new2.txt && git diff > ../p2 && git reset -q && git checkout -q -- . && rm new2.txt")
        File(up, "gnosis_vpn-android").mkdirs(); File(up, "gnosis_vpn-android/Cargo.toml").writeText("crate")
        val env = mapOf("HOME" to home.path, "PATH" to "${bin.path}:${System.getenv("PATH")}", "SRC" to src.path, "UP" to up.path,
            "OUT" to out.path, "ABIS" to "x86_64", "TESTS" to "0")
        val script = File("../scripts/do-build/build-remote.sh").absolutePath
        val lib = File(out, "x86_64/libgnosisvpn.so")
        fun build(patch: String): Pair<Int, String> { File(tmp.root, patch).copyTo(File(up, "0001-android-target.patch"), overwrite = true); return sh(src, "bash '$script'", env) }

        // first build (run.sh): patch applied, crate copied
        assertEquals(0, build("p1").first)
        assertEquals("v1\n", File(src, "a.txt").readText()); assertTrue(File(src, "new1.txt").exists())
        assertTrue(File(src, "gnosis_vpn-android/Cargo.toml").exists()); assertTrue(lib.exists())
        File(src, "target").mkdirs(); File(src, "target/cache").writeText("built")

        // unchanged patch: nothing re-applied
        val (e2, o2) = build("p1")
        assertEquals(o2, 0, e2); assertFalse(o2, o2.contains("patch changed"))
        assertEquals("v1\n", File(src, "a.txt").readText())

        // changed patch: pristine client + the new patch; crate and build cache kept
        val (e3, o3) = build("p2")
        assertEquals(o3, 0, e3); assertTrue(o3, o3.contains("STEP patch changed"))
        assertEquals("v2\n", File(src, "a.txt").readText())
        assertFalse(File(src, "new1.txt").exists()); assertTrue(File(src, "new2.txt").exists())
        assertTrue(File(src, "gnosis_vpn-android/Cargo.toml").exists()); assertTrue(File(src, "target/cache").exists())

        // a patch that does not apply: the build stops and no library is left to copy
        File(tmp.root, "bad").writeText("--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-nothere\n+x\n")
        val (e4, o4) = build("bad")
        assertNotEquals(o4, 0, e4); assertFalse(lib.exists())
    }

    @Test
    fun rebuildUploadsThePatch() {
        val root = tmp.newFolder("repo"); File(root, "scripts/do-build").mkdirs()
        File("../scripts/do-build/rebuild.sh").copyTo(File(root, "scripts/do-build/rebuild.sh"))
        val home = tmp.newFolder("home2")
        File(home, ".local/state/gvpndroid-do").apply { mkdirs() }.resolve("current").writeText("ip=192.0.2.1\nknown_hosts=/dev/null\n")
        val bin = tmp.newFolder("bin2"); val log = File(tmp.root, "scp.log")
        File(bin, "ssh").writeText("#!/bin/bash\nexit 0\n")
        File(bin, "scp").writeText("#!/bin/bash\nd=\"\${@: -1}\"\ncase \"\$d\" in root@*) echo \"\$*\" >> '${log.path}' ;; *) echo new > \"\$d\" ;; esac\n")
        bin.listFiles()!!.forEach { it.setExecutable(true) }
        val (exit, out) = sh(root, "bash scripts/do-build/rebuild.sh", mapOf("HOME" to home.path, "PATH" to "${bin.path}:${System.getenv("PATH")}", "ABIS" to "x86_64"))
        assertEquals(out, 0, exit)
        assertTrue(log.readText(), log.readLines().any { it.contains("rust/patches/0001-android-target.patch") && it.endsWith(" root@192.0.2.1:/root/upload/") })
    }
}
