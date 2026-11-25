package com.runpro.core.recording

import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Elevation
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunId
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp

/** Test time: [at] gives the timestamp [seconds] after an arbitrary monotonic origin. */
internal object TestTime {
    const val ORIGIN_NANOS = 100_000_000_000L
    const val ORIGIN_WALL_MILLIS = 1_764_064_800_000L
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val MILLIS_PER_SECOND = 1_000.0

    fun at(seconds: Double): Timestamp = Timestamp(
        elapsedRealtimeNanos = ORIGIN_NANOS + Math.round(seconds * NANOS_PER_SECOND),
        wallClockMillis = ORIGIN_WALL_MILLIS + Math.round(seconds * MILLIS_PER_SECOND)
    )

    fun at(seconds: Int): Timestamp = at(seconds.toDouble())
}

internal val WATCH = SourceId.HEALTH_SERVICES
internal val STRAP = SourceId.ble("AA:BB:CC:DD:EE:FF")
internal val FOOTPOD = SourceId.ble("11:22:33:44:55:66")

internal fun hr(seconds: Number, bpm: Int, source: SourceId = WATCH) = EngineInput.SampleReceived(
    HeartRateSample(TestTime.at(seconds.toDouble()), source, HeartRate(bpm))
)

internal fun dist(seconds: Number, meters: Double, source: SourceId = WATCH) =
    EngineInput.SampleReceived(
        DistanceSample(TestTime.at(seconds.toDouble()), source, Distance(meters))
    )

internal fun cad(seconds: Number, spm: Double, source: SourceId = WATCH) =
    EngineInput.SampleReceived(
        CadenceSample(TestTime.at(seconds.toDouble()), source, Cadence(spm))
    )

internal fun elev(seconds: Number, meters: Double, source: SourceId = WATCH) =
    EngineInput.SampleReceived(
        ElevationSample(TestTime.at(seconds.toDouble()), source, Elevation(meters))
    )

internal fun speed(seconds: Number, metersPerSecond: Double, source: SourceId = WATCH) =
    EngineInput.SampleReceived(
        SpeedSample(TestTime.at(seconds.toDouble()), source, Speed(metersPerSecond))
    )

internal fun prepare(seconds: Number) = EngineInput.Prepare(TestTime.at(seconds.toDouble()))

internal fun start(seconds: Number) = EngineInput.Start(TestTime.at(seconds.toDouble()))

internal fun pause(seconds: Number, reason: PauseReason = PauseReason.MANUAL) =
    EngineInput.Pause(TestTime.at(seconds.toDouble()), reason)

internal fun resume(seconds: Number) = EngineInput.Resume(TestTime.at(seconds.toDouble()))

internal fun lap(seconds: Number) = EngineInput.MarkLap(TestTime.at(seconds.toDouble()))

internal fun stop(seconds: Number, reason: StopReason = StopReason.USER) =
    EngineInput.Stop(TestTime.at(seconds.toDouble()), reason)

internal fun tick(seconds: Number) = EngineInput.Tick(TestTime.at(seconds.toDouble()))

/** A state machine with predictable run ids (`run-1`, `run-2`, ...). */
internal fun machine(config: EngineConfig = EngineConfig()): RecordingStateMachine {
    var next = 0
    return RecordingStateMachine(config) { RunId("run-${++next}") }
}

/** Feeds [inputs] in order and returns the resulting state. */
internal fun RecordingStateMachine.feed(vararg inputs: EngineInput): RunState =
    feed(inputs.asList())

internal fun RecordingStateMachine.feed(inputs: Iterable<EngineInput>): RunState {
    inputs.forEach(::handle)
    return state
}

/** A machine that is already running since second 0. */
internal fun runningMachine(config: EngineConfig = EngineConfig()): RecordingStateMachine =
    machine(config).also { it.feed(prepare(-10), start(0)) }
