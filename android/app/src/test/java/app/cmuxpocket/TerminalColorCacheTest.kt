package app.cmuxpocket

import app.cmuxpocket.ui.TerminalColorCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalColorCacheTest {

    private class FakeParser(private val values: Map<String, Int>) {
        var calls = 0
            private set

        fun parse(hex: String): Int? {
            calls++
            return values[hex]
        }
    }

    @Test
    fun resolvesEachDistinctHexOnce() {
        val parser = FakeParser(
            mapOf(
                "#111111" to 0xFF111111.toInt(),
                "#222222" to 0xFF222222.toInt()
            )
        )
        val cache = TerminalColorCache(parser::parse)

        repeat(200) {
            assertEquals(0xFF111111.toInt(), cache.resolve("#111111", fallback = 0))
            assertEquals(0xFF222222.toInt(), cache.resolve("#222222", fallback = 0))
        }

        assertEquals(2, parser.calls)
    }

    @Test
    fun returnsFallbackForUnparseableHexAndKeepsRetryingIt() {
        val parser = FakeParser(emptyMap())
        val cache = TerminalColorCache(parser::parse)

        assertEquals(0xFF00FF00.toInt(), cache.resolve("not-a-color", fallback = 0xFF00FF00.toInt()))
        assertEquals(0xFF00FF00.toInt(), cache.resolve("not-a-color", fallback = 0xFF00FF00.toInt()))

        assertEquals(2, parser.calls)
    }

    @Test
    fun boundsItselfSoALongTruecolorSessionCannotGrowItForever() {
        val colors = (0 until 600).associate { index -> "#%06X".format(index) to index }
        val parser = FakeParser(colors)
        val cache = TerminalColorCache(parser::parse)

        colors.keys.forEach { cache.resolve(it, fallback = 0) }
        val callsAfterFirstSweep = parser.calls
        cache.resolve("#000000", fallback = 0)

        assertTrue(parser.calls > callsAfterFirstSweep)
    }

    @Test
    fun clearDropsResolvedEntries() {
        val parser = FakeParser(mapOf("#ABCDEF" to 0xFFABCDEF.toInt()))
        val cache = TerminalColorCache(parser::parse)

        cache.resolve("#ABCDEF", fallback = 0)
        cache.clear()
        cache.resolve("#ABCDEF", fallback = 0)

        assertEquals(2, parser.calls)
    }
}
