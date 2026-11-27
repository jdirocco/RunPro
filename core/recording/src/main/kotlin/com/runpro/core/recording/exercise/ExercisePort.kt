package com.runpro.core.recording.exercise

/**
 * The Health Services `ExerciseClient`, reduced to what [ExerciseController] needs and free of Android
 * types, so the controller is tested on the JVM with a fake. The `:wear` adapter implements it.
 *
 * Every request is asynchronous: it returns at once and calls `done` exactly once, on any thread (a fake
 * may call it before returning). Failures are reported through `done`, never thrown.
 */
interface ExercisePort {
    /** `getCapabilitiesAsync` for `RUNNING` and `getCurrentExerciseInfoAsync`. */
    fun inspect(done: (Result<ExerciseInspection>) -> Unit)

    /** `setUpdateCallback`: updates go to [listener] until [clearListener]. */
    fun setListener(listener: ExercisePortListener)

    /** `clearUpdateCallbackAsync`; failures are only logged. */
    fun clearListener()

    /** `prepareExerciseAsync`: warm up the sensors in [ExercisePlan.warmUpTypes]. */
    fun prepare(plan: ExercisePlan, done: (Result<Unit>) -> Unit)

    /** `startExerciseAsync` for `RUNNING` with [ExercisePlan.dataTypes], no auto-pause. */
    fun start(plan: ExercisePlan, done: (Result<Unit>) -> Unit)

    fun pause(done: (Result<Unit>) -> Unit)

    fun resume(done: (Result<Unit>) -> Unit)

    fun end(done: (Result<Unit>) -> Unit)
}

/** What the `ExerciseUpdateCallback` reports, translated. Calls may come on any thread. */
interface ExercisePortListener {
    /** `onRegistrationFailed`: no updates will arrive. */
    fun onRegistrationFailed(error: Throwable)

    /** The data points of one `ExerciseUpdate`, grouped by data type (samples carry their own time). */
    fun onReadings(readings: List<ExerciseReading>)

    /** `onAvailabilityChanged` for one of RunPro's data types. */
    fun onAvailability(data: ExerciseData, availability: DataAvailability)

    /** The settled state of an update; transitional ones (`USER_PAUSING`, ...) are not reported. */
    fun onState(state: PlatformExerciseState, endReason: ExerciseEndReason?)
}

/** `LocationAvailability` and `DataTypeAvailability`, merged. */
enum class DataAvailability {
    UNKNOWN,
    ACQUIRING,

    /** A usable reading: GPS fix (tethered or not) or heart rate available. */
    AVAILABLE,
    UNAVAILABLE,

    /** `UNAVAILABLE_DEVICE_OFF_BODY`. */
    OFF_BODY,

    /** `NO_GNSS`: the watch has no GPS receiver. */
    NO_GNSS
}

/** The Health Services exercise states the controller acts on. */
enum class PlatformExerciseState {
    PREPARING,
    ACTIVE,
    PAUSED,
    ENDED
}

/** `ExerciseEndReason`: why the Health Services exercise ended. */
enum class ExerciseEndReason {
    /** RunPro ended it (or it is unknown after RunPro asked to end). */
    USER,

    /** Another app started an exercise and took Health Services over (`AUTO_END_SUPERSEDED`). */
    SUPERSEDED,

    /** A permission was revoked during the exercise. */
    PERMISSION_LOST,

    /** Paused for too long. */
    PAUSE_EXPIRED,

    /** Prepared but not started in time. */
    PREPARE_EXPIRED,

    /** Health Services lost its update callback (`AUTO_END_MISSING_LISTENER`). */
    MISSING_LISTENER,
    UNKNOWN
}
