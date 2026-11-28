package com.runpro.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.runpro.core.model.DisplayUnits
import com.runpro.core.recording.AlertKind
import com.runpro.core.recording.RunAlert
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Distinct non-repeating vibration waveforms (ms: initial delay, on, off, ...) for every alert.
 * Every pattern is distinct from the countdown pulse (60 ms) and lap double-pulse (200/100/200 ms)
 * so the runner can tell alerts apart without looking at the watch.
 */
@Suppress("MagicNumber")
internal object AlertFeedback {
    fun waveform(kind: AlertKind): LongArray = when (kind) {
        AlertKind.HR_HIGH -> longArrayOf(0, 100, 80, 100, 80, 100)
        AlertKind.HR_LOW -> longArrayOf(0, 350)
        AlertKind.PACE_TOO_FAST -> longArrayOf(0, 80, 70, 80)
        AlertKind.PACE_TOO_SLOW -> longArrayOf(0, 100, 80, 300)
        AlertKind.SENSOR_LOST -> longArrayOf(0, 300, 120, 300)
        AlertKind.SENSOR_BACK -> longArrayOf(0, 80, 80, 180)
        AlertKind.GPS_LOST -> longArrayOf(0, 300, 100, 80, 80, 80)
        AlertKind.GPS_BACK -> longArrayOf(0, 80, 80, 140, 80, 200)
    }
}

/** Overlays drawn last over the run screen: live alerts and lap summaries. */
@Composable
internal fun RunOverlays(state: RunState) {
    AlertPopup(state)
    LapSummaryPopup(state)
}

/**
 * Shows the most recent live alert for [ALERT_POPUP] of run time. Driven by engine elapsed time so
 * reopening the watch app later never shows a stale alert; tap dismisses immediately.
 */
@Composable
internal fun AlertPopup(state: RunState) {
    val alert = alertToShow(state)
    var dismissed by remember(state.run?.id) { mutableStateOf<Long?>(null) }
    if (alert == null || alert.id == dismissed) return
    val units = state.workoutSettings.runSettings.units
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable { dismissed = alert.id },
        contentAlignment = Alignment.Center
    ) {
        Text(
            alertMessage(alert, units),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
    }
}

internal fun alertToShow(state: RunState): RunAlert? {
    val run = state.run?.takeIf { state.phase == RunPhase.RUNNING } ?: return null
    val now = run.startedAt + state.totals.elapsed
    return state.alerts.lastOrNull()?.takeIf { alert ->
        val age = now.elapsedSince(alert.time)
        age >= Duration.ZERO && age < ALERT_POPUP
    }
}

@Composable
private fun alertMessage(alert: RunAlert, units: DisplayUnits): String {
    val title = stringResource(alertTitle(alert.kind))
    val detail = when (alert.kind) {
        AlertKind.HR_HIGH, AlertKind.HR_LOW ->
            alert.heartRate?.let { stringResource(R.string.source_heart_rate, it.bpm) }

        AlertKind.PACE_TOO_FAST, AlertKind.PACE_TOO_SLOW ->
            alert.pace?.let {
                val unit = stringResource(
                    if (units ==
                        DisplayUnits.IMPERIAL
                    ) {
                        R.string.unit_pace_mi
                    } else {
                        R.string.unit_pace
                    }
                )
                "${pace(it, units.distance)} $unit"
            }

        else -> null
    }
    return if (detail == null) {
        title
    } else {
        stringResource(R.string.alert_banner_value, title, detail)
    }
}

private fun alertTitle(kind: AlertKind): Int = when (kind) {
    AlertKind.HR_HIGH -> R.string.alert_hr_high_title
    AlertKind.HR_LOW -> R.string.alert_hr_low_title
    AlertKind.PACE_TOO_FAST -> R.string.alert_pace_fast_title
    AlertKind.PACE_TOO_SLOW -> R.string.alert_pace_slow_title
    AlertKind.SENSOR_LOST -> R.string.alert_sensor_lost_title
    AlertKind.SENSOR_BACK -> R.string.alert_sensor_back_title
    AlertKind.GPS_LOST -> R.string.alert_gps_lost_title
    AlertKind.GPS_BACK -> R.string.alert_gps_back_title
}

private val ALERT_POPUP = 4.seconds
