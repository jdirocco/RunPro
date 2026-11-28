package com.runpro.core.recording.service

import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.service.ServiceStartPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceStartPolicyTest {
    @Test
    fun `a start command starts a run when none is active`() {
        for (phase in listOf(RunPhase.IDLE, RunPhase.STOPPED)) {
            assertEquals(Decision.START_RUN, ServiceStartPolicy.decide(ServiceCommand.START, phase))
        }
        for (phase in listOf(RunPhase.RUNNING, RunPhase.PAUSED)) {
            val decision = ServiceStartPolicy.decide(ServiceCommand.START, phase)
            assertEquals(Decision.KEEP_RUNNING, decision)
        }
    }

    @Test
    fun `a start command during the warm-up starts the prepared run`() {
        assertEquals(
            Decision.FORWARD,
            ServiceStartPolicy.decide(ServiceCommand.START, RunPhase.PREPARING)
        )
    }

    @Test
    fun `a prepare command warms up the sensors only when no run is active`() {
        for (phase in listOf(RunPhase.IDLE, RunPhase.STOPPED)) {
            assertEquals(
                Decision.PREPARE_RUN,
                ServiceStartPolicy.decide(ServiceCommand.PREPARE, phase)
            )
        }
        for (phase in listOf(RunPhase.PREPARING, RunPhase.RUNNING, RunPhase.PAUSED)) {
            assertEquals(
                Decision.KEEP_RUNNING,
                ServiceStartPolicy.decide(ServiceCommand.PREPARE, phase)
            )
        }
    }

    @Test
    fun `cancel ends a warm-up but never a recording run`() {
        assertEquals(
            Decision.FORWARD,
            ServiceStartPolicy.decide(ServiceCommand.CANCEL, RunPhase.PREPARING)
        )
        // R1: a run that records is only ever stopped (and saved), never discarded.
        for (phase in listOf(RunPhase.RUNNING, RunPhase.PAUSED)) {
            assertEquals(
                Decision.KEEP_RUNNING,
                ServiceStartPolicy.decide(ServiceCommand.CANCEL, phase)
            )
        }
        for (phase in listOf(RunPhase.IDLE, RunPhase.STOPPED)) {
            assertEquals(
                Decision.STOP_SERVICE,
                ServiceStartPolicy.decide(ServiceCommand.CANCEL, phase)
            )
        }
    }

    @Test
    fun `run commands during the warm-up do not touch the prepared run`() {
        for (command in listOf(
            ServiceCommand.PAUSE,
            ServiceCommand.RESUME,
            ServiceCommand.LAP,
            ServiceCommand.STOP
        )) {
            assertEquals(
                Decision.KEEP_RUNNING,
                ServiceStartPolicy.decide(command, RunPhase.PREPARING)
            )
        }
    }

    @Test
    fun `a sticky restart after a kill tries to recover the run`() {
        // The process died, so the engine is new and idle: only the journal knows about the run (#9).
        assertEquals(
            Decision.RECOVER,
            ServiceStartPolicy.decide(ServiceCommand.RESTART, RunPhase.IDLE)
        )
    }

    @Test
    fun `a sticky restart of a live engine changes nothing`() {
        assertEquals(
            Decision.KEEP_RUNNING,
            ServiceStartPolicy.decide(ServiceCommand.RESTART, RunPhase.RUNNING)
        )
    }

    @Test
    fun `run commands go to the engine while a run is active`() {
        for (command in listOf(
            ServiceCommand.PAUSE,
            ServiceCommand.RESUME,
            ServiceCommand.LAP,
            ServiceCommand.STOP
        )) {
            assertEquals(Decision.FORWARD, ServiceStartPolicy.decide(command, RunPhase.RUNNING))
            assertEquals(Decision.FORWARD, ServiceStartPolicy.decide(command, RunPhase.PAUSED))
        }
    }

    @Test
    fun `run commands without a run stop the service instead of leaving it in the foreground`() {
        for (command in listOf(
            ServiceCommand.PAUSE,
            ServiceCommand.RESUME,
            ServiceCommand.LAP,
            ServiceCommand.STOP
        )) {
            assertEquals(Decision.STOP_SERVICE, ServiceStartPolicy.decide(command, RunPhase.IDLE))
        }
        assertEquals(
            Decision.STOP_SERVICE,
            ServiceStartPolicy.decide(ServiceCommand.STOP, RunPhase.STOPPED)
        )
    }

    @Test
    fun `an unknown action is ignored`() {
        assertEquals(Decision.KEEP_RUNNING, ServiceStartPolicy.decide(null, RunPhase.RUNNING))
        assertEquals(Decision.STOP_SERVICE, ServiceStartPolicy.decide(null, RunPhase.IDLE))
    }

    @Test
    fun `the service needs the foreground while a run is preparing, running or paused`() {
        assertEquals(
            setOf(RunPhase.PREPARING, RunPhase.RUNNING, RunPhase.PAUSED),
            RunPhase.entries.filter(ServiceStartPolicy::needsForeground).toSet()
        )
    }
}
