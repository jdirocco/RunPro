package com.runpro.core.recording

import com.runpro.core.model.LapTrigger
import com.runpro.core.model.Metric
import com.runpro.core.model.Run
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp
import com.runpro.core.model.WorkoutSettings

/**
 * The recording core: phases, run events, totals and laps. Pure, synchronous and deterministic, so replay
 * tests feed it directly; [RecordingEngine] runs it on its own thread for the app.
 *
 * Inputs that do not apply to the current phase are rejected ([handle] returns false) and change nothing;
 * standby or invalid-contact samples may update source availability without admitting a measurement;
 * the engine never throws for a valid input in the wrong phase.
 */
// One serialized transition owner, including explicit journal restoration of those transitions.
@Suppress("TooManyFunctions")
class RecordingStateMachine(
    private val config: EngineConfig = EngineConfig(),
    private val requireStorageCheck: Boolean = false,
    internal var automaticLaps: Boolean = true,
    private val runIds: () -> RunId = RunId::random
) {
    private var settings = WorkoutSettings()
    private var workout = WorkoutController(settings)
    private val autoPause = AutoPause()
    private var phase = RunPhase.IDLE
    private var run: Run? = null
    private var metrics: RunMetrics? = null
    private var now: Timestamp? = null
    private var gate = SampleGate(config.sourcePolicy)
    private var resources = ResourceState()
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
                return RunState(
                    phase,
                    current = current,
                    resources = resources,
                    workoutSettings = settings,
                    activeSources = gate.activeSources
                )
            }
            return RunState(
                phase = phase,
                run = run,
                totals = metrics.totals(now),
                laps = run.laps.zip(metrics.laps(now), ::LapTotals),
                current = current,
                resources = resources,
                workoutSettings = settings,
                workout = workout.progress(metrics.totals(now)),
                activeSources = gate.activeSources
            )
        }

    /** Applies [input]; false means a command was rejected or a measurement was not admitted. */
    fun handle(input: EngineInput): Boolean {
        // Laps due before a runner command belong before it, so pause/lap/stop never swallow them.
        if (input is EngineInput.Command) {
            autoPause.reset()
            markAutomaticLaps(input.time)
        }
        val accepted = when (input) {
            // Workout settings can change only before the run starts.
            is EngineInput.ConfigureWorkout ->
                (phase in CONFIGURABLE_PHASES).also { allowed ->
                    if (allowed) {
                        settings = input.settings
                        workout = WorkoutController(settings)
                    }
                }

            is EngineInput.SampleReceived -> onSample(input.sample)

            is EngineInput.SensorReceived -> onSensor(input.event)

            is EngineInput.Tick -> onTick(input.time)

            is EngineInput.Prepare -> moveTo(RunPhase.PREPARING, RunPhase.IDLE)

            is EngineInput.Start -> start(input.time)

            is EngineInput.Reset -> reset()

            is EngineInput.Command -> record(input)

            is EngineInput.Resources -> onResources(input)
        }
        if (accepted) markAutomaticLaps()
        return accepted
    }

    /** Marks every automatic lap due at [commandTime] (or the current engine time) while running. */
    private fun markAutomaticLaps(commandTime: Timestamp? = null) {
        val metrics = metrics.takeIf { automaticLaps && phase == RunPhase.RUNNING } ?: return
        commandTime?.let(::advanceClock)
        val time = now ?: return
        var event = workout.due(metrics.totals(time), time)
        while (event != null) {
            restoreEvent(event.copy(time = maxOf(event.time, run!!.events.last().time)))
            event = workout.due(metrics.totals(time), time)
        }
    }

    private fun onResources(input: EngineInput.Resources): Boolean {
        resources = resources.copy(
            batteryPercent = input.batteryPercent ?: resources.batteryPercent,
            availableBytes = input.availableBytes ?: resources.availableBytes
        )
        val reason = when {
            resources.availableBytes == 0L -> StopReason.STORAGE_FULL
            resources.batteryCritical -> StopReason.LOW_BATTERY
            else -> return true
        }
        record(EngineInput.Stop(maxOf(input.time, now ?: input.time), reason))
        return true
    }

    internal fun stopForStorageFull(): Boolean {
        resources = resources.copy(availableBytes = 0)
        return now?.let { record(EngineInput.Stop(it, StopReason.STORAGE_FULL)) } ?: false
    }

    private fun moveTo(next: RunPhase, vararg from: RunPhase): Boolean {
        val allowed = phase in from
        if (allowed) phase = next
        return allowed
    }

    private fun start(time: Timestamp): Boolean {
        if (!resources.canStart ||
            (requireStorageCheck && resources.availableBytes == null)
        ) {
            return false
        }
        val started = moveTo(RunPhase.RUNNING, RunPhase.PREPARING)
        if (started) {
            // Warmup readings are not part of the persisted run or its source choices.
            gate = SampleGate(config.sourcePolicy)
            current = CurrentValues()
            run = Run(runIds(), listOf(RunEvent.Started(time)))
            metrics = RunMetrics(config, time)
            workout = WorkoutController(settings)
            autoPause.reset()
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
            gate = SampleGate(config.sourcePolicy)
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
        val event = when (command) {
            is EngineInput.Pause -> {
                RunEvent.Paused(time, command.reason)
            }

            is EngineInput.Resume -> {
                RunEvent.Resumed(time)
            }

            is EngineInput.MarkLap -> {
                RunEvent.LapMarked(time, LapTrigger.MANUAL)
            }

            is EngineInput.Stop -> {
                RunEvent.Stopped(time, command.reason)
            }
        }
        restoreEvent(event)
        return true
    }

    /** Replays an existing event exactly, including its lap trigger and sensor/source metadata. */
    internal fun restoreEvent(event: RunEvent) {
        val run = requireNotNull(run)
        val metrics = requireNotNull(metrics)
        require(phase == RunPhase.RUNNING || phase == RunPhase.PAUSED) { "Event after run stop" }
        require(event.time >= run.events.last().time) { "Run events out of order within a boot" }
        advanceClock(event.time)
        when (event) {
            is RunEvent.Started -> error("Duplicate run start")

            is RunEvent.Paused -> {
                require(phase == RunPhase.RUNNING)
                metrics.pause(event.time)
                phase = RunPhase.PAUSED
            }

            is RunEvent.Resumed -> {
                require(phase == RunPhase.PAUSED)
                metrics.resume(event.time)
                phase = RunPhase.RUNNING
            }

            is RunEvent.LapMarked -> {
                workout.marked(event, metrics.totals(event.time))
                metrics.markLap(event.time)
            }

            is RunEvent.Stopped -> {
                metrics.stop(event.time)
                phase = RunPhase.STOPPED
            }

            is RunEvent.SourceSwitched -> {
                gate.restore(event)
                metrics.sourceSwitched(event.metric, event.time)
                current = current.cleared(event.metric)
            }

            is RunEvent.SensorConnected, is RunEvent.SensorLost -> Unit
        }
        this.run = run + event
    }

    private fun onSample(sample: Sample): Boolean {
        val collecting = phase != RunPhase.IDLE && phase != RunPhase.STOPPED
        if (!collecting || run?.startedAt?.let {
                sample.time < it
            } == true || !gate.observe(sample)
        ) {
            return false
        }
        val time = maxOf(sample.time, now ?: sample.time)
        arbitrate(time, listOf(sample.metric))
        val accepted = gate.admit(sample)
        // A late sample of a still silent source counts but is too old to show.
        if (accepted) {
            applySample(sample, display = gate.isLive(sample.metric))
            if (settings.runSettings.autoPause && sample is SpeedSample) {
                autoPause.command(sample, state, time)?.let { command ->
                    markAutomaticLaps(command.time)
                    record(command)
                }
            }
        }
        return accepted
    }

    private fun applySample(sample: Sample, display: Boolean = true) {
        if (display) current = current.updatedWith(sample)
        metrics?.onSample(sample)
        advanceClock(sample.time)
    }

    private fun onTick(time: Timestamp): Boolean = advanceClock(time).also { recording ->
        if (recording) arbitrate(requireNotNull(now))
    }

    private fun onSensor(event: SensorEvent): Boolean {
        if (!advanceClock(event.time)) return false
        gate.sensor(event)
        arbitrate(requireNotNull(now))
        return true
    }

    private fun arbitrate(time: Timestamp, metrics: Iterable<Metric> = Metric.entries) {
        val decisions = gate.switches(time, metrics)
        decisions.silenced.forEach { current = current.cleared(it) }
        decisions.switches.forEach { event ->
            if (run == null) {
                current = current.cleared(event.metric)
            } else {
                restoreEvent(event.copy(time = maxOf(event.time, run!!.events.last().time)))
            }
        }
    }

    /** Journal decisions are authoritative: do not recompute switches from an admitted-only history. */
    internal fun restoreSample(sample: Sample): Boolean = gate.restoreSample(sample).also {
        if (it) applySample(sample)
    }

    internal fun restoreSensor(event: SensorEvent) {
        gate.sensor(event)
        advanceClock(event.time)
    }

    internal fun restoreClock(time: Timestamp) {
        advanceClock(time)
    }

    /** Moves "now" forward while the run is recording; it never goes back. */
    private fun advanceClock(time: Timestamp): Boolean {
        val recording = phase == RunPhase.RUNNING || phase == RunPhase.PAUSED
        if (recording) now = now?.let { maxOf(it, time) } ?: time
        return recording
    }
}

/** Phases in which the workout settings of the next run can still change. */
private val CONFIGURABLE_PHASES = setOf(RunPhase.IDLE, RunPhase.PREPARING)
