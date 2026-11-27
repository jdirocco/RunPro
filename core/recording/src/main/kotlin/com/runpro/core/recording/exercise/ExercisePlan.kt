package com.runpro.core.recording.exercise

import com.runpro.core.recording.service.RunPermission

/**
 * The Health Services data types RunPro records during a run, independent of the platform API. The adapter
 * in `:wear` maps each one to its `DataType` ([permission] is the runtime permission Health Services needs
 * before the type can be requested; requesting it without throws a `SecurityException`).
 */
enum class ExerciseData(val permission: RunPermission) {
    /** `HEART_RATE_BPM`: wrist heart rate. */
    HEART_RATE(RunPermission.HEART_RATE),

    /** `LOCATION`: GPS fixes; also turns GPS on for the exercise. */
    LOCATION(RunPermission.LOCATION),

    /** `DISTANCE`: distance deltas (GPS, or steps without a fix). */
    DISTANCE(RunPermission.ACTIVITY_RECOGNITION),

    /** `SPEED`: instantaneous speed. */
    SPEED(RunPermission.ACTIVITY_RECOGNITION),

    /** `STEPS_PER_MINUTE`: running cadence. */
    STEPS_PER_MINUTE(RunPermission.ACTIVITY_RECOGNITION),

    /** `ABSOLUTE_ELEVATION`: altitude, derived from the location. */
    ELEVATION(RunPermission.LOCATION)
}

/** What Health Services on this watch supports for `ExerciseType.RUNNING`. */
data class ExerciseCapabilities(val runningSupported: Boolean, val supported: Set<ExerciseData>)

/** Who owns the Health Services exercise right now (`ExerciseInfo.exerciseTrackedStatus`). */
enum class ExerciseOwnership {
    /** No exercise is in progress (or the status is unknown). */
    NONE,

    /** RunPro's own exercise is still running: the process was killed during a run and restarted. */
    OURS,

    /** Another app (e.g. Samsung Health) is recording an exercise; starting RunPro's ends it. */
    OTHER_APP
}

/** The result of the capabilities check and the ownership query before a run. */
data class ExerciseInspection(
    val capabilities: ExerciseCapabilities,
    val ownership: ExerciseOwnership
)

/**
 * The exercise RunPro asks Health Services for: every [ExerciseData] that is both supported and permitted.
 *
 * @property dataTypes requested data types.
 * @property missing data types RunPro wants but cannot have (unsupported or not permitted).
 * @property gpsEnabled request GPS (only with [ExerciseData.LOCATION]).
 * @property usable running is supported and at least one data type can be requested.
 */
data class ExercisePlan(
    val dataTypes: Set<ExerciseData>,
    val missing: Set<ExerciseData>,
    val gpsEnabled: Boolean,
    val usable: Boolean
) {
    /** The sensors warmed up by `prepareExercise`: GPS and heart rate, the ones with a visible fix. */
    val warmUpTypes: Set<ExerciseData> get() = dataTypes intersect WARM_UP

    companion object {
        private val WARM_UP = setOf(ExerciseData.HEART_RATE, ExerciseData.LOCATION)

        fun of(capabilities: ExerciseCapabilities, granted: Set<RunPermission>): ExercisePlan {
            val dataTypes = ExerciseData.entries.filter {
                capabilities.runningSupported && it in capabilities.supported &&
                    it.permission in granted
            }.toSet()
            return ExercisePlan(
                dataTypes = dataTypes,
                missing = ExerciseData.entries.toSet() - dataTypes,
                gpsEnabled = ExerciseData.LOCATION in dataTypes,
                usable = dataTypes.isNotEmpty()
            )
        }
    }
}
