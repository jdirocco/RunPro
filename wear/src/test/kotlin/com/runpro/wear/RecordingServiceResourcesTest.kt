package com.runpro.wear

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.journal.FileJournalOutput
import com.runpro.core.journal.JournalEnd
import com.runpro.core.journal.JournalOutput
import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.RunEvent
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.exercise.ExerciseCapabilities
import com.runpro.core.recording.exercise.ExerciseController
import com.runpro.core.recording.exercise.ExerciseData
import com.runpro.core.recording.exercise.ExerciseInspection
import com.runpro.core.recording.exercise.ExerciseOwnership
import com.runpro.core.recording.exercise.ExercisePlan
import com.runpro.core.recording.exercise.ExercisePort
import com.runpro.core.recording.exercise.ExercisePortListener
import com.runpro.core.recording.service.RunPermission
import com.runpro.core.recording.service.ServiceCommand
import com.runpro.wear.sync.WatchStorage
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceResourcesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var controller: ServiceController<RecordingService>? = null
    private lateinit var service: RecordingService
    private lateinit var binder: RecordingService.LocalBinder
    private val space = AtomicLong(100_000_000)
    private val spaceReads = AtomicInteger()
    private val ends = AtomicInteger()
    private var endObservedSaved = false
    private var endObservedForeground = false

    @After
    fun destroy() {
        controller?.destroy()
    }

    private fun create(configure: RecordingService.() -> Unit = {}) {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val controller = Robolectric.buildService(RecordingService::class.java)
        this.controller = controller
        service = controller.get()
        service.readAvailableBytes = {
            check(Looper.myLooper() != Looper.getMainLooper())
            spaceReads.incrementAndGet()
            space.get()
        }
        service.createExercise = {
            ExerciseController(Port(), {
                setOf(RunPermission.ACTIVITY_RECOGNITION)
            }, { Timestamp(0, 0) })
        }
        service.configure()
        controller.create()
        binder = service.onBind(null) as RecordingService.LocalBinder
    }

    private fun command(command: ServiceCommand) {
        service.onStartCommand(RecordingService.intent(app, command), 0, 1)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("Timed out: ${binder.state.value}", condition())
    }

    private fun phase(phase: RunPhase) = await { binder.state.value.phase == phase }

    private fun sendBattery(percent: Int) {
        app.sendBroadcast(battery(percent))
        ShadowLooper.idleMainLooper()
        await { binder.state.value.resources.batteryPercent == percent }
    }

    @Test
    fun `initial sticky critical battery rejects start and charging permits a retry`() {
        app.sendStickyBroadcast(battery(3))
        create()
        command(ServiceCommand.START)
        await { binder.state.value.resources.batteryPercent == 3 }
        phase(RunPhase.PREPARING)
        assertNull(binder.state.value.run)
        assertFalse(binder.state.value.journalFailed)
        assertFalse(WatchStorage.journals(app).exists())

        sendBattery(16)
        command(ServiceCommand.START)
        phase(RunPhase.RUNNING)
        assertFalse(binder.state.value.resources.lowBattery)
    }

    @Test
    fun `battery warnings and automatic saves work while running and paused`() {
        create()
        repeat(2) { index ->
            sendBattery(80)
            command(ServiceCommand.START)
            phase(RunPhase.RUNNING)
            if (index == 1) {
                command(ServiceCommand.PAUSE)
                phase(RunPhase.PAUSED)
            }
            sendBattery(15)
            assertTrue(binder.state.value.resources.lowBattery)
            val expectedPhase = if (index == 0) RunPhase.RUNNING else RunPhase.PAUSED
            assertEquals(expectedPhase, binder.state.value.phase)
            sendBattery(3)
            assertSavedStop(StopReason.LOW_BATTERY, index + 1)
            command(ServiceCommand.START)
            phase(RunPhase.PREPARING)
            assertEquals(3, binder.state.value.resources.batteryPercent)
            assertNull(binder.state.value.run)
        }
    }

    @Test
    fun `one second storage refresh continues while paused and saves at zero space`() {
        create()
        command(ServiceCommand.START)
        phase(RunPhase.RUNNING)
        command(ServiceCommand.PAUSE)
        phase(RunPhase.PAUSED)
        val reads = spaceReads.get()
        space.set(49_999_999)
        await { binder.state.value.resources.lowStorage }
        assertEquals(RunPhase.PAUSED, binder.state.value.phase)
        assertTrue(spaceReads.get() > reads)
        space.set(0)
        assertSavedStop(StopReason.STORAGE_FULL, 1)
    }

    private fun assertSavedStop(reason: StopReason, count: Int) {
        phase(RunPhase.STOPPED)
        await { ends.get() == count && shadowOf(service).isForegroundStopped }
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(endObservedSaved)
        assertTrue(endObservedForeground)
        assertTrue(binder.state.value.journalSaved)
        assertFalse(binder.state.value.journalFailed)
        val file = WatchStorage.journals(app).resolve("${binder.state.value.run!!.id}.journal")
        val contents = JournalReader.read(file.toPath())
        assertEquals(JournalEnd.Clean, contents.end)
        assertEquals(reason, (contents.runEvents.last() as RunEvent.Stopped).reason)
        assertEquals(1, contents.runEvents.filterIsInstance<RunEvent.Stopped>().size)
        assertFalse(file.resolveSibling("${file.name}.reserve").exists())
    }

    @Test
    fun `journal creation failures use the existing error flow and do not start`() {
        WatchStorage.journals(app).writeText("Blocked journal directory")
        create()
        command(ServiceCommand.START)
        await { binder.failure.value != null }
        assertTrue(binder.state.value.journalFailed)
        assertEquals(RunPhase.PREPARING, binder.state.value.phase)
        assertFalse(binder.state.value.journalSaved)
        assertNull(binder.state.value.run)
    }

    @Test
    fun `unrecoverable stop write failure is exposed and never reported as saved`() {
        val failWrites = AtomicBoolean(false)
        create {
            val file = filesDir.resolve("failed.journal")
            createSession = {
                val output = FileJournalOutput.create(file.toPath())
                RecordingSession(
                    journal = JournalWriter(object : JournalOutput by output {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            if (failWrites.get()) throw IOException("Unrecoverable storage failure")
                            output.write(bytes, offset, length)
                        }
                    })
                )
            }
        }
        command(ServiceCommand.START)
        phase(RunPhase.RUNNING)
        failWrites.set(true)
        sendBattery(3)
        phase(RunPhase.STOPPED)
        await { shadowOf(service).isStoppedBySelf }
        assertNotNull(binder.failure.value)
        assertTrue(binder.state.value.journalFailed)
        assertFalse(binder.state.value.journalSaved)
        assertFalse(JournalReader.read(app.filesDir.resolve("failed.journal").toPath()).isStopped)
    }

    /** Immediate completions let END inspect the state before the main-thread service shutdown. */
    private inner class Port : ExercisePort {
        override fun inspect(done: (Result<ExerciseInspection>) -> Unit) = done(
            Result.success(
                ExerciseInspection(
                    ExerciseCapabilities(true, setOf(ExerciseData.DISTANCE)),
                    ExerciseOwnership.NONE
                )
            )
        )
        override fun setListener(listener: ExercisePortListener) = Unit
        override fun clearListener() = Unit
        override fun prepare(plan: ExercisePlan, done: (Result<Unit>) -> Unit) =
            done(Result.success(Unit))
        override fun start(plan: ExercisePlan, done: (Result<Unit>) -> Unit) =
            done(Result.success(Unit))
        override fun pause(done: (Result<Unit>) -> Unit) = done(Result.success(Unit))
        override fun resume(done: (Result<Unit>) -> Unit) = done(Result.success(Unit))
        override fun end(done: (Result<Unit>) -> Unit) {
            endObservedSaved = binder.state.value.journalSaved
            endObservedForeground = !shadowOf(service).isForegroundStopped
            ends.incrementAndGet()
            done(Result.success(Unit))
        }
    }
}
