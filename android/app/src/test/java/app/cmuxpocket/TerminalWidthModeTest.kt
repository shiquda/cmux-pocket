package app.cmuxpocket

import app.cmuxpocket.ui.PHONE_WIDTH_MAX_COLUMNS
import app.cmuxpocket.ui.PHONE_WIDTH_MAX_ROWS
import app.cmuxpocket.ui.PHONE_WIDTH_MIN_COLUMNS
import app.cmuxpocket.ui.PhoneWidthPhase
import app.cmuxpocket.ui.PhoneWidthState
import app.cmuxpocket.ui.TerminalWidthMetrics
import app.cmuxpocket.ui.computePhoneWidthTarget
import app.cmuxpocket.ui.phoneWidthStateWithFrame
import app.cmuxpocket.ui.phoneWidthTargetSatisfied
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalWidthModeTest {

    private val metrics = TerminalWidthMetrics(
        availableWidthPx = 393f,
        canvasHeightPx = 700f,
        cellWidthPx = 8.4f,
        rowHeightPx = 18f,
    )

    @Test
    fun targetUsesFontMetricsAndKeepsTheComputerRowCount() {
        // 393 / 8.4 = 46 columns; the phone fits 38 rows but the computer shows 46.
        assertEquals(46 to 46, computePhoneWidthTarget(metrics, basisRows = 46))
    }

    @Test
    fun targetFallsBackToPhoneRowsWhenTheyExceedTheComputer() {
        val tall = TerminalWidthMetrics(
            availableWidthPx = 393f,
            canvasHeightPx = 900f,
            cellWidthPx = 8.4f,
            rowHeightPx = 18f,
        )
        val (_, rows) = computePhoneWidthTarget(tall, basisRows = 20)!!
        assertEquals(50, rows)
    }

    @Test
    fun targetHonoursHostClamps() {
        val tiny = TerminalWidthMetrics(10f, 10f, 100f, 100f)
        val (columns, rows) = computePhoneWidthTarget(tiny, basisRows = 0)!!
        assertEquals(PHONE_WIDTH_MIN_COLUMNS, columns)
        assertEquals(5, rows)

        val huge = TerminalWidthMetrics(9000f, 9000f, 1f, 1f)
        val (maxColumns, maxRows) = computePhoneWidthTarget(huge, basisRows = 500)!!
        assertEquals(PHONE_WIDTH_MAX_COLUMNS, maxColumns)
        assertEquals(PHONE_WIDTH_MAX_ROWS, maxRows)
    }

    @Test
    fun invalidMetricsProduceNoTarget() {
        assertNull(computePhoneWidthTarget(null, basisRows = 24))
        assertNull(computePhoneWidthTarget(metrics.copy(cellWidthPx = 0f), basisRows = 24))
        assertNull(computePhoneWidthTarget(metrics.copy(availableWidthPx = Float.NaN), basisRows = 24))
    }

    @Test
    fun aFrameCapturedBeforeTheAckCannotConfirmTheResize() {
        val requesting = PhoneWidthState(
            phase = PhoneWidthPhase.REQUESTING,
            targetColumns = 46,
            targetRows = 46,
            generation = 10,
        )

        val stale = phoneWidthStateWithFrame(
            state = requesting,
            columns = 148,
            rows = 46,
            receivedAtNanos = 500,
            confirmedAfterNanos = 1_000,
        )
        assertEquals(PhoneWidthPhase.REQUESTING, stale.phase)

        val fresh = phoneWidthStateWithFrame(
            state = requesting,
            columns = 46,
            rows = 46,
            receivedAtNanos = 1_500,
            confirmedAfterNanos = 1_000,
        )
        assertEquals(PhoneWidthPhase.ACTIVE, fresh.phase)
        assertEquals(46, fresh.effectiveColumns)
        assertEquals(46, fresh.effectiveRows)
        assertTrue(phoneWidthTargetSatisfied(fresh))
    }

    @Test
    fun anOffSurfaceIgnoresFrames() {
        val off = PhoneWidthState(phase = PhoneWidthPhase.OFF)
        val unchanged = phoneWidthStateWithFrame(off, 100, 40, 2_000, 0)
        assertEquals(off, unchanged)
        assertFalse(unchanged.enabled)
    }
}
