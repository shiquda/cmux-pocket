package app.cmuxpocket

import app.cmuxpocket.engine.FrameApplyResult
import app.cmuxpocket.engine.RenderGridStateEngine
import app.cmuxpocket.engine.SurfaceSyncState
import app.cmuxpocket.engine.TerminalCell
import app.cmuxpocket.engine.selectViewportRows
import app.cmuxpocket.engine.updateAnchoredScrollAccumulator
import app.cmuxpocket.engine.updateAnchoredScrollOffset
import app.cmuxpocket.protocol.MobileTerminalRenderGridFrame
import app.cmuxpocket.protocol.Cursor
import app.cmuxpocket.protocol.RowSpan
import app.cmuxpocket.protocol.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderGridStateEngineTest {

    @Test
    fun testFullFrameInitialization() {
        val engine = RenderGridStateEngine()

        val fullFrame = MobileTerminalRenderGridFrame(
            surfaceId = "term-1",
            stateSeq = 1,
            renderEpoch = "epoch-100",
            columns = 10,
            rows = 5,
            full = true,
            cursor = Cursor(row = 0, column = 2, visible = true),
            styles = listOf(
                Style(id = 0, foreground = "#FFFFFF", background = "#000000"),
                Style(id = 1, foreground = "#00FF00", background = "#000000", bold = true)
            ),
            rowSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 1, text = "❯ ls")
            )
        )

        val result = engine.applyFrame(fullFrame)
        assertEquals(FrameApplyResult.BASELINE_APPLIED, result)

        val state = engine.screenState.value
        assertEquals("term-1", state.surfaceId)
        assertEquals(10, state.columns)
        assertEquals(5, state.rows)
        assertEquals(1, state.stateSeq)
        assertEquals("epoch-100", state.renderEpoch)
        assertEquals(0, state.cursor.row)
        assertEquals(2, state.cursor.column)

        // Check painted cells
        val row0 = state.grid[0]
        assertEquals("❯", row0[0].text)
        assertEquals(" ", row0[1].text)
        assertEquals("l", row0[2].text)
        assertEquals("s", row0[3].text)
        assertTrue(row0[0].bold)
        assertEquals("#00FF00", row0[0].foreground)
    }

    @Test
    fun testConsistencyStateMachineGapsAndRecoveryBarrier() {
        val engine = RenderGridStateEngine()

        // 1. Delta without baseline returns NEED_REPLAY
        val orphanDelta = MobileTerminalRenderGridFrame(
            surfaceId = "term-1",
            stateSeq = 5,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 5,
            full = false
        )
        assertEquals(FrameApplyResult.NEED_REPLAY, engine.applyFrame(orphanDelta))

        // 2. Full frame establishes baseline
        val baseline = MobileTerminalRenderGridFrame(
            surfaceId = "term-1",
            stateSeq = 10,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 5,
            full = true
        )
        assertEquals(FrameApplyResult.BASELINE_APPLIED, engine.applyFrame(baseline))

        // 3. Stale duplicate is dropped
        val duplicate = MobileTerminalRenderGridFrame(
            surfaceId = "term-1",
            stateSeq = 10,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 5,
            full = false
        )
        assertEquals(FrameApplyResult.DUPLICATE, engine.applyFrame(duplicate))

        // 4. Missing sequence gap (10 -> 13) requests replay and locks recovery barrier
        val gapFrame = MobileTerminalRenderGridFrame(
            surfaceId = "term-1",
            stateSeq = 13,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 5,
            full = false
        )
        assertEquals(FrameApplyResult.NEED_REPLAY, engine.applyFrame(gapFrame))

        // 5. Strict recovery barrier: subsequent delta (14) must still be REJECTED while awaiting replay
        val barrierDelta = MobileTerminalRenderGridFrame(
            surfaceId = "term-1",
            stateSeq = 14,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 5,
            full = false
        )
        assertEquals(FrameApplyResult.NEED_REPLAY, engine.applyFrame(barrierDelta))

        // 6. Full replay frame successfully clears the barrier
        val replayBaseline = MobileTerminalRenderGridFrame(
            surfaceId = "term-1",
            stateSeq = 15,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 5,
            full = true
        )
        assertEquals(FrameApplyResult.BASELINE_APPLIED, engine.applyFrame(replayBaseline))

        // 7. Subsequent sequential delta (16) applies normally
        val sequentialDelta = MobileTerminalRenderGridFrame(
            surfaceId = "term-1",
            stateSeq = 16,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 5,
            full = false
        )
        assertEquals(FrameApplyResult.DELTA_APPLIED, engine.applyFrame(sequentialDelta))
    }

    @Test
    fun testUnicodeAndWideCharHandling() {
        val engine = RenderGridStateEngine()

        val frame = MobileTerminalRenderGridFrame(
            surfaceId = "term-wide",
            stateSeq = 1,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 3,
            full = true,
            rowSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "中文测试")
            )
        )

        val result = engine.applyFrame(frame)
        assertEquals(FrameApplyResult.BASELINE_APPLIED, result)

        val state = engine.screenState.value
        val row0 = state.grid[0]

        assertEquals("中", row0[0].text)
        assertEquals(2, row0[0].width)
        assertEquals("", row0[1].text)
        assertEquals(0, row0[1].width)

        assertEquals("文", row0[2].text)
        assertEquals(2, row0[2].width)
        assertEquals("", row0[3].text)
        assertEquals(0, row0[3].width)
    }

    @Test
    fun testOfficialWideFrameKeepsRightEdge() {
        val engine = RenderGridStateEngine()
        val cols = 145
        val frame = MobileTerminalRenderGridFrame(
            surfaceId = "surface:170",
            stateSeq = 4,
            renderEpoch = "epoch-live",
            columns = cols,
            rows = 2,
            full = true,
            rowSpans = listOf(
                RowSpan(row = 0, column = 142, styleId = 0, text = " ─╯", cellWidth = 3)
            )
        )

        assertEquals(FrameApplyResult.BASELINE_APPLIED, engine.applyFrame(frame))
        val state = engine.screenState.value
        assertEquals(145, state.columns)
        assertEquals("─", state.grid[0][143].text)
        assertEquals("╯", state.grid[0][144].text)
    }

    @Test
    fun testExplicitReplayScrollbackHydration() {
        val engine = RenderGridStateEngine()
        val replayFrame = MobileTerminalRenderGridFrame(
            surfaceId = "term-sb",
            stateSeq = 1,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 3,
            rowSpaceRevision = 100,
            scrollbackRows = 3,
            scrollbackSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "hist-0"),
                RowSpan(row = 1, column = 0, styleId = 0, text = "hist-1"),
                RowSpan(row = 2, column = 0, styleId = 0, text = "hist-2")
            ),
            rowSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "act-0"),
                RowSpan(row = 1, column = 0, styleId = 0, text = "act-1")
            )
        )

        val result = engine.applyFrame(replayFrame)
        assertEquals(FrameApplyResult.BASELINE_APPLIED, result)

        val state = engine.screenState.value
        assertEquals(3, state.scrollback.size)
        assertEquals("h", state.scrollback[0][0].text)
        assertEquals("i", state.scrollback[0][1].text)
        assertEquals("s", state.scrollback[0][2].text)
        assertEquals("t", state.scrollback[0][3].text)
        assertEquals("-", state.scrollback[0][4].text)
        assertEquals("0", state.scrollback[0][5].text)
        assertEquals("hist-1", state.scrollback[1].map { it.text }.joinToString("").trim())
        assertEquals("hist-2", state.scrollback[2].map { it.text }.joinToString("").trim())
        assertEquals("act-0", state.grid[0].map { it.text }.joinToString("").trim())
        assertEquals("act-1", state.grid[1].map { it.text }.joinToString("").trim())
        assertEquals(3L, state.historyRows)
        assertEquals(100L, state.rowSpaceRevision)
    }

    @Test
    fun testHistoryAdvanceFromPreviousActiveGridOnPrimaryScreen() {
        val engine = RenderGridStateEngine()

        // 1. Initial baseline: grid has "lineA" and "lineB"
        val frame1 = MobileTerminalRenderGridFrame(
            surfaceId = "term-adv",
            stateSeq = 1,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 0,
            rowSpaceRevision = 1,
            rowSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "lineA"),
                RowSpan(row = 1, column = 0, styleId = 0, text = "lineB")
            )
        )
        engine.applyFrame(frame1)
        assertEquals(0, engine.screenState.value.scrollback.size)

        // 2. Next full frame without scrollback payload, but historyRows advanced by 1 (lineA scrolled off)
        val frame2 = MobileTerminalRenderGridFrame(
            surfaceId = "term-adv",
            stateSeq = 2,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 1,
            rowSpaceRevision = 1,
            rowSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "lineB"),
                RowSpan(row = 1, column = 0, styleId = 0, text = "lineC")
            )
        )
        engine.applyFrame(frame2)
        val state2 = engine.screenState.value
        assertEquals(1, state2.scrollback.size)
        assertEquals("lineA", state2.scrollback[0].map { it.text }.joinToString("").trim())
        assertEquals("lineB", state2.grid[0].map { it.text }.joinToString("").trim())
        assertEquals("lineC", state2.grid[1].map { it.text }.joinToString("").trim())
    }

    @Test
    fun testScrollbackCapAt500Rows() {
        val engine = RenderGridStateEngine()
        val spans = (0 until 550).map { idx ->
            RowSpan(row = idx, column = 0, styleId = 0, text = "row-$idx")
        }
        val replayFrame = MobileTerminalRenderGridFrame(
            surfaceId = "term-cap",
            stateSeq = 1,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 550,
            scrollbackRows = 550,
            scrollbackSpans = spans
        )
        engine.applyFrame(replayFrame)
        val state = engine.screenState.value
        assertEquals(500, state.scrollback.size)
        assertEquals("row-50", state.scrollback[0].map { it.text }.joinToString("").trim())
        assertEquals("row-549", state.scrollback[499].map { it.text }.joinToString("").trim())
    }

    @Test
    fun testUnsafeContinuityResetsStaleHistory() {
        val engine = RenderGridStateEngine()

        // 1. Initial hydrated baseline with 2 scrollback rows
        val frame1 = MobileTerminalRenderGridFrame(
            surfaceId = "term-unsafe",
            stateSeq = 1,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 2,
            rowSpaceRevision = 10,
            scrollbackRows = 2,
            scrollbackSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "s0"),
                RowSpan(row = 1, column = 0, styleId = 0, text = "s1")
            )
        )
        engine.applyFrame(frame1)
        assertEquals(2, engine.screenState.value.scrollback.size)

        // 2. Unsafe jump: next full frame with delta > rows count (historyRows jumped from 2 to 10 with rows=2)
        val frameUnsafeJump = MobileTerminalRenderGridFrame(
            surfaceId = "term-unsafe",
            stateSeq = 2,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 10,
            rowSpaceRevision = 10
        )
        val jumpResult = engine.applyFrame(frameUnsafeJump)
        // Unsafe jump returns NEED_REPLAY and clears stale history
        assertEquals(FrameApplyResult.NEED_REPLAY, jumpResult)
        assertEquals(0, engine.screenState.value.scrollback.size)
        assertEquals(SurfaceSyncState.AWAITING_REPLAY, engine.screenState.value.syncState)

        // 3. Re-hydrate with baseline
        val hydrateResult = engine.applyFrame(frame1.copy(stateSeq = 3, historyRows = 10))
        assertEquals(FrameApplyResult.BASELINE_APPLIED, hydrateResult)
        assertEquals(2, engine.screenState.value.scrollback.size)
        assertEquals(SurfaceSyncState.HEALTHY, engine.screenState.value.syncState)

        // 4. Unsafe rewind/reversal: next full frame with historyRows decreasing (10 -> 5)
        val frameRewind = MobileTerminalRenderGridFrame(
            surfaceId = "term-unsafe",
            stateSeq = 4,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 5,
            rowSpaceRevision = 10
        )
        val rewindResult = engine.applyFrame(frameRewind)
        // History rewind returns NEED_REPLAY and clears stale history
        assertEquals(FrameApplyResult.NEED_REPLAY, rewindResult)
        assertEquals(0, engine.screenState.value.scrollback.size)
        assertEquals(SurfaceSyncState.AWAITING_REPLAY, engine.screenState.value.syncState)

        // 5. Re-hydrate and test epoch change reset
        engine.applyFrame(frame1.copy(stateSeq = 5, historyRows = 5))
        assertEquals(2, engine.screenState.value.scrollback.size)

        val frameNewEpoch = MobileTerminalRenderGridFrame(
            surfaceId = "term-unsafe",
            stateSeq = 6,
            renderEpoch = "epoch-2",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 5,
            rowSpaceRevision = 10
        )
        engine.applyFrame(frameNewEpoch)
        assertEquals(0, engine.screenState.value.scrollback.size)
    }

    @Test
    fun testPrimaryHistoryJumpRequestsReplayAndHydrationRestoresScrollback() {
        val engine = RenderGridStateEngine()

        // 1. Initial baseline on fresh tab (80x24, historyRows=0)
        val initialFrame = MobileTerminalRenderGridFrame(
            surfaceId = "term-jump",
            stateSeq = 1,
            renderEpoch = "epoch-1",
            columns = 80,
            rows = 24,
            full = true,
            historyRows = 0,
            rowSpaceRevision = 0,
            rowSpans = listOf(RowSpan(row = 0, column = 0, styleId = 0, text = "$ prompt"))
        )
        val initResult = engine.applyFrame(initialFrame)
        assertEquals(FrameApplyResult.BASELINE_APPLIED, initResult)
        assertEquals(0, engine.screenState.value.scrollback.size)
        assertEquals(SurfaceSyncState.HEALTHY, engine.screenState.value.syncState)

        // 2. Command `seq 1 80` advances historyRows by 80 (delta > rows=24) between full polls
        val jumpedFrame = MobileTerminalRenderGridFrame(
            surfaceId = "term-jump",
            stateSeq = 2,
            renderEpoch = "epoch-1",
            columns = 80,
            rows = 24,
            full = true,
            historyRows = 80,
            rowSpaceRevision = 80,
            rowSpans = listOf(RowSpan(row = 23, column = 0, styleId = 0, text = "80"))
        )
        val jumpResult = engine.applyFrame(jumpedFrame)
        // Unsafe jump publishes active screen (line 23 has "80") but flags NEED_REPLAY to trigger replay barrier
        assertEquals(FrameApplyResult.NEED_REPLAY, jumpResult)
        assertEquals(0, engine.screenState.value.scrollback.size)
        assertEquals(SurfaceSyncState.AWAITING_REPLAY, engine.screenState.value.syncState)
        assertEquals("80", engine.screenState.value.grid[23].take(2).map { it.text }.joinToString(""))

        // 3. Replay hydration frame from gateway arrives with 56 scrollback rows
        val replaySpans = (0 until 56).map { r ->
            RowSpan(row = r, column = 0, styleId = 0, text = "line-${r + 1}")
        }
        val replayFrame = MobileTerminalRenderGridFrame(
            surfaceId = "term-jump",
            stateSeq = 3,
            renderEpoch = "epoch-1",
            columns = 80,
            rows = 24,
            full = true,
            historyRows = 80,
            rowSpaceRevision = 80,
            scrollbackRows = 56,
            scrollbackSpans = replaySpans,
            rowSpans = listOf(RowSpan(row = 23, column = 0, styleId = 0, text = "80"))
        )
        val replayResult = engine.applyFrame(replayFrame)
        assertEquals(FrameApplyResult.BASELINE_APPLIED, replayResult)
        assertEquals(56, engine.screenState.value.scrollback.size)
        assertEquals(SurfaceSyncState.HEALTHY, engine.screenState.value.syncState)
        assertEquals("line-1", engine.screenState.value.scrollback[0].take(6).map { it.text }.joinToString("").trim())
        assertEquals("line-56", engine.screenState.value.scrollback[55].take(7).map { it.text }.joinToString("").trim())
    }

    @Test
    fun testContinuousRevisionChangePreservesRows() {
        val engine = RenderGridStateEngine()

        // 1. Initial hydrated baseline with 2 scrollback rows and grid lines
        val frame1 = MobileTerminalRenderGridFrame(
            surfaceId = "term-rev",
            stateSeq = 1,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 2,
            rowSpaceRevision = 1,
            scrollbackRows = 2,
            scrollbackSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "s0"),
                RowSpan(row = 1, column = 0, styleId = 0, text = "s1")
            ),
            rowSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "g0"),
                RowSpan(row = 1, column = 0, styleId = 0, text = "g1")
            )
        )
        engine.applyFrame(frame1)
        assertEquals(2, engine.screenState.value.scrollback.size)
        assertEquals(1L, engine.screenState.value.rowSpaceRevision)

        // 2. Full frame with rowSpaceRevision change alone (no scrollback payload, delta = 0)
        val frameFullRevChange = MobileTerminalRenderGridFrame(
            surfaceId = "term-rev",
            stateSeq = 2,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 2,
            rowSpaceRevision = 2,
            rowSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "g0"),
                RowSpan(row = 1, column = 0, styleId = 0, text = "g1")
            )
        )
        engine.applyFrame(frameFullRevChange)
        // Scrollback must NOT be cleared on rowSpaceRevision change alone
        assertEquals(2, engine.screenState.value.scrollback.size)
        assertEquals("s0", engine.screenState.value.scrollback[0].map { it.text }.joinToString("").trim())
        assertEquals("s1", engine.screenState.value.scrollback[1].map { it.text }.joinToString("").trim())
        assertEquals(2L, engine.screenState.value.rowSpaceRevision)

        // 3. Delta frame with rowSpaceRevision change alone
        val frameDeltaRevChange = MobileTerminalRenderGridFrame(
            surfaceId = "term-rev",
            stateSeq = 3,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = false,
            historyRows = 2,
            rowSpaceRevision = 3
        )
        engine.applyFrame(frameDeltaRevChange)
        // Delta frame with rowSpaceRevision change must NOT clear scrollback
        assertEquals(2, engine.screenState.value.scrollback.size)
        assertEquals(3L, engine.screenState.value.rowSpaceRevision)

        // 4. Full frame with rowSpaceRevision change and continuous history advancement (delta = 1)
        val frameAdvance = MobileTerminalRenderGridFrame(
            surfaceId = "term-rev",
            stateSeq = 4,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 3,
            rowSpaceRevision = 4,
            rowSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "g1"),
                RowSpan(row = 1, column = 0, styleId = 0, text = "g2")
            )
        )
        engine.applyFrame(frameAdvance)
        // Cached history advances by 1 (g0 moved to scrollback)
        assertEquals(3, engine.screenState.value.scrollback.size)
        assertEquals("s0", engine.screenState.value.scrollback[0].map { it.text }.joinToString("").trim())
        assertEquals("s1", engine.screenState.value.scrollback[1].map { it.text }.joinToString("").trim())
        assertEquals("g0", engine.screenState.value.scrollback[2].map { it.text }.joinToString("").trim())
        assertEquals(4L, engine.screenState.value.rowSpaceRevision)

        // 5. Explicit replay with scrollback spans replaces the buffer
        val frameReplay = MobileTerminalRenderGridFrame(
            surfaceId = "term-rev",
            stateSeq = 5,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            historyRows = 1,
            rowSpaceRevision = 5,
            scrollbackRows = 1,
            scrollbackSpans = listOf(
                RowSpan(row = 0, column = 0, styleId = 0, text = "r0")
            )
        )
        engine.applyFrame(frameReplay)
        assertEquals(1, engine.screenState.value.scrollback.size)
        assertEquals("r0", engine.screenState.value.scrollback[0].map { it.text }.joinToString("").trim())
        assertEquals(5L, engine.screenState.value.rowSpaceRevision)
    }

    @Test
    fun testSelectViewportRowsHelper() {
        val makeRow: (String) -> List<TerminalCell> = { text ->
            text.map { TerminalCell(text = it.toString()) }
        }

        val scrollback = listOf(
            makeRow("S0"),
            makeRow("S1"),
            makeRow("S2"),
            makeRow("S3"),
            makeRow("S4")
        )
        val grid = listOf(
            makeRow("G0"),
            makeRow("G1"),
            makeRow("G2")
        )

        // Offset 0 = bottom (active grid)
        val view0 = selectViewportRows(scrollback, grid, viewportRows = 3, scrollOffset = 0)
        assertEquals(3, view0.size)
        assertEquals("G0", view0[0].map { it.text }.joinToString(""))
        assertEquals("G1", view0[1].map { it.text }.joinToString(""))
        assertEquals("G2", view0[2].map { it.text }.joinToString(""))

        // Offset 2 = scrolled up by 2 lines -> S3, S4, G0
        val view2 = selectViewportRows(scrollback, grid, viewportRows = 3, scrollOffset = 2)
        assertEquals(3, view2.size)
        assertEquals("S3", view2[0].map { it.text }.joinToString(""))
        assertEquals("S4", view2[1].map { it.text }.joinToString(""))
        assertEquals("G0", view2[2].map { it.text }.joinToString(""))

        // Offset 5 = top of scrollback -> S0, S1, S2
        val view5 = selectViewportRows(scrollback, grid, viewportRows = 3, scrollOffset = 5)
        assertEquals(3, view5.size)
        assertEquals("S0", view5[0].map { it.text }.joinToString(""))
        assertEquals("S1", view5[1].map { it.text }.joinToString(""))
        assertEquals("S2", view5[2].map { it.text }.joinToString(""))

        // Offset clamped at max
        val viewOver = selectViewportRows(scrollback, grid, viewportRows = 3, scrollOffset = 100)
        assertEquals("S0", viewOver[0].map { it.text }.joinToString(""))
    }

    @Test
    fun testAlternateScreenDoesNotExposeScrollback() {
        val engine = RenderGridStateEngine()
        val altFrame = MobileTerminalRenderGridFrame(
            surfaceId = "term-alt",
            stateSeq = 1,
            renderEpoch = "epoch-1",
            columns = 10,
            rows = 2,
            full = true,
            activeScreen = "alternate",
            scrollbackRows = 2,
            scrollbackSpans = listOf(
                RowSpan(row = 0, column = 0, text = "s0")
            )
        )
        engine.applyFrame(altFrame)
        val state = engine.screenState.value
        assertEquals("alternate", state.activeScreen)
        assertEquals(0, state.scrollback.size)
    }

    @Test
    fun testAnchoredScrollOffsetBottomFollow() {
        // When offset is zero, stay at zero even when historyRows advances by 50
        val offset = updateAnchoredScrollOffset(
            currentOffset = 0,
            previousHistoryRows = 10L,
            newHistoryRows = 60L,
            scrollbackSize = 100,
            isPrimaryScreen = true,
            isContinuityReset = false
        )
        assertEquals(0, offset)
    }

    @Test
    fun testAnchoredScrollOffsetIncrement() {
        // When scrolled up to line 10, and historyRows advances by 5 (e.g. 20 -> 25),
        // offset increases to 15 so the same historical rows remain anchored
        val offset = updateAnchoredScrollOffset(
            currentOffset = 10,
            previousHistoryRows = 20L,
            newHistoryRows = 25L,
            scrollbackSize = 50,
            isPrimaryScreen = true,
            isContinuityReset = false
        )
        assertEquals(15, offset)
    }

    @Test
    fun testAnchoredScrollOffsetClamp() {
        // When offset + delta exceeds available scrollback size, clamp to scrollbackSize
        val offset = updateAnchoredScrollOffset(
            currentOffset = 40,
            previousHistoryRows = 10L,
            newHistoryRows = 50L,
            scrollbackSize = 50,
            isPrimaryScreen = true,
            isContinuityReset = false
        )
        assertEquals(50, offset)
    }

    @Test
    fun testAnchoredScrollOffsetDiscontinuityAndReset() {
        // 1. Continuity reset flags reset offset to 0
        val resetOffset = updateAnchoredScrollOffset(
            currentOffset = 20,
            previousHistoryRows = 10L,
            newHistoryRows = 15L,
            scrollbackSize = 50,
            isPrimaryScreen = true,
            isContinuityReset = true
        )
        assertEquals(0, resetOffset)

        // 2. Empty scrollback resets offset to 0
        val emptyScrollbackOffset = updateAnchoredScrollOffset(
            currentOffset = 20,
            previousHistoryRows = 10L,
            newHistoryRows = 15L,
            scrollbackSize = 0,
            isPrimaryScreen = true,
            isContinuityReset = false
        )
        assertEquals(0, emptyScrollbackOffset)

        // 3. Alternate screen mode resets offset to 0
        val altScreenOffset = updateAnchoredScrollOffset(
            currentOffset = 20,
            previousHistoryRows = 10L,
            newHistoryRows = 15L,
            scrollbackSize = 50,
            isPrimaryScreen = false,
            isContinuityReset = false
        )
        assertEquals(0, altScreenOffset)

        // 4. No historyRows advance preserves offset clamped to scrollbackSize
        val noAdvanceOffset = updateAnchoredScrollOffset(
            currentOffset = 20,
            previousHistoryRows = 15L,
            newHistoryRows = 15L,
            scrollbackSize = 50,
            isPrimaryScreen = true,
            isContinuityReset = false
        )
        assertEquals(20, noAdvanceOffset)
    }

    @Test
    fun testAnchoredScrollAccumulatorCalculations() {
        // Bottom-follow for float accumulator
        val bottomAcc = updateAnchoredScrollAccumulator(
            currentAccumulator = 0f,
            previousHistoryRows = 0L,
            newHistoryRows = 10L,
            scrollbackSize = 50
        )
        assertEquals(0f, bottomAcc, 0.001f)

        // Anchored float increment
        val anchoredAcc = updateAnchoredScrollAccumulator(
            currentAccumulator = 5.5f,
            previousHistoryRows = 10L,
            newHistoryRows = 14L,
            scrollbackSize = 50
        )
        assertEquals(9.5f, anchoredAcc, 0.001f)

        // Reset float accumulator
        val resetAcc = updateAnchoredScrollAccumulator(
            currentAccumulator = 5.5f,
            previousHistoryRows = 10L,
            newHistoryRows = 14L,
            scrollbackSize = 50,
            isContinuityReset = true
        )
        assertEquals(0f, resetAcc, 0.001f)
    }

}
