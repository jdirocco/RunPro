package com.runpro.core.recording.exercise

import com.runpro.core.model.SourceId
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.service.RunPermission

/** A request [ExerciseController] sends to its [ExercisePort]. */
internal enum class ExerciseRequest {
    INSPECT,
    PREPARE,
    START,
    PAUSE,
    RESUME,
    END,
    CLEAR_LISTENER
}

/**
 * The decisions for one Health Services exercise, from the first check to its end; a new run gets a new
 * session. It compares where the run wants the exercise ([want]) with where the exercise is known to be
 * and names the [next] request. Pure and not thread safe: [ExerciseController] owns it under its lock.
 */
internal class ExerciseSession(source: SourceId) {
    private enum class Target { NONE, PREPARED, ACTIVE, PAUSED, ENDED }

    private enum class Known { NONE, PREPARED, ACTIVE, PAUSED, ENDED }

    private var target = Target.NONE
    private var known = Known.NONE
    private var inspection: ExerciseInspection? = null
    private var unusable = false
    private var failed = false
    private var adopted = false
    private var adoptionConfirmed = false
    private var tookOver = false
    private var wantListener = false
    private var endReason: ExerciseEndReason? = null

    var plan: ExercisePlan? = null
        private set

    /** The update listener is registered with the port. */
    var registered = false
        private set

    var error: String? = null

    val mapper = ExerciseSampleMapper(source)
    val signals = ExerciseSignals(source)

    /** The exercise ended or is ending; only a new session records again. */
    val isOver: Boolean get() = target == Target.ENDED || known == Known.ENDED

    fun want(phase: RunPhase) {
        target = when (phase) {
            RunPhase.PREPARING -> if (target == Target.NONE) Target.PREPARED else target
            RunPhase.RUNNING -> Target.ACTIVE
            RunPhase.PAUSED -> Target.PAUSED
            RunPhase.STOPPED, RunPhase.IDLE -> Target.ENDED
        }
    }

    fun next(): ExerciseRequest? {
        val inspection = inspection
        return when {
            target == Target.NONE -> null

            target == Target.ENDED -> when (known) {
                Known.PREPARED, Known.ACTIVE, Known.PAUSED -> ExerciseRequest.END
                Known.NONE, Known.ENDED -> ExerciseRequest.CLEAR_LISTENER.takeIf { registered }
            }

            unusable || failed || known == Known.ENDED -> null

            inspection == null -> ExerciseRequest.INSPECT

            else -> towardsTarget(inspection.ownership)
        }
    }

    private fun towardsTarget(ownership: ExerciseOwnership): ExerciseRequest? = when (known) {
        // Warm-up does not touch another app's exercise; only the start takes over.
        Known.NONE -> when {
            target != Target.PREPARED -> ExerciseRequest.START
            ownership != ExerciseOwnership.OTHER_APP -> ExerciseRequest.PREPARE
            else -> null
        }

        Known.PREPARED -> ExerciseRequest.START.takeIf { target != Target.PREPARED }

        Known.PAUSED -> ExerciseRequest.RESUME.takeIf { target == Target.ACTIVE }

        Known.ACTIVE -> ExerciseRequest.PAUSE.takeIf { target == Target.PAUSED }

        Known.ENDED -> null
    }

    fun inspected(result: Result<ExerciseInspection>, granted: Set<RunPermission>) {
        val found = result.getOrElse {
            unusable = true
            error = describe(it)
            return
        }
        inspection = found
        val plan = ExercisePlan.of(found.capabilities, granted).also { plan = it }
        if (!plan.usable) {
            unusable = true
            if (!found.capabilities.runningSupported) {
                error =
                    "Health Services does not support running"
            }
        } else if (target != Target.ENDED && found.ownership == ExerciseOwnership.OURS) {
            // The process was restarted during the run: keep the exercise that is still recording.
            wantListener = true
            adopted = true
            known = Known.ACTIVE
        }
    }

