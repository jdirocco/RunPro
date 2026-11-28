package com.runpro.wear.ble

import com.runpro.core.ble.BondState
import com.runpro.core.ble.GattDevicePort
import com.runpro.core.ble.GattRequest
import com.runpro.core.ble.GattUuids
import java.util.concurrent.CopyOnWriteArrayList

/** A GATT that completes every request on the BLE thread, as a working strap does. Thread safe. */
internal class FakeGattPort(private val events: GattEvents, private val post: (Runnable) -> Unit) :
    GattDevicePort {
    val requests: MutableList<GattRequest> = CopyOnWriteArrayList()
    val closed: MutableList<Long> = CopyOnWriteArrayList()

    override fun execute(request: GattRequest) {
        requests += request
        post { events.completed(request.id, 0) }
    }

    override fun close(sessionId: Long) {
        closed += sessionId
    }

    override fun refreshServiceCache(sessionId: Long) = false

    override fun bondState() = BondState.NONE

    override fun removeBond() = false

    fun notify(value: ByteArray) {
        val session = requests.last().sessionId
        post { events.notified(session, GattUuids.HEART_RATE_MEASUREMENT, value) }
    }

    fun drop(status: Int) {
        val session = requests.last().sessionId
        post { events.disconnected(session, status) }
    }
}
