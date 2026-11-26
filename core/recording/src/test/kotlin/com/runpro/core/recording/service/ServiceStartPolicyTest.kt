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
        for (phase in listOf(RunPhase.PREPARING, RunPhase.RUNNING, RunPhase.PAUSED)) {
            val decision = ServiceStartPolicy.decide(ServiceCommand.START, phase)
            assertEquals(Decision.KEEP_RUNNING, decision)
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
