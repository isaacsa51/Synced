package com.serranoie.app.synced.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricStyleTest {
    @Test
    fun italicizesOnlyParentheses() {
        val text = italicizeAsides("Sexy magic in the air (brr)")
        assertEquals("Sexy magic in the air (brr)", text.text)
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("(brr)", text.text.substring(italic.start, italic.end))
    }

    @Test
    fun sweepLightsTheStartOfTheLine() {
        val glow = glowStyle(Color.Red)
        val half = sweep(italicizeAsides("abcd"), glow, 0.5f).spanStyles.single { it.item == glow }
        assertEquals(0 to 2, half.start to half.end)
        assertEquals(0, sweep(italicizeAsides("abcd"), glow, 0f).spanStyles.count { it.item == glow })
    }
}
