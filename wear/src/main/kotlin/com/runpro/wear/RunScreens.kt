package com.runpro.wear

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.runpro.core.model.DisplayUnits
import com.runpro.core.model.RunEvent
import com.runpro.core.model.StopReason
import com.runpro.core.recording.ResourceState
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.core.recording.exercise.ExercisePhase
import com.runpro.core.recording.exercise.ExerciseStatus
import com.runpro.core.recording.exercise.Readiness
import com.runpro.core.recording.service.ServiceCommand

/** GPS and heart rate acquire; the runner starts when ready, or earlier, or goes back. */
internal fun ScalingLazyListScope.warmUpItems(
    status: ExerciseStatus,
    state: RunState,
    actions: SetupActions,
    countdownSeconds: Int = 0
) {
    item { Paragraph(stringResource(R.string.warmup_title)) }
    resourceItems(state, beforeStart = true)
    item { SensorLine(stringResource(R.string.warmup_gps), status.gps) }
    item { SensorLine(stringResource(R.string.warmup_heart_rate), status.heartRate) }
    warmUpNote(status)?.let { note -> item { Paragraph(stringResource(note)) } }
    if (state.journalFailed) {
        item { Paragraph(stringResource(R.string.summary_not_saved)) }
    }
    item {
        StartCountdown(countdownSeconds) { actions.command(ServiceCommand.START) }
    }
    item {
        QuietButton(stringResource(R.string.warmup_cancel)) {
            actions.command(ServiceCommand.CANCEL)
        }
    }
}

private fun warmUpNote(status: ExerciseStatus): Int? = when {
    status.otherAppActive -> R.string.warmup_other_app

    status.phase == ExercisePhase.UNAVAILABLE || status.phase == ExercisePhase.FAILED ->
        R.string.warmup_no_health_services

    else -> null
}

@Composable
private fun SensorLine(name: String, readiness: Readiness) {
    val value = when (readiness) {
        Readiness.READY -> R.string.warmup_ready
        Readiness.WAITING -> R.string.warmup_waiting
        Readiness.UNAVAILABLE -> R.string.warmup_unavailable
        Readiness.NOT_REQUESTED -> R.string.warmup_not_used
    }
    Text(
        "$name: ${stringResource(value)}",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium
    )
}

/** Time, distance, pace and heart rate in large type; lap and pause in reach, stop behind a confirmation. */
internal fun ScalingLazyListScope.runItems(
    state: RunState,
    actions: SetupActions,
    askToStop: () -> Unit
) {
    val paused = state.phase == RunPhase.PAUSED
    val units = state.workoutSettings.runSettings.units
    val imperial = units == DisplayUnits.IMPERIAL
    resourceItems(state, beforeStart = false)
    if (paused) item { Paragraph(stringResource(R.string.run_paused)) }
    item {
        Text(clock(state.totals.timer), style = MaterialTheme.typography.displayLarge)
    }
    if (state.workout != null) {
        item { WorkoutCountdown(state) }
    }
    item {
        Metric(
            displayDistance(state.totals.distance, units.distance),
            stringResource(if (imperial) R.string.unit_mi else R.string.unit_km)
        )
    }
    item {
        Metric(
            pace(state.current.pace, units.distance),
            stringResource(if (imperial) R.string.unit_pace_mi else R.string.unit_pace)
        )
    }
    item { Metric(heartRate(state.current.heartRate), stringResource(R.string.unit_bpm)) }
    item { HeartRateSource(state) }
    state.laps.lastOrNull()?.let { lap ->
        item {
            Paragraph(
                stringResource(R.string.run_lap, lap.lap.index + 1, clock(lap.totals.timer))
            )
        }
    }
    if (!paused) {
        item {
            WideButton(stringResource(R.string.run_lap_button)) {
                actions.command(ServiceCommand.LAP)
            }
        }
    }
    item {
        WideButton(stringResource(if (paused) R.string.run_resume else R.string.run_pause)) {
            actions.command(if (paused) ServiceCommand.RESUME else ServiceCommand.PAUSE)
        }
    }
    item { QuietButton(stringResource(R.string.run_stop), askToStop) }
}

/** Keeping the run is the first and default choice: a stray tap on Stop changes nothing. */
internal fun ScalingLazyListScope.confirmStopItems(stop: () -> Unit, keepRunning: () -> Unit) {
    item { Paragraph(stringResource(R.string.stop_question)) }
    item { WideButton(stringResource(R.string.stop_keep_running), keepRunning) }
    item { QuietButton(stringResource(R.string.stop_confirm), stop) }
}

internal fun ScalingLazyListScope.summaryItems(state: RunState, done: () -> Unit) {
    val totals = state.totals
    val units = state.workoutSettings.runSettings.units
    val imperial = units == DisplayUnits.IMPERIAL
    item { Paragraph(stringResource(R.string.summary_title)) }
    when ((state.run?.events?.lastOrNull() as? RunEvent.Stopped)?.reason) {
        StopReason.LOW_BATTERY -> item {
            Paragraph(stringResource(R.string.resource_stopped_battery))
        }

        StopReason.STORAGE_FULL -> item {
            Paragraph(stringResource(R.string.resource_stopped_storage))
        }

        else -> Unit
    }
    item { Text(clock(totals.timer), style = MaterialTheme.typography.displayMedium) }
    item {
        Metric(
            displayDistance(totals.distance, units.distance),
            stringResource(if (imperial) R.string.unit_mi else R.string.unit_km)
        )
    }
    item {
        Metric(
            pace(totals.averagePace, units.distance),
            stringResource(
                if (imperial) R.string.summary_average_pace_mi else R.string.summary_average_pace
            )
        )
    }
    item { Metric(heartRate(totals.averageHeartRate), stringResource(R.string.summary_average_hr)) }
    if (state.laps.size > 1) {
        item {
            Paragraph(
                pluralStringResource(R.plurals.summary_laps, state.laps.size, state.laps.size)
            )
        }
    }
    item {
        Paragraph(
            stringResource(
                when {
                    state.journalFailed -> R.string.summary_not_saved
                    state.journalSaved -> R.string.summary_saved
                    else -> R.string.summary_saving
                }
            )
        )
    }
    item { WideButton(stringResource(R.string.summary_done), done) }
}

/** Resource policy belongs to the engine; these messages only explain its published state. */
internal fun ScalingLazyListScope.resourceItems(state: RunState, beforeStart: Boolean) {
    val resources = state.resources
    if (resources.lowBattery) {
        val text = if (beforeStart &&
            (resources.batteryPercent ?: Int.MAX_VALUE) <= ResourceState.BATTERY_STOP_PERCENT
        ) {
            R.string.resource_start_battery
        } else {
            R.string.resource_low_battery
        }
        item { Paragraph(stringResource(text)) }
    }
    if (resources.lowStorage) {
        val text = if (beforeStart && resources.availableBytes == 0L) {
            R.string.resource_start_storage
        } else {
            R.string.resource_low_storage
        }
        item { Paragraph(stringResource(text)) }
    }
}

@Composable
private fun Metric(value: String, unit: String) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.displaySmall)
        Text(unit, style = MaterialTheme.typography.labelMedium)
    }
}
