package com.runpro.wear

import android.Manifest
import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.model.Metric
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.exercise.Readiness
import com.runpro.core.recording.location.FusedLocationController
import com.runpro.core.recording.location.LocationFix
import com.runpro.core.recording.location.LocationPort
import com.runpro.core.recording.location.LocationReceiver
import com.runpro.core.recording.service.ServiceCommand
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * The service requests fused location for the run and feeds its gated fixes to the engine, where they are
 * the location source while Health Services has none (#96, ADR 0019).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceLocationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val port = FakePort()
    private var controller: ServiceController<RecordingService>? = null
    private lateinit var binder: RecordingService.LocalBinder

    @After
    fun destroy() {
        controller?.destroy()
    }

    private fun create(locationAllowed: Boolean = true): RecordingService {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val controller = Robolectric.buildService(RecordingService::class.java)
        this.controller = controller
        val service = controller.get()
        service.createLocation = { _ -> FusedLocationController(port, { locationAllowed }, ::now) }
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
    fun `gated fixes become the run's location and distance, coarse ones never do`() {
        val service = create()
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.PREPARE), 0, 1)
        await("warming up") { binder.state.value.phase == RunPhase.PREPARING }
        await("location requested") { port.receivers.size == 1 }
        assertEquals(Readiness.WAITING, binder.location.value.readiness)

        val receiver = port.receivers.single()
        receiver.fix(fix(now(), 0.0, accuracy = 6.0))
        assertEquals(Readiness.READY, binder.location.value.readiness)

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 2)
        await("running") { binder.state.value.phase == RunPhase.RUNNING }
        var north = 0.0
        await("fused location active") {
            // One simulated second per fix: the gate admits only newer fixes.
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            north += 3.0
            receiver.fix(fix(now(), north, accuracy = 6.0, speed = 3.0))
            // A Wi-Fi fix 150 m away between the precise ones.
            receiver.fix(fix(now(), north + 150.0, accuracy = 80.0))
            binder.state.value.activeSources[Metric.LOCATION] == SourceId.FUSED_LOCATION &&
                binder.state.value.totals.distance.meters > 0.0
        }
        assertTrue(binder.location.value.rejected.isNotEmpty())
        val meters = binder.state.value.totals.distance.meters
        assertTrue("distance $meters, at most $north", meters <= north)

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.STOP), 0, 3)
        await("stopped") { binder.state.value.phase == RunPhase.STOPPED }
        await("updates removed") { port.removals.get() == 1 }
        assertEquals(Readiness.NOT_REQUESTED, binder.location.value.readiness)
    }

    @Test
    fun `without the location permission the run records without fused location`() {
        val service = create(locationAllowed = false)
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)
        await("running") { binder.state.value.phase == RunPhase.RUNNING }
        Thread.sleep(100)
        assertTrue(port.receivers.isEmpty())
        assertEquals(Readiness.NOT_REQUESTED, binder.location.value.readiness)
    }

    private fun fix(time: Timestamp, north: Double, accuracy: Double, speed: Double = Double.NaN) =
        LocationFix(
            elapsedNanos = time.elapsedRealtimeNanos,
            latitude = 45.47 + north / 111_195.0,
            longitude = 9.18,
            horizontalAccuracyMeters = accuracy,
            speedMetersPerSecond = speed
        )

    private class FakePort : LocationPort {
        val receivers = CopyOnWriteArrayList<LocationReceiver>()
        val removals = AtomicInteger()

        override fun request(receiver: LocationReceiver) {
            receivers += receiver
        }

        override fun remove() {
            removals.incrementAndGet()
        }
    }

    private companion object {
        fun now() = Timestamp(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
    }
}
