package com.runpro.wear

import android.Manifest
import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.ble.BleSensorKind
import com.runpro.core.ble.PairedSensor
import com.runpro.core.model.HeartRate
import com.runpro.core.model.Metric
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.service.ServiceCommand
import com.runpro.wear.ble.FakeGattPort
import com.runpro.wear.ble.WatchBle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The service connects the paired strap for the run and feeds its heart rate to the engine (#17). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceBleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val ports = CopyOnWriteArrayList<FakeGattPort>()
    private var controller: ServiceController<RecordingService>? = null
    private lateinit var binder: RecordingService.LocalBinder

    @After
    fun destroy() {
        controller?.destroy()
    }

    private fun create(): RecordingService {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val controller = Robolectric.buildService(RecordingService::class.java)
        this.controller = controller
        val service = controller.get()
        service.createBle = { looper ->
            WatchBle(
                app,
                looper,
                clock = {
                    Timestamp(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
                },
                sensors = { listOf(STRAP) },
                allowed = { true },
                ports = { _, events, post -> FakeGattPort(events, post).also { ports += it } }
            )
        }
        controller.create()
        binder = service.onBind(null) as RecordingService.LocalBinder
        return service
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("$what: ${binder.state.value}", condition())
    }

    @Test
    fun `a run streams the strap's heart rate and a dropout never stops it`() {
        val service = create()
        assertTrue(ports.isEmpty())
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)
        await("running") { binder.state.value.phase == RunPhase.RUNNING }
        await("strap connected") { ports.size == 1 && ports.single().requests.size >= 3 }

        val strap = ports.single()
        await("strap heart rate") {
            strap.notify(byteArrayOf(0x00, 0x8C.toByte()))
            binder.state.value.current.heartRate == HeartRate(140)
        }
        assertEquals(STRAP.source, binder.state.value.activeSources[Metric.HEART_RATE])

        strap.drop(133)
        Thread.sleep(100)
        ShadowLooper.idleMainLooper()
        assertEquals(RunPhase.RUNNING, binder.state.value.phase)

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.STOP), 0, 2)
        await("stopped") { binder.state.value.phase == RunPhase.STOPPED }
        await("strap closed") { strap.closed.isNotEmpty() }
    }

    @Test
    fun `no run means no connection`() {
        create()
        Thread.sleep(100)
        assertTrue(ports.isEmpty())
    }

    private companion object {
        val STRAP = PairedSensor("AA:BB:CC:DD:EE:01", BleSensorKind.HEART_RATE, "Strap")
    }
}
