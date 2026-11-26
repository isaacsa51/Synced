package com.serranoie.app.synced.media

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Color

/**
 * The most saturated of the art's top three colors, or null for mostly grey art so callers fall back to the host accent.
 * Slow: call off the main thread.
 */
fun vividColor(art: Bitmap): Int? {
    val colors = try {
        WallpaperColors.fromBitmap(art)
    } catch (_: Exception) {
        return null
    }
    val hsv = FloatArray(3)
    return listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
        .map { it.toArgb() }
        .maxByOrNull { Color.colorToHSV(it, hsv); hsv[1] }
        ?.takeIf { Color.colorToHSV(it, hsv); hsv[1] > 0.2f && hsv[2] > 0.25f }
}
