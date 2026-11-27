package com.runpro.wear

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.core.recording.imu.ImuMode
import com.runpro.core.recording.service.BatteryAdvice
import com.runpro.core.recording.service.RunPermission
import com.runpro.core.recording.service.ServiceCommand
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

@Composable
fun RunProScreen(setup: SetupState, runState: StateFlow<RunState>, actions: SetupActions) {
    val state by runState.collectAsState()
    MaterialTheme {
        ScreenScaffold {
            ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                permissionItems(setup, actions)
                batteryItems(setup.advice, actions)
                runItems(setup, state, actions)
            }
            LapSummaryPopup(state) // Drawn last so it covers the list.
        }
    }
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

private fun ScalingLazyListScope.runItems(
    setup: SetupState,
    state: RunState,
    actions: SetupActions
) {
    when (state.phase) {
        RunPhase.IDLE, RunPhase.STOPPED -> {
            workoutSetup(setup.workoutSettings, actions.configureWorkout)
            item { ImuButton(setup.imuMode, actions.configureImu) }
            if (state.phase == RunPhase.STOPPED) {
                item {
                    Paragraph(stringResource(R.string.run_stopped_after, clock(state.totals.timer)))
                }
            }
            if (setup.readiness.canStart) {
                item {
                    WideButton(stringResource(R.string.run_start)) {
                        actions.command(ServiceCommand.START)
                    }
                }
            }
        }

        RunPhase.PREPARING -> item { Paragraph(stringResource(R.string.run_starting)) }

        RunPhase.RUNNING, RunPhase.PAUSED -> {
            item { Text(clock(state.totals.timer), style = MaterialTheme.typography.displayMedium) }
            item { HeartRateSource(state) }
            item { WorkoutCountdown(state) }
            item {
                WideButton(stringResource(R.string.run_lap)) { actions.command(ServiceCommand.LAP) }
            }
            val paused = state.phase == RunPhase.PAUSED
            item {
                WideButton(
                    stringResource(if (paused) R.string.run_resume else R.string.run_pause)
                ) {
                    actions.command(if (paused) ServiceCommand.RESUME else ServiceCommand.PAUSE)
                }
            }
            item {
                WideButton(stringResource(R.string.run_stop)) {
                    actions.command(ServiceCommand.STOP)
                }
            }
        }
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
private fun Paragraph(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
internal fun WideButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
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

private const val SECONDS_PER_MINUTE = 60

/** `h:mm:ss` or `m:ss`. */
internal fun clock(duration: Duration): String {
    val total = duration.inWholeSeconds
    val hours = total / (SECONDS_PER_MINUTE * SECONDS_PER_MINUTE)
    val minutes = total / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
