package com.runpro.wear

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import com.runpro.core.model.DisplayUnits
import com.runpro.core.model.RunSettings

/** Pre-run settings, saved immediately by the host. Numeric editors commit only on Save. */
@Composable
internal fun SettingsScreen(
    settings: RunSettings,
    update: (RunSettings) -> Unit,
    close: () -> Unit
) {
    var editing by remember { mutableStateOf<ProfileField?>(null) }
    val field = editing
    BackHandler { if (field == null) close() else editing = null }
    MaterialTheme {
        ScreenScaffold {
            if (field != null) {
                ProfileEditor(settings, field, update) { editing = null }
            } else {
                ScalingLazyColumn {
                    item { Paragraph(stringResource(R.string.settings_title)) }
                    item { Paragraph(stringResource(R.string.settings_next_run)) }
                    item { UnitsButton(settings, update) }
                    ProfileField.entries.forEach { entry ->
                        item {
                            val value = profileValue(settings, entry)
                            WideButton(stringResource(entry.label, value)) { editing = entry }
                        }
                    }
                    item {
                        WideButton(
                            stringResource(R.string.settings_auto_pause, onOff(settings.autoPause))
                        ) {
                            update(settings.copy(autoPause = !settings.autoPause))
                        }
                    }
                    item { Paragraph(stringResource(R.string.settings_auto_pause_note)) }
                    item { CountdownSetting(settings, update) }
                    item {
                        WideButton(
                            stringResource(
                                R.string.settings_debug_trace,
                                onOff(settings.debugTrace)
                            )
                        ) {
                            update(settings.copy(debugTrace = !settings.debugTrace))
                        }
                    }
                    item { Paragraph(stringResource(R.string.settings_trace_note)) }
                    item { QuietButton(stringResource(R.string.history_back), close) }
                }
            }
        }
    }
}

@Composable
private fun onOff(enabled: Boolean) =
    stringResource(if (enabled) R.string.settings_on else R.string.workout_off)

@Composable
private fun profileValue(settings: RunSettings, field: ProfileField): String {
    val profile = settings.profile
    return when (field) {
        ProfileField.MAX_HR -> profile.maxHeartRate?.toString()
        ProfileField.RESTING_HR -> profile.restingHeartRate?.toString()
        ProfileField.WEIGHT -> profile.weightKg?.let { weightLabel(it, settings.units) }
    } ?: stringResource(R.string.settings_not_set)
}

@Composable
private fun UnitsButton(settings: RunSettings, update: (RunSettings) -> Unit) {
    val metric = settings.units == DisplayUnits.METRIC
    val name = stringResource(if (metric) R.string.settings_metric else R.string.settings_imperial)
    WideButton(stringResource(R.string.settings_units, name)) {
        update(settings.copy(units = if (metric) DisplayUnits.IMPERIAL else DisplayUnits.METRIC))
    }
}

@Composable
private fun CountdownSetting(settings: RunSettings, update: (RunSettings) -> Unit) {
    val countdown = settings.countdownSeconds
    val value = if (countdown == 0) {
        stringResource(R.string.workout_off)
    } else {
        stringResource(R.string.settings_seconds, countdown)
    }
    WideButton(stringResource(R.string.settings_countdown, value)) {
        val next = COUNTDOWNS[(COUNTDOWNS.indexOf(countdown) + 1) % COUNTDOWNS.size]
        update(settings.copy(countdownSeconds = next))
    }
}

private val COUNTDOWNS = listOf(0, 3, 5, 10)
