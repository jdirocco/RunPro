package com.runpro.core.model

import kotlinx.serialization.Serializable

/**
 * Per-run alert configuration copied into the immutable run snapshot at Start.
 *
 * Null HR or pace thresholds leave that alert disabled. Sensor and GPS alerts default to enabled
 * so dropouts are felt without looking at the watch. [cooldownSeconds] rate-limits repeated alerts
 * of the same kind (and per external sensor for BLE alerts).
 */
@Serializable
data class AlertSettings(
    val hrLowBpm: Int? = null,
    val hrHighBpm: Int? = null,
    val paceFast: Pace? = null,
    val paceSlow: Pace? = null,
    val sensorAlerts: Boolean = true,
    val gpsAlerts: Boolean = true,
    val cooldownSeconds: Int = DEFAULT_COOLDOWN_SECONDS
) {
    init {
        require(hrLowBpm == null || hrLowBpm in MIN_HR_ALERT..MAX_HR_ALERT) {
            "Invalid low HR alert: $hrLowBpm"
        }
        require(hrHighBpm == null || hrHighBpm in MIN_HR_ALERT..MAX_HR_ALERT) {
            "Invalid high HR alert: $hrHighBpm"
        }
        require(hrLowBpm == null || hrHighBpm == null || hrLowBpm < hrHighBpm) {
            "Low HR alert ($hrLowBpm) must be below high HR alert ($hrHighBpm)"
        }
        require(paceFast == null || paceFast.secondsPerKilometer in MIN_PACE_SEC..MAX_PACE_SEC) {
            "Invalid fast pace alert: $paceFast"
        }
        require(paceSlow == null || paceSlow.secondsPerKilometer in MIN_PACE_SEC..MAX_PACE_SEC) {
            "Invalid slow pace alert: $paceSlow"
        }
        require(paceFast == null || paceSlow == null || paceFast < paceSlow) {
            "Fast pace alert ($paceFast) must be faster than slow pace alert ($paceSlow)"
        }
        require(cooldownSeconds in MIN_COOLDOWN_SECONDS..MAX_COOLDOWN_SECONDS) {
            "Alert cooldown must be in $MIN_COOLDOWN_SECONDS..$MAX_COOLDOWN_SECONDS s"
        }
    }

    /** Copies HR low/high limits from [zone] (1..5) of [zones]. Zone 5 leaves upper limit off. */
    fun withHrZone(zones: HeartRateZones, zone: Int): AlertSettings {
        val range = zones.rangeFor(zone)
        return copy(
            hrLowBpm = range.first.coerceIn(MIN_HR_ALERT, MAX_HR_ALERT),
            hrHighBpm = if (zone < HeartRateZones.ZONE_COUNT) {
                range.last.coerceIn(MIN_HR_ALERT, MAX_HR_ALERT)
            } else {
                null
            }
        )
    }

    /** Copies fast/slow pace limits from [zone] (1..5) of [zones]. */
    fun withPaceZone(zones: PaceZones, zone: Int): AlertSettings {
        val range = zones.rangeFor(zone)
        return copy(
            paceFast = range.fastest?.coerceToBounds(),
            paceSlow = range.slowest?.coerceToBounds()
        )
    }

    private fun Pace.coerceToBounds(): Pace =
        Pace(secondsPerKilometer.coerceIn(MIN_PACE_SEC, MAX_PACE_SEC))

    companion object {
        const val MIN_HR_ALERT = 40
        const val MAX_HR_ALERT = 250
        const val MIN_PACE_SEC = 120.0
        const val MAX_PACE_SEC = 1800.0
        const val MIN_COOLDOWN_SECONDS = 5
        const val MAX_COOLDOWN_SECONDS = 300
        const val DEFAULT_COOLDOWN_SECONDS = 30
    }
}
