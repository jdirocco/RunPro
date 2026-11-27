package com.runpro.core.recording.service

import com.runpro.core.recording.RunPhase

/** Why `RecordingService.onStartCommand` was called. */
enum class ServiceCommand {
    /** Warm up GPS and heart rate before the start (the start screen shows their readiness). */
    PREPARE,

    /** Start the run: the prepared one, or a new one without a warm-up. */
    START,

    /** Leave the warm-up without starting; never ends a run that records. */
    CANCEL,
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

        /** Enter the foreground and warm up the sensors for a new run (after a reset, if needed). */
        PREPARE_RUN,

        /** Pass the command to the engine. */
        FORWARD,

        /** The process was killed during a run: enter the foreground and restore the run from its journal. */
        RECOVER,

        /** Nothing to do; the service keeps its current state. */
        KEEP_RUNNING,

        /** No run to work on: leave the foreground and stop, so no empty notification stays behind. */
        STOP_SERVICE
    }

    fun decide(command: ServiceCommand?, phase: RunPhase): Decision = when (command) {
        ServiceCommand.PREPARE ->
            byPhase(phase, idle = Decision.PREPARE_RUN, preparing = Decision.KEEP_RUNNING)

        ServiceCommand.START ->
            byPhase(phase, idle = Decision.START_RUN, preparing = Decision.FORWARD)

        // R1: cancel only discards a warm-up; a recording run is stopped and saved instead.
        ServiceCommand.CANCEL ->
            byPhase(phase, idle = Decision.STOP_SERVICE, preparing = Decision.FORWARD)

        ServiceCommand.RESTART ->
            byPhase(phase, idle = Decision.RECOVER, preparing = Decision.KEEP_RUNNING)

        // The warm-up has no run to pause, lap or stop.
        ServiceCommand.PAUSE, ServiceCommand.RESUME, ServiceCommand.LAP, ServiceCommand.STOP ->
            byPhase(
                phase,
                idle = Decision.STOP_SERVICE,
                preparing = Decision.KEEP_RUNNING,
                recording = Decision.FORWARD
            )

        null -> byPhase(phase, idle = Decision.STOP_SERVICE, preparing = Decision.KEEP_RUNNING)
    }

    /** [idle] without a run (idle or stopped), [preparing] in the warm-up, [recording] while it records. */
    private fun byPhase(
        phase: RunPhase,
        idle: Decision,
        preparing: Decision,
        recording: Decision = Decision.KEEP_RUNNING
    ): Decision = when (phase) {
        RunPhase.IDLE, RunPhase.STOPPED -> idle
        RunPhase.PREPARING -> preparing
        RunPhase.RUNNING, RunPhase.PAUSED -> recording
    }

    /** A run is in progress: the service must stay in the foreground. */
    fun needsForeground(phase: RunPhase): Boolean =
        phase == RunPhase.PREPARING || phase == RunPhase.RUNNING || phase == RunPhase.PAUSED
}
