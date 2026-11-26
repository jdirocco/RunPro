package com.runpro.core.recording.service

import com.runpro.core.recording.RunPhase

/** Why `RecordingService.onStartCommand` was called. */
enum class ServiceCommand {
    START,
    PAUSE,
    RESUME,
    LAP,
    STOP,

    /** `START_STICKY` restart: the system recreated the service after killing the process (null intent). */
    RESTART
}

/** What the service does with a start command, given the phase of its engine. */
object ServiceStartPolicy {
    enum class Decision {
        /** Enter the foreground and start a new run (after a reset, if the last run is stopped). */
        START_RUN,

        /** Pass the command to the engine. */
        FORWARD,

        /** The process was killed during a run: enter the foreground and restore the run from its journal. */
        RECOVER,

        /** Nothing to do; the service keeps its current state. */
        KEEP_RUNNING,

        /** No run to work on: leave the foreground and stop, so no empty notification stays behind. */
        STOP_SERVICE
    }

    fun decide(command: ServiceCommand?, phase: RunPhase): Decision {
        val active = needsForeground(phase)
        return when (command) {
            ServiceCommand.START -> if (active) Decision.KEEP_RUNNING else Decision.START_RUN

            ServiceCommand.RESTART -> if (active) Decision.KEEP_RUNNING else Decision.RECOVER

            ServiceCommand.PAUSE, ServiceCommand.RESUME, ServiceCommand.LAP, ServiceCommand.STOP ->
                if (active) Decision.FORWARD else Decision.STOP_SERVICE

            null -> if (active) Decision.KEEP_RUNNING else Decision.STOP_SERVICE
        }
    }

    /** A run is in progress: the service must stay in the foreground. */
    fun needsForeground(phase: RunPhase): Boolean =
        phase == RunPhase.PREPARING || phase == RunPhase.RUNNING || phase == RunPhase.PAUSED
}
