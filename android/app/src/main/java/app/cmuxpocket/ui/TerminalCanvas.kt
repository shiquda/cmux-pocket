package app.cmuxpocket.ui

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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


internal data class TerminalGeometry(
    val canvasWidth: Float,
    val canvasHeight: Float,
    val sourceCols: Int,
    val rows: Int,
    val cellWidth: Float,
    val rowHeight: Float,
    val baselineOffset: Float,
    val originX: Float,
    val originY: Float
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
    scaleFactor: Float,
    panOffset: Offset,
    textPaint: Paint
): TerminalGeometry {
    val leftPaddingPx = 4f
    val rightPaddingPx = 4f
    val availableWidth = (canvasWidth - leftPaddingPx - rightPaddingPx).coerceAtLeast(10f)

    val baseCellWidth = availableWidth / sourceCols.coerceAtLeast(1).toFloat()
    val cellWidth = baseCellWidth * scaleFactor
    val contentWidth = sourceCols * cellWidth

    // Exact horizontal pan bounds (minPanX brings rightmost column to right edge)
    val minPanX = minOf(0f, availableWidth - contentWidth)
    val maxPanX = 0f
    val clampedPanX = if (contentWidth <= availableWidth) 0f else panOffset.x.coerceIn(minPanX, maxPanX)

    // Derive strictly proportional typography from Maple Mono character metrics
    textPaint.textSize = 100f
    val referenceAdvance = textPaint.measureText("M").coerceAtLeast(1f)
    textPaint.textSize = (100f * cellWidth) / referenceAdvance

    val fontMetrics = textPaint.fontMetrics
    val rowHeight = (fontMetrics.bottom - fontMetrics.top) * 1.10f
    val baselineOffset = -fontMetrics.top
    val contentHeight = rows * rowHeight
    val bottomAlignmentOffset = (canvasHeight - contentHeight).coerceAtLeast(0f)

    val originX = leftPaddingPx + clampedPanX
    val originY = bottomAlignmentOffset + panOffset.y

    return TerminalGeometry(
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        sourceCols = sourceCols,
        rows = rows,
        cellWidth = cellWidth,
        rowHeight = rowHeight,
        baselineOffset = baselineOffset,
        originX = originX,
        originY = originY
    )
}

