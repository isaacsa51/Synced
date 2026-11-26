package com.serranoie.app.synced

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A beat lands at every [offsetMs] + n × [periodMs] of playback position. */
data class Beat(val periodMs: Float, val offsetMs: Float)

/**
 * Estimates a track's beat grid from its synced lyrics alone: no audio, no extra lookups. Sung lines mostly start
 * on a beat, so the period whose grid the line timestamps line up with best is the tempo, and where they line up
 * is the phase. Returns null when the lines don't agree on any grid (free-time songs, sloppy timings, too few lines),
 * and the host then keeps its own free-running animation.
 */
object BeatEstimator {
    // 60–180 BPM: covers nearly all pop; faster songs land on their half-time, which still pulses on the beat
    private const val MIN_PERIOD_MS = 333.0
    private const val MAX_PERIOD_MS = 1000.0
    private const val COARSE_STEP_MS = 1.0
    private const val FINE_STEP_MS = 0.02
    private const val MIN_LINES = 12
    // Tie-breaker between a tempo and its half/double: a mild preference for ~120 BPM, the usual pop pulse
    private const val PREFERRED_PERIOD_MS = 500.0
    private const val PREFERENCE_OCTAVES = 1.0
    private const val MIN_ALIGNMENT = 0.3

    fun estimate(lines: List<LyricLine>): Beat? {
        val onsets = lines.map { it.timeMs.toDouble() }.filter { it > 0 }.distinct()
        if (onsets.size < MIN_LINES) return null
        val n = onsets.size

        // How tightly the onsets cluster at one phase of a grid with this period: 1 = every line on the grid,
        // ~1/sqrt(n) = no relation (the length of the mean unit vector of each onset's phase)
        fun align(period: Double): Pair<Double, Double> {
            var c = 0.0
            var s = 0.0
            for (t in onsets) {
                val a = 2 * PI * t / period
                c += cos(a)
                s += sin(a)
            }
            return hypot(c, s) / n to atan2(s, c)
        }

        fun preference(period: Double): Double {
            val octaves = ln(period / PREFERRED_PERIOD_MS) / ln(2.0) / PREFERENCE_OCTAVES
            return exp(-0.5 * octaves * octaves)
        }

        var best = MIN_PERIOD_MS
        var bestScore = -1.0
        var p = MIN_PERIOD_MS
        while (p <= MAX_PERIOD_MS) {
            val score = align(p).first * preference(p)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
            p += COARSE_STEP_MS
        }
        // Small period errors add up over a whole song, so refine finely around the coarse pick
        var fine = best
        var fineAlign = align(best)
        p = best - COARSE_STEP_MS
        while (p <= best + COARSE_STEP_MS) {
            val a = align(p)
            if (a.first > fineAlign.first) {
                fineAlign = a
                fine = p
            }
            p += FINE_STEP_MS
        }

        val (alignment, phase) = fineAlign
        // Random timestamps still line up a little by chance (~1/sqrt(n)); demand well above that
        if (alignment < max(MIN_ALIGNMENT, 3.0 / sqrt(n.toDouble()))) return null
        val offset = ((phase / (2 * PI) * fine) % fine + fine) % fine
        return Beat(fine.toFloat(), offset.toFloat())
    }
}
