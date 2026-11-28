package com.runpro.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Exactly one positive target, in seconds or metres. */
@Serializable
data class IntervalTarget(val seconds: Int? = null, val meters: Double? = null) {
    init {
        require((seconds == null) != (meters == null)) { "Choose time or distance" }
        require(seconds == null || seconds > 0) { "Time must be positive" }
        require(meters == null || (meters.isFinite() && meters > 0)) { "Distance must be positive" }
    }
}

/** Recovery follows every work repetition, including the last one. Null warm-up/cool-down skips it. */
@Serializable
data class IntervalWorkout(
    val warmUp: IntervalTarget? = null,
    val repetitions: Int,
    val work: IntervalTarget,
    val recovery: IntervalTarget,
    val coolDown: IntervalTarget? = null
) {
    init {
        require(repetitions in 1..MAX_REPETITIONS) { "Repetitions must be in 1..$MAX_REPETITIONS" }
    }

    companion object {
        const val MAX_REPETITIONS = 100
    }
}

/** Interval workouts suppress automatic distance laps. A null auto-lap distance disables auto-lap. */
@Serializable
data class WorkoutSettings(
    val autoLapMeters: Double? = DEFAULT_AUTO_LAP_METERS,
    val workout: IntervalWorkout? = null,
    val runSettings: RunSettings = RunSettings()
) {
    init {
        require(autoLapMeters == null || (autoLapMeters.isFinite() && autoLapMeters > 0))
    }

    companion object {
        const val DEFAULT_AUTO_LAP_METERS = 1000.0
    }
}

/** Settings must retain explicit nulls: omitted autoLapMeters means the default, null means disabled. */
object WorkoutJson {
    val json: Json = Json { encodeDefaults = true }
}
