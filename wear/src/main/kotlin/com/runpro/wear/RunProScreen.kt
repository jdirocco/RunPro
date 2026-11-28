package com.runpro.wear

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.core.recording.exercise.ExerciseStatus
import com.runpro.core.recording.imu.ImuMode
import com.runpro.core.recording.service.BatteryAdvice
import com.runpro.core.recording.service.RunPermission
import com.runpro.core.recording.service.ServiceCommand
import kotlinx.coroutines.flow.StateFlow

/**
 * The one screen of the app, by phase of the run: start (setup checks, then warm-up), warm-up with GPS and
 * heart-rate readiness, run, stop confirmation, summary. Only reads the service state (R6) and sends
 * commands; nothing here can stop a run without the confirmation.
 */
@Composable
fun RunProScreen(
    setup: SetupState,
    runState: StateFlow<RunState>,
    exercise: StateFlow<ExerciseStatus>,
    actions: SetupActions
) {
    val state by runState.collectAsState()
    val status by exercise.collectAsState()
    // Per run: a new run shows its own summary, and the stop confirmation never carries over.
    val runKey = state.run?.id?.value.orEmpty()
    var summaryDismissed by rememberSaveable(runKey) { mutableStateOf(false) }
    var confirmingStop by rememberSaveable(runKey) { mutableStateOf(false) }
    MaterialTheme {
        ScreenScaffold {
            ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
                when (state.phase) {
                    RunPhase.IDLE -> startItems(setup, actions)

                    RunPhase.PREPARING -> warmUpItems(
                        status,
                        state,
                        actions = actions,
                        countdownSeconds = setup.workoutSettings.runSettings.countdownSeconds
                    )

                    RunPhase.RUNNING, RunPhase.PAUSED -> if (confirmingStop) {
                        resourceItems(state, beforeStart = false)
                        confirmStopItems(
                            stop = {
                                confirmingStop = false
                                actions.command(ServiceCommand.STOP)
                            },
                            keepRunning = { confirmingStop = false }
                        )
                    } else {
                        runItems(state, actions, askToStop = { confirmingStop = true })
                    }

                    RunPhase.STOPPED -> if (summaryDismissed) {
                        startItems(setup, actions)
                    } else {
                        summaryItems(state, done = { summaryDismissed = true })
                    }
                }
            }
            if (!confirmingStop) {
                LapSummaryPopup(state) // Drawn last so it covers the list.
            }
        }
    }
}

private fun ScalingLazyListScope.startItems(setup: SetupState, actions: SetupActions) {
    item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium) }
    permissionItems(setup, actions)
    batteryItems(setup.advice, actions)
    workoutSetup(setup.workoutSettings, actions.configureWorkout)
    item { ImuButton(setup.imuMode, actions.configureImu) }
    if (setup.readiness.canStart) {
        item {
            WideButton(stringResource(R.string.run_get_ready)) {
                actions.command(ServiceCommand.PREPARE)
            }
        }
    }
    item { QuietButton(stringResource(R.string.settings_title), actions.openSettings) }
    item { QuietButton(stringResource(R.string.sensors_open), actions.openSensors) }
    item { QuietButton(stringResource(R.string.history_title), actions.openHistory) }
}

private fun ScalingLazyListScope.permissionItems(setup: SetupState, actions: SetupActions) {
    val readiness = setup.readiness
    if (readiness.missingForeground.isNotEmpty()) {
        item { Paragraph(stringResource(R.string.setup_permissions_intro)) }
        readiness.missingForeground.forEach { permission ->
            item { Paragraph(stringResource(permissionText(permission))) }
        }
        item { WideButton(stringResource(R.string.setup_allow), actions.askForeground) }
    } else if (readiness.canAskBackgroundLocation && !setup.backgroundLocationSkipped) {
        item { Paragraph(stringResource(R.string.setup_background_location)) }
        item {
            WideButton(
                stringResource(R.string.setup_allow_all_the_time),
                actions.askBackgroundLocation
            )
        }
        item { WideButton(stringResource(R.string.setup_not_now), actions.skipBackgroundLocation) }
    }
}

private fun ScalingLazyListScope.batteryItems(advice: List<BatteryAdvice>, actions: SetupActions) {
    if (BatteryAdvice.ALLOW_UNRESTRICTED_BATTERY in advice) {
        // No button: the watch has no settings screen for this (field bug #10), only adb.
        item { Paragraph(stringResource(R.string.setup_battery_unrestricted)) }
        item { Paragraph(BATTERY_EXEMPTION_COMMAND) }
    }
    if (BatteryAdvice.REMOVE_FROM_SLEEPING_APPS in advice) {
        item { Paragraph(stringResource(R.string.setup_sleeping_apps)) }
        item { WideButton(stringResource(R.string.setup_checked), actions.confirmSleepingApps) }
    }
}

/** Cycles the IMU capture setting (ADR 0012): accelerometer, accelerometer + gyroscope, off. */
@Composable
internal fun ImuButton(mode: ImuMode, change: (ImuMode) -> Unit) {
    val text = stringResource(
        when (mode) {
            ImuMode.OFF -> R.string.workout_off
            ImuMode.ACCELEROMETER -> R.string.imu_accelerometer
            ImuMode.ACCELEROMETER_AND_GYROSCOPE -> R.string.imu_accelerometer_gyroscope
        }
    )
    WideButton(stringResource(R.string.imu_mode, text)) {
        change(ImuMode.entries[(mode.ordinal + 1) % ImuMode.entries.size])
    }
}

@Composable
internal fun Paragraph(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
internal fun WideButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled) { Text(label) }
}

@Composable
internal fun WideButton(label: String, onClick: () -> Unit) {
    WideButton(label = label, enabled = true, onClick = onClick)
}

/** A secondary action, visually quieter than [WideButton]. */
@Composable
internal fun QuietButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

private fun permissionText(permission: RunPermission): Int = when (permission) {
    RunPermission.LOCATION -> R.string.permission_location
    RunPermission.HEART_RATE -> R.string.permission_heart_rate
    RunPermission.ACTIVITY_RECOGNITION -> R.string.permission_activity
    RunPermission.BLUETOOTH -> R.string.permission_bluetooth
    RunPermission.NOTIFICATIONS -> R.string.permission_notifications
    RunPermission.BACKGROUND_LOCATION -> R.string.setup_background_location
}

/** Exempts RunPro from battery optimisation; the watch offers no settings screen for it. */
internal const val BATTERY_EXEMPTION_COMMAND =
    "adb shell dumpsys deviceidle whitelist +com.runpro.wear"
