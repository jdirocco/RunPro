package com.runpro.core.recording.exercise

import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Elevation
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import kotlin.math.roundToInt

private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

/**
 * One raw Health Services data point, copied out of the platform types without validation. Times are the
 * data point's duration from boot in nanoseconds (the monotonic `elapsedRealtimeNanos` clock).
 */
sealed interface ExerciseReading {
    /** `HEART_RATE_BPM` sample with the sensor status of its `HeartRateAccuracy`. */
    data class HeartRate(val elapsedNanos: Long, val bpm: Double, val quality: HeartRateQuality) :
        ExerciseReading

    /** `LOCATION` sample; NaN altitude or a negative accuracy mean unknown. */
    data class Location(
        val elapsedNanos: Long,
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double = Double.NaN,
        val horizontalAccuracyMeters: Double = Double.NaN
    ) : ExerciseReading

    /** `DISTANCE` interval: metres covered from [startNanos] to [endNanos]. */
    data class DistanceDelta(val startNanos: Long, val endNanos: Long, val meters: Double) :
        ExerciseReading

    /** `SPEED` sample in m/s. */
    data class Speed(val elapsedNanos: Long, val metersPerSecond: Double) : ExerciseReading

    /** `STEPS_PER_MINUTE` sample. */
    data class StepsPerMinute(val elapsedNanos: Long, val stepsPerMinute: Long) : ExerciseReading

    /** `ABSOLUTE_ELEVATION` sample in metres. */
    data class Elevation(val elapsedNanos: Long, val meters: Double) : ExerciseReading

    /** `HeartRateAccuracy.SensorStatus`. */
    enum class HeartRateQuality {
        UNKNOWN,
        NO_CONTACT,
        UNRELIABLE,
        LOW,
        MEDIUM,
        HIGH
    }
}

/**
 * Turns [ExerciseReading]s into [Sample]s of [source]. Invalid readings are dropped here, at the edge, so a
 * bad value never reaches the recording path (the model constructors would throw).
 *
 * Health Services reports distance as deltas; [DistanceSample] is cumulative, so the mapper adds them up
 * from the start of its exercise. Not thread safe: [ExerciseController] calls it under its lock.
 */
class ExerciseSampleMapper(private val source: SourceId) {
    private var distanceMeters = 0.0

    /**
     * Maps the readings of one update. [anchor] is "now" on both clocks when the update arrived; a reading's
     * wall clock is derived from it and the monotonic clock, like every other source.
     */
    fun map(readings: List<ExerciseReading>, anchor: Timestamp): List<Sample> =
        readings.mapNotNull { map(it, anchor) }

    private fun map(reading: ExerciseReading, anchor: Timestamp): Sample? {
        val time = timestamp(reading.time, anchor) ?: return null
        return when (reading) {
            is ExerciseReading.HeartRate -> heartRate(time, reading)

            is ExerciseReading.Location -> location(time, reading)

            is ExerciseReading.DistanceDelta -> distance(time, reading)

            is ExerciseReading.Speed -> reading.metersPerSecond.takeIf {
                it.isFinite() && it >= 0.0
            }
                ?.let { SpeedSample(time, source, Speed(it)) }

            is ExerciseReading.StepsPerMinute -> reading.stepsPerMinute.takeIf { it >= 0 }
                ?.let { CadenceSample(time, source, Cadence(it.toDouble())) }

            is ExerciseReading.Elevation -> reading.meters.takeIf { it.isFinite() }
                ?.let { ElevationSample(time, source, Elevation(it)) }
        }
    }

    private fun heartRate(time: Timestamp, reading: ExerciseReading.HeartRate): Sample? {
        // The wrist sensor reports 0 or unreliable values when the watch is loose or off the wrist.
        if (reading.quality in DISCARDED_QUALITY || !reading.bpm.isFinite()) return null
        return reading.bpm.roundToInt()
            .takeIf { it in 1..com.runpro.core.model.HeartRate.MAX_BPM }
            ?.let { HeartRateSample(time, source, com.runpro.core.model.HeartRate(it)) }
    }

    private fun location(time: Timestamp, reading: ExerciseReading.Location): Sample? {
        val latitudeOk =
            reading.latitude.isFinite() && reading.latitude in -MAX_LATITUDE..MAX_LATITUDE
        val longitudeOk =
            reading.longitude.isFinite() && reading.longitude in -MAX_LONGITUDE..MAX_LONGITUDE
        if (!latitudeOk || !longitudeOk) return null
        return LocationSample(
            time = time,
            source = source,
            latitude = reading.latitude,
            longitude = reading.longitude,
            horizontalAccuracy = reading.horizontalAccuracyMeters
                .takeIf { it.isFinite() && it >= 0.0 }
                ?.let(::Distance),
            altitude = reading.altitudeMeters.takeIf { it.isFinite() }?.let(::Elevation)
        )
    }

    private fun distance(time: Timestamp, reading: ExerciseReading.DistanceDelta): Sample? {
        if (!reading.meters.isFinite() || reading.meters < 0.0) return null
        distanceMeters += reading.meters
        return DistanceSample(time, source, Distance(distanceMeters))
    }

    private fun timestamp(elapsedNanos: Long, anchor: Timestamp): Timestamp? {
        if (elapsedNanos < 0) return null
        val probe = Timestamp(elapsedNanos, 0)
        return Timestamp(elapsedNanos, probe.wallClockFrom(anchor))
    }

    private companion object {
        val DISCARDED_QUALITY = setOf(
            ExerciseReading.HeartRateQuality.NO_CONTACT,
            ExerciseReading.HeartRateQuality.UNRELIABLE
        )
    }
}

private val ExerciseReading.time: Long
    get() = when (this) {
        is ExerciseReading.HeartRate -> elapsedNanos
        is ExerciseReading.Location -> elapsedNanos
        is ExerciseReading.DistanceDelta -> endNanos
        is ExerciseReading.Speed -> elapsedNanos
        is ExerciseReading.StepsPerMinute -> elapsedNanos
        is ExerciseReading.Elevation -> elapsedNanos
    }
