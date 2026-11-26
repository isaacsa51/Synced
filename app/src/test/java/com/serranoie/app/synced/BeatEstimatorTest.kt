package com.serranoie.app.synced

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class BeatEstimatorTest {
    private fun linesOnBeats(
        periodMs: Double,
        offsetMs: Double,
        jitterMs: Double,
        seed: Int,
        offBeatShare: Double = 0.0,
    ): List<LyricLine> {
        val random = Random(seed)
        var beat = 8
        return List(50) {
            // Lines start on irregular beats, like real verses: every 2 to 9 beats
            beat += random.nextInt(2, 10)
            // Some lines come in on the "and" (a pickup), half a beat early
            val pickup = if (random.nextDouble() < offBeatShare) -periodMs / 2 else 0.0
            val jitter = if (jitterMs > 0) random.nextDouble(-jitterMs, jitterMs) else 0.0
            val t = offsetMs + beat * periodMs + pickup + jitter
            LyricLine(t.toLong(), "line $it")
        }
    }

    /** Distance in ms from the nearest estimated beat to a true beat. */
    private fun phaseError(beat: Beat, trueOffsetMs: Double): Double {
        val d = ((trueOffsetMs - beat.offsetMs) % beat.periodMs + beat.periodMs) % beat.periodMs
        return minOf(d, beat.periodMs - d)
    }

    @Test
    fun findsTempoAndPhaseFromTimestamps() {
        for ((bpm, seed) in listOf(96.0 to 1, 120.0 to 2, 128.0 to 3, 140.0 to 4)) {
            val period = 60_000 / bpm
            val beat = BeatEstimator.estimate(linesOnBeats(period, 1_234.0, jitterMs = 40.0, seed = seed))
            assertNotNull("no beat at $bpm BPM", beat)
            assertEquals("tempo at $bpm BPM", period, beat!!.periodMs.toDouble(), period * 0.005)
            assert(phaseError(beat, 1_234.0) < 60) { "phase off at $bpm BPM: $beat" }
        }
    }

    @Test
    fun survivesSloppyTimingAndPickups() {
        for ((bpm, seed) in listOf(100.0 to 11, 124.0 to 12)) {
            val period = 60_000 / bpm
            val beat = BeatEstimator.estimate(linesOnBeats(period, 800.0, jitterMs = 80.0, seed = seed, offBeatShare = 0.25))
            assertNotNull("no beat at $bpm BPM", beat)
            assertEquals("tempo at $bpm BPM", period, beat!!.periodMs.toDouble(), period * 0.005)
            assert(phaseError(beat, 800.0) < 90) { "phase off at $bpm BPM: $beat" }
        }
    }

    @Test
    fun givesUpOnUntimedLines() {
        val random = Random(7)
        var t = 5_000L
        val lines = List(50) { t += random.nextLong(1_500, 6_000); LyricLine(t, "line $it") }
        assertNull(BeatEstimator.estimate(lines))
    }

    @Test
    fun needsEnoughLines() {
        assertNull(BeatEstimator.estimate(linesOnBeats(500.0, 0.0, 0.0, 1).take(5)))
    }
}
