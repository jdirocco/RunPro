package com.runpro.core.model

import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.Serializable

// Units are stored in SI (metres, seconds, m/s) and converted only for display or export.
// Constructors reject impossible values; sensor adapters must validate raw input before building them,
// so a bad reading is dropped at the edge instead of reaching the recording path.

private const val METERS_PER_KILOMETER = 1000.0
private const val METERS_PER_MILE = 1609.344
private const val METERS_PER_FOOT = 0.3048
private const val SECONDS_PER_HOUR = 3600.0
private const val NANOS_PER_SECOND = 1_000_000_000.0

/** Unit used to express distances and paces to the runner. */
enum class DistanceUnit(val meters: Double) {
    KILOMETER(METERS_PER_KILOMETER),
    MILE(METERS_PER_MILE)
}

/** A length in metres. May be negative when it is a difference. */
@Serializable
@JvmInline
value class Distance(val meters: Double) : Comparable<Distance> {
    init {
        require(meters.isFinite()) { "Distance must be finite: $meters" }
    }

    val kilometers: Double get() = meters / METERS_PER_KILOMETER
    val miles: Double get() = meters / METERS_PER_MILE

    fun inUnit(unit: DistanceUnit): Double = meters / unit.meters

    operator fun plus(other: Distance): Distance = Distance(meters + other.meters)

    operator fun minus(other: Distance): Distance = Distance(meters - other.meters)

    override fun compareTo(other: Distance): Int = meters.compareTo(other.meters)

    companion object {
        val ZERO = Distance(0.0)

        fun kilometers(value: Double): Distance = of(value, DistanceUnit.KILOMETER)

        fun miles(value: Double): Distance = of(value, DistanceUnit.MILE)

        fun of(value: Double, unit: DistanceUnit): Distance = Distance(value * unit.meters)
    }
}

/** A non-negative speed in metres per second. */
@Serializable
@JvmInline
value class Speed(val metersPerSecond: Double) : Comparable<Speed> {
    init {
        require(metersPerSecond.isFinite() && metersPerSecond >= 0.0) {
            "Invalid speed: $metersPerSecond m/s"
        }
    }

    val kilometersPerHour: Double get() = metersPerSecond * SECONDS_PER_HOUR / METERS_PER_KILOMETER
    val milesPerHour: Double get() = metersPerSecond * SECONDS_PER_HOUR / METERS_PER_MILE

    /** The pace at this speed, or null when standing still (pace would be infinite). */
    fun pace(): Pace? {
        if (metersPerSecond == 0.0) return null
        return Pace(METERS_PER_KILOMETER / metersPerSecond)
    }

    override fun compareTo(other: Speed): Int = metersPerSecond.compareTo(other.metersPerSecond)

    companion object {
        val ZERO = Speed(0.0)

        fun kilometersPerHour(value: Double): Speed =
            Speed(value * METERS_PER_KILOMETER / SECONDS_PER_HOUR)

        fun milesPerHour(value: Double): Speed = Speed(value * METERS_PER_MILE / SECONDS_PER_HOUR)
    }
}

/** Time per distance, stored as seconds per kilometre. Always positive. */
@Serializable
@JvmInline
value class Pace(val secondsPerKilometer: Double) : Comparable<Pace> {
    init {
        require(secondsPerKilometer.isFinite() && secondsPerKilometer > 0.0) {
            "Invalid pace: $secondsPerKilometer s/km"
        }
    }

    val secondsPerMile: Double get() = secondsPerKilometer * METERS_PER_MILE / METERS_PER_KILOMETER

    /** Time to cover one [unit] at this pace. */
    fun per(unit: DistanceUnit): Duration =
        (secondsPerKilometer * unit.meters / METERS_PER_KILOMETER * NANOS_PER_SECOND).nanoseconds

    fun toSpeed(): Speed = Speed(METERS_PER_KILOMETER / secondsPerKilometer)

    override fun compareTo(other: Pace): Int =
        secondsPerKilometer.compareTo(other.secondsPerKilometer)

    companion object {
        /** The pace of covering one [unit] in [time]. */
        fun of(time: Duration, unit: DistanceUnit): Pace =
            Pace(time.inWholeNanoseconds / NANOS_PER_SECOND * METERS_PER_KILOMETER / unit.meters)
    }
}

/** Altitude above sea level in metres (negative below it). */
@Serializable
@JvmInline
value class Elevation(val meters: Double) : Comparable<Elevation> {
    init {
        require(meters.isFinite()) { "Elevation must be finite: $meters" }
    }

    val feet: Double get() = meters / METERS_PER_FOOT

    override fun compareTo(other: Elevation): Int = meters.compareTo(other.meters)

    companion object {
        fun feet(value: Double): Elevation = Elevation(value * METERS_PER_FOOT)
    }
}

/** Heart rate in beats per minute. 0 is allowed: straps report it when they lose skin contact. */
@Serializable
@JvmInline
value class HeartRate(val bpm: Int) : Comparable<HeartRate> {
    init {
        require(bpm in 0..MAX_BPM) { "Invalid heart rate: $bpm bpm" }
    }

    override fun compareTo(other: HeartRate): Int = bpm.compareTo(other.bpm)

    companion object {
        const val MAX_BPM = 300
    }
}

/**
 * Running cadence in steps per minute (both feet). FIT stores running cadence in strides per minute
 * (one foot), hence [stridesPerMinute].
 */
@Serializable
@JvmInline
value class Cadence(val stepsPerMinute: Double) : Comparable<Cadence> {
    init {
        require(stepsPerMinute.isFinite() && stepsPerMinute >= 0.0) {
            "Invalid cadence: $stepsPerMinute spm"
        }
    }

    val stridesPerMinute: Double get() = stepsPerMinute / STEPS_PER_STRIDE

    override fun compareTo(other: Cadence): Int = stepsPerMinute.compareTo(other.stepsPerMinute)

    companion object {
        private const val STEPS_PER_STRIDE = 2.0

        fun stridesPerMinute(value: Double): Cadence = Cadence(value * STEPS_PER_STRIDE)
    }
}

/** Running power in watts. */
@Serializable
@JvmInline
value class Power(val watts: Int) : Comparable<Power> {
    init {
        require(watts >= 0) { "Invalid power: $watts W" }
    }

    override fun compareTo(other: Power): Int = watts.compareTo(other.watts)
}

/** Acceleration along the device axes in m/s², as reported by the Android accelerometer (gravity included). */
@Serializable
data class Acceleration(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "Acceleration must be finite: ($x, $y, $z)"
        }
    }

    val magnitude: Double get() = sqrt(x * x + y * y + z * z)

    companion object {
        /** Standard gravity in m/s². */
        const val STANDARD_GRAVITY = 9.80665

        fun fromG(x: Double, y: Double, z: Double): Acceleration =
            Acceleration(x * STANDARD_GRAVITY, y * STANDARD_GRAVITY, z * STANDARD_GRAVITY)
    }
}
