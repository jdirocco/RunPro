package com.runpro.wear

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import com.runpro.core.model.HeartRateZones
import com.runpro.core.model.HrZoneMethod
import com.runpro.core.model.PaceZones
import com.runpro.core.model.RunSettings

/** Pre-run zone preview and alert configuration, saved immediately into [RunSettings]. */
@Composable
internal fun ZonesAndAlertsScreen(
    settings: RunSettings,
    update: (RunSettings) -> Unit,
    close: () -> Unit
) {
    var editing by remember { mutableStateOf<AlertField?>(null) }
    val field = editing
    BackHandler { if (field == null) close() else editing = null }
    if (field != null) {
        AlertLimitEditor(settings, field, update) { editing = null }
        return
    }
    ScalingLazyColumn {
        item { Paragraph(stringResource(R.string.zones_alerts_title)) }
        hrZoneItems(settings, update)
        paceZoneItems(settings)
        alertLimitItems(settings) { editing = it }
        alertToggleItems(settings, update)
        item { QuietButton(stringResource(R.string.history_back), close) }
    }
}

private fun ScalingLazyListScope.hrZoneItems(settings: RunSettings, update: (RunSettings) -> Unit) {
    val method = settings.hrZoneMethod
    item {
        val name = stringResource(methodLabel(method))
        WideButton(stringResource(R.string.zones_hr_method, name)) {
            val next = if (method == HrZoneMethod.MAX_HR) HrZoneMethod.LTHR else HrZoneMethod.MAX_HR
            update(settings.copy(hrZoneMethod = next))
        }
    }
    val zones = settings.hrZones()
    if (zones == null) {
        val missing = if (method == HrZoneMethod.MAX_HR) {
            R.string.zones_hr_missing_max
        } else {
            R.string.zones_hr_missing_lthr
        }
        item { Paragraph(stringResource(missing)) }
        return
    }
    item {
        Paragraph(stringResource(R.string.zones_hr_heading, stringResource(methodLabel(method))))
    }
    for (zone in 1..HeartRateZones.ZONE_COUNT) {
        val range = zones.rangeFor(zone)
        item {
            val text = if (zone < HeartRateZones.ZONE_COUNT) {
                stringResource(R.string.zones_hr_band, zone, range.first, range.last)
            } else {
                stringResource(R.string.zones_hr_band_open, zone, range.first)
            }
            Paragraph(text)
        }
    }
}

private fun ScalingLazyListScope.paceZoneItems(settings: RunSettings) {
    item { Paragraph(stringResource(R.string.zones_pace_heading)) }
    val zones = settings.paceZones()
    if (zones == null) {
        item { Paragraph(stringResource(R.string.zones_pace_missing)) }
        return
    }
    val unit = settings.units.distance
    for (zone in 1..PaceZones.ZONE_COUNT) {
        val range = zones.rangeFor(zone)
        item {
            val text = when {
                range.slowest == null -> stringResource(
                    R.string.zones_pace_band_slow,
                    zone,
                    pace(range.fastest, unit)
                )

                range.fastest == null -> stringResource(
                    R.string.zones_pace_band_fast,
                    zone,
                    pace(range.slowest, unit)
                )

                else -> stringResource(
                    R.string.zones_pace_band,
                    zone,
                    pace(range.fastest, unit),
                    pace(range.slowest, unit)
                )
            }
            Paragraph(text)
        }
    }
}

private fun ScalingLazyListScope.alertLimitItems(
    settings: RunSettings,
    edit: (AlertField) -> Unit
) {
    AlertField.entries.forEach { field ->
        item {
            val display = alertFieldValue(settings, field)
            WideButton(stringResource(field.label, display)) { edit(field) }
        }
    }
}

private fun ScalingLazyListScope.alertToggleItems(
    settings: RunSettings,
    update: (RunSettings) -> Unit
) {
    val alerts = settings.alerts
    item {
        val label = stringResource(
            if (alerts.sensorAlerts) R.string.settings_on else R.string.workout_off
        )
        WideButton(stringResource(R.string.alert_sensor_setting, label)) {
            update(settings.copy(alerts = alerts.copy(sensorAlerts = !alerts.sensorAlerts)))
        }
    }
    item {
        val label = stringResource(
            if (alerts.gpsAlerts) R.string.settings_on else R.string.workout_off
        )
        WideButton(stringResource(R.string.alert_gps_setting, label)) {
            update(settings.copy(alerts = alerts.copy(gpsAlerts = !alerts.gpsAlerts)))
        }
    }
    item {
        val value = stringResource(R.string.settings_seconds, alerts.cooldownSeconds)
        WideButton(stringResource(R.string.alert_cooldown_setting, value)) {
            val currentIndex = COOLDOWNS.indexOf(alerts.cooldownSeconds)
            val next = COOLDOWNS[(currentIndex + 1) % COOLDOWNS.size]
            update(settings.copy(alerts = alerts.copy(cooldownSeconds = next)))
        }
    }
}

private fun methodLabel(method: HrZoneMethod): Int = when (method) {
    HrZoneMethod.MAX_HR -> R.string.zones_method_max_hr
    HrZoneMethod.LTHR -> R.string.zones_method_lthr
}

private val COOLDOWNS = listOf(10, 15, 30, 60, 120)
