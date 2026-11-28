package com.runpro.wear.ble

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.ble.BleConnectionState
import com.runpro.core.ble.BleSensorKind
import com.runpro.core.ble.GattOperation
import com.runpro.core.ble.PairedSensor
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The service-side owner of the links: phases, permission, ticking, delivery to the engine feed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchBleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var paired = listOf(STRAP)
    private var allowed = true
    private val ports = mutableListOf<FakeGattPort>()
    private val samples = mutableListOf<Sample>()
    private val events = mutableListOf<SensorEvent>()
    private val ble = WatchBle(
        app,
        Looper.getMainLooper(),
        clock = { Timestamp(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis()) },
        sensors = { paired },
        allowed = { allowed },
        ports = { _, events, post -> FakeGattPort(events, post).also { ports += it } }
    )
    private val listener = object : SensorListener {
        override fun onSample(sample: Sample) {
            samples += sample
        }

        override fun onEvent(event: SensorEvent) {
            events += event
        }
    }

    private fun idle(millis: Long = 0) {
        val looper = shadowOf(Looper.getMainLooper())
        if (millis == 0L) looper.idle() else looper.idleFor(Duration.ofMillis(millis))
    }

    @Test
    fun `the warm-up connects the paired strap and its heart rate reaches the engine feed`() {
        ble.start(listener)
        ble.follow(RunPhase.PREPARING)
        idle()
        assertEquals(1, ports.size)
        assertEquals(BleConnectionState.STREAMING, ble.current.single().state)
        assertEquals(listOf(SensorEvent.Connected::class), events.map { it::class })
        assertEquals(STRAP.source, events.single().source)

        ports.single().notify(byteArrayOf(0x00, 0x8C.toByte()))
        idle()
        assertEquals(140, (samples.single() as HeartRateSample).heartRate.bpm)
    }

    @Test
    fun `running and paused keep the link and stop or reset disconnect it`() {
        ble.start(listener)
        ble.follow(RunPhase.PREPARING)
        idle()
        ble.follow(RunPhase.RUNNING)
        ble.follow(RunPhase.PAUSED)
        ble.follow(RunPhase.RUNNING)
        idle()
        assertEquals(1, ports.size)
        assertTrue(ports.single().closed.isEmpty())

        ble.follow(RunPhase.STOPPED)
        idle()
        assertEquals(listOf(1L), ports.single().closed)
        assertTrue(ble.current.isEmpty())

        // A new run reads the pairing again.
        paired = listOf(STRAP, FOOTPOD)
        ble.follow(RunPhase.PREPARING)
        idle()
        assertEquals(3, ports.size)
    }

    @Test
    fun `the tick drives the silence watchdog while the link is up`() {
        ble.start(listener)
        ble.follow(RunPhase.RUNNING)
        idle()
        val port = ports.single()
        assertEquals(1, port.requests.count { it.operation == GattOperation.SUBSCRIBE })
        idle(5_100)
        assertTrue(port.requests.count { it.operation == GattOperation.SUBSCRIBE } >= 2)
    }

    @Test
    fun `without the Bluetooth permission or a pairing nothing connects`() {
        allowed = false
        ble.start(listener)
        ble.follow(RunPhase.PREPARING)
        idle()
        assertTrue(ports.isEmpty())

        ble.follow(RunPhase.STOPPED)
        allowed = true
        paired = emptyList()
        ble.follow(RunPhase.PREPARING)
        idle()
        assertTrue(ports.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `close disconnects and stops ticking`() {
        ble.start(listener)
        ble.follow(RunPhase.RUNNING)
        idle()
        ble.close()
        idle(10_000)
        assertEquals(listOf(1L), ports.single().closed)
        assertEquals(1, ports.single().requests.count { it.operation == GattOperation.CONNECT })
    }

    private companion object {
        val STRAP = PairedSensor("AA:BB:CC:DD:EE:01", BleSensorKind.HEART_RATE, "Strap")
        val FOOTPOD = PairedSensor("AA:BB:CC:DD:EE:02", BleSensorKind.FOOTPOD)
    }
}