@Composable
fun TerminalCanvas(
    screenState: TerminalScreenState,
    onTap: () -> Unit,
    onTerminalScroll: (Double) -> Unit,
    modifier: Modifier = Modifier,
    userFontSizeSp: Float = 14.5f,
    themeHex: String = "#1E1E1E"
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var scaleFactor by remember { mutableStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasSizePx by remember { mutableStateOf(Size.Zero) }
    val lastDrawnTraceId = remember(screenState.surfaceId) { AtomicLong(0L) }
    val localScrollOffsets = remember { mutableStateMapOf<String, Int>() }
    val localScrollAccumulators = remember { mutableStateMapOf<String, Float>() }
    val lastSeenHistoryRows = remember { mutableStateMapOf<String, Long>() }
    val lastSeenEpoch = remember { mutableStateMapOf<String, String>() }

    var selectionState by remember { mutableStateOf<TerminalSelectionState?>(null) }
    var selectionBackgroundColor by remember { mutableStateOf<Int?>(null) }

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

    LaunchedEffect(surfaceId) {
        clearSelection()
    }

    BackHandler(enabled = selectionState != null) {
        clearSelection()
    }

    val effectiveGrid = if (effectiveOffset > 0 && screenState.scrollback.isNotEmpty()) {
        selectViewportRows(screenState.scrollback, screenState.grid, screenState.rows, effectiveOffset)
    } else {
        screenState.grid
    }

    val mapleTypeface = remember(context) {
        FontLoader.getMapleMonoTypeface(context)
    }

    val textPaint = remember(mapleTypeface) {
        Paint().apply {
            isAntiAlias = true
            typeface = mapleTypeface
        }
    }

    val bgPaint = remember {
        Paint().apply {
            style = Paint.Style.FILL
        }
    }

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

    val isZoomedOrPanned = scaleFactor != 1.0f || panOffset != Offset.Zero
    val currentOnTap by rememberUpdatedState(onTap)
    val currentEffectiveGrid by rememberUpdatedState(effectiveGrid)
    val currentSelectionState by rememberUpdatedState(selectionState)

    val isSelecting = selectionState != null
    val currentSelection = selectionState

    val activeGrid = currentSelection?.viewport?.grid ?: effectiveGrid
    val activeSourceCols = currentSelection?.viewport?.columns ?: calculateRenderSourceCols(effectiveGrid, screenState.columns)
    val activeRows = currentSelection?.viewport?.rows ?: maxOf(screenState.rows, 1)

    val geometry = remember(canvasSizePx, activeSourceCols, activeRows, scaleFactor, panOffset, mapleTypeface) {
        if (canvasSizePx.width > 0f && canvasSizePx.height > 0f) {
            computeTerminalGeometry(
                canvasWidth = canvasSizePx.width,
                canvasHeight = canvasSizePx.height,
                sourceCols = activeSourceCols,
                rows = activeRows,
                scaleFactor = scaleFactor,
                panOffset = panOffset,
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
            .onSizeChanged {
                canvasSizePx = Size(it.width.toFloat(), it.height.toFloat())
            }
            .pointerInput(surfaceId, isSelecting) {
                if (!isSelecting) {
                    detectTapGestures(
                        onDoubleTap = {
                            scaleFactor = 1.0f
                            panOffset = Offset.Zero
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
                            selectionState = beginWordSelection(viewport, geom.pointToCell(offset))
                        },
                        onTap = {
                            currentOnTap()
                        }
                    )
                } else {
                    detectTapGestures(
                        onTap = { offset ->
                            val currentSel = currentSelectionState ?: return@detectTapGestures
                            val geom = currentGeometry ?: return@detectTapGestures
                            if (!geom.containsGridPoint(offset) || !currentSel.contains(geom.pointToCell(offset))) {
                                clearSelection()
                            }
                        }
                    )
                }
            }
            .pointerInput(isSelecting) {
                if (!isSelecting) {
                    detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                        if (zoom != 1.0f || pan != Offset.Zero) {
                            scaleFactor = (scaleFactor * zoom).coerceIn(0.5f, 4.0f)
                            panOffset = Offset(
                                x = panOffset.x + pan.x,
                                y = (panOffset.y + pan.y).coerceIn(-4000f, 400f)
                            )
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val canvasWidth = size.width
            val canvasHeight = size.height
            if (canvasWidth <= 0 || canvasHeight <= 0) return@Canvas

            val activeGeom = computeTerminalGeometry(
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                sourceCols = activeSourceCols,
                rows = activeRows,
                scaleFactor = scaleFactor,
                panOffset = panOffset,
                textPaint = textPaint
            )

            if (activeGrid.isEmpty()) {
                textPaint.color = Color.parseColor("#888888")
                textPaint.textSize = 34f
                nativeCanvas.drawText("cmux Pocket Ready", 40f, 100f, textPaint)
                textPaint.textSize = 26f
                nativeCanvas.drawText("Connecting to session...", 40f, 150f, textPaint)
                return@Canvas
            }

            nativeCanvas.save()
            nativeCanvas.clipRect(0f, 0f, canvasWidth, canvasHeight)
            nativeCanvas.translate(activeGeom.originX, activeGeom.originY)

            // Step 1: Draw Background Cells
            for (r in 0 until minOf(activeRows, activeGrid.size)) {
                val rowCells = activeGrid[r]
                val y = r * activeGeom.rowHeight

                var c = 0
                while (c < rowCells.size) {
                    val cell = rowCells[c]
                    if (cell.width == 0) {
                        c++
                        continue
                    }

                    val cellWidthPx = activeGeom.cellWidth * cell.width
                    val x = c * activeGeom.cellWidth

                    val cellBg = try {
                        Color.parseColor(cell.background)
                    } catch (_: Exception) {
                        activeDefaultBgColor
                    }

                    if (cellBg != defaultBgColor) {
                        bgPaint.color = cellBg
                        nativeCanvas.drawRect(x, y, x + cellWidthPx, y + activeGeom.rowHeight, bgPaint)
                    }

                    c++
                }
            }

            // Step 2: Draw Selection Highlights (before text)
            if (currentSelection != null) {
                for (r in 0 until activeRows) {
                    val range = selectedColumnRange(currentSelection, r)
                    if (range != null) {
                        val startX = range.first * activeGeom.cellWidth
                        val endX = (range.last + 1) * activeGeom.cellWidth
                        val y = r * activeGeom.rowHeight
                        nativeCanvas.drawRect(startX, y, endX, y + activeGeom.rowHeight, selHighlightPaint)
                    }
                }
            }

            // Step 3: Draw Glyphs / Text
            for (r in 0 until minOf(activeRows, activeGrid.size)) {
                val rowCells = activeGrid[r]
                val y = r * activeGeom.rowHeight
                val textBaseline = y + activeGeom.baselineOffset

                var c = 0
                while (c < rowCells.size) {
                    val cell = rowCells[c]
                    if (cell.width == 0) {
                        c++
                        continue
                    }

                    val x = c * activeGeom.cellWidth

                    if (cell.text.isNotEmpty() && cell.text != " ") {
                        val cellFg = try {
                            Color.parseColor(cell.foreground)
                        } catch (_: Exception) {
                            Color.parseColor("#D4D4D4")
                        }

                        textPaint.color = cellFg
                        textPaint.isFakeBoldText = cell.bold
                        textPaint.isUnderlineText = cell.underline

                        nativeCanvas.drawText(cell.text, x, textBaseline, textPaint)
                    }

                    c++
                }
            }

            // Step 4: Draw Cursor (only when not selecting)
            if (currentSelection == null) {
                val cursor = screenState.cursor
                val cursorVisible = cursor.visible && effectiveOffset == 0
                if (cursorVisible && cursor.row in 0 until activeRows && cursor.column in 0 until activeSourceCols) {
                    val cursorX = cursor.column * activeGeom.cellWidth
                    val cursorY = cursor.row * activeGeom.rowHeight

                    bgPaint.color = Color.parseColor("#4CAF50")
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
                    .semantics { contentDescription = "Selection start handle" }
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
                    .semantics { contentDescription = "Selection end handle" }
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

            // Floating Copy Action
            val startCellTopY = currentGeom.originY + normStart.row * currentGeom.rowHeight
            val selectionMinX = minOf(startCellLeftX, endCellRightX)
            val selectionMaxX = maxOf(startCellLeftX, endCellRightX)

            val toolbarWidthPx = with(density) { 100.dp.toPx() }
            val toolbarHeightPx = with(density) { 48.dp.toPx() }
            val marginPx = with(density) { 8.dp.toPx() }

            val preferredAboveY = startCellTopY - toolbarHeightPx - marginPx
            val preferredBelowY = endCellBottomY + marginPx

            val toolbarY = if (preferredAboveY >= marginPx) {
                preferredAboveY
            } else if (preferredBelowY + toolbarHeightPx <= canvasSizePx.height - marginPx) {
                preferredBelowY
            } else {
                preferredAboveY.coerceIn(marginPx, (canvasSizePx.height - toolbarHeightPx - marginPx).coerceAtLeast(marginPx))
            }

            val centerX = (selectionMinX + selectionMaxX) / 2f
            val preferredX = centerX - (toolbarWidthPx / 2f)
            val toolbarX = preferredX.coerceIn(marginPx, (canvasSizePx.width - toolbarWidthPx - marginPx).coerceAtLeast(marginPx))

            Surface(
                modifier = Modifier
                    .offset { IntOffset(toolbarX.roundToInt(), toolbarY.roundToInt()) }
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, ComposeColor(0x55FFFFFF), RoundedCornerShape(24.dp))
                    .clickable {
                        val text = extractSelectedText(activeSelection)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("cmux terminal selection", text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        clearSelection()
                    },
                color = ComposeColor(0xEE222226),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = ComposeColor(0xFF00FF7F),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Copy",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ComposeColor.White
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = isZoomedOrPanned && selectionState == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, ComposeColor(0x55FFFFFF), RoundedCornerShape(20.dp))
                        .clickable {
                            scaleFactor = 1.0f
                            panOffset = Offset.Zero
                        },
                    color = ComposeColor(0xDD222226),
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FitScreen,
                            contentDescription = "Fit Width",
                            tint = ComposeColor(0xFF00FF7F),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Fit Width",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ComposeColor.White
                        )
                    }
                }
            }

            if (selectionState == null) {
                VerticalScrollJoystick(
                    onScrollLines = { deltaLines ->
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
                )
            }
        }
    }
}

@Composable
private fun VerticalScrollJoystick(
    onScrollLines: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var knobY by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }

    LaunchedEffect(held, knobY) {
        if (!held) return@LaunchedEffect
        while (held) {
            if (kotlin.math.abs(knobY) >= 0.08f) {
                // Joystick-up (knobY < 0) emits positive (older output);
                // Joystick-down (knobY > 0) emits negative (newer output).
                onScrollLines(-knobY.toDouble() * 3.0)
            }
            kotlinx.coroutines.delay(50)
        }
    }

    Box(
        modifier = modifier
            .width(48.dp)
            .height(148.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(ComposeColor(0xCC1A1A1E))
            .border(1.dp, ComposeColor(0x33FFFFFF), RoundedCornerShape(24.dp))
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
                .background(ComposeColor(0x33FFFFFF))
        )
        Box(
            modifier = Modifier
                .offset(y = (knobY * 46).dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(if (held) ComposeColor(0xFF64B5F6) else ComposeColor(0xFFE0E0E0))
        )
    }
}
