package com.runpro.core.recording

/**
 * The active and paused intervals of one run on the monotonic clock (nanoseconds).
 *
 * Samples are judged by their own timestamp, not by when they arrive: Health Services delivers batches, so a
 * sample taken just before the pause tap can arrive after it and must still count. A pause covers the open
 * interval (start, end): a sample taken exactly at the pause or resume time is active.
 */
internal class RunTimeline(private val start: Long) {
    private class Pause(val start: Long, var end: Long = Long.MAX_VALUE)

    private val pauses = mutableListOf<Pause>()
    private var stop = Long.MAX_VALUE

    fun pause(time: Long) {
        pauses += Pause(time)
    }

    fun resume(time: Long) {
        pauses.last().end = time
    }

    fun stop(time: Long) {
        stop = time
    }

    fun isActiveAt(time: Long): Boolean =
        time in start..stop && pauses.none { it.start < time && time < it.end }

    /** True when both ends are active and no pause lies between them. */
    fun isActiveBetween(from: Long, to: Long): Boolean = isActiveAt(from) &&
        isActiveAt(to) &&
        pauses.none { it.start < to && it.end > from }

    /** Timer time (pauses excluded) from the start to [time], clamped to the start and the stop. */
    fun timerAt(time: Long): Long {
        val clamped = time.coerceIn(start, maxOf(start, stop))
        val paused = pauses.sumOf { pause ->
            if (pause.start < clamped) minOf(clamped, pause.end) - pause.start else 0L
        }
        return clamped - start - paused
    }
}
