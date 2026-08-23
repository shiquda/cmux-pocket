package app.cmuxpocket.engine

import app.cmuxpocket.protocol.Cursor
import app.cmuxpocket.protocol.MobileTerminalRenderGridFrame
import app.cmuxpocket.protocol.RenderFrameEnvelope
import app.cmuxpocket.protocol.Style
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TerminalCell(
    val text: String = " ",
    val foreground: String = "#D4D4D4",
    val background: String = "#1E1E1E",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val width: Int = 1
)

data class TerminalScreenState(
    val surfaceId: String = "",
    val columns: Int = 80,
    val rows: Int = 24,
    val grid: List<List<TerminalCell>> = emptyList(),
    val scrollback: List<List<TerminalCell>> = emptyList(),
    val cursor: Cursor = Cursor(row = 0, column = 0, visible = true),
    val terminalBackground: String = "#1E1E1E",
    val terminalForeground: String = "#D4D4D4",
    val stateSeq: Long = 0,
    val renderEpoch: String? = null,
    val renderRevision: Long? = null,
    val historyRows: Long = 0L,
    val rowSpaceRevision: Long? = null,
    val activeScreen: String = "primary",
    val hasBaseline: Boolean = false,
    val syncState: SurfaceSyncState = SurfaceSyncState.EMPTY,
    val traceId: Long = 0L,
    val receivedNanos: Long = 0L
)

enum class SurfaceSyncState {
    EMPTY,
    STALE,
    AWAITING_REPLAY,
    HEALTHY,
    FAILED
}

enum class FrameApplyResult {
    BASELINE_APPLIED,
    DELTA_APPLIED,
    DUPLICATE,
    NEED_REPLAY
}

fun getCodePointWidth(codePoint: Int): Int {
    if ((codePoint in 0x1100..0x115F) ||
        (codePoint in 0x2E80..0xA4CF && codePoint != 0x303F) ||
        (codePoint in 0xAC00..0xD7A3) ||
        (codePoint in 0xF900..0xFAFF) ||
        (codePoint in 0xFE10..0xFE19) ||
        (codePoint in 0xFE30..0xFE6F) ||
        (codePoint in 0xFF01..0xFF60) ||
        (codePoint in 0xFFE0..0xFFE6) ||
        (codePoint in 0x1F300..0x1F64F) ||
        (codePoint in 0x1F680..0x1F6FF) ||
        (codePoint in 0x20000..0x2FA1F)) {
        return 2
    }
    return 1
}

fun selectViewportRows(
    scrollback: List<List<TerminalCell>>,
    grid: List<List<TerminalCell>>,
    viewportRows: Int,
    scrollOffset: Int
): List<List<TerminalCell>> {
    if (grid.isEmpty() && scrollback.isEmpty()) return emptyList()
    val targetRows = if (viewportRows <= 0) (if (grid.isNotEmpty()) grid.size else scrollback.size) else viewportRows
    val totalScrollback = scrollback.size
    val clampedOffset = scrollOffset.coerceIn(0, totalScrollback)
    val startIndex = totalScrollback - clampedOffset
    val result = ArrayList<List<TerminalCell>>(targetRows)
    for (i in 0 until targetRows) {
        val bufferIndex = startIndex + i
        if (bufferIndex < totalScrollback) {
            result.add(scrollback[bufferIndex])
        } else {
            val gridIndex = bufferIndex - totalScrollback
            if (gridIndex < grid.size) {
                result.add(grid[gridIndex])
            } else {
                result.add(emptyList())
            }
        }
    }
    return result
}

fun updateAnchoredScrollOffset(
    currentOffset: Int,
    previousHistoryRows: Long?,
    newHistoryRows: Long,
    scrollbackSize: Int,
    isPrimaryScreen: Boolean = true,
    isContinuityReset: Boolean = false
): Int {
    if (!isPrimaryScreen || scrollbackSize <= 0 || isContinuityReset) {
        return 0
    }
    if (currentOffset <= 0) {
        return 0
    }
    val delta = if (previousHistoryRows != null && newHistoryRows > previousHistoryRows) {
        (newHistoryRows - previousHistoryRows).toInt()
    } else {
        0
    }
    return (currentOffset + delta).coerceIn(0, scrollbackSize)
}

