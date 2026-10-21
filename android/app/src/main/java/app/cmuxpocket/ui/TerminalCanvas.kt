package app.cmuxpocket.ui

import app.cmuxpocket.R
import androidx.compose.ui.res.stringResource

import app.cmuxpocket.engine.NumericTraceHelper
import app.cmuxpocket.engine.TerminalCell
import app.cmuxpocket.engine.TerminalScreenState
import app.cmuxpocket.engine.selectViewportRows
import app.cmuxpocket.engine.updateAnchoredScrollAccumulator
import app.cmuxpocket.engine.updateAnchoredScrollOffset
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import android.graphics.Picture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

object FontLoader {
    private var cachedTypeface: Typeface? = null

    fun getMapleMonoTypeface(context: Context): Typeface {
        cachedTypeface?.let { return it }
        return try {
            Typeface.createFromAsset(context.assets, "fonts/MapleMono-NF-Regular.ttf").also {
                cachedTypeface = it
            }
        } catch (_: Exception) {
            Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
    }
}

private val DEFAULT_FOREGROUND_COLOR = 0xFFD4D4D4.toInt()

/**
 * HarfBuzz shapes a batched run, so the font's ligature/contextual features have to stay off:
 * a cell is drawn alone today, and a single glyph can neither ligate nor kern. Without this a
 * run such as `->` would render as one ligature glyph instead of two cells.
 */
private const val CELL_ALIGNED_FONT_FEATURES = "\"liga\" 0, \"clig\" 0, \"calt\" 0, \"kern\" 0"

/**
 * True when [cell] can join a batched glyph run: exactly one printable ASCII glyph (a space
 * included — its advance is one cell, so runs may span word gaps) occupying one column. Anything
 * else (empty, wide, or multi-character cells) is drawn on its own so its position stays exact.
 */
private fun isBatchableGlyph(cell: TerminalCell): Boolean =
    cell.width == 1 && cell.text.length == 1 && cell.text[0] in ' '..'~'

/**
 * Memoizes hex -> ARGB resolution for terminal cells.
 *
 * The renderer resolves a colour for every visible cell on every frame, so a 200x50 viewport
 * issues roughly 10k [Color.parseColor] calls per frame; that parsing alone exceeds a 16ms frame
 * budget. A terminal palette holds a handful of distinct colours, so a small map collapses the
 * parses into hash lookups.
 */
internal class TerminalColorCache(
    private val parse: (String) -> Int? = { hex ->
        try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            null
        }
    }
) {
    private val resolved = HashMap<String, Int>(64)

    /** Resolves [hex], returning [fallback] for unparseable values (which are never cached). */
    fun resolve(hex: String, fallback: Int): Int {
        resolved[hex]?.let { return it }
        val parsed = parse(hex) ?: return fallback
        if (resolved.size >= MAX_ENTRIES) resolved.clear()
        resolved[hex] = parsed
        return parsed
    }

    fun clear() = resolved.clear()

    private companion object {
        const val MAX_ENTRIES = 256
    }
}


internal data class TerminalGeometry(
    val canvasWidth: Float,
    val canvasHeight: Float,
    val sourceCols: Int,
    val rows: Int,
    val cellWidth: Float,
    val rowHeight: Float,
    val baselineOffset: Float,
    val originX: Float,
    val originY: Float,
    val fontSizePx: Float,
    /**
     * Letter spacing, in em, that makes the typeface advance exactly [cellWidth] per glyph. The
     * grid stretches text to fit the viewport, while a shaped run uses the typeface's own
     * advances, so runs drawn without this correction drift away from the column grid.
     */
    val letterSpacingEm: Float = 0f
) {
    fun cellToPoint(row: Int, column: Int): Offset {
        return Offset(
            x = originX + column * cellWidth,
            y = originY + row * rowHeight
        )
    }

    fun pointToCell(point: Offset): TerminalCellPosition {
        val col = kotlin.math.floor((point.x - originX) / cellWidth).toInt().coerceIn(0, sourceCols - 1)
        val row = kotlin.math.floor((point.y - originY) / rowHeight).toInt().coerceIn(0, rows - 1)
        return TerminalCellPosition(row = row, column = col)
    }

    fun containsGridPoint(point: Offset): Boolean {
        val right = originX + sourceCols * cellWidth
        val bottom = originY + rows * rowHeight
        return point.x >= originX && point.x < right && point.y >= originY && point.y < bottom
    }
}

internal fun calculateRenderSourceCols(grid: List<List<TerminalCell>>, defaultCols: Int): Int {
    var occupiedCols = defaultCols
    for (row in grid) {
        for (index in row.indices.reversed()) {
            if (row[index].text.isNotBlank()) {
                occupiedCols = maxOf(occupiedCols, index + maxOf(row[index].width, 1))
                break
            }
        }
    }
    return maxOf(defaultCols, occupiedCols, 1)
}

