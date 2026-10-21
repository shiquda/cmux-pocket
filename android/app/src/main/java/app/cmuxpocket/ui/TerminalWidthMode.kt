package app.cmuxpocket.ui

import kotlin.math.floor

/**
 * Per-surface state of the "lay out for this phone's width" mode.
 *
 * The phone never owns the PTY geometry: cmux takes the smallest viewport
 * reported by every attached client and the Mac pane can constrain it further.
 * [effectiveColumns]/[effectiveRows] therefore come from the last authoritative
 * full frame, never from the request.
 */
enum class PhoneWidthPhase {
    /** Follow the computer; this client reports no viewport. */
    OFF,

    /** A report was acknowledged but no authoritative frame has confirmed it yet. */
    REQUESTING,

    /** The host grid is now driven by this client (possibly clamped). */
    ACTIVE,

    /** The clear request is in flight; the host has not confirmed the restore yet. */
    RESTORING,

    /** The connected Gateway/host does not support real viewport resizing. */
    UNSUPPORTED,

    /** The host rejected the report. The previous grid is still in effect. */
    FAILED,
}

enum class PhoneWidthNotice {
    NONE,
    NO_BASELINE,
    REQUEST_FAILED,
    UNSUPPORTED,
    CLEAR_UNCONFIRMED,
}

data class PhoneWidthState(
    val phase: PhoneWidthPhase = PhoneWidthPhase.OFF,
    val basisColumns: Int? = null,
    val basisRows: Int? = null,
    val targetColumns: Int? = null,
    val targetRows: Int? = null,
    val effectiveColumns: Int? = null,
    val effectiveRows: Int? = null,
    val generation: Long = 0L,
    val notice: PhoneWidthNotice = PhoneWidthNotice.NONE,
) {
    /** Whether this client is (or is trying to be) the geometry source. */
    val enabled: Boolean
        get() = phase == PhoneWidthPhase.REQUESTING || phase == PhoneWidthPhase.ACTIVE

    val busy: Boolean
        get() = phase == PhoneWidthPhase.REQUESTING || phase == PhoneWidthPhase.RESTORING
}

/**
 * Font-derived metrics of the real terminal canvas.
 *
 * [cellWidthPx] is the advance of the user's chosen font, independent of
 * `fitWidth`, so the target column count does not feed back into itself.
 */
data class TerminalWidthMetrics(
    val availableWidthPx: Float,
    val canvasHeightPx: Float,
    val cellWidthPx: Float,
    val rowHeightPx: Float,
)

const val PHONE_WIDTH_MIN_COLUMNS = 20
const val PHONE_WIDTH_MAX_COLUMNS = 300
const val PHONE_WIDTH_MIN_ROWS = 5
const val PHONE_WIDTH_MAX_ROWS = 120

/**
 * Columns that fit the canvas at the current font, and rows that keep every row
 * the computer already shows: `max(phone rows, computer rows)`.
 */
fun computePhoneWidthTarget(
    metrics: TerminalWidthMetrics?,
    basisRows: Int?,
): Pair<Int, Int>? {
    if (metrics == null) return null
    val width = metrics.availableWidthPx
    val height = metrics.canvasHeightPx
    val cellWidth = metrics.cellWidthPx
    val rowHeight = metrics.rowHeightPx
    if (!width.isFinite() || !height.isFinite()) return null
    if (!cellWidth.isFinite() || !rowHeight.isFinite()) return null
    if (width <= 0f || height <= 0f || cellWidth <= 0f || rowHeight <= 0f) return null

    val columns = floor(width / cellWidth).toInt()
        .coerceIn(PHONE_WIDTH_MIN_COLUMNS, PHONE_WIDTH_MAX_COLUMNS)
    val phoneRows = floor(height / rowHeight).toInt()
    val rows = maxOf(phoneRows, basisRows ?: 0)
        .coerceIn(PHONE_WIDTH_MIN_ROWS, PHONE_WIDTH_MAX_ROWS)
    return columns to rows
}

/**
 * Merges an authoritative full frame into the mode state.
 *
 * `receivedAtNanos` and `confirmedAfterNanos` fence the frame against reports
 * that were captured before the host applied the resize.
 */
fun phoneWidthStateWithFrame(
    state: PhoneWidthState,
    columns: Int,
    rows: Int,
    receivedAtNanos: Long,
    confirmedAfterNanos: Long,
): PhoneWidthState {
    if (!state.enabled) return state
    if (receivedAtNanos < confirmedAfterNanos) return state
    return when (state.phase) {
        PhoneWidthPhase.REQUESTING -> state.copy(
            phase = PhoneWidthPhase.ACTIVE,
            effectiveColumns = columns,
            effectiveRows = rows,
        )
        PhoneWidthPhase.ACTIVE -> state.copy(
            effectiveColumns = columns,
            effectiveRows = rows,
        )
        else -> state
    }
}

/** Whether the effective grid already matches the requested target. */
fun phoneWidthTargetSatisfied(state: PhoneWidthState): Boolean {
    val targetColumns = state.targetColumns ?: return false
    val targetRows = state.targetRows ?: return false
    return state.effectiveColumns == targetColumns && state.effectiveRows == targetRows
}
