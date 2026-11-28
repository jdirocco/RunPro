package com.runpro.wear

import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceUnit
import com.runpro.core.model.HeartRate
import com.runpro.core.model.Pace
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class RunFormatTest {
    @Test
    fun `clock shows minutes and seconds, and hours only when needed`() {
        assertEquals("0:00", clock(0.seconds))
        assertEquals("9:05", clock(545.seconds))
        assertEquals("1:02:03", clock(3723.seconds))
    }

    @Test
    fun `distance is in kilometres with two decimals, truncated so it never runs ahead`() {
        assertEquals("0.00", kilometers(Distance(0.0), Locale.US))
        assertEquals("4.99", kilometers(Distance(4_999.9), Locale.US))
        assertEquals("12,34", kilometers(Distance(12_345.0), Locale.ITALY))
    }

    @Test
    fun `pace is minutes per kilometre, a dash without a usable pace`() {
        assertEquals("5:12", pace(Pace(312.0)))
        assertEquals("4:00", pace(Pace(239.6)))
        assertEquals(NO_VALUE, pace(null))
        // Slower than 30 min/km is walking or standing: not a pace worth showing.
        assertEquals(NO_VALUE, pace(Pace(31.minutes.inWholeSeconds.toDouble())))
    }

    @Test
    fun `imperial pace converts seconds per kilometer to seconds per mile`() {
        assertEquals("8:03", pace(Pace(300.0), DistanceUnit.MILE))
        assertEquals(NO_VALUE, pace(null, DistanceUnit.MILE))
        assertEquals(NO_VALUE, pace(Pace(1860.0), DistanceUnit.MILE))
    }

    @Test
    fun `heart rate is in bpm, a dash without a reading`() {
        assertEquals("152", heartRate(HeartRate(152)))
        assertEquals(NO_VALUE, heartRate(null))
    }
}