internal fun computeTerminalGeometry(
    canvasWidth: Float,
    canvasHeight: Float,
    sourceCols: Int,
    rows: Int,
    requestedFontSizePx: Float,
    fitWidth: Boolean,
    panOffset: Offset,
    focusRow: Int?,
    textPaint: Paint
): TerminalGeometry {
    val leftPaddingPx = 4f
    val rightPaddingPx = 4f
    val availableWidth = (canvasWidth - leftPaddingPx - rightPaddingPx).coerceAtLeast(10f)

    textPaint.isFakeBoldText = false
    textPaint.isUnderlineText = false
    textPaint.letterSpacing = 0f
    textPaint.textSize = 100f
    val referenceAdvance = textPaint.measureText("M").coerceAtLeast(1f)
    val cellWidth = terminalCellWidth(
        availableWidth = availableWidth,
        sourceCols = sourceCols,
        requestedFontSizePx = requestedFontSizePx,
        referenceAdvance = referenceAdvance,
        fitWidth = fitWidth
    )
    val contentWidth = sourceCols * cellWidth

    // Exact horizontal pan bounds (minPanX brings rightmost column to right edge)
    val clampedPanX = terminalPanX(availableWidth, contentWidth, panOffset.x)

    // Derive strictly proportional typography from Maple Mono character metrics
    textPaint.textSize = (100f * cellWidth) / referenceAdvance

    // The rendered advance at that size is not the linearly extrapolated one: the grid is
    // stretched to the viewport, and hinting adjusts small glyph advances. Measure what the
    // typeface really produces and add the difference as letter spacing, so a run of glyphs
    // advances exactly one cell per glyph instead of drifting off the column grid.
    val renderedAdvance = textPaint.measureText("M").coerceAtLeast(0.01f)
    val letterSpacingEm = (cellWidth - renderedAdvance) / textPaint.textSize

    val fontMetrics = textPaint.fontMetrics
    val rowHeight = (fontMetrics.bottom - fontMetrics.top) * 1.10f
    val baselineOffset = -fontMetrics.top
    val contentHeight = rows * rowHeight
    val bottomAlignmentOffset = canvasHeight - contentHeight

    val originX = leftPaddingPx + clampedPanX
    val resolvedPanY = if (focusRow != null) terminalFocusPanY(canvasHeight, contentHeight, rowHeight, focusRow)
        else terminalPanY(canvasHeight, contentHeight, panOffset.y)
    val originY = bottomAlignmentOffset + resolvedPanY

    return TerminalGeometry(
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        sourceCols = sourceCols,
        rows = rows,
        cellWidth = cellWidth,
        rowHeight = rowHeight,
        baselineOffset = baselineOffset,
        originX = originX,
        originY = originY,
        fontSizePx = textPaint.textSize,
        letterSpacingEm = letterSpacingEm
    )
}

internal class TerminalRowCache {
    private var cachedPictures = arrayOfNulls<Picture>(128)
    private var cachedRows: Int = 0
    private var cachedCols: Int = 0
    private var cachedCellWidth: Float = 0f
    private var cachedRowHeight: Float = 0f
    private var cachedFontSizePx: Float = 0f
    private var cachedLetterSpacingEm: Float = 0f
    private var cachedOriginX: Float = 0f
    private var cachedOriginY: Float = 0f
    private var cachedDefaultBg: Int = 0
    private var cachedSurfaceId: String = ""
    private var cachedEpoch: String? = null
    private var cachedEffectiveOffset: Int = 0

    fun prepare(
        surfaceId: String,
        epoch: String?,
        rows: Int,
        cols: Int,
        cellWidth: Float,
        rowHeight: Float,
        fontSizePx: Float,
        letterSpacingEm: Float,
        originX: Float,
        originY: Float,
        defaultBg: Int,
        effectiveOffset: Int,
        dirtyRows: Set<Int>?
    ) {
        val geometryOrStyleChanged = rows != cachedRows ||
            cols != cachedCols ||
            cellWidth != cachedCellWidth ||
            rowHeight != cachedRowHeight ||
            fontSizePx != cachedFontSizePx ||
            letterSpacingEm != cachedLetterSpacingEm ||
            originX != cachedOriginX ||
            originY != cachedOriginY ||
            defaultBg != cachedDefaultBg ||
            surfaceId != cachedSurfaceId ||
            epoch != cachedEpoch ||
            effectiveOffset != cachedEffectiveOffset

        if (cachedPictures.size < rows) {
            val old = cachedPictures
            cachedPictures = arrayOfNulls(maxOf(rows, old.size * 2))
            System.arraycopy(old, 0, cachedPictures, 0, old.size)
        }

        if (geometryOrStyleChanged || dirtyRows == null) {
            invalidateAll()
            cachedRows = rows
            cachedCols = cols
            cachedCellWidth = cellWidth
            cachedRowHeight = rowHeight
            cachedFontSizePx = fontSizePx
            cachedLetterSpacingEm = letterSpacingEm
            cachedOriginX = originX
            cachedOriginY = originY
            cachedDefaultBg = defaultBg
            cachedSurfaceId = surfaceId
            cachedEpoch = epoch
            cachedEffectiveOffset = effectiveOffset
        } else {
            for (r in dirtyRows) {
                if (r in cachedPictures.indices) {
                    cachedPictures[r] = null
                }
            }
        }
    }

    fun get(r: Int): Picture? = if (r in cachedPictures.indices) cachedPictures[r] else null

    fun put(r: Int, picture: Picture) {
        if (r in cachedPictures.indices) {
            cachedPictures[r] = picture
        }
    }

    fun invalidateAll() {
        for (i in cachedPictures.indices) {
            cachedPictures[i] = null
        }
    }
}

