package com.runpro.core.recording

import com.runpro.core.model.IntervalTarget
import com.runpro.core.model.IntervalWorkout
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.RunEvent
import com.runpro.core.model.Timestamp
import com.runpro.core.model.WorkoutSettings
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Current stage and remaining target. Completion leaves the run recording until the runner stops it. */
enum class WorkoutStage { WARM_UP, WORK, RECOVERY, COOL_DOWN }

data class WorkoutProgress(
    val stage: WorkoutStage,
    val repetition: Int,
    val repetitions: Int,
    val remainingTime: Duration? = null,
    val remainingMeters: Double? = null,
    val completed: Boolean = false
)

/** Recording-thread state, also rebuilt by applying the journal's existing lap events. */
internal class WorkoutController(private val settings: WorkoutSettings) {
    private data class Step(
        val stage: WorkoutStage,
        val repetition: Int,
        val target: IntervalTarget
    )
    private val steps = settings.workout?.let(::expand).orEmpty()
    private var index = 0
    private var stageTimer = Duration.ZERO
    private var stageMeters = 0.0
    private var nextAutoLap = settings.autoLapMeters ?: Double.POSITIVE_INFINITY

    fun progress(totals: Totals): WorkoutProgress? {
        val step = steps.getOrNull(index) ?: steps.lastOrNull() ?: return null
        val completed = index == steps.size
        return WorkoutProgress(
            step.stage,
            step.repetition,
            settings.workout!!.repetitions,
            remainingTime = step.target.seconds?.let {
                if (completed) {
                    Duration.ZERO
                } else {
                    (it.seconds - (totals.timer - stageTimer)).coerceAtLeast(
                        Duration.ZERO
                    )
                }
            },
            remainingMeters = step.target.meters?.let {
                if (completed) {
                    0.0
                } else {
                    (it - (totals.distance.meters - stageMeters)).coerceAtLeast(
                        0.0
                    )
                }
            },
            completed = completed
        )
    }

    /** At most one distance mark per sample: do not invent empty laps across a sensor gap. */
    fun due(totals: Totals, now: Timestamp): RunEvent.LapMarked? = if (steps.isEmpty()) {
        if (totals.distance.meters >=
            nextAutoLap
        ) {
            RunEvent.LapMarked(now, LapTrigger.DISTANCE)
        } else {
            null
        }
    } else {
        intervalDue(totals, now)
    }

    private fun intervalDue(totals: Totals, now: Timestamp): RunEvent.LapMarked? {
        val step = steps.getOrNull(index) ?: return null
        val seconds = step.target.seconds
        return when {
            seconds != null && totals.timer - stageTimer >= seconds.seconds -> {
                val overshoot = totals.timer - stageTimer - seconds.seconds
                RunEvent.LapMarked(now + -overshoot, LapTrigger.TIME)
            }

            step.target.meters?.let { totals.distance.meters - stageMeters >= it } == true ->
                RunEvent.LapMarked(now, LapTrigger.DISTANCE)

            else -> null
        }
    }

    fun marked(event: RunEvent.LapMarked, totals: Totals) {
        if (event.trigger == LapTrigger.MANUAL) return
        if (steps.isNotEmpty()) {
            if (index < steps.size) index++
            stageTimer = totals.timer
            stageMeters = totals.distance.meters
        } else if (event.trigger == LapTrigger.DISTANCE) {
            settings.autoLapMeters?.let {
                nextAutoLap =
                    (floor(totals.distance.meters / it) + 1) * it
            }
        }
    }

    private fun expand(workout: IntervalWorkout): List<Step> = buildList {
        workout.warmUp?.let { add(Step(WorkoutStage.WARM_UP, 0, it)) }
        for (rep in 1..workout.repetitions) {
            add(Step(WorkoutStage.WORK, rep, workout.work))
            add(Step(WorkoutStage.RECOVERY, rep, workout.recovery))
        }
        workout.coolDown?.let { add(Step(WorkoutStage.COOL_DOWN, 0, it)) }
    }
}
