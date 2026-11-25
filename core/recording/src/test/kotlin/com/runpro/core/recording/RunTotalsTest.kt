package com.runpro.core.recording

import com.runpro.core.model.Cadence
import com.runpro.core.model.Distance
import com.runpro.core.model.Elevation
import com.runpro.core.model.HeartRate
import com.runpro.core.model.Speed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RunTotalsTest {
    private fun RunState.meters() = totals.distance.meters

    @Test
    fun `distance is the sum of the deltas of the cumulative samples`() {
        val state = runningMachine().feed((0..10).map { dist(it, 1000.0 + 3.0 * it) })
        assertEquals(30.0, state.meters(), 1e-9)
    }

    @Test
    fun `samples received while preparing do not count`() {
        val machine = machine()
        machine.feed(prepare(0), dist(1, 50.0), dist(2, 53.0), hr(2, 190), start(3))
        val state = machine.feed(dist(4, 56.0), dist(5, 59.0))
        assertEquals(3.0, state.meters(), 1e-9)
        assertNull(state.totals.maxHeartRate)
    }

    @Test
    fun `distance covered during a pause is not counted`() {
        // A footpod keeps counting while the runner walks during the pause.
        val inputs = (0..10).map { dist(it, 3.0 * it) } +
            pause(10) +
            (11..19).map { dist(it, 30.0 + 1.5 * (it - 10)) } +
            resume(20) +
            (20..30).map { dist(it, 45.0 + 3.0 * (it - 20)) }
        val state = runningMachine().feed(inputs)
        assertEquals(60.0, state.meters(), 1e-9)
    }

    @Test
    fun `a late sample timestamped before the pause still counts`() {
        // Health Services delivers batches: the sample of second 10 arrives after the pause tap.
        val state = runningMachine().feed(
            (0..9).map { dist(it, 3.0 * it) } + pause(10) + dist(10, 30.0)
        )
        assertEquals(30.0, state.meters(), 1e-9)
    }

    @Test
    fun `a decreasing total is a source reset and starts a new segment`() {
        val state = runningMachine().feed(
            dist(0, 500.0),
            dist(1, 503.0),
            dist(2, 506.0),
            dist(3, 0.0),
            dist(4, 3.0)
        )
        assertEquals(9.0, state.meters(), 1e-9)
    }

    @Test
    fun `samples older than the last one of the same metric are dropped`() {
        val state = runningMachine().feed(dist(0, 0.0), dist(2, 6.0), dist(1, 3.0), dist(3, 9.0))
        assertEquals(9.0, state.meters(), 1e-9)
    }

    @Test
    fun `only the first source of a metric is used until source arbitration exists`() {
        val state = runningMachine().feed(
            dist(0, 0.0, WATCH),
            dist(0, 0.0, FOOTPOD),
            dist(1, 3.0, WATCH),
            dist(1, 3.2, FOOTPOD),
            dist(2, 6.0, WATCH),
            dist(2, 6.4, FOOTPOD),
            hr(1, 150, STRAP),
            hr(2, 100, WATCH),
            hr(3, 150, STRAP)
        )
        assertEquals(6.0, state.meters(), 1e-9)
        assertEquals(HeartRate(150), state.totals.maxHeartRate)
        assertEquals(HeartRate(150), state.current.heartRate)
    }

    @Test
    fun `moving time excludes standing still but the timer does not`() {
        val inputs = (0..20).map { dist(it, 3.0 * it) } +
            (21..40).map { dist(it, 60.0) } +
            (41..60).map { dist(it, 60.0 + 3.0 * (it - 40)) }
        val state = runningMachine().feed(inputs)
        assertEquals(40.seconds, state.totals.moving)
        assertEquals(60.seconds, state.totals.timer)
        assertEquals(120.0, state.meters(), 1e-9)
    }

    @Test
    fun `average pace is timer time over distance`() {
        val state = runningMachine().feed((0..100).map { dist(it, 2.5 * it) })
        assertEquals(Speed(2.5), state.totals.averageSpeed)
        assertEquals(400.0, state.totals.averagePace!!.secondsPerKilometer, 1e-9)
    }

    @Test
    fun `there is no average pace before any distance`() {
        val state = runningMachine().feed(tick(30))
        assertEquals(Speed.ZERO, state.totals.averageSpeed)
        assertNull(state.totals.averagePace)
        assertNull(runningMachine().state.totals.averageSpeed)
    }

    @Test
    fun `average heart rate is weighted by time, not by sample count`() {
        // Strap at 1 Hz for 10 s at 100 bpm, then wrist every 5 s for 30 s at 200 bpm.
        val inputs = (0..9).map { hr(it, 100) } + (10..40 step 5).map { hr(it, 200) } + stop(40)
        val state = runningMachine().feed(inputs)
        assertEquals(HeartRate(175), state.totals.averageHeartRate)
        assertEquals(HeartRate(200), state.totals.maxHeartRate)
    }

    @Test
    fun `a heart rate value is held at most for the hold limit`() {
        val config = EngineConfig(maxSampleHold = 5.seconds)
        val state = runningMachine(config).feed(
            (0..9).map { hr(it, 100) } + hr(60, 200) + (61..70).map { hr(it, 200) } + stop(70)
        )
        // 100 bpm for 10 s (last sample held 1 s + capped gap), 200 bpm for 10 s.
        // Weights: 100 x (9 + 5) s, 200 x 10 s.
        val expected = (100.0 * 14 + 200.0 * 10) / 24
        assertEquals(HeartRate(Math.round(expected).toInt()), state.totals.averageHeartRate)
    }

    @Test
    fun `zero heart rate means no skin contact and is ignored`() {
        val inputs = (0..9).map { hr(it, 120) } + (10..19).map { hr(it, 0) } +
            (20..30).map { hr(it, 140) } + stop(30)
        val state = runningMachine().feed(inputs)
        assertEquals(HeartRate(130), state.totals.averageHeartRate)
        assertEquals(HeartRate(140), state.totals.maxHeartRate)
        assertEquals(HeartRate(140), state.current.heartRate)
    }

    @Test
    fun `heart rate during a pause does not count`() {
        val inputs = (0..10).map { hr(it, 120) } + pause(10) + (11..19).map { hr(it, 190) } +
            resume(20) + (20..30).map { hr(it, 140) } + stop(30)
        val state = runningMachine().feed(inputs)
        assertEquals(HeartRate(130), state.totals.averageHeartRate)
        assertEquals(HeartRate(140), state.totals.maxHeartRate)
    }

    @Test
    fun `average cadence ignores standing still`() {
        val inputs = (0..9).map { cad(it, 160.0) } + (10..19).map { cad(it, 0.0) } +
            (20..30).map { cad(it, 180.0) } + stop(30)
        val state = runningMachine().feed(inputs)
        assertEquals(170.0, state.totals.averageCadence!!.stepsPerMinute, 1e-9)
        assertEquals(Cadence(180.0), state.totals.maxCadence)
    }

    @Test
    fun `ascent and descent ignore noise below the threshold`() {
        val noise = listOf(0.0, 1.0, -1.0, 1.5, -1.5, 0.5)
        val inputs = noise.mapIndexed { i, d -> elev(i, 100.0 + d) } +
            (6..26).map { elev(it, 100.0 + (it - 6) * 0.5) } +
            (27..36).map { elev(it, 110.0 - (it - 26) * 1.0) }
        val state = runningMachine(EngineConfig(elevationThreshold = Distance(2.0))).feed(inputs)
        assertEquals(10.0, state.totals.ascent.meters, 1e-9)
        assertEquals(10.0, state.totals.descent.meters, 1e-9)
    }

    @Test
    fun `climbing during a pause is not counted as ascent`() {
        val inputs = (0..10).map { elev(it, 100.0) } + pause(10) +
            (11..19).map { elev(it, 100.0 + (it - 10) * 2.0) } + resume(20) +
            (20..32).map { elev(it, 120.0 + (it - 20) * 0.5) }
        val state = runningMachine().feed(inputs)
        assertEquals(6.0, state.totals.ascent.meters, 1e-9)
        assertEquals(0.0, state.totals.descent.meters, 1e-9)
    }

    @Test
    fun `laps have their own totals`() {
        // The heart rate sample taken at the lap mark belongs to the new lap.
        val inputs = (0..60).map { listOf(dist(it, 3.0 * it), hr(it, if (it < 60) 130 else 160)) }
            .flatten() +
            lap(60) +
            (61..90).map { listOf(dist(it, 180.0 + 2.0 * (it - 60)), hr(it, 160)) }.flatten() +
            stop(90)
        val state = runningMachine().feed(inputs)
        val (first, second) = state.laps
        assertEquals(180.0, first.totals.distance.meters, 1e-9)
        assertEquals(60.seconds, first.totals.timer)
        assertEquals(HeartRate(130), first.totals.averageHeartRate)
        assertEquals(60.0, second.totals.distance.meters, 1e-9)
        assertEquals(30.seconds, second.totals.timer)
        assertEquals(HeartRate(160), second.totals.averageHeartRate)
        assertEquals(HeartRate(160), second.totals.maxHeartRate)
        assertEquals(240.0, state.meters(), 1e-9)
        assertEquals(90.seconds, state.totals.timer)
    }

    @Test
    fun `current values follow the latest samples, also while preparing`() {
        val machine = machine()
        val preparing = machine.feed(prepare(0), hr(1, 92), elev(1, 250.0))
        assertEquals(HeartRate(92), preparing.current.heartRate)
        assertEquals(Elevation(250.0), preparing.current.elevation)

        val running = machine.feed(start(2), speed(3, 3.2), cad(3, 172.0), hr(3, 140))
        assertEquals(Speed(3.2), running.current.speed)
        assertEquals(Cadence(172.0), running.current.cadence)
        assertEquals(HeartRate(140), running.current.heartRate)
    }
}
