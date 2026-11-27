package com.runpro.wear

import android.Manifest
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.journal.ImuStream
import com.runpro.core.journal.JournalEnd
import com.runpro.core.journal.JournalReader
import com.runpro.core.model.ImuSensor
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.imu.ImuCaptureState
import com.runpro.core.recording.imu.ImuMode
import com.runpro.core.recording.imu.ImuProblem
import com.runpro.core.recording.service.ServiceCommand
import com.runpro.wear.imu.ImuPreferences
import com.runpro.wear.sync.WatchStorage
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
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSensor

/** The IMU capture follows the service's runs into `files/imu`, beside the journal (#33, ADR 0012). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceImuTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val sensors = shadowOf(app.getSystemService(SensorManager::class.java))
    private var controller: ServiceController<RecordingService>? = null

    @After
    fun destroy() {
        controller?.destroy()
    }

    private fun start(): RecordingService {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        val service = Robolectric.buildService(RecordingService::class.java).create()
            .also { controller = it }
            .get()
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)
        return service
    }

    private fun RecordingService.binder() = onBind(null) as RecordingService.LocalBinder

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue(what, condition())
    }

    private fun RecordingService.command(command: ServiceCommand, phase: RunPhase) {
        onStartCommand(RecordingService.intent(app, command), 0, 2)
        await("phase $phase") { binder().state.value.phase == phase }
    }

    @Test
    fun `a run records the accelerometer into its IMU stream`() {
        val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        sensors.addSensor(accelerometer)
        ImuPreferences.save(app, ImuMode.ACCELEROMETER)
        val service = start()
        val binder = service.binder()
        await("capturing") { binder.imu.value.state == ImuCaptureState.CAPTURING }
        val runId = binder.state.value.run!!.id

        val first = SystemClock.elapsedRealtimeNanos()
        repeat(120) { i ->
            val event = SensorEventBuilder.newBuilder()
                .setSensor(accelerometer)
                .setTimestamp(first + i * 20_000_000L)
                .setValues(floatArrayOf(0.5f, -9.75f, i / 8f))
                .build()
            sensors.sendSensorEventToListeners(event)
        }
        service.command(ServiceCommand.STOP, RunPhase.STOPPED)
        await("closed") { binder.imu.value.state == ImuCaptureState.IDLE }

        val stream = ImuStream.readRun(WatchStorage.imu(app).toPath(), runId).single()
        assertEquals(JournalEnd.Clean, stream.end)
        assertEquals(120, stream.readingCount)
        assertEquals(setOf(ImuSensor.ACCELEROMETER), stream.batches.map { it.sensor }.toSet())
        assertEquals(mapOf(ImuSensor.ACCELEROMETER to 120L), binder.imu.value.readings)
        // The journal is where it always was, and has no IMU in it.
        val journal = WatchStorage.journals(app).resolve("$runId.journal").toPath()
        assertTrue(JournalReader.read(journal).isStopped)
    }

    @Test
    fun `a watch without motion sensors records the run as usual`() {
        ImuPreferences.save(app, ImuMode.ACCELEROMETER_AND_GYROSCOPE)
        val service = start()
        val binder = service.binder()
        await("no sensors") { binder.imu.value.problem == ImuProblem.NO_SENSORS }
        service.command(ServiceCommand.STOP, RunPhase.STOPPED)
        assertTrue(binder.state.value.journalSaved)
    }

    @Test
    fun `IMU off touches no sensor`() {
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        ImuPreferences.save(app, ImuMode.OFF)
        val service = start()
        val binder = service.binder()
        await("running") { binder.state.value.phase == RunPhase.RUNNING }
        await("off") { binder.imu.value.state == ImuCaptureState.OFF }
        assertTrue(sensors.listeners.isEmpty())
        service.command(ServiceCommand.STOP, RunPhase.STOPPED)
        assertTrue(binder.state.value.journalSaved)
    }
}
