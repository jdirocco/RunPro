package com.runpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

private const val EPS = 1e-9

class UnitsTest {
    @Test
    fun `distance converts between metres, kilometres and miles`() {
        assertEquals(1609.344, Distance.miles(1.0).meters, EPS)
        assertEquals(42.195, Distance.kilometers(42.195).kilometers, EPS)
        assertEquals(26.218_757_5, Distance.kilometers(42.195).miles, 1e-6)
        assertEquals(1.0, Distance.of(1.0, DistanceUnit.MILE).inUnit(DistanceUnit.MILE), EPS)
        assertEquals(Distance(1500.0), Distance(1000.0) + Distance(500.0))
        assertEquals(Distance(500.0), Distance(1000.0) - Distance(500.0))
        assertEquals(true, Distance(1.0) < Distance(2.0))
    }

    @Test
    fun `speed converts to km per hour, miles per hour and pace`() {
        val speed = Speed.kilometersPerHour(12.0)
        assertEquals(10.0 / 3.0, speed.metersPerSecond, EPS)
        assertEquals(12.0, speed.kilometersPerHour, EPS)
        assertEquals(7.456_454, speed.milesPerHour, 1e-6)
        assertEquals(Pace.of(5.minutes, DistanceUnit.KILOMETER), speed.pace())
        assertEquals(12.0, Speed.milesPerHour(7.456_454_306).kilometersPerHour, 1e-6)
    }

    @Test
    fun `standing still has no pace`() {
        assertNull(Speed.ZERO.pace())
    }

    @Test
    fun `pace converts between kilometres and miles and back to speed`() {
        val pace = Pace.of(5.minutes, DistanceUnit.KILOMETER)
        assertEquals(300.0, pace.secondsPerKilometer, EPS)
        assertEquals(482.803_2, pace.secondsPerMile, 1e-6)
        assertEquals(482_803, pace.per(DistanceUnit.MILE).inWholeMilliseconds)
        assertEquals(12.0, pace.toSpeed().kilometersPerHour, EPS)
        val roundTrip = Pace.of(pace.per(DistanceUnit.MILE), DistanceUnit.MILE)
        assertEquals(pace.secondsPerKilometer, roundTrip.secondsPerKilometer, 1e-6)
    }

    @Test
    fun `elevation converts to feet`() {
        assertEquals(3280.839_895, Elevation(1000.0).feet, 1e-6)
        assertEquals(1000.0, Elevation.feet(3280.839_895_013).meters, 1e-6)
        assertEquals(-10.0, Elevation(-10.0).meters, EPS)
    }

    @Test
    fun `cadence converts steps to strides`() {
        assertEquals(90.0, Cadence(180.0).stridesPerMinute, EPS)
        assertEquals(Cadence(172.0), Cadence.stridesPerMinute(86.0))
    }

    @Test
    fun `acceleration converts from g and has a magnitude`() {
        val a = Acceleration.fromG(0.0, 0.0, 1.0)
        assertEquals(Acceleration.STANDARD_GRAVITY, a.z, EPS)
        assertEquals(5.0, Acceleration(3.0, 4.0, 0.0).magnitude, EPS)
    }

    @Test
    fun `units reject impossible values`() {
        assertFailsWith<IllegalArgumentException> { Distance(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Speed(-0.1) }
        assertFailsWith<IllegalArgumentException> { Speed(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { Pace(0.0) }
        assertFailsWith<IllegalArgumentException> { Elevation(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { HeartRate(-1) }
        assertFailsWith<IllegalArgumentException> { HeartRate(HeartRate.MAX_BPM + 1) }
        assertFailsWith<IllegalArgumentException> { Cadence(-1.0) }
        assertFailsWith<IllegalArgumentException> { Power(-1) }
        assertFailsWith<IllegalArgumentException> { Acceleration(Double.NaN, 0.0, 0.0) }
    }
}
