package com.runpro.wear.health

import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.HeartRateAccuracy
import androidx.health.services.client.data.LocationAccuracy
import com.runpro.core.recording.exercise.ExerciseReading
import com.runpro.core.recording.exercise.ExerciseReading.HeartRateQuality
import java.time.Duration

/** Copies the data points of an `ExerciseUpdate` into raw [ExerciseReading]s (validated in `:core`). */
internal object HealthServicesReadings {
    /** The data points of an update in the order of the container, as raw readings. */
    fun of(metrics: DataPointContainer): List<ExerciseReading> = buildList {
        for (point in metrics.getData(DataType.HEART_RATE_BPM)) {
            val quality = (point.accuracy as? HeartRateAccuracy)?.sensorStatus.toQuality()
            add(ExerciseReading.HeartRate(point.timeDurationFromBoot.nanos(), point.value, quality))
        }
        for (point in metrics.getData(DataType.LOCATION)) {
            val accuracy = point.accuracy as? LocationAccuracy
            add(
                ExerciseReading.Location(
                    elapsedNanos = point.timeDurationFromBoot.nanos(),
                    latitude = point.value.latitude,
                    longitude = point.value.longitude,
                    altitudeMeters = point.value.altitude,
                    horizontalAccuracyMeters = accuracy?.horizontalPositionErrorMeters ?: Double.NaN
                )
            )
        }
        for (point in metrics.getData(DataType.DISTANCE)) {
            add(
                ExerciseReading.DistanceDelta(
                    point.startDurationFromBoot.nanos(),
                    point.endDurationFromBoot.nanos(),
                    point.value
                )
            )
        }
        for (point in metrics.getData(DataType.SPEED)) {
            add(ExerciseReading.Speed(point.timeDurationFromBoot.nanos(), point.value))
        }
        for (point in metrics.getData(DataType.STEPS_PER_MINUTE)) {
            add(ExerciseReading.StepsPerMinute(point.timeDurationFromBoot.nanos(), point.value))
        }
        for (point in metrics.getData(DataType.ABSOLUTE_ELEVATION)) {
            add(ExerciseReading.Elevation(point.timeDurationFromBoot.nanos(), point.value))
        }
    }

    private fun HeartRateAccuracy.SensorStatus?.toQuality(): HeartRateQuality = when (this) {
        HeartRateAccuracy.SensorStatus.NO_CONTACT -> HeartRateQuality.NO_CONTACT
        HeartRateAccuracy.SensorStatus.UNRELIABLE -> HeartRateQuality.UNRELIABLE
        HeartRateAccuracy.SensorStatus.ACCURACY_LOW -> HeartRateQuality.LOW
        HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM -> HeartRateQuality.MEDIUM
        HeartRateAccuracy.SensorStatus.ACCURACY_HIGH -> HeartRateQuality.HIGH
        else -> HeartRateQuality.UNKNOWN
    }

    private fun Duration.nanos(): Long = toNanos()
}
