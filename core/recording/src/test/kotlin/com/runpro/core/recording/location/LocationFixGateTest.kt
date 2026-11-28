package com.runpro.core.recording.location

import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.LocationSample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.recording.TestTime
import com.runpro.core.recording.TestTime.at
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #96 (ADR 0019): the accuracy gate in front of `watch:fused-location`. Coarse Wi-Fi or cell fixes,
 * cached, mock and out-of-order fixes never reach the run, and a runner standing still accumulates no
 * distance from jitter.
 */
class LocationFixGateTest {
    @Test
    fun `a precise fix becomes location, cumulative distance and speed samples`() {
        val gate = LocationFixGate()
        val verdict = gate.admit(fix(0.0, north = 0.0, accuracy = 14.0, speed = 2.5), at(0.3))
        val samples = assertIs<FixVerdict.Accepted>(verdict).samples
        val location = assertIs<LocationSample>(samples[0])
        assertEquals(SourceId.FUSED_LOCATION, location.source)
        assertEquals(at(0.0), location.time) // the fix's own time, with the wall clock derived
        assertEquals(Distance(14.0), location.horizontalAccuracy)
        assertEquals(LAT, location.latitude, 1e-9)
        assertEquals(DistanceSample(at(0.0), SourceId.FUSED_LOCATION, Distance.ZERO), samples[1])
        assertEquals(SpeedSample(at(0.0), SourceId.FUSED_LOCATION, Speed(2.5)), samples[2])
    }

    @Test
    fun `coarse network fixes are rejected and the accuracy limit is inclusive`() {
        val gate = LocationFixGate()
        assertEquals(rejected(FixRejection.COARSE), gate.admit(fix(0.0, accuracy = 50.0), at(0)))
        assertEquals(rejected(FixRejection.COARSE), gate.admit(fix(1.0, accuracy = 120.0), at(1)))
        assertEquals(rejected(FixRejection.COARSE), gate.admit(fix(2.0, accuracy = 20.1), at(2)))
        assertIs<FixVerdict.Accepted>(gate.admit(fix(3.0, accuracy = 20.0), at(3)))
    }

    @Test
    fun `fixes without accuracy, mock, invalid, cached or out of order fixes are rejected`() {
        val gate = LocationFixGate()
        assertEquals(
            rejected(FixRejection.NO_ACCURACY),
            gate.admit(fix(0.0, accuracy = Double.NaN), at(0))
        )
        assertEquals(rejected(FixRejection.MOCK), gate.admit(fix(0.0, mock = true), at(0)))
        assertEquals(
            rejected(FixRejection.INVALID),
            gate.admit(LocationFix(at(0).elapsedRealtimeNanos, 91.0, LON, 5.0), at(0))
        )
        assertEquals(
            rejected(FixRejection.INVALID),
            gate.admit(LocationFix(at(0).elapsedRealtimeNanos, LAT, Double.NaN, 5.0), at(0))
        )
        assertEquals(
            rejected(FixRejection.INVALID),
            gate.admit(LocationFix(-1, LAT, LON, 5.0), at(0))
        )
        // A cached last location delivered when updates start is older than 10 s.
        assertEquals(rejected(FixRejection.STALE), gate.admit(fix(0.0), at(10.001)))
        assertIs<FixVerdict.Accepted>(gate.admit(fix(1.0), at(11.0)))
        assertEquals(rejected(FixRejection.OUT_OF_ORDER), gate.admit(fix(1.0), at(11.5)))
        assertEquals(rejected(FixRejection.OUT_OF_ORDER), gate.admit(fix(0.5), at(11.5)))
        assertEquals(Distance.ZERO, gate.distance)
    }

    @Test
    fun `standing still with jitter inside the accuracy adds no distance, with or without speed`() {
        for (speed in listOf(Double.NaN, 0.3)) {
            val gate = LocationFixGate()
            for (s in 0 until 600) {
                // Up to 10 m of jitter around the start, inside the reported 14 m.
                val north = 10.0 * sin(s / 7.0)
                val east = 10.0 * cos(s / 3.0) * cos(s / 11.0)
                val verdict = gate.admit(fix(s.toDouble(), north, east, 14.0, speed), at(s + 0.2))
                assertIs<FixVerdict.Accepted>(verdict)
            }
            assertEquals(Distance.ZERO, gate.distance, "speed $speed")
        }
    }

    @Test
    fun `coarse fixes while standing still neither pass nor move the counted position`() {
        val gate = LocationFixGate()
        for (s in 0 until 120) {
            val coarse = s % 3 == 0
            val verdict = if (coarse) {
                // Wi-Fi / cell triangulation, 80-150 m away.
                gate.admit(fix(s.toDouble(), 80.0 + s % 70, -60.0, accuracy = 90.0), at(s))
            } else {
                gate.admit(fix(s.toDouble(), 2.0 * sin(s.toDouble()), 0.0, accuracy = 8.0), at(s))
            }
            if (coarse) assertEquals(rejected(FixRejection.COARSE), verdict)
        }
        assertEquals(Distance.ZERO, gate.distance)
    }

