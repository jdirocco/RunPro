package com.runpro.core.recording

import kotlin.math.abs

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * The last value of a metric (heart rate, cadence), held until the next sample for at most [maxHoldNanos] of
 * timer time. Averages weight each value by how long it was held, so sources with different sample rates
 * (strap 1 Hz, wrist every few seconds) give the same average.
 */
internal class HeldValue(private val maxHoldNanos: Long) {
    private var value: Double? = null

    /** Timer time up to which the value has been accounted for. */
    private var accountedUntil = 0L
    private var held = 0L

    /** Hands the held value and its duration up to [timer] to [add], then moves the accounting point. */
    fun advanceTo(timer: Long, add: (value: Double, nanos: Long) -> Unit) {
        val current = value ?: return
        val nanos = (timer - accountedUntil).coerceIn(0L, maxHoldNanos - held)
        if (nanos > 0) add(current, nanos)
        held += nanos
        accountedUntil = maxOf(accountedUntil, timer)
    }

    fun hold(value: Double, timer: Long) {
        this.value = value
        // A late sample (older than a lap mark already accounted for) must not count that time twice.
        accountedUntil = maxOf(accountedUntil, timer)
        held = 0L
    }

    fun clear() {
        value = null
    }
}

/** What one distance sample adds to the totals. */
internal data class DistanceStep(val meters: Double, val movingNanos: Long)

/**
 * Turns the cumulative distance of one source into steps. Only intervals that are fully active count, so
 * distance covered during a pause is dropped (a footpod keeps counting while the runner walks). A decrease
 * means the source reset: the interval is dropped and a new segment starts from that sample.
 */
internal class DistanceTracker(private val minMovingSpeed: Double) {
    private var lastTime: Long? = null
    private var lastTotal = 0.0

    fun update(time: Long, total: Double, timeline: RunTimeline): DistanceStep? {
        val previousTime = lastTime
        val delta = total - lastTotal
        lastTime = time
        lastTotal = total
        if (previousTime == null || delta < 0.0 || !timeline.isActiveBetween(previousTime, time)) {
            return null
        }
        val nanos = timeline.timerAt(time) - timeline.timerAt(previousTime)
        val moving = nanos > 0 && delta / (nanos / NANOS_PER_SECOND) >= minMovingSpeed
        return DistanceStep(delta, if (moving) nanos else 0L)
    }
}

/**
 * Ascent and descent with hysteresis: a change counts once it reaches [threshold] from the last reference
 * elevation, which then moves to the new value. After a pause the reference restarts, so a climb during the
 * pause does not count.
 */
internal class ElevationTracker(private val threshold: Double) {
    private var lastTime: Long? = null
    private var reference: Double? = null

    /** The counted change (positive ascent, negative descent), or 0 when nothing counts. */
    fun update(time: Long, elevation: Double, timeline: RunTimeline): Double {
        val previousTime = lastTime
        val base = reference
        lastTime = time
        val continuous = base != null &&
            previousTime != null &&
            timeline.isActiveBetween(previousTime, time)
        val change = if (continuous && base != null) elevation - base else 0.0
        val counted = abs(change) >= threshold
        reference = when {
            !timeline.isActiveAt(time) -> null
            !continuous || counted -> elevation
            else -> base
        }
        return if (counted) change else 0.0
    }
}