fun updateAnchoredScrollAccumulator(
    currentAccumulator: Float,
    previousHistoryRows: Long?,
    newHistoryRows: Long,
    scrollbackSize: Int,
    isPrimaryScreen: Boolean = true,
    isContinuityReset: Boolean = false
): Float {
    if (!isPrimaryScreen || scrollbackSize <= 0 || isContinuityReset) {
        return 0f
    }
    if (currentAccumulator <= 0f) {
        return 0f
    }
    val delta = if (previousHistoryRows != null && newHistoryRows > previousHistoryRows) {
        (newHistoryRows - previousHistoryRows).toFloat()
    } else {
        0f
    }
    return (currentAccumulator + delta).coerceIn(0f, scrollbackSize.toFloat())
}

class RenderGridStateEngine {

    companion object {
        const val MAX_SCROLLBACK_ROWS = 500
    }

    private var currentEpoch: String? = null
    private var lastStateSeq: Long = -1
    private var hasBaseline: Boolean = false
    private var columns: Int = 80
    private var rows: Int = 24
    private var cells: Array<Array<TerminalCell>> = Array(24) { Array(80) { TerminalCell() } }
    private val scrollbackBuffer = ArrayList<List<TerminalCell>>()
    private var lastHistoryRows: Long? = null
    private var lastRowSpaceRevision: Long? = null
    private var activeScreen: String = "primary"
    private var stylesMap: MutableMap<Int, Style> = mutableMapOf()
    private var currentCursor: Cursor = Cursor(0, 0, visible = true)
    private var terminalBg: String = "#1E1E1E"
    private var terminalFg: String = "#D4D4D4"
    private var syncState: SurfaceSyncState = SurfaceSyncState.EMPTY

    private val _screenState = MutableStateFlow(TerminalScreenState())
    val screenState: StateFlow<TerminalScreenState> = _screenState.asStateFlow()

    private fun paintSpansToGrid(
        targetCells: Array<Array<TerminalCell>>,
        targetRows: Int,
        targetCols: Int,
        spans: List<app.cmuxpocket.protocol.RowSpan>,
        styles: Map<Int, Style>,
        defaultFg: String,
        defaultBg: String
    ) {
        for (span in spans) {
            val r = span.row
            if (r !in 0 until targetRows) continue

            val style = styles[span.styleId]
            val fg = style?.foreground ?: defaultFg
            val bg = style?.background ?: defaultBg
            val bold = style?.bold ?: false
            val italic = style?.italic ?: false
            val underline = style?.underline ?: false

            var c = span.column
            var strIdx = 0
            val textLen = span.text.length

            while (strIdx < textLen && c < targetCols) {
                val codePoint = span.text.codePointAt(strIdx)
                val charCount = Character.charCount(codePoint)
                val glyph = span.text.substring(strIdx, strIdx + charCount)
                val charW = getCodePointWidth(codePoint)

                targetCells[r][c] = TerminalCell(
                    text = glyph,
                    foreground = fg,
                    background = bg,
                    bold = bold,
                    italic = italic,
                    underline = underline,
                    width = charW
                )

                if (charW == 2 && c + 1 < targetCols) {
                    targetCells[r][c + 1] = TerminalCell(
                        text = "",
                        foreground = fg,
                        background = bg,
                        width = 0
                    )
                }

                c += charW
                strIdx += charCount
            }
        }
    }

    @Synchronized
    fun applyFrame(envelope: RenderFrameEnvelope): FrameApplyResult {
        val frame = envelope.frame
        val result = applyFrameInternal(frame, envelope.traceId, envelope.receivedNanos)
        val applyNanos = System.nanoTime()
        NumericTraceHelper.logApply(
            traceId = envelope.traceId,
            surfaceId = frame.surfaceId,
            stateSeq = frame.stateSeq,
            result = result,
            receivedNanos = envelope.receivedNanos,
            appliedNanos = applyNanos
        )
        return result
    }

    @Synchronized
    fun applyFrame(frame: MobileTerminalRenderGridFrame): FrameApplyResult {
        return applyFrameInternal(frame, traceId = 0L, receivedNanos = 0L)
    }

