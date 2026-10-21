package app.cmuxpocket.ui

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TerminalDisplayPreference(
    val fontSizeSp: Float = 14.5f,
    val fitWidth: Boolean = false,
    val panX: Float = 0f,
    val panY: Float? = null,
    // The row that was current when the user deliberately moved the view vertically.
    // A later cursor move means the terminal should resume following live output.
    val panAnchorRow: Int? = null
) {
    fun normalized(): TerminalDisplayPreference = copy(
        fontSizeSp = if (fontSizeSp.isFinite()) fontSizeSp.coerceIn(2f, 48f) else 14.5f,
        panX = if (panX.isFinite()) panX else 0f,
        panY = panY?.takeIf { it.isFinite() },
        panAnchorRow = panAnchorRow?.takeIf { panY?.isFinite() == true && it >= 0 }
    )
}

private val displayJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun terminalDisplayKey(host: String, port: Int, surfaceId: String): String =
    displayJson.encodeToString(listOf(host, port.toString(), surfaceId))

internal fun decodeTerminalDisplay(raw: String?, defaultFontSizeSp: Float): TerminalDisplayPreference =
    try {
        if (raw == null) TerminalDisplayPreference(fontSizeSp = defaultFontSizeSp).normalized()
        else displayJson.decodeFromString<TerminalDisplayPreference>(raw).normalized()
    } catch (_: Exception) {
        TerminalDisplayPreference(fontSizeSp = defaultFontSizeSp).normalized()
    }

internal fun encodeTerminalDisplay(preference: TerminalDisplayPreference): String =
    displayJson.encodeToString(preference.normalized())

/** Cell sizing shared by drawing, selection, and viewport controls. */
internal fun terminalCellWidth(
    availableWidth: Float,
    sourceCols: Int,
    requestedFontSizePx: Float,
    referenceAdvance: Float,
    fitWidth: Boolean
): Float {
    val baseWidth = if (fitWidth) availableWidth / sourceCols.coerceAtLeast(1)
        else requestedFontSizePx * referenceAdvance.coerceAtLeast(1f) / 100f
    return baseWidth
}

internal fun transformTerminalDisplay(
    preference: TerminalDisplayPreference,
    geometry: TerminalGeometry,
    nextFontSizeSp: Float,
    scaleRatio: Float,
    centroid: Offset,
    pan: Offset,
    isZooming: Boolean,
    focusRow: Int?
): TerminalDisplayPreference {
    val contentWidth = geometry.sourceCols * geometry.cellWidth * scaleRatio
    val contentHeight = geometry.rows * geometry.rowHeight * scaleRatio
    val desiredX = centroid.x - (centroid.x - geometry.originX) * scaleRatio + pan.x
    val desiredY = centroid.y - (centroid.y - geometry.originY) * scaleRatio + pan.y
    val nextPanY = terminalPanY(geometry.canvasHeight, contentHeight, desiredY - (geometry.canvasHeight - contentHeight))
    val focusPanY = focusRow?.let {
        terminalFocusPanY(geometry.canvasHeight, contentHeight, geometry.rowHeight * scaleRatio, it)
    }
    val keepsFollowingCursor = focusPanY != null && kotlin.math.abs(nextPanY - focusPanY) < 0.5f
    val preserveVerticalPosition = pan.y == 0f && !isZooming
    return preference.copy(
        fontSizeSp = nextFontSizeSp,
        fitWidth = preference.fitWidth && !isZooming,
        panX = terminalPanX(geometry.canvasWidth - 8f, contentWidth, desiredX - 4f),
        panY = when {
            preserveVerticalPosition -> preference.panY
            keepsFollowingCursor -> null
            else -> nextPanY
        },
        panAnchorRow = when {
            preserveVerticalPosition -> preference.panAnchorRow
            keepsFollowingCursor -> null
            else -> focusRow
        }
    ).normalized()
}

/**
 * Damping applied to scrollback lines converted from a drag that the viewport can no longer
 * absorb. The viewport pan itself stays 1:1 so content tracks the finger; the damped part only
 * keeps an inward nudge at the boundary from jumping several history rows at once.
 */
internal const val BOUNDARY_SCROLL_DAMPING = 0.85f

/**
 * A vertical pan split into the part the viewport can still absorb and the part that chains into
 * scrollback: `pan.y` is the residual viewport movement, `scrollLines` the history rows the drag
 * requested past the boundary (positive = older rows).
 */
internal data class TerminalBoundaryPan(val pan: Offset, val scrollLines: Float)

/**
 * Splits [pan] at the vertical edge of the rendered grid.
 *
 * The viewport can only move while there is content left to reveal; a finger that keeps pulling at
 * the top (older) or bottom (newest) edge produces excess pixels, which become scrollback lines at
 * one line per [TerminalGeometry.rowHeight]. Zooming never chains because pinch is reserved for
 * font scaling.
 */
internal fun chainTerminalBoundaryPan(
    geometry: TerminalGeometry,
    pan: Offset,
    isZooming: Boolean,
    damping: Float = BOUNDARY_SCROLL_DAMPING
): TerminalBoundaryPan {
    if (isZooming || pan.y == 0f || geometry.rowHeight <= 0f) return TerminalBoundaryPan(pan, 0f)
    val contentHeight = geometry.rows * geometry.rowHeight
    val currentPanY = geometry.originY - (geometry.canvasHeight - contentHeight)
    val maxPanY = (contentHeight - geometry.canvasHeight).coerceAtLeast(0f)
    val nextPanY = (currentPanY + pan.y).coerceIn(0f, maxPanY)
    val consumed = nextPanY - currentPanY
    val excess = pan.y - consumed
    val scrollLines = if (kotlin.math.abs(excess) < 0.01f) 0f else excess / geometry.rowHeight * damping
    return TerminalBoundaryPan(pan.copy(y = consumed), scrollLines)
}

/** A manual vertical position applies only while the cursor remains at its original row. */
internal fun terminalViewportIsDetached(preference: TerminalDisplayPreference, liveCursorRow: Int?): Boolean =
    preference.panY != null && preference.panAnchorRow != null &&
        (liveCursorRow == null || preference.panAnchorRow == liveCursorRow)

internal fun terminalPanX(availableWidth: Float, contentWidth: Float, panX: Float): Float =
    panX.coerceIn(minOf(0f, availableWidth - contentWidth), 0f)

internal fun terminalPanY(canvasHeight: Float, contentHeight: Float, panY: Float): Float =
    panY.coerceIn(0f, (contentHeight - canvasHeight).coerceAtLeast(0f))

internal fun terminalFocusPanY(canvasHeight: Float, contentHeight: Float, rowHeight: Float, focusRow: Int): Float =
    terminalPanY(canvasHeight, contentHeight, contentHeight - (focusRow.coerceAtLeast(0) + 1) * rowHeight)
