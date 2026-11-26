package com.serranoie.app.synced.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrclibTest {
    @Test
    fun parsesSyncedLyrics() {
        val lines = Lrclib.parse("[00:12.50] Hello\n[01:02.00][00:05.00] Chorus\nno tag\n[00:20.00]")
        assertEquals(
            listOf(
                LyricLine(5_000, "Chorus"),
                LyricLine(12_500, "Hello"),
                LyricLine(20_000, ""),
                LyricLine(62_000, "Chorus"),
            ),
            lines,
        )
    }
}
