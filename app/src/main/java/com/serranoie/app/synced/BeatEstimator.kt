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

data class Beat(val periodMs: Float, val offsetMs: Float)

/**
 * Estimates a track's beat grid from its synced lyrics alone: no audio, no extra lookups.
 *
 * Lyric phrases tend to start at the same point of a bar, so this fits a bar grid (four beats) to the line
 * timestamps and divides by four. Fitting single beats doesn't work on real lyrics: hand-made LRC timings are off by
 * 100–200 ms and lines come in on pickups and off-beats, which is noise the size of a beat but small next to a bar.
 * Returns null when the lines don't agree on any grid better than chance; the host then keeps its free-running lights.
 */
object BeatEstimator {
    private const val BEATS_PER_BAR = 4
    private const val MIN_BAR_MS = 1333.0
    private const val MAX_BAR_MS = 4000.0
    private const val COARSE_STEP_MS = 1.0
    private const val FINE_STEP_MS = 0.02
    private const val MIN_LINES = 16
    private const val PREFERRED_BAR_MS = 2000.0
    private const val PREFERENCE_OCTAVES = 1.0
    private const val CHANCE_FACTOR = 3.0
    private const val MIN_ALIGNMENT = 0.3

    fun estimate(lines: List<LyricLine>): Beat? {
        val onsets = lines.map { it.timeMs.toDouble() }.filter { it > 0 }.distinct()
        if (onsets.size < MIN_LINES) return null
        val n = onsets.size

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
            val octaves = ln(period / PREFERRED_BAR_MS) / ln(2.0) / PREFERENCE_OCTAVES
            return exp(-0.5 * octaves * octaves)
        }

        var best = MIN_BAR_MS
        var bestScore = -1.0
        var p = MIN_BAR_MS
        while (p <= MAX_BAR_MS) {
            val score = align(p).first * preference(p)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
            p += COARSE_STEP_MS
        }
        var bar = best
        var barAlign = align(best)
        p = best - COARSE_STEP_MS
        while (p <= best + COARSE_STEP_MS) {
            val a = align(p)
            if (a.first > barAlign.first) {
                barAlign = a
                bar = p
            }
            p += FINE_STEP_MS
        }

        val (alignment, barPhase) = barAlign
        if (alignment < max(MIN_ALIGNMENT, CHANCE_FACTOR / sqrt(n.toDouble()))) return null
        val beat = bar / BEATS_PER_BAR
        val barStart = wrap(barPhase / (2 * PI) * bar, bar)
        val beatPhase = wrap(align(beat).second / (2 * PI) * beat, beat)
        val snapped = beatPhase + Math.round((barStart - beatPhase) / beat) * beat
        return Beat(beat.toFloat(), wrap(snapped, bar).toFloat())
    }

    private fun wrap(value: Double, period: Double) = (value % period + period) % period
}