    @Test
    fun `running with Doppler speed counts every second`() {
        val gate = LocationFixGate()
        val emitted = mutableListOf<DistanceSample>()
        for (s in 0..300) {
            val jitter = 2.0 * sin(s / 5.0)
            val verdict = gate.admit(fix(s.toDouble(), 3.0 * s, jitter, 8.0, 3.0), at(s + 0.2))
            emitted +=
                assertIs<FixVerdict.Accepted>(verdict).samples.filterIsInstance<DistanceSample>()
        }
        assertEquals(900.0, gate.distance.meters, 900.0 * 0.03)
        assertEquals(301, emitted.size)
        assertEquals(gate.distance, emitted.last().total)
    }

    @Test
    fun `running without speed counts once the runner leaves the accuracy circle`() {
        val gate = LocationFixGate()
        val emitted = mutableListOf<DistanceSample>()
        for (s in 0..300) {
            val verdict = gate.admit(fix(s.toDouble(), 3.0 * s, 0.0, 8.0), at(s + 0.2))
            emitted +=
                assertIs<FixVerdict.Accepted>(verdict).samples.filterIsInstance<DistanceSample>()
        }
        // Counted in straight steps from the last counted point: at most one step (2 x 16 m) is pending.
        assertTrue(gate.distance.meters in 900.0 - 33.0..900.0, "distance ${gate.distance}")
        // A distance sample only when something is counted, so the engine sees each step's real duration.
        assertTrue(emitted.size in 20..40, "${emitted.size} distance samples")
        val steps = emitted.zipWithNext { a, b ->
            (b.total.meters - a.total.meters) / b.time.elapsedSince(a.time).inWholeMilliseconds *
                1000
        }
        assertTrue(steps.all { it in 2.5..3.5 }, "step speeds $steps")
    }

    @Test
    fun `a single jump is rejected and a lasting one restarts the track without distance`() {
        val gate = LocationFixGate()
        for (s in 0..10) gate.admit(fix(s.toDouble(), 3.0 * s, 0.0, 5.0, 3.0), at(s))
        val before = gate.distance
        assertEquals(
            rejected(FixRejection.JUMP),
            gate.admit(fix(11.0, 400.0, 0.0, 5.0, 3.0), at(11))
        )
        assertIs<FixVerdict.Accepted>(gate.admit(fix(12.0, 36.0, 0.0, 5.0, 3.0), at(12)))
        assertEquals(before.meters + 6.0, gate.distance.meters, 1e-6)

        // Three fixes in a row 1 km away: the track restarts there, the jump itself is not distance.
        val counted = gate.distance
        assertEquals(
            rejected(FixRejection.JUMP),
            gate.admit(fix(13.0, 1000.0, 0.0, 5.0, 3.0), at(13))
        )
        assertEquals(
            rejected(FixRejection.JUMP),
            gate.admit(fix(14.0, 1003.0, 0.0, 5.0, 3.0), at(14))
        )
        assertIs<FixVerdict.Accepted>(gate.admit(fix(15.0, 1006.0, 0.0, 5.0, 3.0), at(15)))
        assertEquals(counted, gate.distance)
        assertIs<FixVerdict.Accepted>(gate.admit(fix(16.0, 1009.0, 0.0, 5.0, 3.0), at(16)))
        assertEquals(counted.meters + 3.0, gate.distance.meters, 1e-6)
    }

    @Test
    fun `an impossible speed reading is dropped but the fix is kept`() {
        val gate = LocationFixGate()
        val samples = assertIs<FixVerdict.Accepted>(
            gate.admit(fix(0.0, speed = 40.0), at(0))
        ).samples
        assertEquals(
            listOf(LocationSample::class, DistanceSample::class),
            samples.map {
                it::class
            }
        )
    }

    @Test
    fun `distance across a gap of rejected fixes counts once the runner is seen again`() {
        val gate = LocationFixGate()
        for (s in 0..60) {
            // 20-40 s: only coarse fixes while the runner keeps going at 3 m/s.
            val accuracy = if (s in 20 until 40) 70.0 else 6.0
            gate.admit(fix(s.toDouble(), 3.0 * s, 0.0, accuracy, 3.0), at(s))
        }
        assertEquals(180.0, gate.distance.meters, 1e-6)
    }

    private fun rejected(reason: FixRejection) = FixVerdict.Rejected(reason)

    private fun fix(
        seconds: Double,
        north: Double = 0.0,
        east: Double = 0.0,
        accuracy: Double = 5.0,
        speed: Double = Double.NaN,
        mock: Boolean = false
    ) = LocationFix(
        elapsedNanos = TestTime.at(seconds).elapsedRealtimeNanos,
        latitude = LAT + north / METERS_PER_DEGREE,
        longitude = LON + east / (METERS_PER_DEGREE * cos(Math.toRadians(LAT))),
        horizontalAccuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        mock = mock
    )

    private companion object {
        const val LAT = 45.47
        const val LON = 9.18
        val METERS_PER_DEGREE = LocationFixGate.EARTH_RADIUS_METERS * Math.PI / 180.0
    }
}
