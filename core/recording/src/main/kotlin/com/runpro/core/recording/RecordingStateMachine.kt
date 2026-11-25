package com.runpro.core.recording

import com.runpro.core.model.LapTrigger
import com.runpro.core.model.Run
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.Timestamp

/**
 * The recording core: phases, run events, totals and laps. Pure, synchronous and deterministic, so replay
 * tests feed it directly; [RecordingEngine] runs it on its own thread for the app.
 *
 * Inputs that do not apply to the current phase are rejected ([handle] returns false) and change nothing;
 * the engine never throws for a valid input in the wrong phase.
 */
class RecordingStateMachine(
    private val config: EngineConfig = EngineConfig(),
    private val runIds: () -> RunId = RunId::random
) {
    private var phase = RunPhase.IDLE
    private var run: Run? = null
    private var metrics: RunMetrics? = null
    private var now: Timestamp? = null
    private var gate = SampleGate()
    private var current = CurrentValues()

    /** A snapshot of the current state. */
    val state: RunState
        get() {
            val run = run
            val metrics = metrics
            val now = run?.stoppedAt ?: now
            if (run == null || metrics == null ||
                now == null
            ) {
                return RunState(phase, current = current)
            }
            return RunState(
                phase = phase,
                run = run,
                totals = metrics.totals(now),
                laps = run.laps.zip(metrics.laps(now), ::LapTotals),
                current = current
            )
        }

    /** Applies [input]; returns false when it does not apply to the current phase. */
    fun handle(input: EngineInput): Boolean = when (input) {
        is EngineInput.SampleReceived -> onSample(input.sample)
        is EngineInput.Tick -> advanceClock(input.time)
        is EngineInput.Prepare -> moveTo(RunPhase.PREPARING, RunPhase.IDLE)
        is EngineInput.Start -> start(input.time)
        is EngineInput.Reset -> reset()
        is EngineInput.Command -> record(input)
    }

    private fun moveTo(next: RunPhase, vararg from: RunPhase): Boolean {
        val allowed = phase in from
        if (allowed) phase = next
        return allowed
    }

    private fun start(time: Timestamp): Boolean {
        val started = moveTo(RunPhase.RUNNING, RunPhase.PREPARING)
        if (started) {
            run = Run(runIds(), listOf(RunEvent.Started(time)))
            metrics = RunMetrics(config, time)
            now = time
        }
        return started
    }

    private fun reset(): Boolean {
        val reset = moveTo(RunPhase.IDLE, RunPhase.PREPARING, RunPhase.STOPPED)
        if (reset) {
            run = null
            metrics = null
            now = null
            gate = SampleGate()
            current = CurrentValues()
        }
        return reset
    }

    private fun record(command: EngineInput.Command): Boolean {
        val run = run
        val metrics = metrics
        val allowed = when (command) {
            is EngineInput.Pause, is EngineInput.MarkLap -> phase == RunPhase.RUNNING
            is EngineInput.Resume -> phase == RunPhase.PAUSED
            is EngineInput.Stop -> phase == RunPhase.RUNNING || phase == RunPhase.PAUSED
        }
        if (!allowed || run == null || metrics == null) return false

        val time = maxOf(command.time, run.events.last().time)
        advanceClock(time)
        this.run = run + when (command) {
            is EngineInput.Pause -> {
                metrics.pause(time)
                phase = RunPhase.PAUSED
                RunEvent.Paused(time, command.reason)
            }

            is EngineInput.Resume -> {
                metrics.resume(time)
                phase = RunPhase.RUNNING
                RunEvent.Resumed(time)
            }

            is EngineInput.MarkLap -> {
                metrics.markLap(time)
                RunEvent.LapMarked(time, LapTrigger.MANUAL)
            }

            is EngineInput.Stop -> {
                metrics.stop(time)
                phase = RunPhase.STOPPED
                RunEvent.Stopped(time, command.reason)
            }
        }
        return true
    }

    private fun onSample(sample: Sample): Boolean {
        val accepted = phase != RunPhase.IDLE && phase != RunPhase.STOPPED && gate.admit(sample)
        if (accepted) {
            current = current.updatedWith(sample)
            metrics?.let {
                it.onSample(sample)
                advanceClock(sample.time)
            }
        }
        return accepted
    }

    /** Moves "now" forward while the run is recording; it never goes back. */
    private fun advanceClock(time: Timestamp): Boolean {
        val recording = phase == RunPhase.RUNNING || phase == RunPhase.PAUSED
        if (recording) now = now?.let { maxOf(it, time) } ?: time
        return recording
    }
}
