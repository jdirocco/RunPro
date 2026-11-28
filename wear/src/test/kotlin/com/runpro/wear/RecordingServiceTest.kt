package com.runpro.wear

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.journal.JournalReader
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunSettings
import com.runpro.core.model.UserProfile
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.service.RunRecovery
import com.runpro.core.recording.service.ServiceCommand
import com.runpro.core.recording.service.WarmUpTimeout
import com.runpro.wear.sync.WatchStorage
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    private fun grantAll() = grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val services = mutableListOf<ServiceController<RecordingService>>()

    private fun service() = Robolectric.buildService(RecordingService::class.java).create().also {
        services +=
            it
    }

    @After
    fun destroyServices() {
        services.forEach { it.destroy() }
    }

    /** Waits for the engine thread, then runs what it posted to the main thread. */
    private fun RecordingService.awaitPhase(phase: RunPhase) {
        val binder = onBind(null) as RecordingService.LocalBinder
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (binder.state.value.phase != phase && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(phase, binder.state.value.phase)
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `saved settings apply to the next run and never mutate the active snapshot`() {
        grantAll()
        val selected =
            WorkoutSettings(runSettings = RunSettings(profile = UserProfile(190, 50, 72.5)))
        WorkoutPreferences.save(app, selected)
        val service = service().get()
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)
        service.awaitPhase(RunPhase.RUNNING)
        val binder = service.onBind(null) as RecordingService.LocalBinder
        assertEquals(selected, binder.state.value.workoutSettings)
        WorkoutPreferences.save(app, WorkoutSettings())
        assertEquals(selected, binder.state.value.workoutSettings)
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.STOP), 0, 2)
        service.awaitPhase(RunPhase.STOPPED)
        val file = WatchStorage.journals(app).resolve("${binder.state.value.run!!.id}.journal")
        val start = JournalReader.read(file.toPath()).records.first() as
            com.runpro.core.journal.JournalRecord.RunStarted
        assertEquals(selected, start.workout)
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 3)
        service.awaitPhase(RunPhase.RUNNING)
        assertEquals(WorkoutSettings(), binder.state.value.workoutSettings)
    }

    @Test
    fun `start enters the foreground with every granted type and records`() {
        grantAll()
        val service = service().get()

        val result = service.onStartCommand(
            RecordingService.intent(app, ServiceCommand.START),
            0,
            1
        )

        assertEquals(Service.START_STICKY, result)
        val shadow = shadowOf(service)
        assertNotNull(shadow.lastForegroundNotification)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            service.foregroundServiceType
        )
        service.awaitPhase(RunPhase.RUNNING)
        assertFalse(shadow.isStoppedBySelf)
    }

    @Test
    fun `a missing permission drops its type instead of crashing the service`() {
        grant(Manifest.permission.ACTIVITY_RECOGNITION)
        val service = service().get()

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH, service.foregroundServiceType)
        service.awaitPhase(RunPhase.RUNNING)
    }

    @Test
    fun `without any permission the run does not start and the service stops`() {
        val service = service().get()

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)

        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(WatchStorage.journals(app).exists())
    }

    @Test
    fun `stop ends the run and leaves the foreground`() {
        grantAll()
        val service = service().get()
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)
        service.awaitPhase(RunPhase.RUNNING)

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.STOP), 0, 2)

        service.awaitPhase(RunPhase.STOPPED)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `prepare warms up in the foreground and start begins the prepared run`() {
        grantAll()
        val service = service().get()

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.PREPARE), 0, 1)

        service.awaitPhase(RunPhase.PREPARING)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isStoppedBySelf)

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 2)

        service.awaitPhase(RunPhase.RUNNING)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `cancel during the warm-up leaves the foreground without a run`() {
        grantAll()
        val service = service().get()
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.PREPARE), 0, 1)
        service.awaitPhase(RunPhase.PREPARING)

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.CANCEL), 0, 2)

        service.awaitPhase(RunPhase.IDLE)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        val binder = service.onBind(null) as RecordingService.LocalBinder
        assertNull(binder.state.value.run)
    }

    @Test
    fun `a warm-up left alone ends on its own and leaves the foreground`() {
        grantAll()
        val service = service().get()
        service.warmUpTimeout = WarmUpTimeout(limit = 1.seconds)
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.PREPARE), 0, 1)
        service.awaitPhase(RunPhase.PREPARING)

        // Robolectric's boot clock stands still: move it on while the real ticker runs once a second.
        val binder = service.onBind(null) as RecordingService.LocalBinder
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (binder.state.value.phase == RunPhase.PREPARING && System.nanoTime() < deadline) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(100))
            Thread.sleep(50)
        }
        service.awaitPhase(RunPhase.IDLE)

        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(binder.state.value.run)
        assertFalse(WatchStorage.journals(app).exists())
    }

    @Test
    fun `the warm-up timeout never ends a recording run`() {
        grantAll()
        val service = service().get()
        service.warmUpTimeout = WarmUpTimeout(limit = 1.seconds)
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)
        service.awaitPhase(RunPhase.RUNNING)

        repeat(25) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(200))
            Thread.sleep(100)
        }

        service.awaitPhase(RunPhase.RUNNING)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `cancel during a run keeps recording`() {
        grantAll()
        val service = service().get()
        service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)
        service.awaitPhase(RunPhase.RUNNING)

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.CANCEL), 0, 2)

        service.awaitPhase(RunPhase.RUNNING)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `service writes a closed stopped journal to the phone outbox for each run`() {
        grantAll()
        val service = service().get()
        assertFalse(WatchStorage.journals(app).exists())
        repeat(2) { index ->
            service.onStartCommand(RecordingService.intent(app, ServiceCommand.START), 0, 1)
            service.awaitPhase(RunPhase.RUNNING)
            val binder = service.onBind(null) as RecordingService.LocalBinder
            val id = binder.state.value.run!!.id
            val file = WatchStorage.journals(app).resolve("$id.journal")
            assertEquals(id, JournalReader.read(file.toPath()).runId)
            service.onStartCommand(RecordingService.intent(app, ServiceCommand.PAUSE), 0, 2)
            service.awaitPhase(RunPhase.PAUSED)
            service.onStartCommand(RecordingService.intent(app, ServiceCommand.RESUME), 0, 3)
            service.awaitPhase(RunPhase.RUNNING)
            service.onStartCommand(RecordingService.intent(app, ServiceCommand.STOP), 0, 4)
            service.awaitPhase(RunPhase.STOPPED)
            assertTrue(binder.state.value.journalSaved)
            assertFalse(binder.state.value.journalFailed)
            assertNull(binder.failure.value)
            val journal = JournalReader.read(file.toPath())
            assertEquals(binder.state.value.run!!.events, journal.runEvents)
            assertEquals(4, journal.runEvents.size)
            assertTrue(journal.runEvents.last() is RunEvent.Stopped)
            assertFalse(file.resolveSibling("${file.name}.reserve").exists())
            assertEquals(
                index + 1,
                WatchStorage.journals(app).listFiles()!!.count {
                    it.extension ==
                        "journal"
                }
            )
        }
    }

    @Test
    fun `a sticky restart asks the recovery and keeps the foreground when the run is restored`() {
        grantAll()
        val service = service().get()
        var asked = false
        service.useRecovery(RunRecovery { _, _ -> true.also { asked = true } })

        val result = service.onStartCommand(null, Service.START_FLAG_RETRY, 1)

        assertEquals(Service.START_STICKY, result)
        assertTrue(asked)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a sticky restart from the background drops location without background location`() {
        grantAll()
        val service = service().get()
        service.useRecovery(RunRecovery { _, _ -> true })

        service.onStartCommand(null, 0, 1)

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            service.foregroundServiceType
        )
    }

    @Test
    fun `a sticky restart with nothing to restore stops the service`() {
        grantAll()
        val service = service().get()

        service.onStartCommand(null, 0, 1)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `pause without a run does not leave a notification behind`() {
        grantAll()
        val service = service().get()

        service.onStartCommand(RecordingService.intent(app, ServiceCommand.PAUSE), 0, 1)

        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
