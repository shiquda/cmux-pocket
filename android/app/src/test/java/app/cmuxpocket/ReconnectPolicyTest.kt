package app.cmuxpocket

import app.cmuxpocket.ui.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun usesBoundedExponentialBackoff() {
        assertEquals(1_000L, ReconnectPolicy.delayMillis(0))
        assertEquals(2_000L, ReconnectPolicy.delayMillis(1))
        assertEquals(4_000L, ReconnectPolicy.delayMillis(2))
        assertEquals(8_000L, ReconnectPolicy.delayMillis(3))
        assertEquals(15_000L, ReconnectPolicy.delayMillis(4))
        assertEquals(30_000L, ReconnectPolicy.delayMillis(5))
        assertEquals(30_000L, ReconnectPolicy.delayMillis(99))
    }

    @Test
    fun rejectsNegativeAttempt() {
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.delayMillis(-1)
        }
    }

    @Test
    fun limitsAutomaticAttempts() {
        assertEquals(6, ReconnectPolicy.maxAttempts)
    }
}
