package com.runpro.tools.replay

import com.runpro.core.model.IntervalTarget
import com.runpro.core.model.IntervalWorkout
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.RunEvent
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingStateMachine
import com.runpro.core.recording.RunPhase
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IntervalWorkoutReplayTest {
    @Test
    fun `committed six by three minute trace produces all fourteen workout laps`() {
        val trace = Trace.read(
            Paths.get(System.getProperty("runpro.traces"), SyntheticTraces.INTERVALS)
        )
        val start = trace.records.first().time
        val machine = RecordingStateMachine()
        machine.handle(
            EngineInput.ConfigureWorkout(
                WorkoutSettings(
                    workout = IntervalWorkout(
                        IntervalTarget(seconds = 600),
                        6,
                        IntervalTarget(seconds = 180),
                        IntervalTarget(seconds = 120),
                        IntervalTarget(seconds = 300)
                    )
                )
            )
        )
        machine.handle(EngineInput.Prepare(start))
        machine.handle(EngineInput.Start(start))
        val listener = object : SensorListener {
            override fun onSample(sample: Sample) {
                machine.handle(EngineInput.SampleReceived(sample))
            }
            override fun onEvent(event: SensorEvent) = Unit
        }
        val replayer = Replayer(trace)
        replayer.sources.forEach { it.start(listener) }
        replayer.run()
        machine.handle(EngineInput.Tick(start + 2700.seconds))
        val state = machine.state
        assertEquals(RunPhase.RUNNING, state.phase)
        assertTrue(state.workout!!.completed)
        assertEquals(
            listOf(600) + List(6) { listOf(180, 120) }.flatten() + listOf(300),
            state.laps.dropLast(1).map { it.totals.timer.inWholeSeconds.toInt() }
        )
        assertEquals(14, state.run!!.events.filterIsInstance<RunEvent.LapMarked>().size)
        assertTrue(state.laps.dropLast(1).all { it.lap.trigger == LapTrigger.TIME })
        assertEquals(2700.seconds, state.totals.timer)
        assertEquals(
            state.totals.distance.meters,
            state.laps.sumOf { it.totals.distance.meters },
            1e-6
        )
    }
}
