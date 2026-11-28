package com.runpro.core.model

import kotlinx.serialization.Serializable

/** Display conversions only; recording and export retain SI units. */
@Serializable
enum class DisplayUnits(val distance: DistanceUnit) {
    METRIC(DistanceUnit.KILOMETER),
    IMPERIAL(DistanceUnit.MILE)
}

/** Unspecified profile values stay unknown rather than inventing a runner's physiology. */
@Serializable
data class UserProfile(
    val maxHeartRate: Int? = null,
    val restingHeartRate: Int? = null,
    val weightKg: Double? = null,
    val lactateThresholdHeartRate: Int? = null,
    val thresholdPace: Pace? = null
) {
    init {
        require(maxHeartRate == null || maxHeartRate in MIN_MAX_HR..MAX_HR)
        require(restingHeartRate == null || restingHeartRate in MIN_RESTING_HR..MAX_RESTING_HR)
        require(maxHeartRate == null || restingHeartRate == null || restingHeartRate < maxHeartRate)
        require(
            lactateThresholdHeartRate == null || lactateThresholdHeartRate in MIN_LTHR..MAX_HR
        )
        require(
            restingHeartRate == null || lactateThresholdHeartRate == null ||
                restingHeartRate < lactateThresholdHeartRate
        )
        require(
            maxHeartRate == null || lactateThresholdHeartRate == null ||
                lactateThresholdHeartRate < maxHeartRate
        )
        require(
            weightKg == null || (weightKg.isFinite() && weightKg in MIN_WEIGHT_KG..MAX_WEIGHT_KG)
        )
        require(
            thresholdPace == null ||
                thresholdPace.secondsPerKilometer in MIN_THRESHOLD_PACE_SEC..MAX_THRESHOLD_PACE_SEC
        )
    }

    companion object {
        const val MIN_MAX_HR = 60
        const val MAX_HR = 250
        const val MIN_LTHR = 60
        const val MIN_RESTING_HR = 20
        const val MAX_RESTING_HR = 150
        const val MIN_WEIGHT_KG = 20.0
        const val MAX_WEIGHT_KG = 300.0
        const val MIN_THRESHOLD_PACE_SEC = 120.0
        const val MAX_THRESHOLD_PACE_SEC = 900.0
    }
}

/** Immutable selection copied into the next run's durable workout snapshot. */
@Serializable
data class RunSettings(
    val units: DisplayUnits = DisplayUnits.METRIC,
    val profile: UserProfile = UserProfile(),
    val autoPause: Boolean = false,
    val countdownSeconds: Int = 0,
    val debugTrace: Boolean = false,
    val hrZoneMethod: HrZoneMethod = HrZoneMethod.MAX_HR,
    val alerts: AlertSettings = AlertSettings()
) {
    init {
        require(countdownSeconds in 0..MAX_COUNTDOWN_SECONDS)
    }

    /** Active HR zones for [hrZoneMethod], or null when its profile reference is unset. */
    fun hrZones(): HeartRateZones? = when (hrZoneMethod) {
        HrZoneMethod.MAX_HR -> profile.maxHeartRate?.let(HeartRateZones::fromMaxHeartRate)

        HrZoneMethod.LTHR ->
            profile.lactateThresholdHeartRate?.let(HeartRateZones::fromLactateThreshold)
    }

    /** Active pace zones from [UserProfile.thresholdPace], or null when unset. */
    fun paceZones(): PaceZones? = profile.thresholdPace?.let(PaceZones::fromThreshold)

    companion object {
        const val MAX_COUNTDOWN_SECONDS = 10
    }
}
