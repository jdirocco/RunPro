package com.runpro.wear.ble

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.runpro.core.ble.BleSensorKind
import com.runpro.core.ble.PairedSensor
import com.runpro.wear.Paragraph
import com.runpro.wear.QuietButton
import com.runpro.wear.R
import com.runpro.wear.WideButton

/** What the sensors screen shows. [searching] is the kind being paired, [found] the scan fallback's results. */
data class SensorScreenState(
    val paired: List<PairedSensor> = emptyList(),
    val bluetoothAllowed: Boolean = false,
    val searching: BleSensorKind? = null,
    val found: List<PairedSensor> = emptyList(),
    val message: Int? = null
)

class SensorActions(
    val allowBluetooth: () -> Unit = {},
    val pair: (BleSensorKind) -> Unit = {},
    val choose: (PairedSensor) -> Unit = {},
    val stopSearch: () -> Unit = {},
    val forget: (BleSensorKind) -> Unit = {},
    val close: () -> Unit = {}
)

/** Pair or forget one heart rate strap and one footpod; the next warm-up connects to them. */
@Composable
fun SensorScreen(state: SensorScreenState, actions: SensorActions) {
    MaterialTheme {
        ScreenScaffold {
            ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Text(
                        stringResource(R.string.sensors_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (!state.bluetoothAllowed) {
                    item { Paragraph(stringResource(R.string.permission_bluetooth)) }
                    item {
                        WideButton(stringResource(R.string.setup_allow), actions.allowBluetooth)
                    }
                }
                BleSensorKind.entries.forEach { kind -> sensorItems(kind, state, actions) }
                if (state.searching != null) {
                    item { Paragraph(stringResource(R.string.sensors_searching)) }
                    state.found.forEach { sensor ->
                        item {
                            WideButton(sensor.name ?: sensor.address) { actions.choose(sensor) }
                        }
                    }
                    item {
                        QuietButton(
                            stringResource(R.string.sensors_stop_search),
                            actions.stopSearch
                        )
                    }
                }
                state.message?.let { message -> item { Paragraph(stringResource(message)) } }
                item { Paragraph(stringResource(R.string.sensors_next_run)) }
                item { QuietButton(stringResource(R.string.sensors_close), actions.close) }
            }
        }
    }
}

private fun ScalingLazyListScope.sensorItems(
    kind: BleSensorKind,
    state: SensorScreenState,
    actions: SensorActions
) {
    val sensor = state.paired.firstOrNull { it.kind == kind }
    if (sensor == null) {
        item {
            WideButton(
                stringResource(pairLabel(kind)),
                enabled = state.bluetoothAllowed && state.searching == null
            ) { actions.pair(kind) }
        }
    } else {
        item { Paragraph(stringResource(kindLabel(kind), sensor.name ?: sensor.address)) }
        item { QuietButton(stringResource(R.string.sensors_forget)) { actions.forget(kind) } }
    }
}

private fun pairLabel(kind: BleSensorKind) = when (kind) {
    BleSensorKind.HEART_RATE -> R.string.sensors_pair_strap
    BleSensorKind.FOOTPOD -> R.string.sensors_pair_footpod
}

private fun kindLabel(kind: BleSensorKind) = when (kind) {
    BleSensorKind.HEART_RATE -> R.string.sensors_strap
    BleSensorKind.FOOTPOD -> R.string.sensors_footpod
}
