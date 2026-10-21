package app.cmuxpocket.ui

import app.cmuxpocket.engine.TerminalCell

/** Aspect-ratio presets offered for the floating terminal window. */
enum class PipSizePreset(
    val label: String,
    val aspectWidth: Int,
    val aspectHeight: Int
) {
    SQUARE("1:1", 1, 1),
    CLASSIC("4:3", 4, 3),
    PHOTO("3:2", 3, 2),
    STANDARD("16:9", 16, 9),
    CINEMA("2:1", 2, 1),
    WIDE("2.39:1", 239, 100);

    val ratio: Float get() = aspectWidth.toFloat() / aspectHeight
    val ratioLabel: String get() = label

    fun next(): PipSizePreset = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Android rejects picture-in-picture ratios outside 1/2.39 … 2.39. */
        const val MIN_ASPECT_RATIO = 0.6f
        const val MAX_ASPECT_RATIO = 2.39f
        const val DEFAULT_ASPECT_RATIO = 1f

        fun fromStorage(value: String?): PipSizePreset =
            entries.firstOrNull { it.name == value } ?: SQUARE

        fun fromRatio(ratio: Float): PipSizePreset? =
            entries.firstOrNull { kotlin.math.abs(it.ratio - ratio) < 0.01f }

        /** Next preset when cycling from an arbitrary custom ratio. */
        fun nextAfter(ratio: Float): PipSizePreset =
            entries.firstOrNull { it.ratio > ratio + 0.01f } ?: SQUARE
    }
}

/**
 * Wraps render-grid rows into visual lines that fit a fixed number of columns.
 * Wide (double-width) cells are never split across two lines, and a break prefers
 * the last whitespace on the line so narrow windows do not chop words in half.
 */
internal fun wrapPipGrid(grid: List<List<TerminalCell>>, maxColumns: Int): List<List<TerminalCell>> {
    if (maxColumns <= 0) return emptyList()
    val wrapped = ArrayList<List<TerminalCell>>()
    for (row in grid) {
        var line = ArrayList<TerminalCell>()
        var usedColumns = 0
        var lastSpaceIndex = -1

        fun flushBefore(index: Int) {
            val head = line.subList(0, index).dropLastWhile { it.text.isBlank() }
            if (head.isNotEmpty()) wrapped.add(head)
            line = ArrayList(line.subList(index, line.size).filter { it.text.isNotBlank() })
            usedColumns = line.sumOf { it.width.coerceAtLeast(1) }
            lastSpaceIndex = -1
        }

        for (cell in row) {
            if (cell.width == 0) continue
            if (cell.text.isEmpty()) continue
            val cellWidth = cell.width.coerceAtLeast(1)
            if (usedColumns > 0 && usedColumns + cellWidth > maxColumns) {
                if (lastSpaceIndex > 0) {
                    flushBefore(lastSpaceIndex)
                } else {
                    wrapped.add(line)
                    line = ArrayList()
                    usedColumns = 0
                }
                if (usedColumns > 0 && usedColumns + cellWidth > maxColumns) {
                    wrapped.add(line)
                    line = ArrayList()
                    usedColumns = 0
                }
            }
            if (cell.text.isBlank()) lastSpaceIndex = line.size
            line.add(cell)
            usedColumns += cellWidth
        }
        if (line.isNotEmpty()) {
            wrapped.add(line)
        }
    }
    return wrapped
}

/**
 * Keeps only visual lines with visible text and returns the newest ones.
 * Trailing blank rows must be ignored: a fresh terminal usually has output at the
 * top and the cursor near it, leaving the rest of the grid empty, so slicing the
 * bottom rows verbatim would render nothing but spaces.
 */
internal fun tailPipLines(lines: List<List<TerminalCell>>, maxLines: Int): List<List<TerminalCell>> {
    if (maxLines <= 0) return emptyList()
    return lines.filter { line -> line.any { it.text.isNotBlank() } }.takeLast(maxLines)
}
