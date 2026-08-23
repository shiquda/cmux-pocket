package app.cmuxpocket

import app.cmuxpocket.engine.FrameApplyResult
import app.cmuxpocket.engine.NumericTraceHelper
import app.cmuxpocket.engine.SurfaceSessionStore
import app.cmuxpocket.protocol.Cursor
import app.cmuxpocket.protocol.MobileTerminalRenderGridFrame
import app.cmuxpocket.protocol.RenderFrameEnvelope
import app.cmuxpocket.protocol.RowSpan
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NumericTraceTest {

    @Before
    fun setUp() {
        NumericTraceHelper.clearOrdinals()
    }

    @Test
    fun testSurfaceOrdinalAssignment() {
        assertEquals(0, NumericTraceHelper.getSurfaceOrdinal(""))

        val ordA = NumericTraceHelper.getSurfaceOrdinal("surface-alpha")
        val ordB = NumericTraceHelper.getSurfaceOrdinal("surface-beta")
        val ordA2 = NumericTraceHelper.getSurfaceOrdinal("surface-alpha")

        assertEquals(1, ordA)
        assertEquals(2, ordB)
        assertEquals(1, ordA2) // Stable ordinal

        NumericTraceHelper.clearOrdinals()
        val ordAfterReset = NumericTraceHelper.getSurfaceOrdinal("surface-alpha")
        assertEquals(1, ordAfterReset)
    }

    @Test
    fun testNumericTracePrivacyNoSensitiveContent() {
        val logLines = mutableListOf<String>()
        NumericTraceHelper.testSink = { logLines.add(it) }

        val rawSecretSurfaceId = "secret-surface-token-http-auth-xyz-987"
        val stateSeq = 10L
        val tRecv = 1_000_000_000L
        val tDecode = 1_000_050_000L
        val tApply = 1_000_120_000L
        val tDraw = 1_000_300_000L

        try {
            NumericTraceHelper.logReceive(
                traceId = 101L,
                surfaceId = rawSecretSurfaceId,
                stateSeq = stateSeq,
                full = true,
                receivedNanos = tRecv
            )

            NumericTraceHelper.logDecode(
                traceId = 101L,
                surfaceId = rawSecretSurfaceId,
                stateSeq = stateSeq,
                receivedNanos = tRecv,
                decodedNanos = tDecode
            )

            NumericTraceHelper.logApply(
                traceId = 101L,
                surfaceId = rawSecretSurfaceId,
                stateSeq = stateSeq,
                result = FrameApplyResult.BASELINE_APPLIED,
                receivedNanos = tRecv,
                appliedNanos = tApply
            )

            NumericTraceHelper.logDraw(
                traceId = 101L,
                surfaceId = rawSecretSurfaceId,
                stateSeq = stateSeq,
                columns = 80,
                rows = 24,
                receivedNanos = tRecv,
                drawNanos = tDraw
            )

            assertEquals(4, logLines.size)

            for (line in logLines) {
                // MUST NOT contain raw string ID or secret tokens
                assertFalse("Log contains raw surface ID: $line", line.contains(rawSecretSurfaceId))
                assertFalse("Log contains secret/auth/token keyword: $line", line.contains("secret") || line.contains("token") || line.contains("http"))
                // MUST contain numeric identifiers
                assertTrue("Log contains trace_id: $line", line.contains("trace_id=101"))
                assertTrue("Log contains surf_ord=1: $line", line.contains("surf_ord=1"))
                assertTrue("Log contains seq=10: $line", line.contains("seq=10"))
            }

            // Verify stage prefixes
            assertTrue(logLines[0].startsWith("RECV"))
            assertTrue(logLines[1].startsWith("DECODE"))
            assertTrue(logLines[2].startsWith("APPLY"))
            assertTrue(logLines[3].startsWith("DRAW"))
        } finally {
            NumericTraceHelper.testSink = null
        }
    }

    @Test
    fun testRenderFrameEnvelopePropagation() {
        val store = SurfaceSessionStore()

        val fullFrame = MobileTerminalRenderGridFrame(
            surfaceId = "surf-trace-test",
            stateSeq = 1,
            renderEpoch = "epoch-1",
            columns = 80,
            rows = 24,
            full = true,
            cursor = Cursor(row = 0, column = 0, visible = true),
            rowSpans = listOf(RowSpan(row = 0, column = 0, styleId = 0, text = "Hello Trace"))
        )

        val envelope1 = RenderFrameEnvelope(
            traceId = 42L,
            frame = fullFrame,
            receivedNanos = 100_000_000L,
            decodedNanos = 100_050_000L
        )

        val result1 = store.routeFrame(envelope1)
        assertEquals(FrameApplyResult.BASELINE_APPLIED, result1)

        val session = store.getSession("surf-trace-test")
        assertNotNull(session)
        val state1 = session!!.screenState.value
        assertEquals(42L, state1.traceId)
        assertEquals(100_000_000L, state1.receivedNanos)
        assertEquals(1L, state1.stateSeq)
        assertEquals("epoch-1", state1.renderEpoch)
        assertEquals("Hello Trace", state1.grid[0].take(11).map { it.text }.joinToString(""))

        // Route delta frame envelope
        val deltaFrame = MobileTerminalRenderGridFrame(
            surfaceId = "surf-trace-test",
            stateSeq = 2,
            renderEpoch = "epoch-1",
            columns = 80,
            rows = 24,
            full = false,
            cursor = Cursor(row = 0, column = 5, visible = true),
            rowSpans = listOf(RowSpan(row = 0, column = 0, styleId = 0, text = "World Trace"))
        )

        val envelope2 = RenderFrameEnvelope(
            traceId = 43L,
            frame = deltaFrame,
            receivedNanos = 200_000_000L,
            decodedNanos = 200_050_000L
        )

        val result2 = store.routeFrame(envelope2)
        assertEquals(FrameApplyResult.DELTA_APPLIED, result2)

        val state2 = session.screenState.value
        assertEquals(43L, state2.traceId)
        assertEquals(200_000_000L, state2.receivedNanos)
        assertEquals(2L, state2.stateSeq)
        assertEquals("World Trace", state2.grid[0].take(11).map { it.text }.joinToString(""))
    }
}