    /** Marks the listener registered once an exercise is prepared, started or adopted. */
    fun registerListener(): Boolean {
        if (!wantListener || registered) return false
        registered = true
        return true
    }

    fun completed(request: ExerciseRequest, result: Result<Unit>) {
        when (request) {
            ExerciseRequest.END -> {
                known = Known.ENDED
                if (endReason == null) endReason = ExerciseEndReason.USER
                result.exceptionOrNull()?.let { error = describe(it) }
            }

            ExerciseRequest.CLEAR_LISTENER -> {
                wantListener = false
                registered = false
            }

            else -> result.fold(
                onSuccess = {
                    known = reached(request)
                    if (target != Target.ENDED) wantListener = true
                    val otherApp = inspection?.ownership == ExerciseOwnership.OTHER_APP
                    if (request == ExerciseRequest.START && otherApp) tookOver = true
                },
                onFailure = {
                    failed = true
                    error = describe(it)
                }
            )
        }
    }

    /**
     * A settled state from an update. Returns true when the exercise ended without RunPro asking (another
     * app took over, a permission was revoked, ...): the run goes on without it (R4).
     */
    fun platformState(state: PlatformExerciseState, reason: ExerciseEndReason?): Boolean {
        val wasRunning = known != Known.ENDED
        when (state) {
            PlatformExerciseState.ENDED -> {
                known = Known.ENDED
                if (endReason == null) endReason = reason ?: ExerciseEndReason.UNKNOWN
            }

            PlatformExerciseState.ACTIVE, PlatformExerciseState.PAUSED -> if (adopted &&
                !adoptionConfirmed
            ) {
                // After a restart only the first update says whether the exercise was paused.
                adoptionConfirmed = true
                known = if (state == PlatformExerciseState.ACTIVE) Known.ACTIVE else Known.PAUSED
            }

            PlatformExerciseState.PREPARING -> Unit
        }
        return wasRunning && known == Known.ENDED && target != Target.ENDED
    }

    fun status(busy: Boolean): ExerciseStatus = ExerciseStatus(
        phase = phase(busy),
        gps = signals.readiness(ExerciseData.LOCATION, plan),
        heartRate = signals.readiness(ExerciseData.HEART_RATE, plan),
        missing = plan?.missing.orEmpty(),
        otherAppActive = otherAppActive(),
        tookOverFromOtherApp = tookOver,
        endReason = endReason,
        error = error
    )

    private fun phase(busy: Boolean): ExercisePhase = when {
        known == Known.ENDED -> ExercisePhase.ENDED
        target == Target.ENDED && known == Known.NONE && !busy -> ExercisePhase.ENDED
        unusable -> ExercisePhase.UNAVAILABLE
        failed -> ExercisePhase.FAILED
        target == Target.NONE -> ExercisePhase.IDLE
        inspection == null -> ExercisePhase.CHECKING
        else -> exercisePhase()
    }

    private fun exercisePhase(): ExercisePhase = when (known) {
        Known.ACTIVE -> ExercisePhase.ACTIVE

        Known.PAUSED -> ExercisePhase.PAUSED

        Known.PREPARED -> ExercisePhase.WARMING_UP

        else -> when {
            target != Target.PREPARED -> ExercisePhase.STARTING
            inspection?.ownership == ExerciseOwnership.OTHER_APP -> ExercisePhase.OTHER_APP_ACTIVE
            else -> ExercisePhase.WARMING_UP
        }
    }

    private fun otherAppActive(): Boolean {
        val before = inspection?.ownership == ExerciseOwnership.OTHER_APP &&
            !tookOver && (known == Known.NONE || known == Known.PREPARED)
        return before || endReason == ExerciseEndReason.SUPERSEDED
    }

    private companion object {
        fun reached(request: ExerciseRequest): Known = when (request) {
            ExerciseRequest.PREPARE -> Known.PREPARED
            ExerciseRequest.PAUSE -> Known.PAUSED
            else -> Known.ACTIVE
        }
    }
}

internal fun describe(error: Throwable): String = error.message ?: error.javaClass.simpleName
