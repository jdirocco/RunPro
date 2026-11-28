package com.runpro.wear.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.runpro.core.ble.BleLogLine
import com.runpro.core.ble.BleSensorLink
import com.runpro.core.ble.GattDevicePort
import com.runpro.core.ble.PairedSensor
import com.runpro.core.model.SensorListener
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.service.RunPermission
import com.runpro.core.recording.service.ServiceStartPolicy
import com.runpro.wear.AndroidPermissions
import java.util.UUID

/**
 * The BLE sensors of `RecordingService` (#17, ADR 0017): one [BleSensorLink] per paired sensor while a
 * run prepares, records or is paused; none otherwise.
 *
 * Everything runs on [looper] (the `runpro-ble` thread): links, GATT callbacks, the 100 ms tick. [follow]
 * is called from the engine thread and only posts (R6). Samples and sensor events go to the listener
 * given to [start], which submits them to the engine without blocking. A missing permission, Bluetooth
 * off or a sensor that never connects leaves the run recording from the watch sensors (R4).
 */
internal class WatchBle(
    private val context: Context,
    looper: Looper,
    private val clock: () -> Timestamp,
    private val sensors: () -> List<PairedSensor> = { BlePreferences.load(context) },
    private val allowed: () -> Boolean = {
        RunPermission.BLUETOOTH in AndroidPermissions.granted(context)
    },
    private val ports: (
        PairedSensor,
        GattEvents,
        (Runnable) -> Unit
    ) -> GattDevicePort? = { sensor, events, post ->
        androidPort(context, sensor, events, post)
    }
) : AutoCloseable {
    private val handler = Handler(looper)
    private val ticker = Runnable(::tick)
    private var listener: SensorListener? = null
    private var links = emptyList<BleSensorLink>()

    @Volatile private var active = false

    /** The live links, on the BLE thread; for tests and the diagnostics screen (#20). */
    internal val current: List<BleSensorLink> get() = links

    fun start(listener: SensorListener) {
        handler.post { this.listener = listener }
    }

    /** Engine thread: connects for preparing, running and paused, disconnects otherwise. */
    fun follow(phase: RunPhase) {
        val wanted = ServiceStartPolicy.needsForeground(phase)
        if (wanted == active) return
        active = wanted
        handler.post { if (wanted) connect() else disconnect() }
    }

    override fun close() {
        active = false
        handler.post {
            disconnect()
            listener = null
        }
    }

    private fun connect() {
        val target = listener?.takeIf { links.isEmpty() && active } ?: return
        val paired = sensors()
        when {
            paired.isEmpty() -> Unit

            !allowed() -> Log.w(
                TAG,
                "Bluetooth permission missing: ${paired.size} sensor(s) not connected"
            )

            else -> {
                links = paired.mapNotNull { sensor -> link(sensor) }
                val now = clock()
                links.forEach { it.start(target, now) }
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, TICK_MILLIS)
            }
        }
    }

    private fun link(sensor: PairedSensor): BleSensorLink? {
        val events = LinkEvents()
        val port = ports(sensor, events, handler::post) ?: return null
        return BleSensorLink(sensor, port, ::log).also { events.link = it }
    }

    private fun disconnect() {
        handler.removeCallbacks(ticker)
        val now = clock()
        links.forEach { it.stop(now) }
        links = emptyList()
    }

    private fun tick() {
        if (links.isEmpty()) return
        val now = clock()
        links.forEach { it.tick(now) }
        handler.postDelayed(ticker, TICK_MILLIS)
    }

    private fun log(line: BleLogLine) {
        Log.i(TAG, line.toString())
    }

    /** Runs on the BLE thread; a stopped link ignores what its closed sessions still report. */
    private inner class LinkEvents : GattEvents {
        lateinit var link: BleSensorLink

        override fun completed(requestId: Long, status: Int) {
            link.completed(requestId, status, clock())
        }

        override fun disconnected(sessionId: Long, status: Int) {
            link.disconnected(sessionId, status, clock())
        }

        override fun notified(sessionId: Long, characteristic: UUID, value: ByteArray) {
            link.notified(sessionId, characteristic, value, clock())
        }
    }

    companion object {
        private const val TAG = "RunProBle"

        /** The controller needs a tick at least every 100 ms (ADR 0012). */
        const val TICK_MILLIS = 100L

        private fun androidPort(
            context: Context,
            sensor: PairedSensor,
            events: GattEvents,
            post: (Runnable) -> Unit
        ): GattDevicePort? {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) {
                Log.w(TAG, "no Bluetooth adapter: ${sensor.source} not connected")
                return null
            }
            // An off adapter still gives a device; the controller keeps retrying until it is back on.
            val device = adapter.getRemoteDevice(sensor.address)
            return AndroidGattPort(context.applicationContext, device, events) { post(it) }
        }
    }
}
