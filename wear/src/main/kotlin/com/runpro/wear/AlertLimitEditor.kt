package com.runpro.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import com.runpro.core.model.AlertSettings
import com.runpro.core.model.DisplayUnits
import com.runpro.core.model.Pace
import com.runpro.core.model.RunSettings
import kotlin.math.roundToInt

@Composable
internal fun AlertLimitEditor(
    settings: RunSettings,
    field: AlertField,
    update: (RunSettings) -> Unit,
    close: () -> Unit
) {
    val alerts = settings.alerts
    val scale = paceScale(settings.units)
    val limits = alertLimits(alerts, field, scale)
    var value by remember(field) {
        mutableStateOf(initialAlertValue(alerts, field, scale).coerceIn(limits))
    }
    ScalingLazyColumn {
        item {
            val text = formattedAlertValue(field, value, settings.units, scale)
            Paragraph(stringResource(field.label, text))
        }
        item {
            WideButton(stringResource(R.string.settings_increase)) {
                value = (value + ALERT_STEP).coerceIn(limits)
            }
        }
        item {
            WideButton(stringResource(R.string.settings_decrease)) {
                value = (value - ALERT_STEP).coerceIn(limits)
            }
        }
        item {
            WideButton(stringResource(R.string.settings_save)) {
                update(settings.copy(alerts = saveAlert(alerts, field, value, scale)))
                close()
            }
        }
        item {
            QuietButton(stringResource(R.string.settings_clear)) {
                update(settings.copy(alerts = saveAlert(alerts, field, null, scale)))
                close()
            }
        }
        item { QuietButton(stringResource(R.string.history_cancel), close) }
    }
}

@Composable
internal fun alertFieldValue(settings: RunSettings, field: AlertField): String {
    val alerts = settings.alerts
    val formatted = when (field) {
        AlertField.HR_HIGH -> alerts.hrHighBpm?.let {
            stringResource(R.string.source_heart_rate, it)
        }

        AlertField.HR_LOW -> alerts.hrLowBpm?.let { stringResource(R.string.source_heart_rate, it) }

        AlertField.PACE_FAST -> alerts.paceFast?.let { paceWithUnit(it, settings.units) }

        AlertField.PACE_SLOW -> alerts.paceSlow?.let { paceWithUnit(it, settings.units) }
    }
    return formatted ?: stringResource(R.string.workout_off)
}

@Composable
private fun formattedAlertValue(
    field: AlertField,
    value: Double,
    units: DisplayUnits,
    scale: Double
): String = when (field) {
    AlertField.HR_HIGH, AlertField.HR_LOW ->
        stringResource(R.string.source_heart_rate, value.roundToInt())

    AlertField.PACE_FAST, AlertField.PACE_SLOW -> paceWithUnit(
        Pace((value / scale).coerceIn(AlertSettings.MIN_PACE_SEC, AlertSettings.MAX_PACE_SEC)),
        units
    )
}

private fun initialAlertValue(alerts: AlertSettings, field: AlertField, scale: Double): Double =
    when (field) {
        AlertField.HR_HIGH -> (alerts.hrHighBpm ?: DEFAULT_HR_HIGH).toDouble()
        AlertField.HR_LOW -> (alerts.hrLowBpm ?: DEFAULT_HR_LOW).toDouble()
        AlertField.PACE_FAST -> (alerts.paceFast?.secondsPerKilometer ?: DEFAULT_PACE_FAST) * scale
        AlertField.PACE_SLOW -> (alerts.paceSlow?.secondsPerKilometer ?: DEFAULT_PACE_SLOW) * scale
    }

private fun alertLimits(
    alerts: AlertSettings,
    field: AlertField,
    scale: Double
): ClosedFloatingPointRange<Double> = when (field) {
    AlertField.HR_HIGH -> maxOf(
        AlertSettings.MIN_HR_ALERT,
        (alerts.hrLowBpm ?: 0) + 1
    ).toDouble()..AlertSettings.MAX_HR_ALERT.toDouble()

    AlertField.HR_LOW -> AlertSettings.MIN_HR_ALERT.toDouble()..minOf(
        AlertSettings.MAX_HR_ALERT,
        (alerts.hrHighBpm ?: (AlertSettings.MAX_HR_ALERT + 1)) - 1
    ).toDouble()

    AlertField.PACE_FAST -> (AlertSettings.MIN_PACE_SEC * scale)..(
        minOf(
            AlertSettings.MAX_PACE_SEC,
            (alerts.paceSlow?.secondsPerKilometer ?: (AlertSettings.MAX_PACE_SEC + 1.0)) - 1.0
        ) * scale
        )

    AlertField.PACE_SLOW -> (
        maxOf(
            AlertSettings.MIN_PACE_SEC,
            (alerts.paceFast?.secondsPerKilometer ?: 0.0) + 1.0
        ) * scale
        )..(AlertSettings.MAX_PACE_SEC * scale)
}

private fun saveAlert(
    alerts: AlertSettings,
    field: AlertField,
    value: Double?,
    scale: Double
): AlertSettings = when (field) {
    AlertField.HR_HIGH -> alerts.copy(hrHighBpm = value?.roundToInt())

    AlertField.HR_LOW -> alerts.copy(hrLowBpm = value?.roundToInt())

    AlertField.PACE_FAST -> alerts.copy(
        paceFast = value?.let {
            Pace((it / scale).coerceIn(AlertSettings.MIN_PACE_SEC, AlertSettings.MAX_PACE_SEC))
        }
    )

    AlertField.PACE_SLOW -> alerts.copy(
        paceSlow = value?.let {
            Pace((it / scale).coerceIn(AlertSettings.MIN_PACE_SEC, AlertSettings.MAX_PACE_SEC))
        }
    )
}

private const val ALERT_STEP = 5.0
private const val DEFAULT_HR_HIGH = 175
private const val DEFAULT_HR_LOW = 125
private const val DEFAULT_PACE_FAST = 270.0
private const val DEFAULT_PACE_SLOW = 360.0
