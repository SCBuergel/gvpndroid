package org.gnosisvpn.stub

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner decision (2026-11-19): until further notice every release stays in the 0.0.x range.
 * versionCode keeps rising (it only has to grow so an update installs over the previous build).
 */
class VersionPolicyTest {
    @Test fun versionNameStaysIn00x() {
        assertTrue("versionName ${BuildConfig.VERSION_NAME} must be 0.0.x", Regex("""0\.0\.\d+""").matches(BuildConfig.VERSION_NAME))
    }
}
