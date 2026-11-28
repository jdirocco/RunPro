package com.runpro.core.recording.location

import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The accuracy gate in front of `watch:fused-location` (ADR 0019), one per run. It decides which fixes
 * enter the run and derives the cumulative distance from the admitted ones.
 *
 * Admission: a fix needs valid coordinates, a measurement time newer than the last admitted fix and no
 * older than [maxAge] on arrival, a reported horizontal accuracy of at most [maxAccuracy] (Wi-Fi and cell
 * fixes report 50 m and more), not to be a mock, and a plausible speed from the last admitted fix.
 * A jump is kept out until [jumpConfirmations] consecutive fixes agree on the new place; the track then
 * restarts there and the jump itself adds no distance.
 *
 * Distance: while the Doppler speed says the runner moves (at least [movingSpeed]) every admitted fix
 * counts its straight distance from the last counted point. Otherwise a displacement counts only once it
 * leaves twice the combined accuracy of both points, so jitter while standing still adds nothing.
 * A [DistanceSample] is emitted for the first fix and whenever distance is counted, so the engine sees
 * the real duration of each step.
 *
 * Not thread safe: the controller calls it under its lock.
 */
class LocationFixGate(
    private val maxAccuracy: Distance = MAX_ACCURACY,
    private val maxAge: Duration = MAX_AGE,
    private val movingSpeed: Speed = MOVING_SPEED,
    private val maxSpeed: Speed = MAX_SPEED,
    private val jumpConfirmations: Int = JUMP_CONFIRMATIONS,
    private val source: SourceId = SourceId.FUSED_LOCATION
) {
    init {
        require(jumpConfirmations >= 1) { "Invalid jump confirmations: $jumpConfirmations" }
    }

    private class Point(
        val time: Timestamp,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double
    )

    private var last: Point? = null
    private var counted: Point? = null
    private val jump = mutableListOf<Point>()

    /** Distance counted so far in this run. */
    var distance: Distance = Distance.ZERO
        private set

    /** Judges [fix], which arrived at [now]. */
    fun admit(fix: LocationFix, now: Timestamp): FixVerdict {
        val problem = problemOf(fix, now)
        if (problem != null) return FixVerdict.Rejected(problem)
        val accuracy = fix.horizontalAccuracyMeters
        val point = Point(timeOf(fix, now), fix.latitude, fix.longitude, accuracy)
        val previous = last
        return if (previous != null && !plausible(previous, point)) {
            jumped(fix, point)
        } else {
            jump.clear()
            last = point
            FixVerdict.Accepted(samplesOf(fix, point, distanceCounted = count(fix, point)))
        }
    }

    /** A fix far from the last one: rejected until enough fixes agree, then the track restarts there. */
    private fun jumped(fix: LocationFix, point: Point): FixVerdict {
        if (jump.isNotEmpty() && !plausible(jump.last(), point)) jump.clear()
        jump += point
        if (jump.size < jumpConfirmations) return FixVerdict.Rejected(FixRejection.JUMP)
        // The new place is confirmed: restart the track there without counting the jump.
        jump.clear()
        last = point
        counted = point
        return FixVerdict.Accepted(samplesOf(fix, point, distanceCounted = false))
    }

    /** Counts the step to [point] when it is real movement; true when the counted point moved. */
    private fun count(fix: LocationFix, point: Point): Boolean {
        val anchor = counted
        val step = anchor?.let { from ->
            val meters = metersBetween(from, point)
            val moving = fix.speedMetersPerSecond >= movingSpeed.metersPerSecond
            meters.takeIf { moving || meters > 2 * (from.accuracy + point.accuracy) }
        }
        val moved = anchor == null || step != null
        if (moved) {
            counted = point
            distance += Distance(step ?: 0.0)
        }
        return moved
    }

    private fun problemOf(fix: LocationFix, now: Timestamp): FixRejection? {
        val validPosition =
            fix.latitude.isFinite() && fix.latitude in -MAX_LATITUDE..MAX_LATITUDE &&
                fix.longitude.isFinite() && fix.longitude in -MAX_LONGITUDE..MAX_LONGITUDE
        val accuracy = fix.horizontalAccuracyMeters
        val previous = last
        return when {
            fix.elapsedNanos < 0 || !validPosition -> FixRejection.INVALID

            fix.mock -> FixRejection.MOCK

            !accuracy.isFinite() || accuracy < 0.0 -> FixRejection.NO_ACCURACY

            previous != null && fix.elapsedNanos <= previous.time.elapsedRealtimeNanos ->
                FixRejection.OUT_OF_ORDER

            now.elapsedRealtimeNanos - fix.elapsedNanos > maxAge.inWholeNanoseconds ->
                FixRejection.STALE

            accuracy > maxAccuracy.meters -> FixRejection.COARSE

            else -> null
        }
    }

    /** At most [maxSpeed] between [from] and [to], allowing for the accuracy of both. */
    private fun plausible(from: Point, to: Point): Boolean {
        val seconds = to.time.elapsedSince(from.time).toDouble(DurationUnit.SECONDS)
        val beyondAccuracy = metersBetween(from, to) - from.accuracy - to.accuracy
        return beyondAccuracy <= maxSpeed.metersPerSecond * seconds
    }

    private fun samplesOf(fix: LocationFix, point: Point, distanceCounted: Boolean): List<Sample> =
        buildList {
            add(
                LocationSample(
                    point.time,
                    source,
                    point.latitude,
                    point.longitude,
                    horizontalAccuracy = Distance(point.accuracy)
                )
            )
            if (distanceCounted) add(DistanceSample(point.time, source, distance))
            val speed = fix.speedMetersPerSecond
            if (speed.isFinite() && speed >= 0.0 && speed <= maxSpeed.metersPerSecond) {
                add(SpeedSample(point.time, source, Speed(speed)))
            }
        }

    companion object {
        /** Satellite fixes near buildings report about 5–20 m; Wi-Fi and cell 50–120 m and more. */
        val MAX_ACCURACY = Distance(20.0)

        /** A fix measured longer ago when it arrives is a cached location. */
        val MAX_AGE: Duration = 10.seconds

        /** Doppler speed from which the runner is moving; standing still reads below 0.5 m/s. */
        val MOVING_SPEED = Speed(0.8)

        /** Above any runner's sprint: faster speeds and jumps are not a run. */
        val MAX_SPEED = Speed(12.0)

        const val JUMP_CONFIRMATIONS = 3

        /** Mean Earth radius (IUGG), for great-circle distances. */
        const val EARTH_RADIUS_METERS = 6_371_008.8

        private const val MAX_LATITUDE = 90.0
        private const val MAX_LONGITUDE = 180.0

        private fun metersBetween(a: Point, b: Point): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h =
                sin(dLat / 2).let { it * it } +
                    cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
            return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(max(0.0, h))))
        }

        /** The fix's own monotonic time, with the wall clock derived from its arrival. */
        private fun timeOf(fix: LocationFix, now: Timestamp): Timestamp {
            val probe = Timestamp(fix.elapsedNanos, 0)
            return Timestamp(fix.elapsedNanos, probe.wallClockFrom(now))
        }
    }
}