@Composable
fun TerminalCanvas(
    screenState: TerminalScreenState,
    onTap: () -> Unit,
    onTerminalScroll: (Double) -> Unit,
    modifier: Modifier = Modifier,
    displayPreference: TerminalDisplayPreference = TerminalDisplayPreference(),
    onDisplayPreferenceChange: (TerminalDisplayPreference) -> Unit = {},
    themeHex: String = "#1E1E1E",
    onSendKey: (String) -> Unit = {},
    onClearScreen: () -> Unit = {},
    renderEnabled: Boolean = true
) {
    val context = LocalContext.current
    val readyLabel = stringResource(R.string.canvas_ready)
    val connectingLabel = stringResource(R.string.canvas_connecting)
    val startHandleLabel = stringResource(R.string.canvas_selection_start_handle)
    val endHandleLabel = stringResource(R.string.canvas_selection_end_handle)
    val density = LocalDensity.current
    val requestedFontSizePx = with(density) { displayPreference.fontSizeSp.sp.toPx() }
    val panOffset = Offset(displayPreference.panX, displayPreference.panY ?: 0f)
    val currentDisplayPreference by rememberUpdatedState(displayPreference)
    val currentOnDisplayPreferenceChange by rememberUpdatedState(onDisplayPreferenceChange)
    var canvasSizePx by remember { mutableStateOf(Size.Zero) }
    val lastDrawnTraceId = remember(screenState.surfaceId) { AtomicLong(0L) }
    val rowCache = remember { TerminalRowCache() }
    val localScrollOffsets = remember { mutableStateMapOf<String, Int>() }
    val localScrollAccumulators = remember { mutableStateMapOf<String, Float>() }
    val lastSeenHistoryRows = remember { mutableStateMapOf<String, Long>() }
    val lastSeenEpoch = remember { mutableStateMapOf<String, String>() }

    var selectionState by remember { mutableStateOf<TerminalSelectionState?>(null) }
    var selectionBackgroundColor by remember { mutableStateOf<Int?>(null) }
    var contextualBubbleOffset by remember { mutableStateOf<Offset?>(null) }

    val surfaceId = screenState.surfaceId
    val isConfirmedPrimary = screenState.activeScreen == "primary"
    val scrollbackSize = if (isConfirmedPrimary) screenState.scrollback.size else 0

    val prevHist = lastSeenHistoryRows[surfaceId]
    val prevEpoch = lastSeenEpoch[surfaceId]
    val isEpochReset = screenState.renderEpoch != null && prevEpoch != null && screenState.renderEpoch != prevEpoch

    val currentOffset = localScrollOffsets[surfaceId] ?: 0
    val currentAccum = localScrollAccumulators[surfaceId] ?: 0f

    val effectiveOffset = updateAnchoredScrollOffset(
        currentOffset = currentOffset,
        previousHistoryRows = prevHist,
        newHistoryRows = screenState.historyRows,
        scrollbackSize = scrollbackSize,
        isPrimaryScreen = isConfirmedPrimary,
        isContinuityReset = isEpochReset
    )

    val effectiveAccum = updateAnchoredScrollAccumulator(
        currentAccumulator = currentAccum,
        previousHistoryRows = prevHist,
        newHistoryRows = screenState.historyRows,
        scrollbackSize = scrollbackSize,
        isPrimaryScreen = isConfirmedPrimary,
        isContinuityReset = isEpochReset
    )

    SideEffect {
        if (effectiveOffset != currentOffset) {
            localScrollOffsets[surfaceId] = effectiveOffset
        }
        if (effectiveAccum != currentAccum) {
            localScrollAccumulators[surfaceId] = effectiveAccum
        }
        if (surfaceId.isNotBlank()) {
            lastSeenHistoryRows[surfaceId] = screenState.historyRows
            if (screenState.renderEpoch != null) {
                lastSeenEpoch[surfaceId] = screenState.renderEpoch
            }
        }
    }

    fun clearSelection() {
        selectionState = null
        selectionBackgroundColor = null
    }

    // The joystick and a drag that runs past the rendered edge both feed this one handler, so the
    // viewport offset, the knob position, and accumulated fractional lines can never disagree.
    fun applyScrollLines(deltaLines: Double) {
        if (deltaLines == 0.0) return
        if (isConfirmedPrimary && screenState.scrollback.isNotEmpty()) {
            val maxScroll = screenState.scrollback.size.toFloat()
            val curAcc = localScrollAccumulators[surfaceId] ?: 0f
            val newAcc = (curAcc + deltaLines.toFloat()).coerceIn(0f, maxScroll)
            val newOff = kotlin.math.round(newAcc).toInt().coerceIn(0, screenState.scrollback.size)
            localScrollAccumulators[surfaceId] = newAcc
            localScrollOffsets[surfaceId] = newOff
        } else {
            onTerminalScroll(deltaLines)
        }
    }
    val currentApplyScrollLines by rememberUpdatedState<(Double) -> Unit>({ applyScrollLines(it) })

    LaunchedEffect(surfaceId) {
        clearSelection()
    }

    BackHandler(enabled = selectionState != null) {
        clearSelection()
    }

    val effectiveGrid = remember(screenState.scrollback, screenState.grid, screenState.rows, effectiveOffset) {
        if (effectiveOffset > 0 && screenState.scrollback.isNotEmpty()) {
            selectViewportRows(screenState.scrollback, screenState.grid, screenState.rows, effectiveOffset)
        } else {
            screenState.grid
        }
    }

    val mapleTypeface = remember(context) {
        FontLoader.getMapleMonoTypeface(context)
    }

    val textPaint = remember(mapleTypeface) {
        Paint().apply {
            isAntiAlias = true
            typeface = mapleTypeface
            try {
                fontFeatureSettings = CELL_ALIGNED_FONT_FEATURES
            } catch (_: Exception) {
                // Older platforms may reject the CSS syntax; runs then fall back to the font's
                // default features, which the per-cell path never exercises anyway.
            }
        }
    }

    val bgPaint = remember {
        Paint().apply {
            style = Paint.Style.FILL
        }
    }

    val colorCache = remember { TerminalColorCache() }

    val cursorBlockColor = remember { Color.parseColor("#4CAF50") }

    val selHighlightPaint = remember {
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#4D64B5F6")
        }
    }

    val handleFillPaint = remember {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.parseColor("#4C90F6")
        }
    }

    val handleStrokePaint = remember {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#4C90F6")
        }
    }

    val defaultBgColor = remember(screenState.terminalBackground, themeHex) {
        try {
            Color.parseColor(themeHex)
        } catch (_: Exception) {
            try {
                Color.parseColor(screenState.terminalBackground)
            } catch (_: Exception) {
                Color.parseColor("#1E1E1E")
            }
        }
    }
    val activeDefaultBgColor = selectionBackgroundColor ?: defaultBgColor
    val currentDefaultBgColor by rememberUpdatedState(defaultBgColor)

    val currentOnTap by rememberUpdatedState(onTap)
    val currentEffectiveGrid by rememberUpdatedState(effectiveGrid)
    val currentSelectionState by rememberUpdatedState(selectionState)

    val isSelecting = selectionState != null
    val currentSelection = selectionState

    val activeGrid = currentSelection?.viewport?.grid ?: effectiveGrid
    val effectiveSourceCols = remember(effectiveGrid, screenState.columns) {
        calculateRenderSourceCols(effectiveGrid, screenState.columns)
    }
    val activeSourceCols = currentSelection?.viewport?.columns ?: effectiveSourceCols
    val activeRows = currentSelection?.viewport?.rows ?: maxOf(screenState.rows, 1)
    val liveCursorRow = if (screenState.cursor.visible && effectiveOffset == 0) {
        screenState.cursor.row.coerceIn(0, activeRows - 1)
    } else null
    val lastContentRow = remember(activeGrid) {
        activeGrid.indexOfLast { row -> row.any { it.text.isNotBlank() } }.coerceAtLeast(0)
    }
    val activeFocusRow = liveCursorRow ?: lastContentRow
    val focusRow = if (isSelecting || terminalViewportIsDetached(displayPreference, liveCursorRow)) null else activeFocusRow

    LaunchedEffect(displayPreference.panY, displayPreference.panAnchorRow, liveCursorRow) {
        if (displayPreference.panY != null && displayPreference.panAnchorRow != null &&
            liveCursorRow != null && displayPreference.panAnchorRow != liveCursorRow
        ) {
            currentOnDisplayPreferenceChange(displayPreference.copy(panY = null, panAnchorRow = null))
        }
    }

    val geometry = remember(canvasSizePx, activeSourceCols, activeRows, requestedFontSizePx, displayPreference.fitWidth, panOffset, focusRow, mapleTypeface) {
        if (canvasSizePx.width > 0f && canvasSizePx.height > 0f) {
            computeTerminalGeometry(
                canvasWidth = canvasSizePx.width,
                canvasHeight = canvasSizePx.height,
                sourceCols = activeSourceCols,
                rows = activeRows,
                requestedFontSizePx = requestedFontSizePx,
                fitWidth = displayPreference.fitWidth,
                panOffset = panOffset,
                focusRow = focusRow,
                textPaint = textPaint
            )
        } else {
            null
        }
    }
    val currentGeometry by rememberUpdatedState(geometry)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ComposeColor(activeDefaultBgColor))
            .pointerInput(surfaceId, isSelecting) {
                if (!isSelecting) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            contextualBubbleOffset = offset
                        },
                        onLongPress = { offset ->
                            val geom = currentGeometry ?: return@detectTapGestures
                            if (!geom.containsGridPoint(offset)) return@detectTapGestures
                            val currentGrid = currentEffectiveGrid
                            val viewport = freezeTerminalViewport(
                                grid = currentGrid,
                                columns = geom.sourceCols,
                                rows = geom.rows
                            )
                            selectionBackgroundColor = currentDefaultBgColor
                            currentOnDisplayPreferenceChange(currentDisplayPreference.copy(
                                panY = geom.originY - (geom.canvasHeight - geom.rows * geom.rowHeight)
                            ))
                            selectionState = beginWordSelection(viewport, geom.pointToCell(offset))
                            contextualBubbleOffset = offset
                        },
                        onTap = { offset ->
                            if (contextualBubbleOffset != null) {
                                contextualBubbleOffset = null
                            } else if (offset.y < canvasSizePx.height) {
                                currentOnTap()
                            }
                        }
                    )
                } else {
                    detectTapGestures(
                        onTap = { offset ->
                            contextualBubbleOffset = null
                            val currentSel = currentSelectionState ?: return@detectTapGestures
                            val geom = currentGeometry ?: return@detectTapGestures
                            if (!geom.containsGridPoint(offset) || !currentSel.contains(geom.pointToCell(offset))) {
                                clearSelection()
                            }
                        }
                    )
                }
            }
            .pointerInput(surfaceId, isSelecting, density) {
                if (!isSelecting) {
                    // The viewport is re-rendered on every gesture event: the transform is applied to
                    // the layout, not to a composited snapshot, so content that scrolls into view is
                    // painted in the same frame instead of staying blank until the gesture ends.
                    detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
                        if (zoom != 1.0f || pan != Offset.Zero) {
                            if (contextualBubbleOffset != null) {
                                contextualBubbleOffset = null
                            }
                            val geom = currentGeometry ?: return@detectTransformGestures
                            val pref = currentDisplayPreference
                            val nextFont = if (zoom != 1f) {
                                (with(density) { geom.fontSizePx.toSp().value } * zoom).coerceIn(2f, 48f)
                            } else pref.fontSizeSp
                            val ratio = if (zoom != 1f) with(density) { nextFont.sp.toPx() } / geom.fontSizePx else 1f
                            val boundaryPan = chainTerminalBoundaryPan(geom, pan, zoom != 1f)
                            currentOnDisplayPreferenceChange(transformTerminalDisplay(
                                preference = pref,
                                geometry = geom,
                                nextFontSizeSp = nextFont,
                                scaleRatio = ratio,
                                centroid = centroid,
                                pan = boundaryPan.pan,
                                isZooming = zoom != 1f,
                                focusRow = activeFocusRow
                            ))
                            currentApplyScrollLines(boundaryPan.scrollLines.toDouble())
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(bottom = 48.dp).onSizeChanged {
            canvasSizePx = Size(it.width.toFloat(), it.height.toFloat())
        }) {
            if (!renderEnabled) return@Canvas
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val canvasWidth = size.width
            val canvasHeight = size.height
            if (canvasWidth <= 0 || canvasHeight <= 0) return@Canvas

            val activeGeom = geometry?.takeIf { it.canvasWidth == canvasWidth && it.canvasHeight == canvasHeight }
                ?: computeTerminalGeometry(
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    sourceCols = activeSourceCols,
                    rows = activeRows,
                    requestedFontSizePx = requestedFontSizePx,
                    fitWidth = displayPreference.fitWidth,
                    panOffset = panOffset,
                    focusRow = focusRow,
                    textPaint = textPaint
                )
            // computeTerminalGeometry derives the paint's text size from the cell metrics; the
            // hoisted path has to re-apply it because the paint is shared across draws.
            textPaint.textSize = activeGeom.fontSizePx
            textPaint.letterSpacing = activeGeom.letterSpacingEm

            if (activeGrid.isEmpty()) {
                textPaint.color = Color.parseColor("#888888")
                textPaint.letterSpacing = 0f
                textPaint.textSize = 34f
                nativeCanvas.drawText(readyLabel, 40f, 100f, textPaint)
                textPaint.textSize = 26f
                nativeCanvas.drawText(connectingLabel, 40f, 150f, textPaint)
                return@Canvas
            }

            nativeCanvas.save()
            nativeCanvas.clipRect(0f, 0f, canvasWidth, canvasHeight)
            nativeCanvas.translate(activeGeom.originX, activeGeom.originY)

            val recordingWidth = canvasWidth.toInt().coerceAtLeast(1)

            if (currentSelection != null) {
                // Interactive selection: bypass cache so highlights render immediately
                rowCache.invalidateAll()
                for (r in 0 until minOf(activeRows, activeGrid.size)) {
                    val rowCells = activeGrid[r]
                    val y = r * activeGeom.rowHeight
                    renderRowBackgrounds(
                        nativeCanvas, rowCells, y, activeGeom.rowHeight, activeGeom.cellWidth,
                        colorCache, activeDefaultBgColor, defaultBgColor, bgPaint
                    )
                }

                for (r in 0 until activeRows) {
                    val range = selectedColumnRange(currentSelection, r)
                    if (range != null) {
                        val startX = range.first * activeGeom.cellWidth
                        val endX = (range.last + 1) * activeGeom.cellWidth
                        val y = r * activeGeom.rowHeight
                        nativeCanvas.drawRect(startX, y, endX, y + activeGeom.rowHeight, selHighlightPaint)
                    }
                }

                val runBuffer = StringBuilder(activeSourceCols.coerceAtLeast(1))
                for (r in 0 until minOf(activeRows, activeGrid.size)) {
                    val rowCells = activeGrid[r]
                    val y = r * activeGeom.rowHeight
                    renderRowGlyphs(
                        nativeCanvas, rowCells, y + activeGeom.baselineOffset, activeGeom.cellWidth,
                        colorCache, textPaint, runBuffer
                    )
                }
            } else {
                // Recording a row into a Picture only pays off when a small part of the grid
                // changed. When everything is dirty (baseline, viewport move, resize) recording and
                // replaying every row costs more than drawing the viewport once, so the cache is
                // only engaged for genuinely partial updates.
                val dirtyRows = screenState.dirtyRows
                val useRowCache = dirtyRows != null && dirtyRows.size <= (activeRows / 2).coerceAtLeast(1)
                if (useRowCache) {
                    rowCache.prepare(
                        surfaceId = surfaceId,
                        epoch = screenState.renderEpoch,
                        rows = activeRows,
                        cols = activeSourceCols,
                        cellWidth = activeGeom.cellWidth,
                        rowHeight = activeGeom.rowHeight,
                        fontSizePx = activeGeom.fontSizePx,
                        letterSpacingEm = activeGeom.letterSpacingEm,
                        originX = activeGeom.originX,
                        originY = activeGeom.originY,
                        defaultBg = defaultBgColor,
                        effectiveOffset = effectiveOffset,
                        dirtyRows = dirtyRows
                    )
                } else {
                    rowCache.invalidateAll()
                }

                val runBuffer = StringBuilder(activeSourceCols.coerceAtLeast(1))
                for (r in 0 until minOf(activeRows, activeGrid.size)) {
                    val cached = if (useRowCache) rowCache.get(r) else null
                    if (cached != null) {
                        nativeCanvas.drawPicture(cached)
                    } else {
                        val rowCells = activeGrid[r]
                        val y = r * activeGeom.rowHeight
                        if (!useRowCache) {
                            renderRowBackgrounds(
                                nativeCanvas, rowCells, y, activeGeom.rowHeight, activeGeom.cellWidth,
                                colorCache, activeDefaultBgColor, defaultBgColor, bgPaint
                            )
                            renderRowGlyphs(
                                nativeCanvas, rowCells, y + activeGeom.baselineOffset, activeGeom.cellWidth,
                                colorCache, textPaint, runBuffer
                            )
                        } else {
                            val picture = Picture()
                            val recordingHeight = (y + activeGeom.rowHeight).toInt().coerceAtLeast(1)
                            val pCanvas = picture.beginRecording(recordingWidth, recordingHeight)

                            renderRowBackgrounds(
                                pCanvas, rowCells, y, activeGeom.rowHeight, activeGeom.cellWidth,
                                colorCache, activeDefaultBgColor, defaultBgColor, bgPaint
                            )
                            renderRowGlyphs(
                                pCanvas, rowCells, y + activeGeom.baselineOffset, activeGeom.cellWidth,
                                colorCache, textPaint, runBuffer
                            )

                            picture.endRecording()
                            rowCache.put(r, picture)
                            nativeCanvas.drawPicture(picture)
                        }
                    }
                }
            }

            // Step 4: Draw Cursor (only when not selecting)
            if (currentSelection == null) {
                val cursor = screenState.cursor
                val cursorVisible = cursor.visible && effectiveOffset == 0
                if (cursorVisible && cursor.row in 0 until activeRows && cursor.column in 0 until activeSourceCols) {
                    val cursorX = cursor.column * activeGeom.cellWidth
                    val cursorY = cursor.row * activeGeom.rowHeight

                    bgPaint.color = cursorBlockColor
                    nativeCanvas.drawRect(cursorX, cursorY, cursorX + (activeGeom.cellWidth * 0.9f), cursorY + activeGeom.rowHeight, bgPaint)

                    if (cursor.row < activeGrid.size && cursor.column < activeGrid[cursor.row].size) {
                        val underCursor = activeGrid[cursor.row][cursor.column]
                        if (underCursor.text.isNotEmpty() && underCursor.text != " ") {
                            textPaint.color = Color.BLACK
                            textPaint.isFakeBoldText = true
                            nativeCanvas.drawText(underCursor.text, cursorX, cursorY + activeGeom.baselineOffset, textPaint)
                        }
                    }
                }
            }

            // Step 5: Draw Selection Handles visuals
            if (currentSelection != null) {
                val (normStart, normEnd) = currentSelection.normalizedBounds()
                val startX = normStart.column * activeGeom.cellWidth
                val startY = normStart.row * activeGeom.rowHeight
                val endColWidth = cellDisplayWidth(currentSelection.viewport.grid, normEnd.row, normEnd.column)
                val endX = (normEnd.column + endColWidth) * activeGeom.cellWidth
                val endY = normEnd.row * activeGeom.rowHeight

                val handleRadius = 14.dp.toPx()
                // Start handle: vertical line on left edge of start cell, pin droplet below-left
                nativeCanvas.drawLine(startX, startY, startX, startY + activeGeom.rowHeight, handleStrokePaint)
                nativeCanvas.drawCircle(startX - handleRadius / 2f, startY + activeGeom.rowHeight + handleRadius, handleRadius, handleFillPaint)

                // End handle: vertical line on right edge of end cell, pin droplet below-right
                nativeCanvas.drawLine(endX, endY, endX, endY + activeGeom.rowHeight, handleStrokePaint)
                nativeCanvas.drawCircle(endX + handleRadius / 2f, endY + activeGeom.rowHeight + handleRadius, handleRadius, handleFillPaint)
            }

            nativeCanvas.restore()

            // Numeric trace logging: only log for live frames when not frozen
            if (currentSelection == null && effectiveGrid.isNotEmpty()) {
                val currentTraceId = screenState.traceId
                if (currentTraceId > 0L && currentTraceId != lastDrawnTraceId.get()) {
                    lastDrawnTraceId.set(currentTraceId)
                    val drawNanos = System.nanoTime()
                    NumericTraceHelper.logDraw(
                        traceId = currentTraceId,
                        surfaceId = screenState.surfaceId,
                        stateSeq = screenState.stateSeq,
                        columns = screenState.columns,
                        rows = screenState.rows,
                        receivedNanos = screenState.receivedNanos,
                        drawNanos = drawNanos
                    )
                }
            }
        }

        val activeSelection = selectionState
        val currentGeom = geometry
        if (activeSelection != null && currentGeom != null) {
            val (normStart, normEnd) = activeSelection.normalizedBounds()
            val isReversed = activeSelection.startHandle > activeSelection.endHandle

            val endColWidth = cellDisplayWidth(activeSelection.viewport.grid, normEnd.row, normEnd.column)
            val startCellLeftX = currentGeom.originX + normStart.column * currentGeom.cellWidth
            val startCellBottomY = currentGeom.originY + (normStart.row + 1) * currentGeom.rowHeight

            val endCellRightX = currentGeom.originX + (normEnd.column + endColWidth) * currentGeom.cellWidth
            val endCellBottomY = currentGeom.originY + (normEnd.row + 1) * currentGeom.rowHeight

            val handle1AnchorX = if (!isReversed) startCellLeftX else endCellRightX
            val handle1AnchorY = if (!isReversed) startCellBottomY else endCellBottomY

            val handle2AnchorX = if (!isReversed) endCellRightX else startCellLeftX
            val handle2AnchorY = if (!isReversed) endCellBottomY else startCellBottomY

            val touchTargetHalfPx = with(density) { 24.dp.toPx() }
            val currentHandle1Anchor by rememberUpdatedState(Offset(handle1AnchorX, handle1AnchorY))
            val currentHandle2Anchor by rememberUpdatedState(Offset(handle2AnchorX, handle2AnchorY))

            // Handle 1 Drag Target (controls startHandle)
            var drag1Accumulator by remember { mutableStateOf(Offset.Zero) }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (handle1AnchorX - touchTargetHalfPx).roundToInt(),
                            (handle1AnchorY - touchTargetHalfPx).roundToInt()
                        )
                    }
                    .size(48.dp)
                    .semantics { contentDescription = startHandleLabel }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                val anchor = currentHandle1Anchor
                                drag1Accumulator = Offset(anchor.x, anchor.y - (currentGeometry?.rowHeight ?: currentGeom.rowHeight) / 2f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                drag1Accumulator += dragAmount
                                val targetCell = currentGeometry?.pointToCell(drag1Accumulator)
                                if (targetCell != null) {
                                    selectionState = selectionState?.moveStart(targetCell)
                                }
                            }
                        )
                    }
            )

            // Handle 2 Drag Target (controls endHandle)
            var drag2Accumulator by remember { mutableStateOf(Offset.Zero) }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (handle2AnchorX - touchTargetHalfPx).roundToInt(),
                            (handle2AnchorY - touchTargetHalfPx).roundToInt()
                        )
                    }
                    .size(48.dp)
                    .semantics { contentDescription = endHandleLabel }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                val anchor = currentHandle2Anchor
                                drag2Accumulator = Offset(anchor.x, anchor.y - (currentGeometry?.rowHeight ?: currentGeom.rowHeight) / 2f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                drag2Accumulator += dragAmount
                                val targetCell = currentGeometry?.pointToCell(drag2Accumulator)
                                if (targetCell != null) {
                                    selectionState = selectionState?.moveEnd(targetCell)
                                }
                            }
                        )
                    }
            )

        }

        val bubblePos = contextualBubbleOffset
        if (bubblePos != null) {
            val bubbleWidthPx = with(density) { 190.dp.toPx() }
            val bubbleHeightPx = with(density) { 180.dp.toPx() }
            // The breadcrumb pill floats over the top of the canvas; keep the bubble clear of it
            // so the two never overlap (status bar inset + pill height + margins).
            val floatingChromePx = WindowInsets.statusBars.getTop(density) + with(density) { 60.dp.toPx() }
            val lowestTop = (canvasSizePx.height - bubbleHeightPx - 12f).coerceAtLeast(floatingChromePx)
            val clampedX = (bubblePos.x - bubbleWidthPx / 2f).coerceIn(12f, (canvasSizePx.width - bubbleWidthPx - 12f).coerceAtLeast(12f))
            val clampedY = (bubblePos.y - bubbleHeightPx - 16f).let { y ->
                if (y >= floatingChromePx) y
                else (bubblePos.y + 16f).coerceIn(floatingChromePx, lowestTop)
            }

            ContextualActionBubble(
                offset = IntOffset(clampedX.roundToInt(), clampedY.roundToInt()),
                hasSelection = selectionState != null,
                onCopy = {
                    val currentSel = selectionState
                    val textToCopy = if (currentSel != null) {
                        extractSelectedText(currentSel)
                    } else {
                        currentEffectiveGrid.joinToString("\n") { row ->
                            row.map { it.text }.joinToString("").trimEnd()
                        }.trim()
                    }
                    if (textToCopy.isNotEmpty()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("cmux terminal content", textToCopy)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, context.getString(R.string.canvas_copied_selection), Toast.LENGTH_SHORT).show()
                    }
                    clearSelection()
                    contextualBubbleOffset = null
                },
                onPaste = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val pasteText = clip.getItemAt(0).text?.toString().orEmpty()
                        if (pasteText.isNotEmpty()) {
                            onSendKey(pasteText)
                            Toast.makeText(context, context.getString(R.string.canvas_pasted), Toast.LENGTH_SHORT).show()
                        }
                    }
                    contextualBubbleOffset = null
                },
                onInterrupt = {
                    onSendKey("\u0003")
                    Toast.makeText(context, context.getString(R.string.canvas_interrupt_sent), Toast.LENGTH_SHORT).show()
                    contextualBubbleOffset = null
                },
                onClear = {
                    onClearScreen()
                    onSendKey("\u000c")
                    Toast.makeText(context, context.getString(R.string.canvas_cleared), Toast.LENGTH_SHORT).show()
                    clearSelection()
                    contextualBubbleOffset = null
                },
                onDismiss = {
                    contextualBubbleOffset = null
                }
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selectionState == null) {
                VerticalScrollJoystick(
                    onScrollLines = { deltaLines -> applyScrollLines(deltaLines) },
                    historyFraction = if (scrollbackSize > 0) {
                        effectiveOffset.toFloat() / scrollbackSize
                    } else 0f
                )
            }
        }
        TerminalTextControls(
            preference = displayPreference,
            renderedFontSizeSp = geometry?.let { with(density) { it.fontSizePx.toSp().value } } ?: displayPreference.fontSizeSp,
            enabled = !isSelecting,
            onChange = onDisplayPreferenceChange,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun TerminalTextControls(
    preference: TerminalDisplayPreference,
    renderedFontSizeSp: Float,
    enabled: Boolean,
    onChange: (TerminalDisplayPreference) -> Unit,
    modifier: Modifier = Modifier
) {
    val canDecrease = enabled && !preference.fitWidth && preference.fontSizeSp > 2f
    val canIncrease = enabled && !preference.fitWidth && preference.fontSizeSp < 48f
    val decreaseLabel = stringResource(R.string.canvas_decrease_text_size)
    val increaseLabel = stringResource(R.string.canvas_increase_text_size)
    val theme = CmuxTheme.colors
    Surface(modifier = modifier, color = theme.containerBg) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        onChange(preference.copy(fontSizeSp = (preference.fontSizeSp - 1f).coerceAtLeast(2f), panX = 0f, panY = null))
                    },
                    enabled = canDecrease,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = decreaseLabel }
                ) { Text("A−", color = if (canDecrease) theme.onContainer else theme.onContainerMuted) }
                Text(stringResource(R.string.font_size_sp_value, ((renderedFontSizeSp * 10).roundToInt() / 10f).toString()), color = theme.onContainerMuted, fontSize = 12.sp)
                TextButton(
                    onClick = {
                        onChange(preference.copy(fontSizeSp = (preference.fontSizeSp + 1f).coerceAtMost(48f), panX = 0f, panY = null))
                    },
                    enabled = canIncrease,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = increaseLabel }
                ) { Text("A+", color = if (canIncrease) theme.onContainer else theme.onContainerMuted) }
            }
            TextButton(
                onClick = { onChange(preference.copy(fitWidth = !preference.fitWidth, panX = 0f, panY = null)) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.FitScreen, contentDescription = null, tint = theme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (preference.fitWidth) stringResource(R.string.canvas_readable) else stringResource(R.string.canvas_fit_width), color = theme.onContainer, fontSize = 12.sp)
            }
        }
    }
}

