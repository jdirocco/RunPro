package com.runpro.core.recording.service

import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.TestTime.at
import com.runpro.core.recording.feed
import com.runpro.core.recording.machine
import com.runpro.core.recording.pause
import com.runpro.core.recording.prepare
import com.runpro.core.recording.start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class WarmUpTimeoutTest {
    private val limitSeconds = WarmUpTimeout.DEFAULT_LIMIT.inWholeSeconds.toInt()

    @Test
    fun `the default limit is ten minutes`() {
        assertEquals(10.minutes, WarmUpTimeout.DEFAULT_LIMIT)
    }

    @Test
    fun `a warm-up left alone is cancelled once the limit is reached, not before`() {
        val timeout = WarmUpTimeout()

        (0 until limitSeconds).forEach { second ->
            assertFalse(timeout.shouldCancel(RunPhase.PREPARING, at(second)), "second $second")
        }
        assertTrue(timeout.shouldCancel(RunPhase.PREPARING, at(limitSeconds)))
    }

    @Test
    fun `the limit counts from the first tick of the warm-up`() {
        val timeout = WarmUpTimeout(limit = 2.minutes)

        assertFalse(timeout.shouldCancel(RunPhase.IDLE, at(0)))
        assertFalse(timeout.shouldCancel(RunPhase.PREPARING, at(500)))
        assertFalse(timeout.shouldCancel(RunPhase.PREPARING, at(619)))
        assertTrue(timeout.shouldCancel(RunPhase.PREPARING, at(620)))
    }

    @Test
    fun `the cancel is asked once per warm-up`() {
        val timeout = WarmUpTimeout(limit = 1.minutes)
        timeout.shouldCancel(RunPhase.PREPARING, at(0))

        assertTrue(timeout.shouldCancel(RunPhase.PREPARING, at(60)))
        // The reset may not be processed yet: the next tick still sees the warm-up.
        assertFalse(timeout.shouldCancel(RunPhase.PREPARING, at(61)))
        assertFalse(timeout.shouldCancel(RunPhase.PREPARING, at(600)))
    }

    @Test
    fun `a new warm-up gets a full limit again`() {
        val timeout = WarmUpTimeout(limit = 1.minutes)
        timeout.shouldCancel(RunPhase.PREPARING, at(0))
        assertTrue(timeout.shouldCancel(RunPhase.PREPARING, at(60)))
        timeout.shouldCancel(RunPhase.IDLE, at(61))

        assertFalse(timeout.shouldCancel(RunPhase.PREPARING, at(100)))
        assertFalse(timeout.shouldCancel(RunPhase.PREPARING, at(159)))
        assertTrue(timeout.shouldCancel(RunPhase.PREPARING, at(160)))
    }

    @Test
    fun `R1 - a running or paused run is never cancelled, however long it lasts`() {
        val timeout = WarmUpTimeout(limit = 1.minutes)
        timeout.shouldCancel(RunPhase.PREPARING, at(0))
        timeout.shouldCancel(RunPhase.PREPARING, at(59))

        (60..(60 + 4 * 3600) step 30).forEachIndexed { index, second ->
            val phase = if (index % 2 == 0) RunPhase.RUNNING else RunPhase.PAUSED
            assertFalse(timeout.shouldCancel(phase, at(second)), "$phase at $second")
        }
        assertFalse(timeout.shouldCancel(RunPhase.STOPPED, at(20_000)))
    }

    @Test
    fun `a warm-up that started before the limit is not cancelled after the start`() {
        val timeout = WarmUpTimeout()
        timeout.shouldCancel(RunPhase.PREPARING, at(0))
        timeout.shouldCancel(RunPhase.PREPARING, at(limitSeconds - 1))

        assertFalse(timeout.shouldCancel(RunPhase.RUNNING, at(limitSeconds)))
        assertFalse(timeout.shouldCancel(RunPhase.RUNNING, at(limitSeconds * 2)))
    }

    @Test
    fun `R1 - the reset the service sends cannot discard a run that has just started`() {
        // The tick reads the phase before the engine processes a start tapped at the same moment.
        val timeout = WarmUpTimeout(limit = 1.minutes)
        val machine = machine()
        machine.feed(prepare(0))
        timeout.shouldCancel(machine.state.phase, at(0))

        val cancel = timeout.shouldCancel(machine.state.phase, at(60))
        machine.feed(start(60))
        if (cancel) machine.feed(EngineInput.Reset)

        assertTrue(cancel)
        assertEquals(RunPhase.RUNNING, machine.state.phase)
        assertNotNull(machine.state.run)
        machine.feed(pause(70), EngineInput.Reset)
        assertEquals(RunPhase.PAUSED, machine.state.phase)
    }

    @Test
    fun `the reset ends a warm-up without leaving a run`() {
        val machine = machine()
        machine.feed(prepare(0), EngineInput.Reset)

        assertEquals(RunPhase.IDLE, machine.state.phase)
        assertEquals(null, machine.state.run)
    }
}
