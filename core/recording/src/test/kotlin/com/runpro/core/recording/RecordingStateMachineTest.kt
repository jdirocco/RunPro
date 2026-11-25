package com.runpro.core.recording

import com.runpro.core.model.LapTrigger
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.StopReason
import com.runpro.core.recording.TestTime.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RecordingStateMachineTest {
    @Test
    fun `a new machine is idle without a run`() {
        val state = machine().state
        assertEquals(RunPhase.IDLE, state.phase)
        assertNull(state.run)
        assertEquals(Totals(), state.totals)
        assertTrue(state.laps.isEmpty())
    }

    @Test
    fun `idle goes through preparing to running`() {
        val machine = machine()
        assertTrue(machine.handle(prepare(0)))
        assertEquals(RunPhase.PREPARING, machine.state.phase)
        assertNull(machine.state.run)

        assertTrue(machine.handle(start(5)))
        val state = machine.state
        assertEquals(RunPhase.RUNNING, state.phase)
        assertEquals(RunId("run-1"), state.run?.id)
        assertEquals(listOf<RunEvent>(RunEvent.Started(at(5))), state.run?.events)
        assertEquals(1, state.laps.size)
        assertTrue(state.laps.single().lap.isOpen)
    }

    @Test
    fun `start is only accepted while preparing`() {
        val machine = machine()
        assertFalse(machine.handle(start(0)))
        assertEquals(RunPhase.IDLE, machine.state.phase)

        machine.feed(prepare(0), start(1))
        assertFalse(machine.handle(start(2)))
        assertEquals(1, machine.state.run?.events?.size)
    }

    @Test
    fun `running and paused alternate and record events`() {
        val state = runningMachine().feed(
            pause(10),
            resume(20),
            pause(30, PauseReason.AUTO)
        )
        assertEquals(RunPhase.PAUSED, state.phase)
        assertEquals(
            listOf(
                RunEvent.Started(at(0)),
                RunEvent.Paused(at(10), PauseReason.MANUAL),
                RunEvent.Resumed(at(20)),
                RunEvent.Paused(at(30), PauseReason.AUTO)
            ),
            state.run?.events
        )
    }

    @Test
    fun `transitions that do not apply are rejected without changing the run`() {
        val machine = runningMachine()
        assertFalse(machine.handle(resume(5)))
        assertTrue(machine.handle(pause(10)))
        assertFalse(machine.handle(pause(11)))
        assertFalse(machine.handle(lap(12)))
        assertFalse(machine.handle(prepare(13)))
        assertFalse(machine.handle(EngineInput.Reset))
        assertEquals(2, machine.state.run?.events?.size)
        assertEquals(RunPhase.PAUSED, machine.state.phase)
    }

    @Test
    fun `manual laps close the current lap and open the next`() {
        val state = runningMachine().feed(lap(60), lap(130))
        assertEquals(
            listOf(LapTrigger.MANUAL, LapTrigger.MANUAL, null),
            state.laps.map { it.lap.trigger }
        )
        assertEquals(listOf(at(0), at(60), at(130)), state.laps.map { it.lap.start })
        assertEquals(60.seconds, state.laps[0].totals.timer)
        assertEquals(70.seconds, state.laps[1].totals.timer)
        assertEquals(listOf(0, 1, 2), state.laps.map { it.lap.index })
    }

    @Test
    fun `stop from running closes the last lap and freezes the run`() {
        val machine = runningMachine()
        machine.feed(lap(60))
        assertTrue(machine.handle(stop(100)))
        val state = machine.state
        assertEquals(RunPhase.STOPPED, state.phase)
        assertEquals(RunEvent.Stopped(at(100), StopReason.USER), state.run?.events?.last())
        assertEquals(listOf(LapTrigger.MANUAL, LapTrigger.STOP), state.laps.map { it.lap.trigger })
        assertEquals(100.seconds, state.totals.elapsed)

        assertFalse(machine.handle(lap(110)))
        assertFalse(machine.handle(pause(110)))
        assertFalse(machine.handle(stop(120)))
        machine.handle(tick(500))
        machine.handle(dist(200, 1000.0))
        assertEquals(state, machine.state)
    }

    @Test
    fun `stop while paused excludes the pause from the timer`() {
        val state = runningMachine().feed(pause(40), tick(90), stop(100, StopReason.LOW_BATTERY))
        assertEquals(RunPhase.STOPPED, state.phase)
        assertEquals(RunEvent.Stopped(at(100), StopReason.LOW_BATTERY), state.run?.events?.last())
        assertEquals(100.seconds, state.totals.elapsed)
        assertEquals(40.seconds, state.totals.timer)
        assertEquals(40.seconds, state.laps.single().totals.timer)
    }

    @Test
    fun `elapsed includes pauses and the timer does not`() {
        val machine = runningMachine()
        machine.feed(tick(30), pause(30), tick(50))
        assertEquals(50.seconds, machine.state.totals.elapsed)
        assertEquals(30.seconds, machine.state.totals.timer)

        machine.feed(resume(60), tick(75))
        assertEquals(75.seconds, machine.state.totals.elapsed)
        assertEquals(45.seconds, machine.state.totals.timer)
    }

    @Test
    fun `samples move the clock forward like ticks`() {
        val state = runningMachine().feed(hr(12, 140))
        assertEquals(12.seconds, state.totals.elapsed)
    }

    @Test
    fun `a command older than the last event is moved to the last event time`() {
        val state = runningMachine().feed(pause(50), stop(20))
        assertEquals(RunEvent.Stopped(at(50), StopReason.USER), state.run?.events?.last())
        assertEquals(50.seconds, state.totals.timer)
    }

    @Test
    fun `reset returns to idle from preparing or stopped and the next run gets a new id`() {
        val machine = machine()
        machine.feed(prepare(0))
        assertTrue(machine.handle(EngineInput.Reset))
        assertEquals(RunState(), machine.state)

        machine.feed(prepare(0), start(1), dist(2, 3.0), dist(3, 6.0), stop(10))
        assertTrue(machine.handle(EngineInput.Reset))
        assertEquals(RunState(), machine.state)

        machine.feed(prepare(20), start(21), dist(22, 100.0), dist(23, 103.0))
        assertEquals(RunId("run-2"), machine.state.run?.id)
        assertEquals(3.0, machine.state.totals.distance.meters, 1e-9)
    }

    @Test
    fun `the open lap totals follow the clock`() {
        val state = runningMachine().feed(lap(10), tick(25))
        assertEquals(15.seconds, state.laps.last().totals.elapsed)
        assertEquals(Duration.ZERO, state.laps.last().totals.moving)
    }
}
