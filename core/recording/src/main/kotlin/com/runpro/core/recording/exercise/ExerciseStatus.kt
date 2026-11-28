package com.runpro.core.recording.exercise

/** Where the Health Services exercise is, as shown to the runner. */
enum class ExercisePhase {
    IDLE,

    /** Capabilities and ownership are being checked. */
    CHECKING,

    /** Another app is recording an exercise; starting the run takes Health Services over. */
    OTHER_APP_ACTIVE,

    /** `prepareExercise`: GPS and heart rate warm up. */
    WARMING_UP,

    /** `startExercise` was sent. */
    STARTING,
    ACTIVE,
    PAUSED,

    /** The exercise ended, by RunPro or by the platform ([ExerciseStatus.endReason]). */
    ENDED,

    /** Health Services cannot record a run here (missing, no running support, no permitted data). */
    UNAVAILABLE,

    /** A request failed ([ExerciseStatus.error]); the run records without Health Services. */
    FAILED
}

/** Readiness of one warm-up sensor. */
enum class Readiness {
    /** Not part of the exercise (unsupported or not permitted). */
    NOT_REQUESTED,
    WAITING,
    READY,
    UNAVAILABLE
}

/**
 * The state of the Health Services exercise for the UI and the diagnostics log.
 *
 * @property missing data types RunPro wanted but cannot get (unsupported or not permitted).
 * @property otherAppActive another app owns the exercise: before RunPro started, or it took over later.
 * @property tookOverFromOtherApp RunPro started while another app had an exercise, which ended it.
 */
data class ExerciseStatus(
    val phase: ExercisePhase = ExercisePhase.IDLE,
    val gps: Readiness = Readiness.NOT_REQUESTED,
    val heartRate: Readiness = Readiness.NOT_REQUESTED,
    val missing: Set<ExerciseData> = emptySet(),
    val otherAppActive: Boolean = false,
    val tookOverFromOtherApp: Boolean = false,
    val endReason: ExerciseEndReason? = null,
    val error: String? = null
) {
    /** No warm-up sensor is still acquiring: the run can start with every fix it will get. */
    val warmedUp: Boolean get() = gps != Readiness.WAITING && heartRate != Readiness.WAITING

    /**
     * This status with the GPS readiness of the fused location source ([location], ADR 0019) folded in:
     * the run has a fix when either source has one, so the warm-up shows the better of the two.
     */
    fun withLocation(location: Readiness): ExerciseStatus =
        copy(gps = maxOf(gps, location, compareBy(GPS_ORDER::indexOf)))

    private companion object {
        /** From worst to best for the runner waiting to start. */
        val GPS_ORDER = listOf(
            Readiness.NOT_REQUESTED,
            Readiness.UNAVAILABLE,
            Readiness.WAITING,
            Readiness.READY
        )
    }
}
