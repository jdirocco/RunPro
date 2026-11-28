package com.runpro.wear.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.Parcelable
import android.util.Log
import com.runpro.core.ble.BleSensorKind
import com.runpro.core.ble.PairedSensor

/**
 * Pairs a sensor through `CompanionDeviceManager` (ADR 0017). The association is what keeps RunPro's
 * background rights with the sensor (`REQUEST_COMPANION_RUN_IN_BACKGROUND`,
 * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`); the system chooser needs no scan
 * permission. Main thread only. [supported] is false when the watch has no companion device setup:
 * [BleScan] is the fallback.
 */
internal class BleAssociation(
    private val context: Context,
    private val launch: (IntentSender) -> Unit,
    private val paired: (PairedSensor) -> Unit,
    private val failed: (String?) -> Unit
) {
    private val manager: CompanionDeviceManager? =
        if (context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_COMPANION_DEVICE_SETUP
            )
        ) {
            context.getSystemService(CompanionDeviceManager::class.java)
        } else {
            null
        }
    private var kind = BleSensorKind.HEART_RATE

    val supported: Boolean get() = manager != null

    fun pair(kind: BleSensorKind) {
        val manager = manager ?: return failed(null)
        this.kind = kind
        val request = request(kind)
        // The platform may refuse at once (for instance too many associations).
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(request, context.mainExecutor, Callback())
            } else {
                @Suppress("DEPRECATION")
                manager.associate(request, LegacyCallback(), Handler(Looper.getMainLooper()))
            }
        }.onFailure {
            Log.w(TAG, "association refused", it)
            failed(it.message)
        }
    }

    /** The chooser's result: the association (API 33+) or the selected scan result (older APIs). */
    fun result(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return failed(null)
        val sensor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java
            )
                ?.let(::sensorOf)
        } else {
            @Suppress("DEPRECATION")
            legacySensorOf(data.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE))
        }
        if (sensor == null) failed(null) else paired(sensor)
    }

    /** Drops the association of a sensor that is forgotten or replaced; best effort. */
    fun forget(sensor: PairedSensor) {
        val manager = manager ?: return
        runCatching {
            val id = sensor.associationId
            if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.disassociate(id)
            } else {
                @Suppress("DEPRECATION")
                manager.disassociate(sensor.address)
            }
        }.onFailure { Log.w(TAG, "disassociate ${sensor.source} failed", it) }
    }

    // AssociationInfo exists from API 33; the platform calls this only there.
    @SuppressLint("NewApi")
    private fun sensorOf(info: AssociationInfo): PairedSensor? = runCatching {
        val address = checkNotNull(info.deviceMacAddress).toString()
        PairedSensor.of(address, kind, info.displayName?.toString(), info.id)
    }.getOrNull()

    // The name is optional; without the permission it stays null.
    @SuppressLint("MissingPermission")
    private fun legacySensorOf(extra: Parcelable?): PairedSensor? {
        val device = when (extra) {
            is ScanResult -> extra.device
            is BluetoothDevice -> extra
            else -> return null
        }
        val name = (extra as? ScanResult)?.scanRecord?.deviceName
            ?: runCatching { device.name }.getOrNull()
        return runCatching { PairedSensor.of(device.address, kind, name) }.getOrNull()
    }

    private inner class Callback : CompanionDeviceManager.Callback() {
        override fun onAssociationPending(intentSender: IntentSender) {
            launch(intentSender)
        }

        override fun onAssociationCreated(associationInfo: AssociationInfo) {
            sensorOf(associationInfo)?.let(paired)
        }

        override fun onFailure(error: CharSequence?) {
            failed(error?.toString())
        }
    }

    private inner class LegacyCallback : CompanionDeviceManager.Callback() {
        @Deprecated("Replaced by onAssociationPending from API 33")
        override fun onDeviceFound(intentSender: IntentSender) {
            launch(intentSender)
        }

        override fun onFailure(error: CharSequence?) {
            failed(error?.toString())
        }
    }

    companion object {
        private const val TAG = "RunProBle"

        fun request(kind: BleSensorKind): AssociationRequest {
            val scan = ScanFilter.Builder().setServiceUuid(ParcelUuid(kind.service)).build()
            return AssociationRequest.Builder()
                .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(scan).build())
                .setSingleDevice(false)
                .build()
        }
    }
}

/**
 * Fallback when the watch has no companion device setup: a short low-latency scan for [BleSensorKind.service]
 * that reports each device once. Needs `BLUETOOTH_SCAN` (declared `neverForLocation`). Main thread only.
 */
@SuppressLint("MissingPermission") // The screen asks for the Bluetooth permissions before scanning.
internal class BleScan(context: Context, private val found: (PairedSensor) -> Unit) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val seen = mutableSetOf<String>()
    private var kind = BleSensorKind.HEART_RATE
    private var callback: ScanCallback? = null

    /** False when Bluetooth is off or unavailable. */
    fun start(kind: BleSensorKind, finished: () -> Unit): Boolean {
        stop()
        this.kind = kind
        seen.clear()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(kind.service)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val scan = Results()
        val started = runCatching {
            val scanner = checkNotNull(adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner)
            scanner.startScan(listOf(filter), settings, scan)
        }.isSuccess
        if (started) {
            callback = scan
            handler.postDelayed({
                stop()
                finished()
            }, SCAN_MILLIS)
        }
        return started
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val scan = callback ?: return
        callback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
    }

    private inner class Results : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (callback !== this || !seen.add(address)) return
            val name =
                result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
            runCatching { PairedSensor.of(address, kind, name) }.getOrNull()?.let(found)
        }
    }

    private companion object {
        const val SCAN_MILLIS = 15_000L
    }
}
