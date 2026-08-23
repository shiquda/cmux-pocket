package app.cmuxpocket

import app.cmuxpocket.engine.TerminalCell
import app.cmuxpocket.ui.FrozenTerminalViewport
import app.cmuxpocket.ui.TerminalCellPosition
import app.cmuxpocket.ui.TerminalSelectionState
import app.cmuxpocket.ui.beginWordSelection
import app.cmuxpocket.ui.extractSelectedText
import app.cmuxpocket.ui.freezeTerminalViewport
import app.cmuxpocket.ui.selectedColumnRange
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSelectionTest {
    @Test
    fun longPressSelectsAsciiWord() {
        val viewport = viewport(asciiRow("hello world"), columns = 11)

        val selection = beginWordSelection(viewport, TerminalCellPosition(row = 0, column = 2))

        assertEquals(TerminalCellPosition(0, 0), selection.startHandle)
        assertEquals(TerminalCellPosition(0, 4), selection.endHandle)
        assertEquals("hello", extractSelectedText(selection))
    }

    @Test
    fun cjkContinuationNormalizesToWideLeadingCell() {
        val row = listOf(
            TerminalCell(text = "中", width = 2),
            TerminalCell(text = "", width = 0),
            TerminalCell(text = "文", width = 2),
            TerminalCell(text = "", width = 0),
            TerminalCell()
        )
        val viewport = viewport(row, columns = 5)

        val selection = beginWordSelection(viewport, TerminalCellPosition(row = 0, column = 1))

        assertEquals(TerminalCellPosition(0, 0), selection.startHandle)
        assertEquals(TerminalCellPosition(0, 2), selection.endHandle)
        assertEquals(0..3, selectedColumnRange(selection, 0))
        assertEquals("中文", extractSelectedText(selection))
    }

    @Test
    fun reverseHandleDragNormalizesCrossLineSelection() {
        val viewport = FrozenTerminalViewport(
            grid = listOf(asciiRow("alpha   "), asciiRow("beta    ")),
            columns = 8,
            rows = 2
        )
        val selection = TerminalSelectionState(
            viewport = viewport,
            startHandle = TerminalCellPosition(1, 1),
            endHandle = TerminalCellPosition(0, 2)
        )

        assertEquals("pha\nbe", extractSelectedText(selection))
        assertEquals(2..7, selectedColumnRange(selection, 0))
        assertEquals(0..1, selectedColumnRange(selection, 1))
    }

    @Test
    fun extractionRemovesGridPaddingButPreservesInteriorBlanks() {
        val viewport = viewport(asciiRow("a b     "), columns = 8)
        val selection = TerminalSelectionState(
            viewport,
            TerminalCellPosition(0, 0),
            TerminalCellPosition(0, 7)
        )

        assertEquals("a b", extractSelectedText(selection))
    }

    @Test
    fun blankRowsPreserveSelectedLineBreaks() {
        val viewport = FrozenTerminalViewport(
            grid = listOf(asciiRow("top "), asciiRow("    "), asciiRow("end ")),
            columns = 4,
            rows = 3
        )
        val selection = TerminalSelectionState(
            viewport,
            TerminalCellPosition(0, 0),
            TerminalCellPosition(2, 2)
        )

        assertEquals("top\n\nend", extractSelectedText(selection))
    }

    @Test
    fun frozenViewportDoesNotFollowIncomingRows() {
        val source = mutableListOf(asciiRow("old "))
        val viewport = freezeTerminalViewport(source, columns = 4, rows = 1)
        val selection = TerminalSelectionState(
            viewport,
            TerminalCellPosition(0, 0),
            TerminalCellPosition(0, 3)
        )

        source[0] = asciiRow("new ")

        assertEquals("old", extractSelectedText(selection))
    }

    private fun viewport(row: List<TerminalCell>, columns: Int): FrozenTerminalViewport {
        return FrozenTerminalViewport(grid = listOf(row), columns = columns, rows = 1)
    }

    private fun asciiRow(text: String): List<TerminalCell> {
        return text.map { TerminalCell(text = it.toString()) }
    }
}
