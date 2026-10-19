package app.cmuxpocket

import android.graphics.Picture
import app.cmuxpocket.ui.TerminalRowCache
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalRowCacheTest {

    @Test
    fun fullInvalidationOnGeometryChange() {
        val cache = TerminalRowCache()
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 24, cols = 80,
            cellWidth = 10f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 0,
            dirtyRows = null
        )
        // Simulate populated cache
        cache.put(0, Picture())
        cache.put(1, Picture())
        assertNotNull(cache.get(0))
        assertNotNull(cache.get(1))

        // Change cellWidth: full invalidation
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 24, cols = 80,
            cellWidth = 12f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 0,
            dirtyRows = setOf(0)
        )
        assertNull(cache.get(0))
        assertNull(cache.get(1))
    }

    @Test
    fun selectiveInvalidationOnDelta() {
        val cache = TerminalRowCache()
        // Baseline setup
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 24, cols = 80,
            cellWidth = 10f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 0,
            dirtyRows = null
        )
        cache.put(0, Picture())
        cache.put(1, Picture())
        cache.put(2, Picture())

        // Delta frame modifying only row 1
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 24, cols = 80,
            cellWidth = 10f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 0,
            dirtyRows = setOf(1)
        )

        // Row 0 and Row 2 should remain cached; Row 1 should be invalidated
        assertNotNull(cache.get(0))
        assertNull(cache.get(1))
        assertNotNull(cache.get(2))
    }

    @Test
    fun fullInvalidationWhenDirtyRowsIsNull() {
        val cache = TerminalRowCache()
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 24, cols = 80,
            cellWidth = 10f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 0,
            dirtyRows = null
        )
        cache.put(0, Picture())
        cache.put(1, Picture())

        // Same geometry but null dirtyRows (baseline frame received)
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 24, cols = 80,
            cellWidth = 10f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 0,
            dirtyRows = null
        )
        assertNull(cache.get(0))
        assertNull(cache.get(1))
    }

    @Test
    fun invalidationOnScrollOffsetChange() {
        val cache = TerminalRowCache()
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 24, cols = 80,
            cellWidth = 10f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 0,
            dirtyRows = null
        )
        cache.put(0, Picture())

        // User scrolled via joystick: effectiveOffset becomes 5
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 24, cols = 80,
            cellWidth = 10f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 5,
            dirtyRows = emptySet()
        )
        assertNull(cache.get(0))
    }

    @Test
    fun dynamicallyGrowsForLargeTerminal() {
        val cache = TerminalRowCache()
        // 200 rows terminal
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 200, cols = 80,
            cellWidth = 10f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 0,
            dirtyRows = null
        )
        cache.put(199, Picture())
        assertNotNull(cache.get(199))

        // Invalidate row 199 only
        cache.prepare(
            surfaceId = "surf1", epoch = "e1", rows = 200, cols = 80,
            cellWidth = 10f, rowHeight = 20f, fontSizePx = 14f, letterSpacingEm = 0f,
            originX = 0f, originY = 0f, defaultBg = 0xFF0000, effectiveOffset = 0,
            dirtyRows = setOf(199)
        )
        assertNull(cache.get(199))
    }
}
