package com.runpro.core.recording

import com.runpro.core.journal.JournalOutput
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.RunEvent
import com.runpro.core.model.StopReason
import com.runpro.core.recording.TestTime.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LowResourceTest {
    @Test
    fun `battery thresholds warn at fifteen and stop at three including paused runs`() {
        for (paused in listOf(false, true)) {
            val machine = RecordingStateMachine()
            machine.handle(prepare(0))
            machine.handle(
                EngineInput.Resources(at(0), batteryPercent = 16, availableBytes = 50_000_000)
            )
            machine.handle(start(0))
            assertFalse(machine.state.resources.lowBattery)
            machine.handle(EngineInput.Resources(at(1), batteryPercent = 15))
            assertTrue(machine.state.resources.lowBattery)
            if (paused) machine.handle(pause(2))
            machine.handle(EngineInput.Resources(at(3), batteryPercent = 4))
            assertTrue(machine.state.phase != RunPhase.STOPPED)
            machine.handle(EngineInput.Resources(at(4), batteryPercent = 3))
            machine.handle(EngineInput.Resources(at(5), batteryPercent = 0))
            assertEquals(
                RunEvent.Stopped(at(4), StopReason.LOW_BATTERY),
                machine.state.run!!.events.last()
            )
            assertEquals(1, machine.state.run!!.events.filterIsInstance<RunEvent.Stopped>().size)
        }
    }

    @Test
    fun `journal sessions require a space check before starting and low space is a warning`() {
        val machine = RecordingStateMachine(requireStorageCheck = true)
        machine.handle(prepare(0))
        assertFalse(machine.handle(start(0)))
        machine.handle(EngineInput.Resources(at(1), availableBytes = 49_999_999))
        assertTrue(machine.state.resources.lowStorage)
        assertTrue(machine.handle(start(1)))
        machine.handle(EngineInput.Resources(at(2), availableBytes = 0))
        assertEquals(
            RunEvent.Stopped(at(2), StopReason.STORAGE_FULL),
            machine.state.run!!.events.last()
        )
    }

    @Test
    fun `session checks its actual output capacity on each start attempt`() {
        var available = 0L
        val output = object : JournalOutput {
            override val availableBytes: Long get() = available
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            override fun sync() = Unit
            override fun close() = Unit
        }
        RecordingSession(journal = JournalWriter(output)).use { session ->
            session.handle(prepare(0))
            assertFalse(session.handle(start(0)))
            assertTrue(session.state.resources.lowStorage)
            available = 49_999_999
            assertTrue(session.handle(start(1)))
            assertTrue(session.state.resources.lowStorage)
            available = 0
            assertFalse(session.handle(start(2)))
            assertEquals(RunPhase.RUNNING, session.state.phase)
        }
    }

    @Test
    fun `empty storage or critical battery blocks start and fresh resources allow retry`() {
        val machine = RecordingStateMachine()
        machine.handle(prepare(0))
        machine.handle(EngineInput.Resources(at(0), batteryPercent = 3, availableBytes = 0))
        assertFalse(machine.handle(start(0)))
        machine.handle(
            EngineInput.Resources(at(1), batteryPercent = 16, availableBytes = 50_000_000)
        )
        assertFalse(machine.state.resources.lowStorage)
        assertTrue(machine.handle(start(1)))
        machine.handle(stop(2))
        val stopped = machine.state.run
        machine.handle(EngineInput.Resources(at(3), batteryPercent = 0, availableBytes = 0))
        assertEquals(stopped, machine.state.run)
    }
}