    private fun applyFrameInternal(
        frame: MobileTerminalRenderGridFrame,
        traceId: Long,
        receivedNanos: Long
    ): FrameApplyResult {
        if (frame.format != "cmux.render-grid.v1") return FrameApplyResult.DUPLICATE

        val incomingActiveScreen = frame.activeScreen ?: "primary"
        val isFullBaseline = frame.full

        // Update styles table early
        for (style in frame.styles) {
            stylesMap[style.id] = style
        }

        // 1. Full snapshot establishes new baseline
        var isHistoryDiscontinuity = false
        if (isFullBaseline) {
            // Guard against stale full snapshots from older epochs/sequences
            if (hasBaseline && currentEpoch == frame.renderEpoch && frame.stateSeq < lastStateSeq) {
                return FrameApplyResult.DUPLICATE
            }

            // Check epoch continuity: epoch change resets local history
            if (currentEpoch != null && frame.renderEpoch != currentEpoch) {
                scrollbackBuffer.clear()
                lastHistoryRows = null
                lastRowSpaceRevision = null
            }


            val hasScrollbackPayload = frame.scrollbackSpans.isNotEmpty() || (frame.scrollbackRows != null && frame.scrollbackRows > 0)
            if (hasScrollbackPayload) {
                // Explicit full replay hydration with scrollback spans
                val sbRows = frame.scrollbackRows ?: (frame.scrollbackSpans.maxOfOrNull { it.row + 1 } ?: 0)
                val sbCols = frame.columns
                val sbCells = Array(sbRows) { Array(sbCols) { TerminalCell(background = frame.terminalBackground ?: "#1E1E1E", foreground = frame.terminalForeground ?: "#D4D4D4") } }
                paintSpansToGrid(
                    targetCells = sbCells,
                    targetRows = sbRows,
                    targetCols = sbCols,
                    spans = frame.scrollbackSpans,
                    styles = stylesMap,
                    defaultFg = frame.terminalForeground ?: "#D4D4D4",
                    defaultBg = frame.terminalBackground ?: "#1E1E1E"
                )
                scrollbackBuffer.clear()
                for (r in 0 until sbRows) {
                    scrollbackBuffer.add(sbCells[r].toList())
                }
                while (scrollbackBuffer.size > MAX_SCROLLBACK_ROWS) {
                    scrollbackBuffer.removeAt(0)
                }
                lastHistoryRows = frame.historyRows ?: sbRows.toLong()
                lastRowSpaceRevision = frame.rowSpaceRevision
            } else {
                // Full frame without scrollback payload: on primary screen, advance cached history from top rows of previous active grid
                if (incomingActiveScreen == "primary" && hasBaseline && cells.isNotEmpty()) {
                    if (frame.historyRows != null && lastHistoryRows != null) {
                        val delta = frame.historyRows - lastHistoryRows!!
                        if (delta in 1..rows.toLong()) {
                            val advanceCount = delta.toInt()
                            for (r in 0 until advanceCount) {
                                if (r < cells.size) {
                                    scrollbackBuffer.add(cells[r].toList())
                                }
                            }
                            while (scrollbackBuffer.size > MAX_SCROLLBACK_ROWS) {
                                scrollbackBuffer.removeAt(0)
                            }
                            lastHistoryRows = frame.historyRows
                            lastRowSpaceRevision = frame.rowSpaceRevision
                        } else if (delta > rows.toLong() || delta < 0L) {
                            // Unsafe continuity (lines skipped or history rewound): clear stale history to rely on replay
                            scrollbackBuffer.clear()
                            lastHistoryRows = frame.historyRows
                            lastRowSpaceRevision = frame.rowSpaceRevision
                            isHistoryDiscontinuity = true
                        } else {
                            // delta == 0L
                            lastHistoryRows = frame.historyRows
                            lastRowSpaceRevision = frame.rowSpaceRevision
                        }
                    } else {
                        if (frame.historyRows != null) lastHistoryRows = frame.historyRows
                        if (frame.rowSpaceRevision != null) lastRowSpaceRevision = frame.rowSpaceRevision
                    }
                } else {
                    if (frame.historyRows != null) lastHistoryRows = frame.historyRows
                    if (frame.rowSpaceRevision != null) lastRowSpaceRevision = frame.rowSpaceRevision
                }
            }

            activeScreen = incomingActiveScreen
            currentEpoch = frame.renderEpoch
            lastStateSeq = frame.stateSeq
            columns = frame.columns
            rows = frame.rows
            terminalBg = frame.terminalBackground ?: "#1E1E1E"
            terminalFg = frame.terminalForeground ?: "#D4D4D4"
            hasBaseline = true
            syncState = if (isHistoryDiscontinuity) SurfaceSyncState.AWAITING_REPLAY else SurfaceSyncState.HEALTHY

            cells = Array(rows) { Array(columns) { TerminalCell(background = terminalBg, foreground = terminalFg) } }
        } else {
            // STRICT RECOVERY BARRIER: If awaiting replay, reject all delta frames until baseline arrives
            if (syncState == SurfaceSyncState.AWAITING_REPLAY || !hasBaseline || frame.renderEpoch != currentEpoch) {
                syncState = SurfaceSyncState.AWAITING_REPLAY
                return FrameApplyResult.NEED_REPLAY
            }

            // Drop stale/duplicate frames
            if (frame.stateSeq <= lastStateSeq) {
                return FrameApplyResult.DUPLICATE
            }

            // Gap check: if delta missed intermediate frames, request replay and lock barrier
            if (frame.stateSeq > lastStateSeq + 1) {
                syncState = SurfaceSyncState.AWAITING_REPLAY
                return FrameApplyResult.NEED_REPLAY
            }

            if (frame.rowSpaceRevision != null) lastRowSpaceRevision = frame.rowSpaceRevision
            if (frame.historyRows != null) lastHistoryRows = frame.historyRows

            activeScreen = incomingActiveScreen
            lastStateSeq = frame.stateSeq
            syncState = SurfaceSyncState.HEALTHY
        }

        // 3. Clear requested rows (for delta frames)
        for (rowIdx in frame.clearedRows) {
            if (rowIdx in 0 until rows) {
                for (colIdx in 0 until columns) {
                    cells[rowIdx][colIdx] = TerminalCell(background = terminalBg, foreground = terminalFg)
                }
            }
        }

        // 4. Paint row spans with Unicode CodePoint and Surrogate awareness
        paintSpansToGrid(
            targetCells = cells,
            targetRows = rows,
            targetCols = columns,
            spans = frame.rowSpans,
            styles = stylesMap,
            defaultFg = terminalFg,
            defaultBg = terminalBg
        )

        // 5. Update cursor
        frame.cursor?.let {
            currentCursor = it
        }

        // Publish thread-safe immutable projection
        val immutableGrid = ArrayList<List<TerminalCell>>(rows)
        for (r in 0 until rows) {
            immutableGrid.add(cells[r].toList())
        }

        _screenState.value = TerminalScreenState(
            surfaceId = frame.surfaceId,
            columns = columns,
            rows = rows,
            grid = immutableGrid,
            scrollback = if (activeScreen == "primary") scrollbackBuffer.toList() else emptyList(),
            cursor = currentCursor,
            terminalBackground = terminalBg,
            terminalForeground = terminalFg,
            stateSeq = lastStateSeq,
            renderEpoch = currentEpoch,
            renderRevision = frame.renderRevision,
            historyRows = lastHistoryRows ?: 0L,
            rowSpaceRevision = lastRowSpaceRevision,
            activeScreen = activeScreen,
            hasBaseline = hasBaseline,
            syncState = syncState,
            traceId = traceId,
            receivedNanos = receivedNanos
        )

        return if (isFullBaseline) {
            if (isHistoryDiscontinuity) FrameApplyResult.NEED_REPLAY else FrameApplyResult.BASELINE_APPLIED
        } else {
            FrameApplyResult.DELTA_APPLIED
        }
    }

