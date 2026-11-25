package com.runpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TimestampTest {
    private val start =
        Timestamp(elapsedRealtimeNanos = 5_000_000_000, wallClockMillis = 1_764_064_800_000)

    @Test
    fun `elapsed time uses the monotonic clock only`() {
        // The wall clock jumped back one hour (time zone or NTP correction), the monotonic clock did not.
        val later = Timestamp(
            elapsedRealtimeNanos = 7_500_000_000,
            wallClockMillis =
                start.wallClockMillis - 3_600_000
        )
        assertEquals(2.5.seconds, later.elapsedSince(start))
    }

    @Test
    fun `wall clock can be projected from an anchor through monotonic time`() {
        val later = Timestamp(elapsedRealtimeNanos = 7_500_000_000, wallClockMillis = 0)
        assertEquals(start.wallClockMillis + 2_500, later.wallClockFrom(start))
    }

    @Test
    fun `adding a duration advances both clocks`() {
        val later = start + 1_500.milliseconds
        assertEquals(6_500_000_000, later.elapsedRealtimeNanos)
        assertEquals(start.wallClockMillis + 1_500, later.wallClockMillis)
    }

    @Test
    fun `timestamps are ordered by monotonic time`() {
        val later = Timestamp(elapsedRealtimeNanos = 5_000_000_001, wallClockMillis = 0)
        assertTrue(start < later)
        assertEquals(listOf(start, later), listOf(later, start).sorted())
    }

    @Test
    fun `negative monotonic time is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Timestamp(elapsedRealtimeNanos = -1, wallClockMillis = 0)
        }
    }
}
