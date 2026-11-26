package com.serranoie.app.synced.lyrics

/** Fixed sweep length for the last line, which has no next timestamp to end at. */
const val LAST_LINE_MS = 4_000L

/** Index of the line being sung at [positionMs], or -1 before the first line. */
fun List<LyricLine>.indexAt(positionMs: Long): Int = indexOfLast { it.timeMs <= positionMs }

/** How far (0..1) playback is through line [index], or null when there is no such line. */
fun List<LyricLine>.progressAt(index: Int, positionMs: Long): Float? {
    val start = getOrNull(index)?.timeMs ?: return null
    val end = getOrNull(index + 1)?.timeMs ?: (start + LAST_LINE_MS)
    if (end <= start) return 1f
    return ((positionMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}
