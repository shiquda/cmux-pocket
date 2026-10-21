package app.cmuxpocket

import app.cmuxpocket.ui.ReleaseUpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdateCheckerTest {

    @Test
    fun testVersionComparison_newerVersions() {
        assertTrue(ReleaseUpdateChecker.isNewerVersion("0.2.1", "0.2.0"))
        assertTrue(ReleaseUpdateChecker.isNewerVersion("0.3.0", "0.2.9"))
        assertTrue(ReleaseUpdateChecker.isNewerVersion("1.0.0", "0.9.9"))
        assertTrue(ReleaseUpdateChecker.isNewerVersion("0.2.0.1", "0.2.0"))
    }

    @Test
    fun testVersionComparison_withVPrefix() {
        assertTrue(ReleaseUpdateChecker.isNewerVersion("v0.2.1", "v0.2.0"))
        assertTrue(ReleaseUpdateChecker.isNewerVersion("v0.3.0", "0.2.0"))
        assertTrue(ReleaseUpdateChecker.isNewerVersion("0.3.0", "v0.2.0"))
    }

    @Test
    fun testVersionComparison_sameVersion() {
        assertFalse(ReleaseUpdateChecker.isNewerVersion("0.2.0", "0.2.0"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("v0.2.0", "0.2.0"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("0.2.0", "v0.2.0"))
    }

    @Test
    fun testVersionComparison_olderVersions() {
        assertFalse(ReleaseUpdateChecker.isNewerVersion("0.1.9", "0.2.0"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("0.1.0", "0.2.0"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("0.0.9", "1.0.0"))
    }

    @Test
    fun testVersionComparison_differentLengthParts() {
        assertTrue(ReleaseUpdateChecker.isNewerVersion("0.2.0.1", "0.2.0"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("0.2", "0.2.0"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("0.2.0", "0.2.1"))
    }
}
