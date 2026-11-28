package com.runpro.wear.ble

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.runpro.core.ble.BleSensorKind
import com.runpro.core.ble.PairedSensor
import com.runpro.core.ble.PairedSensors
import com.runpro.core.recording.service.RunPermission
import com.runpro.wear.AndroidPermissions
import com.runpro.wear.R

/**
 * Pairs the strap and the footpod (#17): the companion device chooser where the watch has one,
 * otherwise a short scan. A chooser that fails or closes without an association (Wear OS refuses
 * third-party associations on the Galaxy Watch 5 Pro, #115) falls back to the scan, which the screen
 * then uses directly.
 * The choice is saved in [BlePreferences]; a run already recording keeps its sensors and the next
 * warm-up connects to the new ones.
 */
class SensorActivity : ComponentActivity() {
    private var state by mutableStateOf(SensorScreenState())
    private lateinit var association: BleAssociation
    private lateinit var scan: BleScan
    private var companionWorks = true

    private val askBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val chooser = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { association.result(it.resultCode, it.data) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        association = BleAssociation(
            this,
            launch = { chooser.launch(IntentSenderRequest.Builder(it).build()) },
            paired = ::save,
            failed = ::companionFailed
        )
        scan = BleScan(this) { found ->
            if (state.found.none { it.address == found.address }) {
                state =
                    state.copy(found = state.found + found)
            }
        }
        state = state.copy(paired = BlePreferences.load(this))
        setContent {
            SensorScreen(
                state,
                SensorActions(
                    allowBluetooth = ::askForBluetooth,
                    pair = ::pair,
                    choose = ::save,
                    stopSearch = ::stopSearch,
                    forget = ::forget,
                    close = ::finish
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        // The companion chooser covers this screen: only an own scan stops here.
        if (scan.active) stopSearch()
        super.onStop()
    }

    private fun refresh() {
        state =
            state.copy(
                bluetoothAllowed =
                    RunPermission.BLUETOOTH in AndroidPermissions.granted(this)
            )
    }

    private fun askForBluetooth() {
        val platform = AndroidPermissions.platform(RunPermission.BLUETOOTH)
        if (platform.isEmpty()) refresh() else askBluetooth.launch(platform.toTypedArray())
    }

    private fun pair(kind: BleSensorKind) {
        state = state.copy(searching = kind, found = emptyList(), message = null)
        if (association.supported && companionWorks) {
            association.pair(kind)
        } else if (!scan.start(kind) { state = state.copy(searching = null) }) {
            state = state.copy(searching = null, message = R.string.sensors_bluetooth_off)
        }
    }

    /** The chooser failed, was refused or closed without an association: scan instead (#115). */
    private fun companionFailed(reason: String?) {
        Log.w(TAG, "companion association failed: ${reason ?: "chooser closed"}; scanning")
        companionWorks = false
        val kind = state.searching
        when {
            scan.active -> Unit
            kind != null -> pair(kind)
            else -> state = state.copy(message = R.string.sensors_not_paired)
        }
    }

    private fun stopSearch() {
        scan.stop()
        state = state.copy(searching = null, found = emptyList())
    }

    private fun save(sensor: PairedSensor) {
        scan.stop()
        // The replaced sensor loses its association, unless it is the very association just made.
        state.paired
            .filter { it.kind == sensor.kind || it.address == sensor.address }
            .filterNot { it.address == sensor.address && it.associationId == sensor.associationId }
            .forEach(association::forget)
        update(PairedSensors.pair(state.paired, sensor), R.string.sensors_paired)
    }

    private fun forget(kind: BleSensorKind) {
        state.paired.filter { it.kind == kind }.forEach(association::forget)
        update(PairedSensors.forget(state.paired, kind), message = null)
    }

    private fun update(paired: List<PairedSensor>, message: Int?) {
        BlePreferences.save(this, paired)
        state =
            state.copy(paired = paired, searching = null, found = emptyList(), message = message)
    }

    private companion object {
        const val TAG = "RunProBle"
    }
}
