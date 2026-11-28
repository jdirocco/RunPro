package com.runpro.wear.location

import android.location.Location
import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Play services locations become gate inputs with nothing invented (#96, ADR 0019). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class FusedLocationMappingTest {
    @Test
    fun `a fix keeps its own time, accuracy and speed`() {
        val location = Location("fused").apply {
            latitude = 45.47
            longitude = 9.18
            accuracy = 14f
            speed = 2.5f
            elapsedRealtimeNanos = 123_456_789_000L
        }
        val fix = FusedLocationMapping.fixOf(location)
        assertEquals(123_456_789_000L, fix.elapsedNanos)
        assertEquals(45.47, fix.latitude, 0.0)
        assertEquals(9.18, fix.longitude, 0.0)
        assertEquals(14.0, fix.horizontalAccuracyMeters, 0.0)
        assertEquals(2.5, fix.speedMetersPerSecond, 0.0)
        assertFalse(fix.mock)
    }

    @Test
    fun `missing accuracy and speed are NaN, not zero`() {
        val fix = FusedLocationMapping.fixOf(
            Location("fused").apply {
                latitude = 45.47
                longitude = 9.18
            }
        )
        assertTrue(fix.horizontalAccuracyMeters.isNaN())
        assertTrue(fix.speedMetersPerSecond.isNaN())
    }

    @Test
    @Config(sdk = [34])
    fun `a mock location is marked`() {
        val location = Location("fused").apply {
            accuracy = 3f
            isMock = true
        }
        assertTrue(FusedLocationMapping.fixOf(location).mock)
    }

    @Test
    fun `updates are high accuracy, once a second and not batched`() {
        val request = FusedLocationMapping.request()
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, request.priority)
        assertEquals(1_000L, request.intervalMillis)
        assertEquals(1_000L, request.minUpdateIntervalMillis)
        // No delay beyond one interval: no batching.
        assertEquals(1_000L, request.maxUpdateDelayMillis)
    }
}
