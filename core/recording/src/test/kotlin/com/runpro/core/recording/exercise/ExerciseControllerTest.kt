package com.runpro.core.recording.exercise

import com.runpro.core.model.DistanceSample
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.exercise.ExerciseReading.HeartRateQuality
import com.runpro.core.recording.service.RunPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExerciseControllerTest {
    private val port = FakeExercisePort()
    private var now = Timestamp(SECOND * 100, 1_000_000)
    private val controller = ExerciseController(
        port = port,
        grantedPermissions = { RunPermission.entries.toSet() },
        clock = { now }
    )
    private val received = RecordingListener()

    @Test
    fun `prepare checks capabilities and ownership, then warms up GPS and heart rate`() {
        controller.follow(RunPhase.PREPARING)

        assertEquals(listOf("inspect", "setListener", "prepare"), port.calls)
        assertEquals(ExerciseData.entries.toSet(), port.preparedPlan?.dataTypes)
        val warming = controller.status.value
        assertEquals(ExercisePhase.WARMING_UP, warming.phase)
        assertEquals(Readiness.WAITING, warming.gps)
        assertEquals(Readiness.WAITING, warming.heartRate)

        port.listener.onAvailability(ExerciseData.LOCATION, DataAvailability.ACQUIRING)
        port.listener.onAvailability(ExerciseData.HEART_RATE, DataAvailability.AVAILABLE)
        assertEquals(Readiness.WAITING, controller.status.value.gps)
        assertEquals(Readiness.READY, controller.status.value.heartRate)

        port.listener.onAvailability(ExerciseData.LOCATION, DataAvailability.AVAILABLE)
        assertEquals(Readiness.READY, controller.status.value.gps)
        assertTrue(controller.status.value.warmedUp)
    }

    @Test
    fun `a sensor without permission is not warmed up and is reported missing`() {
        val noLocation = ExerciseController(
            port = port,
            grantedPermissions = { RunPermission.entries.toSet() - RunPermission.LOCATION },
            clock = { now }
        )

        noLocation.follow(RunPhase.PREPARING)

        assertEquals(Readiness.NOT_REQUESTED, noLocation.status.value.gps)
        assertEquals(
            setOf(ExerciseData.LOCATION, ExerciseData.ELEVATION),
            noLocation.status.value.missing
        )
        assertFalse(port.preparedPlan!!.gpsEnabled)
    }

    @Test
    fun `start, pause, resume and end reach Health Services in order`() {
        controller.start(received)
        controller.follow(RunPhase.PREPARING)
        controller.follow(RunPhase.RUNNING)
        assertEquals(ExercisePhase.ACTIVE, controller.status.value.phase)
        controller.follow(RunPhase.PAUSED)
        assertEquals(ExercisePhase.PAUSED, controller.status.value.phase)
        controller.follow(RunPhase.RUNNING)
        controller.follow(RunPhase.STOPPED)

        assertEquals(
            listOf(
                "inspect",
                "setListener",
                "prepare",
                "start",
                "pause",
                "resume",
                "end",
                "clearListener"
            ),
            port.calls
        )
        assertEquals(ExercisePhase.ENDED, controller.status.value.phase)
        assertEquals(ExerciseEndReason.USER, controller.status.value.endReason)
    }

    @Test
    fun `commands given while a request is in flight wait for it`() {
        port.autoComplete = false

        // RecordingService prepares and starts in the same instant.
        controller.follow(RunPhase.PREPARING)
        controller.follow(RunPhase.RUNNING)
        controller.follow(RunPhase.PAUSED)
        assertEquals(listOf("inspect"), port.calls)

        // The start is already wanted when the inspection completes, so the warm-up is skipped.
        port.completeNext()
        assertEquals(listOf("inspect", "setListener", "start"), port.calls)
        assertEquals(ExercisePhase.STARTING, controller.status.value.phase)
        port.completeNext()
        assertEquals(listOf("inspect", "setListener", "start", "pause"), port.calls)
        port.completeNext()
        assertEquals(ExercisePhase.PAUSED, controller.status.value.phase)
        assertTrue(port.pending.isEmpty())
    }

    @Test
    fun `a start without warm-up skips prepare`() {
        controller.follow(RunPhase.RUNNING)

        assertEquals(listOf("inspect", "setListener", "start"), port.calls)
    }

    @Test
    fun `updates become samples from the Health Services source`() {
        controller.start(received)
        controller.follow(RunPhase.RUNNING)

        port.listener.onReadings(
            listOf(
                ExerciseReading.HeartRate(SECOND * 99, 150.0, HeartRateQuality.HIGH),
                ExerciseReading.DistanceDelta(SECOND * 95, SECOND * 99, 11.0)
            )
        )

        assertEquals(2, received.samples.size)
        assertTrue(received.samples.all { it.source == SourceId.HEALTH_SERVICES })
        assertIs<HeartRateSample>(received.samples[0])
        assertEquals(11.0, (received.samples[1] as DistanceSample).total.meters)
        assertEquals(Readiness.READY, controller.status.value.heartRate)
    }

    @Test
    fun `GPS fix changes become sensor events`() {
        controller.start(received)
        controller.follow(RunPhase.RUNNING)

        port.listener.onAvailability(ExerciseData.LOCATION, DataAvailability.ACQUIRING)
        now = Timestamp(SECOND * 101, 1_001_000)
        port.listener.onAvailability(ExerciseData.LOCATION, DataAvailability.AVAILABLE)
        port.listener.onAvailability(ExerciseData.LOCATION, DataAvailability.AVAILABLE)
        now = Timestamp(SECOND * 200, 1_100_000)
        port.listener.onAvailability(ExerciseData.LOCATION, DataAvailability.ACQUIRING)

        assertEquals(
            listOf(
                SensorEvent.GpsAcquired(
                    Timestamp(SECOND * 101, 1_001_000),
                    SourceId.HEALTH_SERVICES
                ),
                SensorEvent.GpsLost(Timestamp(SECOND * 200, 1_100_000), SourceId.HEALTH_SERVICES)
            ),
            received.events
        )
    }

    @Test
    fun `another app owning the exercise is reported during warm-up and taken over at start`() {
        port.ownership = ExerciseOwnership.OTHER_APP

        controller.follow(RunPhase.PREPARING)
        assertEquals(listOf("inspect", "setListener"), port.calls)
        assertEquals(ExercisePhase.OTHER_APP_ACTIVE, controller.status.value.phase)
        assertTrue(controller.status.value.otherAppActive)

        // The runner pressed start in RunPro: RunPro records, the other app's exercise ends.
        controller.follow(RunPhase.RUNNING)
        assertEquals(listOf("inspect", "setListener", "start"), port.calls)
        assertEquals(ExercisePhase.ACTIVE, controller.status.value.phase)
        assertTrue(controller.status.value.tookOverFromOtherApp)
        assertFalse(controller.status.value.otherAppActive)
    }

    @Test
    fun `another app taking over the exercise is reported and the run keeps going without it`() {
        controller.start(received)
        controller.follow(RunPhase.RUNNING)
        port.listener.onAvailability(ExerciseData.LOCATION, DataAvailability.AVAILABLE)

        port.listener.onState(PlatformExerciseState.ENDED, ExerciseEndReason.SUPERSEDED)

        val status = controller.status.value
        assertEquals(ExercisePhase.ENDED, status.phase)
        assertEquals(ExerciseEndReason.SUPERSEDED, status.endReason)
        assertTrue(status.otherAppActive)
        assertIs<SensorEvent.GpsLost>(received.events.last())
        // R4: the adapter never tries to pause or stop the run; it also does not fight the other app.
        controller.follow(RunPhase.PAUSED)
        controller.follow(RunPhase.RUNNING)
        assertEquals(listOf("inspect", "setListener", "start"), port.calls)
        controller.follow(RunPhase.STOPPED)
        assertEquals(listOf("inspect", "setListener", "start", "clearListener"), port.calls)
    }

    @Test
    fun `after a restart our own running exercise is adopted, not started again`() {
        port.ownership = ExerciseOwnership.OURS

        controller.start(received)
        controller.follow(RunPhase.RUNNING)

        assertEquals(listOf("inspect", "setListener"), port.calls)
        assertEquals(ExercisePhase.ACTIVE, controller.status.value.phase)

        // The exercise was paused when the process died, but the restored run is recording.
        port.listener.onState(PlatformExerciseState.PAUSED, null)
        assertEquals(listOf("inspect", "setListener", "resume"), port.calls)
        port.listener.onState(PlatformExerciseState.PAUSED, null)
        assertEquals(listOf("inspect", "setListener", "resume"), port.calls)
    }

    @Test
    fun `Health Services without running support makes the adapter unavailable`() {
        port.capabilities = ExerciseCapabilities(runningSupported = false, supported = emptySet())

        controller.follow(RunPhase.PREPARING)
        controller.follow(RunPhase.RUNNING)

        assertEquals(listOf("inspect"), port.calls)
        assertEquals(ExercisePhase.UNAVAILABLE, controller.status.value.phase)
        controller.follow(RunPhase.STOPPED)
        assertEquals(ExercisePhase.ENDED, controller.status.value.phase)
    }

    @Test
    fun `a failed inspection is reported`() {
        port.failNext = IllegalStateException("Health Services not installed")

        controller.follow(RunPhase.RUNNING)

        assertEquals(ExercisePhase.UNAVAILABLE, controller.status.value.phase)
        assertEquals("Health Services not installed", controller.status.value.error)
    }

    @Test
    fun `a failed start is reported and not retried`() {
        controller.follow(RunPhase.PREPARING)
        port.failNext = SecurityException("no permission")

        controller.follow(RunPhase.RUNNING)
        controller.follow(RunPhase.PAUSED)

        assertEquals(ExercisePhase.FAILED, controller.status.value.phase)
        assertEquals("no permission", controller.status.value.error)
        assertEquals(listOf("inspect", "setListener", "prepare", "start"), port.calls)
        controller.follow(RunPhase.STOPPED)
        assertEquals(
            listOf("inspect", "setListener", "prepare", "start", "end", "clearListener"),
            port.calls
        )
    }

    @Test
    fun `a failed registration is reported`() {
        controller.follow(RunPhase.RUNNING)

        port.listener.onRegistrationFailed(IllegalStateException("binder died"))

        assertEquals("binder died", controller.status.value.error)
        assertEquals(ExercisePhase.ACTIVE, controller.status.value.phase)
    }

    @Test
    fun `ending before the inspection completes starts nothing`() {
        port.autoComplete = false
        controller.follow(RunPhase.PREPARING)
        controller.follow(RunPhase.RUNNING)
        controller.follow(RunPhase.STOPPED)

        port.completeNext()

        assertEquals(listOf("inspect"), port.calls)
        assertEquals(ExercisePhase.ENDED, controller.status.value.phase)
    }

    @Test
    fun `a new exercise can be prepared after the last one ended`() {
        controller.start(received)
        controller.follow(RunPhase.RUNNING)
        port.listener.onReadings(listOf(ExerciseReading.DistanceDelta(0, SECOND, 100.0)))
        controller.follow(RunPhase.STOPPED)
        port.calls.clear()

        controller.follow(RunPhase.RUNNING)
        port.listener.onReadings(listOf(ExerciseReading.DistanceDelta(SECOND, SECOND * 2, 5.0)))

        assertEquals(listOf("inspect", "setListener", "start"), port.calls)
        assertEquals(5.0, (received.samples.last() as DistanceSample).total.meters)
    }

    @Test
    fun `the source delivers only between start and stop`() {
        controller.follow(RunPhase.RUNNING)
        controller.start(received)
        assertFailsWith<IllegalStateException> { controller.start(received) }

        controller.stop()
        controller.stop()
        port.listener.onReadings(listOf(ExerciseReading.Speed(SECOND, 3.0)))

        assertTrue(received.samples.isEmpty())
        assertEquals(SourceId.HEALTH_SERVICES, controller.id)
    }

    @Test
    fun `the status names the platform end reason`() {
        controller.follow(RunPhase.RUNNING)

        port.listener.onState(PlatformExerciseState.ENDED, ExerciseEndReason.PERMISSION_LOST)

        assertEquals(ExerciseEndReason.PERMISSION_LOST, controller.status.value.endReason)
        assertFalse(controller.status.value.otherAppActive)
        assertNotNull(controller.status.value.endReason)
    }

    @Test
    fun `the exercise follows the phases of the run`() {
        controller.follow(RunPhase.PREPARING)
        controller.follow(RunPhase.RUNNING)
        controller.follow(RunPhase.PAUSED)
        controller.follow(RunPhase.RUNNING)
        controller.follow(RunPhase.STOPPED)
        controller.follow(RunPhase.IDLE)

        assertEquals(
            listOf(
                "inspect",
                "setListener",
                "prepare",
                "start",
                "pause",
                "resume",
                "end",
                "clearListener"
            ),
            port.calls
        )
    }

    @Test
    fun `a run restored paused starts or adopts the exercise and pauses it`() {
        controller.follow(RunPhase.PAUSED)

        assertEquals(listOf("inspect", "setListener", "start", "pause"), port.calls)
        assertEquals(ExercisePhase.PAUSED, controller.status.value.phase)
    }

    @Test
    fun `a cancelled warm-up ends the prepared exercise`() {
        controller.follow(RunPhase.PREPARING)
        controller.follow(RunPhase.IDLE)

        assertEquals(
            listOf("inspect", "setListener", "prepare", "end", "clearListener"),
            port.calls
        )
    }

    private class RecordingListener : SensorListener {
        val samples = mutableListOf<Sample>()
        val events = mutableListOf<SensorEvent>()

        override fun onSample(sample: Sample) {
            samples += sample
        }

        override fun onEvent(event: SensorEvent) {
            events += event
        }
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}

/** An [ExercisePort] that records calls and completes them at once or when told to. */
internal class FakeExercisePort : ExercisePort {
    val calls = mutableListOf<String>()
    val pending = ArrayDeque<() -> Unit>()
    var autoComplete = true
    var failNext: Throwable? = null
    var ownership = ExerciseOwnership.NONE
    var capabilities =
        ExerciseCapabilities(runningSupported = true, supported = ExerciseData.entries.toSet())
    var preparedPlan: ExercisePlan? = null
    private var registered: ExercisePortListener? = null
    val listener: ExercisePortListener get() = checkNotNull(registered) { "no listener registered" }

    fun completeNext() {
        pending.removeFirst().invoke()
    }

    override fun inspect(done: (Result<ExerciseInspection>) -> Unit) {
        calls += "inspect"
        complete(done) { ExerciseInspection(capabilities, ownership) }
    }

    override fun setListener(listener: ExercisePortListener) {
        calls += "setListener"
        registered = listener
    }

    override fun clearListener() {
        calls += "clearListener"
    }

    override fun prepare(plan: ExercisePlan, done: (Result<Unit>) -> Unit) {
        calls += "prepare"
        preparedPlan = plan
        complete(done) { }
    }

    override fun start(plan: ExercisePlan, done: (Result<Unit>) -> Unit) {
        calls += "start"
        complete(done) { }
    }

    override fun pause(done: (Result<Unit>) -> Unit) {
        calls += "pause"
        complete(done) { }
    }

    override fun resume(done: (Result<Unit>) -> Unit) {
        calls += "resume"
        complete(done) { }
    }

    override fun end(done: (Result<Unit>) -> Unit) {
        calls += "end"
        complete(done) { }
    }

    private fun <T> complete(done: (Result<T>) -> Unit, value: () -> T) {
        val failure = failNext
        failNext = null
        val result = if (failure != null) Result.failure(failure) else Result.success(value())
        if (autoComplete) done(result) else pending.addLast { done(result) }
    }
}
