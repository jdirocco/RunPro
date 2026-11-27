package com.runpro.core.recording

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.IntervalTarget
import com.runpro.core.model.IntervalWorkout
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.RunEvent
import com.runpro.core.model.WorkoutSettings
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class WorkoutTest {
    @Test
    fun `default kilometer laps use counted distance and do not repeat on ticks`() {
        val machine = runningMachine()
        machine.feed(dist(0, 50.0), dist(10, 1049.0), tick(11))
        assertEquals(1, machine.state.laps.size)
        machine.feed(dist(12, 1051.0), tick(13), dist(20, 2052.0))
        assertEquals(
            listOf(LapTrigger.DISTANCE, LapTrigger.DISTANCE, null),
            machine.state.laps.map { it.lap.trigger }
        )
        assertEquals(
            listOf(1001.0, 1001.0, 0.0),
            machine.state.laps.map {
                it.totals.distance.meters
            }
        )
    }

    @Test
    fun `manual marks do not reset kilometer targets and sparse distance creates one real lap`() {
        val machine = runningMachine()
        machine.feed(dist(0, 0.0), dist(5, 500.0), lap(5), dist(10, 3500.0), tick(11))
        assertEquals(
            listOf(LapTrigger.MANUAL, LapTrigger.DISTANCE, null),
            machine.state.laps.map { it.lap.trigger }
        )
        machine.feed(dist(12, 4000.0))
        assertEquals(4, machine.state.laps.size)
    }

    @Test
    fun `pause movement source resets and rejected sources do not trigger laps`() {
        val machine = runningMachine()
        machine.feed(
            dist(0, 0.0), dist(5, 500.0), pause(5), dist(10, 2000.0),
            resume(15), dist(15, 2100.0), dist(20, 10.0), dist(21, 100.0),
            dist(22, 9000.0, FOOTPOD)
        )
        assertEquals(1, machine.state.laps.size)
        machine.feed(dist(25, 510.0))
        assertEquals(2, machine.state.laps.size)
        assertEquals(RunPhase.RUNNING, machine.state.phase)
    }

    @Test
    fun `settings can change only before start and disabled auto lap remains manual`() {
        val machine = machine()
        assertTrue(
            machine.handle(EngineInput.ConfigureWorkout(WorkoutSettings(autoLapMeters = null)))
        )
        machine.feed(prepare(0), start(0), dist(0, 0.0), dist(10, 5000.0))
        assertEquals(1, machine.state.laps.size)
        assertFalse(machine.handle(EngineInput.ConfigureWorkout(WorkoutSettings())))
    }

    @Test
    fun `time intervals catch up exactly exclude pauses and continue recording after cooldown`() {
        val machine = configured(timeWorkout())
        machine.feed(tick(8))
        assertEquals(2.seconds, machine.state.workout?.remainingTime)
        machine.feed(pause(9), tick(100), resume(109), tick(141))
        assertEquals(
            listOf(10, 15, 18, 23, 26, 30),
            machine.state.run!!.events.filterIsInstance<RunEvent.LapMarked>().map {
                // Subtract the pause from event times.
                (it.time.elapsedSince(TestTime.at(0)).inWholeSeconds - 100).toInt()
            }
        )
        assertTrue(machine.state.workout!!.completed)
        assertEquals(RunPhase.RUNNING, machine.state.phase)
        assertEquals(
            listOf(10, 5, 3, 5, 3, 4, 11),
            machine.state.laps.map { it.totals.timer.inWholeSeconds.toInt() }
        )
    }

    @Test
    fun `distance stages advance at samples and manual laps leave workout progress intact`() {
        val workout = IntervalWorkout(
            null,
            2,
            IntervalTarget(meters = 100.0),
            IntervalTarget(seconds = 5),
            null
        )
        val machine = configured(workout)
        machine.feed(dist(0, 0.0), dist(5, 50.0), lap(5), dist(10, 105.0))
        assertEquals(WorkoutStage.RECOVERY, machine.state.workout?.stage)
        assertEquals(5.seconds, machine.state.workout?.remainingTime)
        machine.feed(tick(15), dist(15, 105.0), dist(20, 205.0), tick(25))
        assertTrue(machine.state.workout!!.completed)
        assertEquals(
            listOf(
                LapTrigger.MANUAL,
                LapTrigger.DISTANCE,
                LapTrigger.TIME,
                LapTrigger.DISTANCE,
                LapTrigger.TIME
            ),
            machine.state.run!!.events.filterIsInstance<RunEvent.LapMarked>().map { it.trigger }
        )
    }

    @Test
    fun `journal recovery retains settings and stage without duplicating laps`(@TempDir dir: Path) {
        val file = dir.resolve("workout.journal")
        val settings = WorkoutSettings(workout = timeWorkout())
        RecordingSession(journal = JournalWriter.create(file)).use { session ->
            session.handle(EngineInput.ConfigureWorkout(settings))
            listOf(prepare(0), start(0), dist(0, 0.0), dist(10, 50.0), tick(16))
                .forEach(session::handle)
        }
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use { session ->
            assertEquals(settings, session.state.workoutSettings)
            assertEquals(WorkoutStage.RECOVERY, session.state.workout?.stage)
            session.handle(tick(30))
            session.handle(stop(31))
            val recorded = RecordedRun.from(JournalReader.read(file))
            assertEquals(session.state.run, recorded.state.run)
            assertEquals(session.state.laps, recorded.state.laps)
            assertEquals(6, session.state.run!!.events.filterIsInstance<RunEvent.LapMarked>().size)
        }
    }

    @Test
    fun `workout values must be positive finite and bounded`() {
        assertFailsWith<IllegalArgumentException> { IntervalTarget(seconds = 0) }
        assertFailsWith<IllegalArgumentException> { IntervalTarget(meters = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { IntervalTarget(seconds = 1, meters = 1.0) }
        assertFailsWith<IllegalArgumentException> { WorkoutSettings(autoLapMeters = 0.0) }
        assertFailsWith<IllegalArgumentException> {
            IntervalWorkout(null, 0, IntervalTarget(seconds = 1), IntervalTarget(seconds = 1), null)
        }
    }

    @Test
    fun `disabled auto lap survives journal round trip`(@TempDir dir: Path) {
        val file = dir.resolve("disabled.journal")
        val settings = WorkoutSettings(autoLapMeters = null)
        RecordingSession(journal = JournalWriter.create(file)).use {
            it.handle(EngineInput.ConfigureWorkout(settings))
            it.handle(prepare(0))
            it.handle(start(0))
        }
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use {
            assertEquals(settings, it.state.workoutSettings)
            it.handle(dist(0, 0.0))
            it.handle(dist(10, 2000.0))
            assertEquals(1, it.state.laps.size)
        }
    }

    private fun timeWorkout() = IntervalWorkout(
        IntervalTarget(seconds = 10),
        2,
        IntervalTarget(seconds = 5),
        IntervalTarget(seconds = 3),
        IntervalTarget(seconds = 4)
    )

    private fun configured(workout: IntervalWorkout): RecordingStateMachine = machine().also {
        it.handle(EngineInput.ConfigureWorkout(WorkoutSettings(workout = workout)))
        it.feed(prepare(0), start(0))
    }
}
