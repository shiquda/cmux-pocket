package app.cmuxpocket.ui

import app.cmuxpocket.engine.TerminalCell
import app.cmuxpocket.ui.PipSizePreset
import app.cmuxpocket.ui.tailPipLines
import app.cmuxpocket.ui.wrapPipGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipSizePresetTest {

    @Test
    fun presetsStayInsideAndroidPipAspectBounds() {
        for (preset in PipSizePreset.entries) {
            val ratio = preset.aspectWidth.toFloat() / preset.aspectHeight
            assertTrue("${preset.name} ratio $ratio is too narrow", ratio >= 1f / 2.39f)
            assertTrue("${preset.name} ratio $ratio is too wide", ratio <= 2.39f)
        }
    }

    @Test
    fun nextCyclesThroughAllPresets() {
        assertEquals(PipSizePreset.CLASSIC, PipSizePreset.SQUARE.next())
        assertEquals(PipSizePreset.PHOTO, PipSizePreset.CLASSIC.next())
        assertEquals(PipSizePreset.STANDARD, PipSizePreset.PHOTO.next())
        assertEquals(PipSizePreset.CINEMA, PipSizePreset.STANDARD.next())
        assertEquals(PipSizePreset.WIDE, PipSizePreset.CINEMA.next())
        assertEquals(PipSizePreset.SQUARE, PipSizePreset.WIDE.next())
    }

    @Test
    fun storageFallsBackToSquareForUnknownValues() {
        assertEquals(PipSizePreset.SQUARE, PipSizePreset.fromStorage(null))
        assertEquals(PipSizePreset.SQUARE, PipSizePreset.fromStorage("future-preset"))
        assertEquals(PipSizePreset.WIDE, PipSizePreset.fromStorage("WIDE"))
    }

    @Test
    fun customRatiosMapToTheMatchingPreset() {
        assertEquals(PipSizePreset.SQUARE, PipSizePreset.fromRatio(1f))
        assertEquals(PipSizePreset.STANDARD, PipSizePreset.fromRatio(16f / 9f))
        assertEquals(null, PipSizePreset.fromRatio(1.62f))
    }

    @Test
    fun cyclingFromACustomRatioPicksTheNextPreset() {
        assertEquals(PipSizePreset.PHOTO, PipSizePreset.nextAfter(1.4f))
        assertEquals(PipSizePreset.CINEMA, PipSizePreset.nextAfter(16f / 9f))
        assertEquals(PipSizePreset.WIDE, PipSizePreset.nextAfter(2.2f))
        assertEquals(PipSizePreset.SQUARE, PipSizePreset.nextAfter(2.39f))
    }
}

class PipTailFormatterTest {

    @Test
    fun wrapsRowsAtTheColumnLimit() {
        val grid = listOf(row("a", "b", "c", "d"))
        val lines = wrapPipGrid(grid, maxColumns = 3)

        assertEquals(2, lines.size)
        assertEquals("abc", text(lines[0]))
        assertEquals("d", text(lines[1]))
    }

    @Test
    fun breaksAtWhitespaceInsteadOfChoppingWords() {
        val grid = listOf(row("h", "e", "l", "l", "o", " ", "w", "o", "r", "l", "d"))
        val lines = wrapPipGrid(grid, maxColumns = 8)

        assertEquals(2, lines.size)
        assertEquals("hello", text(lines[0]))
        assertEquals("world", text(lines[1]))
    }

    @Test
    fun neverSplitsDoubleWidthCells() {
        val grid = listOf(
            listOf(
                cell("a"),
                cell("界", width = 2),
                cell("b")
            )
        )
        val lines = wrapPipGrid(grid, maxColumns = 3)

        assertEquals(2, lines.size)
        assertEquals(2, lines[0].size)
        assertEquals("b", text(lines[1]))
    }

    @Test
    fun tailKeepsNewestLinesAndDropsLeadingBlanks() {
        val lines = listOf(
            emptyList(),
            row("old"),
            row("middle"),
            row("new")
        )

        val tail = tailPipLines(lines, maxLines = 2)

        assertEquals(2, tail.size)
        assertEquals("middle", text(tail[0]))
        assertEquals("new", text(tail[1]))
    }

    @Test
    fun tailIgnoresTrailingBlankRowsFromAnUnfilledGrid() {
        val blank = row(" ", " ", " ")
        val lines = listOf(
            row("=== cmux Terminal ==="),
            row("> "),
            blank,
            blank
        )

        val tail = tailPipLines(lines, maxLines = 3)

        assertEquals(2, tail.size)
        assertEquals("=== cmux Terminal ===", text(tail[0]))
        assertEquals("> ", text(tail[1]))
    }

    private fun row(vararg values: String): List<TerminalCell> = values.map { cell(it) }

    private fun cell(text: String, width: Int = 1) = TerminalCell(text = text, width = width)

    private fun text(line: List<TerminalCell>): String = line.joinToString("") { it.text }
}
