package com.runpro.core.recording

import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.DisplayUnits
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.IntervalTarget
import com.runpro.core.model.IntervalWorkout
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunSettings
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import com.runpro.core.model.UserProfile
import com.runpro.core.model.WorkoutSettings
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunSettingsTest {
    @Test
    fun `settings freeze at start and survive real journal recovery`(@TempDir dir: Path) {
        val settings =
            WorkoutSettings(
                runSettings = RunSettings(
                    DisplayUnits.IMPERIAL,
                    UserProfile(190, 50, 72.5),
                    true,
                    3,
                    true
                )
            )
        val file = dir.resolve("settings.journal")
        RecordingSession(journal = JournalWriter.create(file)).use {
            it.handle(EngineInput.ConfigureWorkout(settings))
            it.handle(EngineInput.Prepare(at(0)))
            it.handle(EngineInput.Start(at(0)))
            assertFalse(it.handle(EngineInput.ConfigureWorkout(WorkoutSettings())))
            it.handle(speed(1, 0.0))
            it.handle(speed(3, 0.0))
            it.handle(speed(5, 0.0))
            it.handle(speed(6, 0.0))
            assertEquals(RunPhase.PAUSED, it.state.phase)
        }
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use {
            assertEquals(settings, it.state.workoutSettings)
            assertEquals(1, it.state.run!!.events.filterIsInstance<RunEvent.Paused>().size)
            it.handle(speed(7, 2.0))
            assertEquals(RunPhase.RUNNING, it.state.phase)
            assertEquals(1, it.state.run!!.events.filterIsInstance<RunEvent.Resumed>().size)
        }
    }

    @Test
    fun `auto pause is opt in and never reacts to silence or batched old samples`() {
        val machine = started(false)
        listOf(1, 3, 5, 6).forEach { machine.handle(speed(it, 0.0)) }
        assertEquals(RunPhase.RUNNING, machine.state.phase)
        val enabled = started(true)
        enabled.handle(speed(1, 0.0))
        enabled.handle(EngineInput.Tick(at(20)))
        enabled.handle(speed(6, 0.0))
        enabled.handle(speed(20, 0.0))
        assertEquals(RunPhase.RUNNING, enabled.state.phase)
    }

    @Test
    fun `fresh low speed pauses with hysteresis and manual pause never auto resumes`() {
        val machine = started(true)
        listOf(1, 3, 5, 6).forEach { machine.handle(speed(it, 0.0)) }
        assertEquals(
            PauseReason.AUTO,
            machine.state.run!!.events.filterIsInstance<RunEvent.Paused>().single().reason
        )
        machine.handle(speed(7, 0.7))
        assertEquals(RunPhase.PAUSED, machine.state.phase)
        machine.handle(speed(8, 2.0))
        assertEquals(RunPhase.RUNNING, machine.state.phase)
        machine.handle(EngineInput.Pause(at(9)))
        machine.handle(speed(10, 3.0))
        assertEquals(RunPhase.PAUSED, machine.state.phase)
        assertTrue(machine.handle(EngineInput.Resume(at(11))))
    }

    @Test
    fun `ticks and HR ahead of delayed speed still allow automatic pause and resume`() {
        for (latency in listOf(700.milliseconds, 3.seconds)) {
            val machine = started(true)
            machine.handle(speed(0, 2.0))
            machine.handle(hr(at(0)))
            for (second in 1..10) {
                val delivery = at(second) + latency
                machine.handle(EngineInput.Tick(delivery))
                machine.handle(hr(delivery))
                assertTrue(machine.handle(speed(second, if (second < 10) 0.0 else 2.0)))
                assertEquals(
                    if (second in 6..9) RunPhase.PAUSED else RunPhase.RUNNING,
                    machine.state.phase,
                    "Speed latency $latency at second $second"
                )
            }
            val events = machine.state.run!!.events
            assertEquals(at(6), events.filterIsInstance<RunEvent.Paused>().single().time)
            assertEquals(at(10), events.filterIsInstance<RunEvent.Resumed>().single().time)
            assertEquals(6.seconds + latency, machine.state.totals.timer)
        }
    }

    @Test
    fun `old speed batches cannot pause or resume even when admitted for totals`() {
        val running = started(true)
        running.handle(speed(0, 2.0))
        running.handle(EngineInput.Tick(at(20)))
        (1..10).forEach { assertTrue(running.handle(speed(it, 0.0))) }
        assertEquals(RunPhase.RUNNING, running.state.phase)
        assertTrue(running.state.run!!.events.none { it is RunEvent.Paused })

        val paused = started(true)
        (0..5).forEach { paused.handle(speed(it, 0.0)) }
        assertEquals(RunPhase.PAUSED, paused.state.phase)
        paused.handle(EngineInput.Tick(at(20)))
        // Just beyond the three-second freshness bound must still be rejected for auto-resume.
        val old = SpeedSample(at(16) + 999.milliseconds, SourceId.HEALTH_SERVICES, Speed(2.0))
        assertTrue(paused.handle(EngineInput.SampleReceived(old)))
        assertEquals(RunPhase.PAUSED, paused.state.phase)
        assertTrue(paused.state.run!!.events.none { it is RunEvent.Resumed })
        assertTrue(paused.handle(speed(20, 2.0)))
        assertEquals(RunPhase.RUNNING, paused.state.phase)
    }

    @Test
    fun `an automatic pause never swallows a workout boundary due at the same instant`() {
        val machine = RecordingStateMachine()
        val workout = IntervalWorkout(
            warmUp = IntervalTarget(seconds = 5),
            repetitions = 1,
            work = IntervalTarget(seconds = 10),
            recovery = IntervalTarget(seconds = 5)
        )
        machine.handle(
            EngineInput.ConfigureWorkout(
                WorkoutSettings(workout = workout, runSettings = RunSettings(autoPause = true))
            )
        )
        machine.handle(EngineInput.Prepare(at(0)))
        machine.handle(EngineInput.Start(at(0)))
        (0..5).forEach { machine.handle(speed(it, 0.0)) }
        val lap = machine.state.run!!.events.filterIsInstance<RunEvent.LapMarked>().single()
        assertEquals(LapTrigger.TIME, lap.trigger)
        assertEquals(at(5), lap.time)
        assertEquals(RunPhase.PAUSED, machine.state.phase)
    }

    private fun started(autoPause: Boolean) = RecordingStateMachine().also {
        it.handle(
            EngineInput.ConfigureWorkout(
                WorkoutSettings(runSettings = RunSettings(autoPause = autoPause))
            )
        )
        it.handle(EngineInput.Prepare(at(0)))
        it.handle(EngineInput.Start(at(0)))
    }

    private fun at(seconds: Int) = Timestamp(seconds * 1_000_000_000L, seconds * 1000L)
    private fun speed(seconds: Int, value: Double) =
        EngineInput.SampleReceived(SpeedSample(at(seconds), SourceId.HEALTH_SERVICES, Speed(value)))

    private fun hr(time: Timestamp) =
        EngineInput.SampleReceived(HeartRateSample(time, SourceId("ble:strap"), HeartRate(140)))
}
