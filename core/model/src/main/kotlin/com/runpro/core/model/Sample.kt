package com.runpro.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

/** One timestamped reading of one [Metric] from one source. */
@Serializable
sealed interface Sample {
    val time: Timestamp
    val source: SourceId
    val metric: Metric
}

@Serializable
@SerialName("heart_rate")
data class HeartRateSample(
    override val time: Timestamp,
    override val source: SourceId,
    val heartRate: HeartRate
) : Sample {
    override val metric: Metric get() = Metric.HEART_RATE
}

/** A position fix in WGS84 degrees. */
@Serializable
@SerialName("location")
data class LocationSample(
    override val time: Timestamp,
    override val source: SourceId,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Distance? = null,
    val altitude: Elevation? = null
) : Sample {
    init {
        require(latitude.isFinite() && latitude in -MAX_LATITUDE..MAX_LATITUDE) {
            "Invalid latitude: $latitude"
        }
        require(longitude.isFinite() && longitude in -MAX_LONGITUDE..MAX_LONGITUDE) {
            "Invalid longitude: $longitude"
        }
        require(horizontalAccuracy == null || horizontalAccuracy.meters >= 0.0) {
            "Invalid accuracy: $horizontalAccuracy"
        }
    }

    override val metric: Metric get() = Metric.LOCATION
}

/**
 * Cumulative distance reported by [source] since it started reporting in this run. A lost sample loses no
 * distance; a decrease means the source reset and the consumer must start a new segment.
 */
@Serializable
@SerialName("distance")
data class DistanceSample(
    override val time: Timestamp,
    override val source: SourceId,
    val total: Distance
) : Sample {
    init {
        require(total.meters >= 0.0) { "Cumulative distance cannot be negative: $total" }
    }

    override val metric: Metric get() = Metric.DISTANCE
}

@Serializable
@SerialName("speed")
data class SpeedSample(
    override val time: Timestamp,
    override val source: SourceId,
    val speed: Speed
) : Sample {
    override val metric: Metric get() = Metric.SPEED
}

@Serializable
@SerialName("cadence")
data class CadenceSample(
    override val time: Timestamp,
    override val source: SourceId,
    val cadence: Cadence
) : Sample {
    override val metric: Metric get() = Metric.CADENCE
}

@Serializable
@SerialName("power")
data class PowerSample(
    override val time: Timestamp,
    override val source: SourceId,
    val power: Power
) : Sample {
    override val metric: Metric get() = Metric.POWER
}

@Serializable
@SerialName("elevation")
data class ElevationSample(
    override val time: Timestamp,
    override val source: SourceId,
    val elevation: Elevation
) : Sample {
    override val metric: Metric get() = Metric.ELEVATION
}

@Serializable
@SerialName("acceleration")
data class AccelerationSample(
    override val time: Timestamp,
    override val source: SourceId,
    val acceleration: Acceleration
) : Sample {
    override val metric: Metric get() = Metric.ACCELERATION
}
