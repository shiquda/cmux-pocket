package app.cmuxpocket.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.cmuxpocket.engine.NumericTraceHelper
import app.cmuxpocket.engine.TerminalCell
import app.cmuxpocket.engine.TerminalScreenState
import java.util.concurrent.atomic.AtomicLong

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
internal fun rememberTerminalCanvasRenderer(
    typeface: Typeface
): TerminalCanvasRenderer {
    return remember(typeface) {
        TerminalCanvasRenderer(typeface)
    }
}

internal class TerminalCanvasRenderer(
    val typeface: Typeface
) {
    val textPaint: Paint = Paint().apply {
        isAntiAlias = true
        this.typeface = this@TerminalCanvasRenderer.typeface
        try {
            fontFeatureSettings = CELL_ALIGNED_FONT_FEATURES
        } catch (_: Exception) {
            // Older platforms may reject the CSS syntax; runs then fall back to the font's
            // default features, which the per-cell path never exercises anyway.
        }
    }

    val bgPaint: Paint = Paint().apply {
        style = Paint.Style.FILL
    }

    val colorCache: TerminalColorCache = TerminalColorCache()

    val cursorBlockColor: Int = Color.parseColor("#4CAF50")

    val selHighlightPaint: Paint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4D64B5F6")
    }

    val handleFillPaint: Paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#4C90F6")
    }

    val handleStrokePaint: Paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#4C90F6")
    }

    val rowCache: TerminalRowCache = TerminalRowCache()

    fun render(
        canvas: android.graphics.Canvas,
        canvasWidth: Float,
        canvasHeight: Float,
        geometry: TerminalGeometry,
        activeGrid: List<List<TerminalCell>>,
        activeRows: Int,
        activeSourceCols: Int,
        screenState: TerminalScreenState,
        selection: TerminalSelectionState?,
        effectiveOffset: Int,
        defaultBgColor: Int,
        activeDefaultBgColor: Int,
        handleRadiusPx: Float,
        readyLabel: String,
        connectingLabel: String,
        effectiveGridNotEmpty: Boolean,
        lastDrawnTraceId: AtomicLong
    ) {
        // computeTerminalGeometry derives the paint's text size from the cell metrics; the
        // hoisted path has to re-apply it because the paint is shared across draws.
        textPaint.textSize = geometry.fontSizePx
        textPaint.letterSpacing = geometry.letterSpacingEm

        if (activeGrid.isEmpty()) {
            renderEmptyState(canvas, readyLabel, connectingLabel)
            return
        }

        canvas.save()
        canvas.clipRect(0f, 0f, canvasWidth, canvasHeight)
        canvas.translate(geometry.originX, geometry.originY)

        if (selection != null) {
            renderSelectedRows(
                canvas = canvas,
                geometry = geometry,
                activeGrid = activeGrid,
                activeRows = activeRows,
                activeSourceCols = activeSourceCols,
                selection = selection,
                activeDefaultBgColor = activeDefaultBgColor,
                defaultBgColor = defaultBgColor
            )
            renderSelectionHandles(
                canvas = canvas,
                geometry = geometry,
                selection = selection,
                handleRadiusPx = handleRadiusPx
            )
        } else {
            renderNormalRows(
                canvas = canvas,
                canvasWidth = canvasWidth,
                geometry = geometry,
                activeGrid = activeGrid,
                activeRows = activeRows,
                activeSourceCols = activeSourceCols,
                screenState = screenState,
                effectiveOffset = effectiveOffset,
                activeDefaultBgColor = activeDefaultBgColor,
                defaultBgColor = defaultBgColor
            )
            renderCursor(
                canvas = canvas,
                geometry = geometry,
                activeGrid = activeGrid,
                activeRows = activeRows,
                activeSourceCols = activeSourceCols,
                screenState = screenState,
                effectiveOffset = effectiveOffset
            )
        }

        canvas.restore()

        logTraceIfLive(
            screenState = screenState,
            selection = selection,
            effectiveGridNotEmpty = effectiveGridNotEmpty,
            lastDrawnTraceId = lastDrawnTraceId
        )
    }

    private fun renderEmptyState(
        canvas: android.graphics.Canvas,
        readyLabel: String,
        connectingLabel: String
    ) {
        textPaint.color = Color.parseColor("#888888")
        textPaint.letterSpacing = 0f
        textPaint.textSize = 34f
        canvas.drawText(readyLabel, 40f, 100f, textPaint)
        textPaint.textSize = 26f
        canvas.drawText(connectingLabel, 40f, 150f, textPaint)
    }

    private fun renderSelectedRows(
        canvas: android.graphics.Canvas,
        geometry: TerminalGeometry,
        activeGrid: List<List<TerminalCell>>,
        activeRows: Int,
        activeSourceCols: Int,
        selection: TerminalSelectionState,
        activeDefaultBgColor: Int,
        defaultBgColor: Int
    ) {
        // Interactive selection: bypass cache so highlights render immediately
        rowCache.invalidateAll()
        for (r in 0 until minOf(activeRows, activeGrid.size)) {
            val rowCells = activeGrid[r]
            val y = r * geometry.rowHeight
            renderRowBackgrounds(
                canvas, rowCells, y, geometry.rowHeight, geometry.cellWidth,
                colorCache, activeDefaultBgColor, defaultBgColor, bgPaint
            )
        }

        for (r in 0 until activeRows) {
            val range = selectedColumnRange(selection, r)
            if (range != null) {
                val startX = range.first * geometry.cellWidth
                val endX = (range.last + 1) * geometry.cellWidth
                val y = r * geometry.rowHeight
                canvas.drawRect(startX, y, endX, y + geometry.rowHeight, selHighlightPaint)
            }
        }

        val runBuffer = StringBuilder(activeSourceCols.coerceAtLeast(1))
        for (r in 0 until minOf(activeRows, activeGrid.size)) {
            val rowCells = activeGrid[r]
            val y = r * geometry.rowHeight
            renderRowGlyphs(
                canvas, rowCells, y + geometry.baselineOffset, geometry.cellWidth,
                colorCache, textPaint, runBuffer
            )
        }
    }

    private fun renderNormalRows(
        canvas: android.graphics.Canvas,
        canvasWidth: Float,
        geometry: TerminalGeometry,
        activeGrid: List<List<TerminalCell>>,
        activeRows: Int,
        activeSourceCols: Int,
        screenState: TerminalScreenState,
        effectiveOffset: Int,
        activeDefaultBgColor: Int,
        defaultBgColor: Int
    ) {
        // Recording a row into a Picture only pays off when a small part of the grid
        // changed. When everything is dirty (baseline, viewport move, resize) recording and
        // replaying every row costs more than drawing the viewport once, so the cache is
        // only engaged for genuinely partial updates.
        val dirtyRows = screenState.dirtyRows
        val useRowCache = dirtyRows != null && dirtyRows.size <= (activeRows / 2).coerceAtLeast(1)
        if (useRowCache) {
            rowCache.prepare(
                surfaceId = screenState.surfaceId,
                epoch = screenState.renderEpoch,
                rows = activeRows,
                cols = activeSourceCols,
                cellWidth = geometry.cellWidth,
                rowHeight = geometry.rowHeight,
                fontSizePx = geometry.fontSizePx,
                letterSpacingEm = geometry.letterSpacingEm,
                originX = geometry.originX,
                originY = geometry.originY,
                defaultBg = defaultBgColor,
                effectiveOffset = effectiveOffset,
                dirtyRows = dirtyRows
            )
        } else {
            rowCache.invalidateAll()
        }

        val recordingWidth = canvasWidth.toInt().coerceAtLeast(1)
        val runBuffer = StringBuilder(activeSourceCols.coerceAtLeast(1))
        for (r in 0 until minOf(activeRows, activeGrid.size)) {
            val cached = if (useRowCache) rowCache.get(r) else null
            if (cached != null) {
                canvas.drawPicture(cached)
            } else {
                val rowCells = activeGrid[r]
                val y = r * geometry.rowHeight
                if (!useRowCache) {
                    renderRowBackgrounds(
                        canvas, rowCells, y, geometry.rowHeight, geometry.cellWidth,
                        colorCache, activeDefaultBgColor, defaultBgColor, bgPaint
                    )
                    renderRowGlyphs(
                        canvas, rowCells, y + geometry.baselineOffset, geometry.cellWidth,
                        colorCache, textPaint, runBuffer
                    )
                } else {
                    val picture = Picture()
                    val recordingHeight = (y + geometry.rowHeight).toInt().coerceAtLeast(1)
                    val pCanvas = picture.beginRecording(recordingWidth, recordingHeight)

                    renderRowBackgrounds(
                        pCanvas, rowCells, y, geometry.rowHeight, geometry.cellWidth,
                        colorCache, activeDefaultBgColor, defaultBgColor, bgPaint
                    )
                    renderRowGlyphs(
                        pCanvas, rowCells, y + geometry.baselineOffset, geometry.cellWidth,
                        colorCache, textPaint, runBuffer
                    )

                    picture.endRecording()
                    rowCache.put(r, picture)
                    canvas.drawPicture(picture)
                }
            }
        }
    }

    private fun renderCursor(
        canvas: android.graphics.Canvas,
        geometry: TerminalGeometry,
        activeGrid: List<List<TerminalCell>>,
        activeRows: Int,
        activeSourceCols: Int,
        screenState: TerminalScreenState,
        effectiveOffset: Int
    ) {
        val cursor = screenState.cursor
        val cursorVisible = cursor.visible && effectiveOffset == 0
        if (cursorVisible && cursor.row in 0 until activeRows && cursor.column in 0 until activeSourceCols) {
            val cursorX = cursor.column * geometry.cellWidth
            val cursorY = cursor.row * geometry.rowHeight

            bgPaint.color = cursorBlockColor
            canvas.drawRect(cursorX, cursorY, cursorX + (geometry.cellWidth * 0.9f), cursorY + geometry.rowHeight, bgPaint)

            if (cursor.row < activeGrid.size && cursor.column < activeGrid[cursor.row].size) {
                val underCursor = activeGrid[cursor.row][cursor.column]
                if (underCursor.text.isNotEmpty() && underCursor.text != " ") {
                    textPaint.color = Color.BLACK
                    textPaint.isFakeBoldText = true
                    canvas.drawText(underCursor.text, cursorX, cursorY + geometry.baselineOffset, textPaint)
                }
            }
        }
    }

    private fun renderSelectionHandles(
        canvas: android.graphics.Canvas,
        geometry: TerminalGeometry,
        selection: TerminalSelectionState,
        handleRadiusPx: Float
    ) {
        val (normStart, normEnd) = selection.normalizedBounds()
        val startX = normStart.column * geometry.cellWidth
        val startY = normStart.row * geometry.rowHeight
        val endColWidth = cellDisplayWidth(selection.viewport.grid, normEnd.row, normEnd.column)
        val endX = (normEnd.column + endColWidth) * geometry.cellWidth
        val endY = normEnd.row * geometry.rowHeight

        // Start handle: vertical line on left edge of start cell, pin droplet below-left
        canvas.drawLine(startX, startY, startX, startY + geometry.rowHeight, handleStrokePaint)
        canvas.drawCircle(startX - handleRadiusPx / 2f, startY + geometry.rowHeight + handleRadiusPx, handleRadiusPx, handleFillPaint)

        // End handle: vertical line on right edge of end cell, pin droplet below-right
        canvas.drawLine(endX, endY, endX, endY + geometry.rowHeight, handleStrokePaint)
        canvas.drawCircle(endX + handleRadiusPx / 2f, endY + geometry.rowHeight + handleRadiusPx, handleRadiusPx, handleFillPaint)
    }

    private fun logTraceIfLive(
        screenState: TerminalScreenState,
        selection: TerminalSelectionState?,
        effectiveGridNotEmpty: Boolean,
        lastDrawnTraceId: AtomicLong
    ) {
        if (selection == null && effectiveGridNotEmpty) {
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
