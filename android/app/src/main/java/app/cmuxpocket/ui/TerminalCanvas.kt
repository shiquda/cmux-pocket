package app.cmuxpocket.ui

import app.cmuxpocket.engine.TerminalCell
import app.cmuxpocket.engine.TerminalScreenState
import app.cmuxpocket.engine.selectViewportRows
import app.cmuxpocket.engine.updateAnchoredScrollAccumulator
import app.cmuxpocket.engine.NumericTraceHelper
import app.cmuxpocket.engine.updateAnchoredScrollOffset
import java.util.concurrent.atomic.AtomicLong
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
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
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


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

fun extractScreenText(grid: List<List<TerminalCell>>): String {
    val sb = StringBuilder()
    for (row in grid) {
        val rowText = StringBuilder()
        for (cell in row) {
            if (cell.width > 0) {
                rowText.append(cell.text)
            }
        }
        sb.append(rowText.toString().trimEnd()).append("\n")
    }
    return sb.toString().trimEnd()
}

fun extractScreenText(screenState: TerminalScreenState): String {
    return extractScreenText(screenState.grid)
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
    var scaleFactor by remember { mutableStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasWidthPx by remember { mutableStateOf(0f) }
    val lastDrawnTraceId = remember(screenState.surfaceId) { AtomicLong(0L) }
    val localScrollOffsets = remember { mutableStateMapOf<String, Int>() }
    val localScrollAccumulators = remember { mutableStateMapOf<String, Float>() }
    val lastSeenHistoryRows = remember { mutableStateMapOf<String, Long>() }
    val lastSeenEpoch = remember { mutableStateMapOf<String, String>() }

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

    val isZoomedOrPanned = scaleFactor != 1.0f || panOffset != Offset.Zero
    val currentOnTap by rememberUpdatedState(onTap)
    val currentEffectiveGrid by rememberUpdatedState(effectiveGrid)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ComposeColor(defaultBgColor))
            .onSizeChanged { canvasWidthPx = it.width.toFloat() }
            .pointerInput(surfaceId) {
                detectTapGestures(
                    onDoubleTap = {
                        scaleFactor = 1.0f
                        panOffset = Offset.Zero
                    },
                    onLongPress = {
                        val text = extractScreenText(currentEffectiveGrid)
                        if (text.isNotBlank()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("cmux terminal", text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Terminal screen copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onTap = {
                        currentOnTap()
                    }
                )
            }
            .pointerInput(Unit) {
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
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val canvasWidth = size.width
            val canvasHeight = size.height
            if (canvasWidth <= 0 || canvasHeight <= 0) return@Canvas

            var occupiedCols = screenState.columns
            for (row in effectiveGrid) {
                for (index in row.indices.reversed()) {
                    if (row[index].text.isNotBlank()) {
                        occupiedCols = maxOf(occupiedCols, index + maxOf(row[index].width, 1))
                        break
                    }
                }
            }

            val sourceCols = maxOf(screenState.columns, occupiedCols, 1)
            val rows = maxOf(screenState.rows, 1)

            val leftPaddingPx = 4f
            val rightPaddingPx = 4f
            val availableWidth = (canvasWidth - leftPaddingPx - rightPaddingPx).coerceAtLeast(10f)

            val baseCellWidth = availableWidth / sourceCols.toFloat()
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

            if (effectiveGrid.isEmpty()) {
                textPaint.color = Color.parseColor("#888888")
                textPaint.textSize = 34f
                nativeCanvas.drawText("cmux Pocket Ready", 40f, 100f, textPaint)
                textPaint.textSize = 26f
                nativeCanvas.drawText("Connecting to session...", 40f, 150f, textPaint)
                return@Canvas
            }

            nativeCanvas.save()
            nativeCanvas.clipRect(0f, 0f, canvasWidth, canvasHeight)
            nativeCanvas.translate(leftPaddingPx + clampedPanX, bottomAlignmentOffset + panOffset.y)


            // Step 1: Draw Background Cells
            for (r in 0 until minOf(rows, effectiveGrid.size)) {
                val rowCells = effectiveGrid[r]
                val y = r * rowHeight

                var c = 0
                while (c < rowCells.size) {
                    val cell = rowCells[c]
                    if (cell.width == 0) {
                        c++
                        continue
                    }

                    val cellWidthPx = cellWidth * cell.width
                    val x = c * cellWidth

                    val cellBg = try {
                        Color.parseColor(cell.background)
                    } catch (_: Exception) {
                        defaultBgColor
                    }

                    if (cellBg != defaultBgColor) {
                        bgPaint.color = cellBg
                        nativeCanvas.drawRect(x, y, x + cellWidthPx, y + rowHeight, bgPaint)
                    }

                    c++
                }
            }

            // Step 2: Draw Glyphs / Text
            for (r in 0 until minOf(rows, effectiveGrid.size)) {
                val rowCells = effectiveGrid[r]
                val y = r * rowHeight
                val textBaseline = y + baselineOffset

                var c = 0
                while (c < rowCells.size) {
                    val cell = rowCells[c]
                    if (cell.width == 0) {
                        c++
                        continue
                    }

                    val x = c * cellWidth

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

            // Step 3: Draw Cursor
            val cursor = screenState.cursor
            val cursorVisible = cursor.visible && effectiveOffset == 0
            if (cursorVisible && cursor.row in 0 until rows && cursor.column in 0 until sourceCols) {
                val cursorX = cursor.column * cellWidth
                val cursorY = cursor.row * rowHeight

                bgPaint.color = Color.parseColor("#4CAF50")
                nativeCanvas.drawRect(cursorX, cursorY, cursorX + (cellWidth * 0.9f), cursorY + rowHeight, bgPaint)

                if (cursor.row < effectiveGrid.size && cursor.column < effectiveGrid[cursor.row].size) {
                    val underCursor = effectiveGrid[cursor.row][cursor.column]
                    if (underCursor.text.isNotEmpty() && underCursor.text != " ") {
                        textPaint.color = Color.BLACK
                        textPaint.isFakeBoldText = true
                        nativeCanvas.drawText(underCursor.text, cursorX, cursorY + baselineOffset, textPaint)
                    }
                }
            }
            nativeCanvas.restore()

            if (effectiveGrid.isNotEmpty()) {
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
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = isZoomedOrPanned,
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
