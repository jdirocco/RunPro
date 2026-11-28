package com.runpro.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.runpro.core.ble.BondState
import com.runpro.core.ble.GattDevicePort
import com.runpro.core.ble.GattOperation
import com.runpro.core.ble.GattRequest
import com.runpro.core.ble.GattUuids
import java.util.UUID

/** What [AndroidGattPort] reports, on the BLE thread, bound to the ids of the original request/session. */
internal interface GattEvents {
    fun completed(requestId: Long, status: Int)

    fun disconnected(sessionId: Long, status: Int)

    fun notified(sessionId: Long, characteristic: UUID, value: ByteArray)
}

/**
 * The real [GattDevicePort] of one device over `BluetoothGatt` (ADR 0012, ADR 0017).
 *
 * Every method runs on the BLE thread. Binder callbacks copy their arguments and [post] themselves to
 * that thread, so completions are never inline from [execute] and no state is shared across threads.
 * Each session has its own callback: a late callback of a closed session is dropped here, and a stale
 * request id is rejected by the controller. Connections use `TRANSPORT_LE`; `autoConnect` comes from the
 * controller (false for the first connection, true for reconnects). [close] always calls
 * `BluetoothGatt.close()`, never waiting for a disconnect callback.
 *
 * The caller checks `BLUETOOTH_CONNECT` before creating links; a `SecurityException` thrown anyway (the
 * permission revoked mid-run) is a rejected request, and the run continues without the sensor (R4).
 */
@SuppressLint("MissingPermission")
internal class AndroidGattPort(
    private val context: Context,
    private val device: BluetoothDevice,
    private val events: GattEvents,
    private val post: (Runnable) -> Unit
) : GattDevicePort {
    private val sessions = mutableMapOf<Long, Session>()

    override fun execute(request: GattRequest) {
        when (request.operation) {
            GattOperation.CONNECT -> connect(request)
            GattOperation.DISCOVER_SERVICES -> discover(request)
            GattOperation.SUBSCRIBE -> subscribe(request)
        }
    }

    override fun close(sessionId: Long) {
        val session = sessions[sessionId] ?: return
        session.closed = true
        session.gatt?.let { gatt ->
            runCatching { gatt.disconnect() } // close() below is what releases the client.
            gatt.close()
        }
        sessions.remove(sessionId)
    }

    // BluetoothGatt.refresh() is hidden; it is the only way to drop a stale service cache.
    @SuppressLint("DiscouragedPrivateApi")
    override fun refreshServiceCache(sessionId: Long): Boolean {
        val gatt = sessions[sessionId]?.gatt ?: return false
        return runCatching {
            BluetoothGatt::class.java.getMethod("refresh").invoke(gatt) as? Boolean ?: false
        }.getOrDefault(false)
    }

    override fun bondState(): BondState = when (device.bondState) {
        BluetoothDevice.BOND_BONDING -> BondState.BONDING
        BluetoothDevice.BOND_BONDED -> BondState.BONDED
        else -> BondState.NONE
    }

    private fun connect(request: GattRequest) {
        check(sessions.isEmpty()) { "previous GATT session not closed" }
        val session = Session(request.sessionId).also { it.pending = request }
        sessions[request.sessionId] = session
        session.gatt = device.connectGatt(
            context,
            request.autoConnect,
            Callback(session),
            BluetoothDevice.TRANSPORT_LE
        ) ?: error("connectGatt returned null")
    }

    private fun discover(request: GattRequest) {
        val session = live(request)
        session.pending = request
        check(session.requireGatt().discoverServices()) { "discoverServices refused" }
    }

    private fun subscribe(request: GattRequest) {
        val session = live(request)
        val gatt = session.requireGatt()
        val uuid = requireNotNull(request.characteristic)
        val characteristic = gatt.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }
            ?: error("characteristic $uuid missing")
        val cccd = characteristic.getDescriptor(GattUuids.CLIENT_CHARACTERISTIC_CONFIGURATION)
            ?: error("no CCCD on $uuid")
        val value = when {
            characteristic.has(BluetoothGattCharacteristic.PROPERTY_NOTIFY) ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            characteristic.has(BluetoothGattCharacteristic.PROPERTY_INDICATE) ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

            else -> error("$uuid cannot notify")
        }
        check(gatt.setCharacteristicNotification(characteristic, true)) {
            "notifications refused on $uuid"
        }
        session.pending = request
        check(writeDescriptor(gatt, cccd, value)) { "CCCD write refused on $uuid" }
    }

    private fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        descriptor.value = value
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(descriptor)
    }

    private fun live(request: GattRequest): Session =
        checkNotNull(sessions[request.sessionId]?.takeUnless { it.closed }) {
            "no live GATT session ${request.sessionId}"
        }

    /** BLE thread only. */
    private inner class Session(val id: Long) {
        var gatt: BluetoothGatt? = null
        var pending: GattRequest? = null
        var closed = false

        fun requireGatt(): BluetoothGatt = checkNotNull(gatt) { "GATT session $id has no client" }

        /** Completes the in-flight request of [operation], if it is the one in flight. */
        fun complete(operation: GattOperation, status: Int, characteristic: UUID? = null) {
            val request = pending?.takeIf {
                it.operation == operation &&
                    (characteristic == null || it.characteristic == characteristic)
            } ?: return
            pending = null
            events.completed(request.id, status)
        }

        fun connectionChanged(status: Int, newState: Int) {
            if (closed) return
            val connecting = pending?.operation == GattOperation.CONNECT
            val up = newState == BluetoothProfile.STATE_CONNECTED
            when {
                up && status == BluetoothGatt.GATT_SUCCESS -> complete(
                    GattOperation.CONNECT,
                    status
                )

                up -> Unit

                connecting -> complete(
                    GattOperation.CONNECT,
                    if (status ==
                        0
                    ) {
                        STATUS_DISCONNECTED
                    } else {
                        status
                    }
                )

                else -> events.disconnected(id, status)
            }
        }

        fun notified(characteristic: UUID, value: ByteArray) {
            if (!closed) events.notified(id, characteristic, value)
        }
    }

    /** One per session; binder thread: copy, then post. */
    private inner class Callback(private val session: Session) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            post { session.connectionChanged(status, newState) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            post { if (!session.closed) session.complete(GattOperation.DISCOVER_SERVICES, status) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != GattUuids.CLIENT_CHARACTERISTIC_CONFIGURATION) return
            val characteristic = descriptor.characteristic.uuid
            post {
                if (!session.closed) {
                    session.complete(
                        GattOperation.SUBSCRIBE,
                        status,
                        characteristic
                    )
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val uuid = characteristic.uuid
            val copy = value.copyOf()
            post { session.notified(uuid, copy) }
        }

        // Before API 33 this is the only notification callback; from 33 the one above replaces it.
        @Deprecated("Replaced by the value overload from API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val copy = characteristic.value?.copyOf() ?: return
            val uuid = characteristic.uuid
            post { session.notified(uuid, copy) }
        }
    }

    companion object {
        /** A connect that ended in a disconnect reporting success (seen with `autoConnect`). */
        const val STATUS_DISCONNECTED = -2
    }
}

private fun BluetoothGattCharacteristic.has(property: Int) = properties and property != 0