    @Synchronized
    fun markStale() {
        syncState = SurfaceSyncState.STALE
        _screenState.value = _screenState.value.copy(syncState = SurfaceSyncState.STALE)
    }

    @Synchronized
    fun markAwaitingReplay() {
        syncState = SurfaceSyncState.AWAITING_REPLAY
        _screenState.value = _screenState.value.copy(syncState = SurfaceSyncState.AWAITING_REPLAY)
    }

    @Synchronized
    fun reset() {
        hasBaseline = false
        currentEpoch = null
        lastStateSeq = -1
        lastHistoryRows = null
        lastRowSpaceRevision = null
        activeScreen = "primary"
        scrollbackBuffer.clear()
        stylesMap.clear()
        syncState = SurfaceSyncState.EMPTY
        cells = Array(rows) { Array(columns) { TerminalCell(background = terminalBg, foreground = terminalFg) } }
        _screenState.value = TerminalScreenState(
            columns = columns,
            rows = rows,
            terminalBackground = terminalBg,
            terminalForeground = terminalFg,
            hasBaseline = false,
            syncState = SurfaceSyncState.EMPTY,
            traceId = 0L,
            receivedNanos = 0L
        )
    }

    fun getDimensions(): Pair<Int, Int> = Pair(columns, rows)
}
