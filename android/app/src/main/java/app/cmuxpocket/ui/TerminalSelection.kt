package app.cmuxpocket.ui

import app.cmuxpocket.engine.TerminalCell

data class TerminalCellPosition(
    val row: Int,
    val column: Int
) : Comparable<TerminalCellPosition> {
    override fun compareTo(other: TerminalCellPosition): Int {
        return compareValuesBy(this, other, TerminalCellPosition::row, TerminalCellPosition::column)
    }
}

data class FrozenTerminalViewport(
    val grid: List<List<TerminalCell>>,
    val columns: Int,
    val rows: Int
) {
    init {
        require(columns > 0)
        require(rows > 0)
    }

    fun normalize(position: TerminalCellPosition): TerminalCellPosition {
        val row = position.row.coerceIn(0, rows - 1)
        val column = position.column.coerceIn(0, columns - 1)
        return TerminalCellPosition(row, leadingColumn(grid, row, column))
    }
}

data class TerminalSelectionState(
    val viewport: FrozenTerminalViewport,
    val startHandle: TerminalCellPosition,
    val endHandle: TerminalCellPosition
) {
    fun normalizedBounds(): Pair<TerminalCellPosition, TerminalCellPosition> {
        val start = viewport.normalize(startHandle)
        val end = viewport.normalize(endHandle)
        return if (start <= end) start to end else end to start
    }

    fun moveStart(position: TerminalCellPosition): TerminalSelectionState {
        return copy(startHandle = viewport.normalize(position))
    }

    fun moveEnd(position: TerminalCellPosition): TerminalSelectionState {
        return copy(endHandle = viewport.normalize(position))
    }

    fun contains(position: TerminalCellPosition): Boolean {
        val normalizedPosition = viewport.normalize(position)
        val (start, end) = normalizedBounds()
        return normalizedPosition in start..end
    }
}

fun freezeTerminalViewport(
    grid: List<List<TerminalCell>>,
    columns: Int,
    rows: Int
): FrozenTerminalViewport {
    return FrozenTerminalViewport(
        grid = grid.map { it.toList() },
        columns = columns.coerceAtLeast(1),
        rows = rows.coerceAtLeast(1)
    )
}

fun beginWordSelection(
    viewport: FrozenTerminalViewport,
    position: TerminalCellPosition
): TerminalSelectionState {
    val anchor = viewport.normalize(position)
    val cell = cellAt(viewport.grid, anchor.row, anchor.column)
    if (cell == null || !isWordCell(cell)) {
        return TerminalSelectionState(viewport, anchor, anchor)
    }

    var startColumn = anchor.column
    while (startColumn > 0) {
        val previous = leadingColumn(viewport.grid, anchor.row, startColumn - 1)
        if (previous >= startColumn || !isWordCell(cellAt(viewport.grid, anchor.row, previous))) break
        startColumn = previous
    }

    var endColumn = anchor.column
    while (true) {
        val width = cellDisplayWidth(viewport.grid, anchor.row, endColumn)
        val next = endColumn + width
        if (next >= viewport.columns || !isWordCell(cellAt(viewport.grid, anchor.row, next))) break
        endColumn = next
    }

    return TerminalSelectionState(
        viewport = viewport,
        startHandle = TerminalCellPosition(anchor.row, startColumn),
        endHandle = TerminalCellPosition(anchor.row, endColumn)
    )
}

fun selectedColumnRange(selection: TerminalSelectionState, row: Int): IntRange? {
    val (start, end) = selection.normalizedBounds()
    if (row !in start.row..end.row) return null

    val first = if (row == start.row) start.column else 0
    val last = if (row == end.row) {
        end.column + cellDisplayWidth(selection.viewport.grid, end.row, end.column) - 1
    } else {
        selection.viewport.columns - 1
    }
    return first.coerceAtLeast(0)..last.coerceAtMost(selection.viewport.columns - 1)
}

fun extractSelectedText(selection: TerminalSelectionState): String {
    val (start, end) = selection.normalizedBounds()
    val lines = ArrayList<String>(end.row - start.row + 1)

    for (row in start.row..end.row) {
        val range = selectedColumnRange(selection, row) ?: continue
        val text = StringBuilder()
        var column = range.first
        while (column <= range.last) {
            val leading = leadingColumn(selection.viewport.grid, row, column)
            val cell = cellAt(selection.viewport.grid, row, leading)
            val width = cellDisplayWidth(selection.viewport.grid, row, leading)
            if (leading >= range.first) {
                when {
                    cell == null -> text.append(' ')
                    cell.text.isEmpty() -> repeat(width) { text.append(' ') }
                    else -> text.append(cell.text)
                }
            }
            column = maxOf(column + 1, leading + width)
        }
        lines += text.toString().trimEnd()
    }

    return lines.joinToString("\n")
}

fun cellDisplayWidth(grid: List<List<TerminalCell>>, row: Int, column: Int): Int {
    return cellAt(grid, row, column)?.width?.coerceAtLeast(1) ?: 1
}

private fun leadingColumn(
    grid: List<List<TerminalCell>>,
    row: Int,
    column: Int
): Int {
    val cells = grid.getOrNull(row) ?: return column
    val cell = cells.getOrNull(column) ?: return column
    if (cell.width > 0) return column

    for (candidate in column - 1 downTo 0) {
        val leading = cells[candidate]
        if (leading.width > 0 && candidate + leading.width > column) {
            return candidate
        }
    }
    return column
}

private fun cellAt(grid: List<List<TerminalCell>>, row: Int, column: Int): TerminalCell? {
    return grid.getOrNull(row)?.getOrNull(column)?.takeIf { it.width > 0 }
}

private fun isWordCell(cell: TerminalCell?): Boolean {
    val text = cell?.text ?: return false
    if (text.isBlank()) return false
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val type = Character.getType(codePoint)
        val isWordCodePoint = Character.isLetterOrDigit(codePoint) ||
            codePoint == '_'.code ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
        if (!isWordCodePoint) return false
        index += Character.charCount(codePoint)
    }
    return true
}
