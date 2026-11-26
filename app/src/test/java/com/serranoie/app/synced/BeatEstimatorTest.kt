package com.serranoie.app.synced

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class BeatEstimatorTest {
    private fun phrases(
        bpm: Double,
        offsetMs: Double,
        jitterMs: Double,
        seed: Int,
        pickupShare: Double = 0.2
    ): List<LyricLine> {
        val random = Random(seed)
        val beat = 60_000 / bpm
        var bar = 4
        return List(50) {
            bar += random.nextInt(1, 3)
            val pickup = if (random.nextDouble() < pickupShare) -beat else 0.0
            val jitter = if (jitterMs > 0) random.nextDouble(-jitterMs, jitterMs) else 0.0
            LyricLine((offsetMs + bar * 4 * beat + pickup + jitter).toLong(), "line $it")
        }
    }

    private fun phaseError(
        beat: Beat,
        bpm: Double,
        trueOffsetMs: Double,
        lines: List<LyricLine>
    ): Double {
        val truePeriod = 60_000 / bpm
        return lines.maxOf { line ->
            val n = Math.round((line.timeMs - beat.offsetMs) / beat.periodMs)
            val estimated = beat.offsetMs + n * beat.periodMs.toDouble()
            val d = ((estimated - trueOffsetMs) % truePeriod + truePeriod) % truePeriod
            minOf(d, truePeriod - d)
        }
    }

    @Test
    fun findsTempoAndPhaseOfPhrases() {
        for ((bpm, seed) in listOf(96.0 to 1, 120.0 to 2, 128.0 to 3, 140.0 to 4)) {
            val period = 60_000 / bpm
            val lines = phrases(bpm, 1_234.0, jitterMs = 80.0, seed = seed)
            val beat = BeatEstimator.estimate(lines)
            assertNotNull("no beat at $bpm BPM", beat)
            assertEquals("tempo at $bpm BPM", period, beat!!.periodMs.toDouble(), period * 0.005)
            val error = phaseError(beat, bpm, 1_234.0, lines)
            assertTrue("phase off by $error ms at $bpm BPM: $beat", error < 60)
        }
    }

    @Test
    fun givesUpOnUntimedLines() {
        for (seed in 1..5) {
            val random = Random(seed)
            var t = 5_000L
            val lines = List(50) { t += random.nextLong(1_500, 6_000); LyricLine(t, "line $it") }
            assertNull("seed $seed", BeatEstimator.estimate(lines))
        }
    }

    @Test
    fun needsEnoughLines() {
        assertNull(BeatEstimator.estimate(phrases(120.0, 0.0, 0.0, 1).take(10)))
    }

    @Test
    fun realLrclibTimings() {
        val json = javaClass.classLoader!!.getResource("lrclib_line_times.json")!!.readText()
        val songs =
            Regex(""""title": "([^"]*)", "bpm": (\d+), "times": \[([\d, ]*)]""").findAll(json)
                .map { m ->
                    Triple(
                        m.groupValues[1],
                        m.groupValues[2].toDouble(),
                        m.groupValues[3].split(", ").map { LyricLine(it.trim().toLong(), "") })
                }.toList()
        assertEquals(15, songs.size)
        var locked = 0
        var off = 0
        val report = songs.map { (title, bpm, lines) ->
            val beat = BeatEstimator.estimate(lines)
            val estimated = beat?.let { 60_000 / it.periodMs.toDouble() }
            val onTempo =
                estimated != null && listOf(0.5, 1.0, 2.0).any { abs(estimated / bpm - it) < 0.025 }
            when {
                beat == null -> {}
                onTempo -> locked++
                else -> off++
            }
            "$title: $bpm → ${estimated?.let { "%.1f".format(it) } ?: "none"}"
        }
        println(report.joinToString("\n"))
        assertTrue("locked $locked\n${report.joinToString("\n")}", locked >= 10)
        assertTrue("off $off\n${report.joinToString("\n")}", off <= 3)
    }
}
