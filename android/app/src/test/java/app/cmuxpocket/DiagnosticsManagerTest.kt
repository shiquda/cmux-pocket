package app.cmuxpocket

import app.cmuxpocket.diagnostics.DiagnosticsManager
import app.cmuxpocket.transport.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsManagerTest {

    @Before
    fun setup() {
        DiagnosticsManager.clear()
        DiagnosticsManager.isEnabled = true
    }

    @Test
    fun testTokenRedactionInLogs() {
        DiagnosticsManager.log("net", "Connecting with token=secret_auth_token_999 to gateway")
        DiagnosticsManager.log("auth", "Authorization: Bearer my_super_secret_jwt_token_abc")
        DiagnosticsManager.log("uri", "cmux://gateway?token=super_confidential_key")

        val logs = DiagnosticsManager.getLogs()
        assertEquals(3, logs.size)

        for (log in logs) {
            assertFalse("Log should redact secret_auth_token_999: $log", log.contains("secret_auth_token_999"))
            assertFalse("Log should redact my_super_secret_jwt_token_abc: $log", log.contains("my_super_secret_jwt_token_abc"))
            assertFalse("Log should redact super_confidential_key: $log", log.contains("super_confidential_key"))
            assertTrue("Log should contain masked marker: $log", log.contains("***REDACTED***"))
        }
    }

    @Test
    fun testDisabledLoggingDoesNotRecord() {
        DiagnosticsManager.isEnabled = false
        DiagnosticsManager.log("test", "This should not be saved")

        val logs = DiagnosticsManager.getLogs()
        assertTrue("Logs should be empty when disabled", logs.isEmpty())
    }

    @Test
    fun testBufferLimit500() {
        for (i in 1..600) {
            DiagnosticsManager.log("test", "Entry #$i")
        }

        val logs = DiagnosticsManager.getLogs()
        assertEquals(500, logs.size)
        // Earliest retained entry should be 101
        assertTrue(logs.first().contains("Entry #101"))
        assertTrue(logs.last().contains("Entry #600"))
    }
}
