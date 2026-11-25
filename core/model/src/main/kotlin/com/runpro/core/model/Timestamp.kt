package com.runpro.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * When something happened, on two clocks.
 *
 * [elapsedRealtimeNanos] is the monotonic clock (Android `SystemClock.elapsedRealtimeNanos()`, which keeps
 * counting in deep sleep). It is the source of truth for durations and ordering, because the wall clock can
 * jump (NTP, time zone, manual change). It restarts from zero when the watch reboots, so it is comparable
 * only within one boot.
 *
 * [wallClockMillis] is the wall clock (`System.currentTimeMillis()`, Unix epoch) at the same instant. It is
 * kept for display and export; use [wallClockFrom] to get a wall time free of jumps.
 *
 * Ordering compares the monotonic clock first and the wall clock only to break ties, so it is consistent
 * with equality.
 */
@Serializable
data class Timestamp(
    @SerialName("elapsedNanos") val elapsedRealtimeNanos: Long,
    @SerialName("wallMillis") val wallClockMillis: Long
) : Comparable<Timestamp> {
    init {
        require(elapsedRealtimeNanos >= 0) {
            "Monotonic time cannot be negative: $elapsedRealtimeNanos"
        }
    }

    /** Monotonic time from [earlier] to this timestamp (negative if [earlier] is after it). */
    fun elapsedSince(earlier: Timestamp): Duration {
        val nanos = elapsedRealtimeNanos - earlier.elapsedRealtimeNanos
        return nanos.nanoseconds
    }

    /**
     * The wall clock at this instant derived from [anchor] and the monotonic clock,
     * ignoring wall clock jumps since the anchor.
     */
    fun wallClockFrom(anchor: Timestamp): Long = anchor.wallClockMillis +
        Math.floorDiv(elapsedRealtimeNanos - anchor.elapsedRealtimeNanos, NANOS_PER_MILLI)

    /** This timestamp moved forward by [duration] on both clocks. */
    operator fun plus(duration: Duration): Timestamp = Timestamp(
        elapsedRealtimeNanos = elapsedRealtimeNanos + duration.inWholeNanoseconds,
        wallClockMillis = wallClockMillis + duration.inWholeMilliseconds
    )

    override fun compareTo(other: Timestamp): Int = compareValuesBy(
        this,
        other,
        Timestamp::elapsedRealtimeNanos,
        Timestamp::wallClockMillis
    )
}
