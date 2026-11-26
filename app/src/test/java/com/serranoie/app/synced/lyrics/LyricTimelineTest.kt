package com.serranoie.app.synced.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricTimelineTest {
    private val lines = listOf(LyricLine(1_000, "a"), LyricLine(3_000, "b"), LyricLine(7_000, "c"))

    @Test
    fun findsCurrentLine() {
        assertEquals(-1, lines.indexAt(500))
        assertEquals(0, lines.indexAt(1_000))
        assertEquals(1, lines.indexAt(6_999))
        assertEquals(2, lines.indexAt(60_000))
    }

    @Test
    fun progressRunsUntilNextLine() {
        assertEquals(0f, lines.progressAt(1, 3_000)!!, 0f)
        assertEquals(0.5f, lines.progressAt(1, 5_000)!!, 0f)
        assertEquals(1f, lines.progressAt(1, 9_000)!!, 0f)
        assertEquals(0.5f, lines.progressAt(2, 7_000 + LAST_LINE_MS / 2)!!, 0f)
        assertNull(lines.progressAt(-1, 0))
    }
}
