package com.runpro.core.recording

import com.runpro.core.model.HeartRate
import com.runpro.core.model.LapTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Replays the 30 min easy run ([SyntheticTraces.easyThirtyMinutes]) and checks the golden totals,
 * derived by hand from the trace description.
 */
class EasyRunReplayTest {
    /** Golden values for one set of totals. Durations are exact; the rest is compared within [TOLERANCE]. */
    private data class Golden(
        val elapsed: Duration,
        val timer: Duration,
        val moving: Duration,
        val meters: Double,
        val averageHeartRate: Int,
        val maxHeartRate: Int,
        val averageCadence: Double,
        val maxCadence: Double,
        val ascent: Double,
        val descent: Double
    )

    private val run = Golden(
        elapsed = 1860.seconds,
        timer = 1800.seconds,
        // 30 s at the traffic light without moving.
        moving = 1770.seconds,
        // 300 s x 2.5 m/s + 1470 s x 3.0 m/s.
        meters = 5160.0,
        // (130 x 300 + 175 x 10 + 155 x 1490) / 1800 = 150.94.
        averageHeartRate = 151,
        maxHeartRate = 175,
        // (160 x 300 + 170 x 1470) / 1770 (cadence 0 at the traffic light is ignored).
        averageCadence = 297_900.0 / 1770,
        maxCadence = 170.0,
        ascent = 30.0,
        descent = 30.0
    )

    private val laps = listOf(
        Golden(
            elapsed = 600.seconds,
            timer = 600.seconds,
            moving = 600.seconds,
            meters = 1650.0,
            // (130 x 300 + 155 x 300) / 600 = 142.5, rounded half up.
            averageHeartRate = 143,
            // The sample at the lap mark (155) is the last one of the first lap.
            maxHeartRate = 155,
            averageCadence = 165.0,
            maxCadence = 170.0,
            ascent = 0.0,
            descent = 0.0
        ),
        Golden(
            // Includes the 60 s pause.
            elapsed = 660.seconds,
            timer = 600.seconds,
            moving = 600.seconds,
            meters = 1800.0,
            averageHeartRate = 155,
            maxHeartRate = 155,
            averageCadence = 170.0,
            maxCadence = 170.0,
            ascent = 30.0,
            descent = 0.0
        ),
        Golden(
            elapsed = 600.seconds,
            timer = 600.seconds,
            moving = 570.seconds,
            meters = 1710.0,
            // (155 x 590 + 175 x 10) / 600 = 155.33.
            averageHeartRate = 155,
            maxHeartRate = 175,
            averageCadence = 170.0,
            maxCadence = 170.0,
            ascent = 0.0,
            descent = 30.0
        )
    )

    @Test
    fun `the 30 min easy run produces the golden totals`() {
        val state = machine().feed(SyntheticTraces.easyThirtyMinutes())
        assertEquals(RunPhase.STOPPED, state.phase)
        assertMatches(run, state.totals, "run")
        assertEquals(laps.size, state.laps.size)
        laps.zip(state.laps).forEachIndexed { i, (golden, actual) ->
            assertMatches(golden, actual.totals, "lap $i")
        }
    }

    @Test
    fun `the laps of the state are the laps derived from the run events`() {
        val state = machine().feed(SyntheticTraces.easyThirtyMinutes())
        assertEquals(state.run?.laps, state.laps.map { it.lap })
        assertEquals(
            listOf(LapTrigger.MANUAL, LapTrigger.MANUAL, LapTrigger.STOP),
            state.laps.map { it.lap.trigger }
        )
    }

    @Test
    fun `the average pace matches distance over timer time`() {
        val totals = machine().feed(SyntheticTraces.easyThirtyMinutes()).totals
        assertEquals(1800.0 / 5.160, totals.averagePace!!.secondsPerKilometer, TOLERANCE)
    }

    private fun assertMatches(golden: Golden, totals: Totals, what: String) {
        assertEquals(golden.elapsed, totals.elapsed, "$what elapsed")
        assertEquals(golden.timer, totals.timer, "$what timer")
        assertEquals(golden.moving, totals.moving, "$what moving")
        assertEquals(golden.meters, totals.distance.meters, TOLERANCE, "$what distance")
        assertEquals(HeartRate(golden.averageHeartRate), totals.averageHeartRate, "$what avg HR")
        assertEquals(HeartRate(golden.maxHeartRate), totals.maxHeartRate, "$what max HR")
        assertEquals(
            golden.averageCadence,
            totals.averageCadence!!.stepsPerMinute,
            TOLERANCE,
            "$what avg cadence"
        )
        assertEquals(
            golden.maxCadence,
            totals.maxCadence!!.stepsPerMinute,
            TOLERANCE,
            "$what max cadence"
        )
        assertEquals(golden.ascent, totals.ascent.meters, TOLERANCE, "$what ascent")
        assertEquals(golden.descent, totals.descent.meters, TOLERANCE, "$what descent")
    }

    private companion object {
        const val TOLERANCE = 0.01
    }
}