// Lines scrolled per 50 ms tick when the joystick is at full deflection.
private const val JOYSTICK_LINES_PER_TICK = 4.8

@Composable
private fun VerticalScrollJoystick(
    onScrollLines: (Double) -> Unit,
    historyFraction: Float = 0f,
    modifier: Modifier = Modifier
) {
    var knobY by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val theme = CmuxTheme.colors

    LaunchedEffect(held, knobY) {
        if (!held) return@LaunchedEffect
        while (held) {
            if (kotlin.math.abs(knobY) >= 0.08f) {
                // Joystick-up (knobY < 0) emits positive (older output);
                // Joystick-down (knobY > 0) emits negative (newer output).
                onScrollLines(-knobY.toDouble() * JOYSTICK_LINES_PER_TICK)
            }
            kotlinx.coroutines.delay(50)
        }
    }

    Box(
        modifier = modifier
            .width(48.dp)
            .height(148.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(theme.islandBg)
            .border(1.dp, theme.islandBorder, RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { held = true },
                    onDragEnd = {
                        held = false
                        knobY = 0f
                    },
                    onDragCancel = {
                        held = false
                        knobY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val range = size.height / 2f
                        if (range > 0f) {
                            knobY = ((knobY * range) + dragAmount.y).coerceIn(-range, range) / range
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight(0.72f)
                .clip(RoundedCornerShape(2.dp))
                .background(theme.controlBorder)
        ) {
            // The fill mirrors the history offset a drag or the joystick produced, so the control
            // shows where the viewport sits in the scrollback instead of only reacting to touch.
            val filled = historyFraction.coerceIn(0f, 1f)
            if (filled > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(filled)
                        .background(theme.primary.copy(alpha = 0.55f))
                )
            }
        }
        Box(
            modifier = Modifier
                .offset(y = (knobY * 46).dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(if (held) theme.primary else theme.onSurfaceVariant)
        )
    }
}

private fun renderRowBackgrounds(
    canvas: android.graphics.Canvas,
    rowCells: List<TerminalCell>,
    y: Float,
    rowHeight: Float,
    cellWidth: Float,
    colorCache: TerminalColorCache,
    activeDefaultBgColor: Int,
    defaultBgColor: Int,
    bgPaint: Paint
) {
    var c = 0
    while (c < rowCells.size) {
        val cell = rowCells[c]
        if (cell.width == 0) {
            c++
            continue
        }

        val cellBg = colorCache.resolve(cell.background, activeDefaultBgColor)
        if (cellBg == defaultBgColor) {
            c++
            continue
        }

        var span = cell.width
        var end = c + 1
        while (end < rowCells.size) {
            val next = rowCells[end]
            if (next.width == 0) break
            if (colorCache.resolve(next.background, activeDefaultBgColor) != cellBg) break
            span += next.width
            end++
        }

        val x = c * cellWidth
        bgPaint.color = cellBg
        canvas.drawRect(x, y, x + span * cellWidth, y + rowHeight, bgPaint)

        c = end
    }
}

private fun renderRowGlyphs(
    canvas: android.graphics.Canvas,
    rowCells: List<TerminalCell>,
    textBaseline: Float,
    cellWidth: Float,
    colorCache: TerminalColorCache,
    textPaint: Paint,
    runBuffer: StringBuilder
) {
    var c = 0
    while (c < rowCells.size) {
        val cell = rowCells[c]
        if (!isBatchableGlyph(cell)) {
            if (cell.width != 0 && cell.text.isNotEmpty() && cell.text != " ") {
                textPaint.color = colorCache.resolve(cell.foreground, DEFAULT_FOREGROUND_COLOR)
                textPaint.isFakeBoldText = cell.bold
                textPaint.isUnderlineText = cell.underline
                canvas.drawText(
                    cell.text,
                    c * cellWidth,
                    textBaseline,
                    textPaint
                )
            }
            c++
            continue
        }

        var end = c + 1
        var inkEnd = if (cell.text[0] == ' ') c else c + 1
        while (end < rowCells.size) {
            val next = rowCells[end]
            if (!isBatchableGlyph(next) ||
                next.foreground != cell.foreground ||
                next.bold != cell.bold ||
                next.underline != cell.underline
            ) {
                break
            }
            if (next.text[0] != ' ') inkEnd = end + 1
            end++
        }

        val x = c * cellWidth
        val runLength = inkEnd - c
        if (runLength <= 0) {
            c = end
            continue
        }

        textPaint.color = colorCache.resolve(cell.foreground, DEFAULT_FOREGROUND_COLOR)
        textPaint.isFakeBoldText = cell.bold
        textPaint.isUnderlineText = cell.underline

        if (runLength == 1) {
            canvas.drawText(cell.text, x, textBaseline, textPaint)
        } else {
            runBuffer.setLength(0)
            for (index in c until inkEnd) runBuffer.append(rowCells[index].text)
            canvas.drawText(runBuffer.toString(), x, textBaseline, textPaint)
        }

        c = end
    }
}
