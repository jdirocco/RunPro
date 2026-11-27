package com.runpro.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import com.runpro.core.model.IntervalTarget
import com.runpro.core.model.IntervalWorkout
import com.runpro.core.model.WorkoutSettings
import kotlin.time.Duration.Companion.seconds

/** Large tap targets cycle useful time/distance targets, without a watch keyboard. */
internal fun ScalingLazyListScope.workoutSetup(
    settings: WorkoutSettings,
    update: (WorkoutSettings) -> Unit
) {
    val workout = settings.workout
    item {
        val mode = stringResource(
            if (workout ==
                null
            ) {
                R.string.workout_free
            } else {
                R.string.workout_intervals
            }
        )
        WideButton(stringResource(R.string.workout_mode, mode)) {
            val next = if (workout == null) defaultIntervals() else null
            update(settings.copy(workout = next))
        }
    }
    if (workout == null) {
        item { AutoLapButton(settings, update) }
    } else {
        intervalSetup(workout) { update(settings.copy(workout = it)) }
    }
}

private fun ScalingLazyListScope.intervalSetup(
    workout: IntervalWorkout,
    update: (IntervalWorkout) -> Unit
) {
    item {
        WideButton(stringResource(R.string.workout_repetitions, workout.repetitions)) {
            update(workout.copy(repetitions = workout.repetitions % MAX_SETUP_REPETITIONS + 1))
        }
    }
    item {
        TargetButton(R.string.workout_warm_up, workout.warmUp, optional = true) {
            update(workout.copy(warmUp = it))
        }
    }
    item {
        TargetButton(R.string.workout_work, workout.work) {
            update(workout.copy(work = requireNotNull(it)))
        }
    }
    item {
        TargetButton(R.string.workout_recovery, workout.recovery) {
            update(workout.copy(recovery = requireNotNull(it)))
        }
    }
    item {
        TargetButton(R.string.workout_cool_down, workout.coolDown, optional = true) {
            update(workout.copy(coolDown = it))
        }
    }
}

@Composable
private fun AutoLapButton(settings: WorkoutSettings, update: (WorkoutSettings) -> Unit) {
    val distance = settings.autoLapMeters
    val text = if (distance == null) {
        stringResource(R.string.workout_off)
    } else {
        stringResource(R.string.workout_meters, distance.toInt())
    }
    WideButton(stringResource(R.string.workout_auto_lap, text)) {
        update(settings.copy(autoLapMeters = AUTO_LAP_OPTIONS.next(distance)))
    }
}

@Composable
private fun TargetButton(
    label: Int,
    target: IntervalTarget?,
    optional: Boolean = false,
    change: (IntervalTarget?) -> Unit
) {
    val text = when {
        target == null -> stringResource(R.string.workout_off)
        target.seconds != null -> clock(target.seconds!!.seconds)
        else -> stringResource(R.string.workout_meters, target.meters!!.toInt())
    }
    WideButton(stringResource(R.string.workout_target, stringResource(label), text)) {
        val options = (if (optional) listOf(null) else emptyList()) + TARGETS
        change(options.next(target))
    }
}

private fun <T> List<T>.next(value: T): T = this[(indexOf(value) + 1) % size]

private const val MAX_SETUP_REPETITIONS = 20

@Suppress("MagicNumber") // User-visible presets in seconds/metres.
private val TARGETS = listOf(30, 60, 120, 180, 300, 600).map { IntervalTarget(seconds = it) } +
    listOf(100.0, 200.0, 400.0, 800.0, 1000.0).map { IntervalTarget(meters = it) }

@Suppress("MagicNumber") // User-visible auto-lap presets in metres.
private val AUTO_LAP_OPTIONS = listOf(null, 500.0, 1000.0, 2000.0)

@Suppress("MagicNumber") // Matches the committed 6 x 3 minute replay trace.
private fun defaultIntervals() = IntervalWorkout(
    IntervalTarget(seconds = 600),
    6,
    IntervalTarget(seconds = 180),
    IntervalTarget(seconds = 120),
    IntervalTarget(seconds = 300)
)
