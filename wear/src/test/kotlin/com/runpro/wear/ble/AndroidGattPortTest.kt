package com.runpro.wear.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.ble.BondState
import com.runpro.core.ble.GattOperation
import com.runpro.core.ble.GattRequest
import com.runpro.core.ble.GattUuids
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice

/** The real GATT adapter against Robolectric's Bluetooth stack, on both callback generations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class AndroidGattPortTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val posted = ArrayDeque<Runnable>()
    private val events = Events()
    private lateinit var device: BluetoothDevice
    private lateinit var port: AndroidGattPort

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        device = ShadowBluetoothDevice.newInstance(ADDRESS)
        port = AndroidGattPort(app, device, events) { posted += it }
    }

    private fun drain() {
        while (posted.isNotEmpty()) posted.removeFirst().run()
    }

    private fun gatt(): BluetoothGatt = shadowOf(device).bluetoothGatts.last()

    private fun callback(): BluetoothGattCallback = shadowOf(gatt()).gattCallback

    private fun request(id: Long, operation: GattOperation, session: Long = 1, uuid: UUID? = null) =
        GattRequest(id, session, operation, 1_000, uuid, autoConnect = false)

    private fun connect(session: Long = 1, id: Long = 1) {
        port.execute(request(id, GattOperation.CONNECT, session))
        callback().onConnectionStateChange(
            gatt(),
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED
        )
        drain()
    }

    private fun heartRateService(): Pair<BluetoothGattService, BluetoothGattCharacteristic> {
        val service =
            BluetoothGattService(
                GattUuids.HEART_RATE_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
        val characteristic = BluetoothGattCharacteristic(
            GattUuids.HEART_RATE_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                GattUuids.CLIENT_CHARACTERISTIC_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(characteristic)
        shadowOf(gatt()).addDiscoverableService(service)
        shadowOf(gatt()).allowCharacteristicNotification(characteristic)
        return service to characteristic
    }

    private fun notify(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callback().onCharacteristicChanged(gatt(), characteristic, value)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            callback().onCharacteristicChanged(gatt(), characteristic)
        }
    }

    @Test
    fun `connect opens one client and completes only once connected, never inline`() {
        port.execute(request(1, GattOperation.CONNECT))
        assertEquals(1, shadowOf(device).bluetoothGatts.size)
        callback().onConnectionStateChange(
            gatt(),
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED
        )
        assertTrue(events.log.isEmpty())
        drain()
        assertEquals(listOf("completed 1 0"), events.log)
    }

    @Test
    fun `a connect that fails reports its status, and a later drop reports a disconnect`() {
        port.execute(request(1, GattOperation.CONNECT))
        callback().onConnectionStateChange(gatt(), 133, BluetoothProfile.STATE_DISCONNECTED)
        drain()
        assertEquals(listOf("completed 1 133"), events.log)
        port.close(1)

        connect(session = 2, id = 2)
        callback().onConnectionStateChange(gatt(), 8, BluetoothProfile.STATE_DISCONNECTED)
        drain()
        assertEquals("disconnected 2 8", events.log.last())
    }

    @Test
    fun `discovery and subscription complete through their callbacks and enable notifications`() {
        connect()
        heartRateService()
        port.execute(request(2, GattOperation.DISCOVER_SERVICES))
        assertEquals(listOf("completed 1 0"), events.log)
        drain()
        port.execute(request(3, GattOperation.SUBSCRIBE, uuid = GattUuids.HEART_RATE_MEASUREMENT))
        drain()
        assertEquals(listOf("completed 1 0", "completed 2 0", "completed 3 0"), events.log)
        assertArrayEquals(
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            shadowOf(gatt()).latestWrittenBytes
        )
    }

    @Test
    fun `a missing characteristic or refused notification rejects the subscription`() {
        connect()
        val (_, characteristic) = heartRateService()
        port.execute(request(2, GattOperation.DISCOVER_SERVICES))
        drain()
        assertThrows(IllegalStateException::class.java) {
            port.execute(request(3, GattOperation.SUBSCRIBE, uuid = GattUuids.RSC_MEASUREMENT))
        }
        shadowOf(gatt()).disallowCharacteristicNotification(characteristic)
        assertThrows(IllegalStateException::class.java) {
            port.execute(
                request(4, GattOperation.SUBSCRIBE, uuid = GattUuids.HEART_RATE_MEASUREMENT)
            )
        }
    }

    @Test
    fun `notifications carry the session and a copy of the bytes`() {
        connect()
        val (_, characteristic) = heartRateService()
        val value = byteArrayOf(0x00, 0x8C.toByte())
        notify(characteristic, value)
        value[1] = 0
        drain()
        assertEquals("notified 1 ${GattUuids.HEART_RATE_MEASUREMENT} [0, -116]", events.log.last())
    }

    @Test
    fun `close releases the client, is idempotent and silences late callbacks`() {
        connect()
        val (_, characteristic) = heartRateService()
        val stale = callback()
        val staleGatt = gatt()
        port.close(1)
        assertTrue(shadowOf(staleGatt).isClosed)
        port.close(1)
        stale.onConnectionStateChange(staleGatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        notify(characteristic, byteArrayOf(0x00, 0x50))
        drain()
        assertEquals(listOf("completed 1 0"), events.log)
        connect(session = 2, id = 2)
        assertEquals(2, shadowOf(device).bluetoothGatts.size)
    }

    @Test
    fun `a second client is refused until the previous session is closed`() {
        connect()
        assertThrows(IllegalStateException::class.java) {
            port.execute(request(2, GattOperation.CONNECT, session = 2))
        }
    }

    @Test
    fun `operations without a live session are rejected`() {
        assertThrows(IllegalStateException::class.java) {
            port.execute(request(1, GattOperation.DISCOVER_SERVICES))
        }
        assertFalse(port.refreshServiceCache(1))
    }

    @Test
    fun `bond state follows the device`() {
        assertEquals(BondState.NONE, port.bondState())
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDING)
        assertEquals(BondState.BONDING, port.bondState())
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        assertEquals(BondState.BONDED, port.bondState())
    }

    @Test
    fun `a cache refresh never throws`() {
        connect()
        port.refreshServiceCache(1)
    }

    private class Events : GattEvents {
        val log = mutableListOf<String>()

        override fun completed(requestId: Long, status: Int) {
            log += "completed $requestId $status"
        }

        override fun disconnected(sessionId: Long, status: Int) {
            log += "disconnected $sessionId $status"
        }

        override fun notified(sessionId: Long, characteristic: UUID, value: ByteArray) {
            log += "notified $sessionId $characteristic ${value.toList()}"
        }
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
    }
}
