package com.runpro.core.fit

import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Acceleration
import com.runpro.core.model.AccelerationSample
import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Elevation
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Metric
import com.runpro.core.model.PauseReason
import com.runpro.core.model.Power
import com.runpro.core.model.PowerSample
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorLossReason
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.StopReason
import com.runpro.core.model.SwitchReason
import com.runpro.core.model.Timestamp
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingSession
import com.runpro.tools.replay.Trace
import com.runpro.tools.replay.TraceRecord
import java.nio.file.Path
import kotlin.time.Duration.Companion.nanoseconds

private const val NANOS_PER_SECOND = 1_000_000_000.0

/** A runner command [atSeconds] after the first record of a trace. */
internal data class Command(val atSeconds: Double, val input: (Timestamp) -> EngineInput)

internal fun lap(at: Double) = Command(at) { EngineInput.MarkLap(it) }

internal fun pause(at: Double, reason: PauseReason = PauseReason.MANUAL) =
    Command(at) { EngineInput.Pause(it, reason) }

internal fun resume(at: Double) = Command(at) { EngineInput.Resume(it) }

internal fun stop(at: Double) = Command(at) { EngineInput.Stop(it) }

/**
 * Records [trace] through a real [RecordingSession] into the journal [file], the way the watch does: every
 * sample is an engine input, each command is given right before the first record later than it, a tick
 * follows every record. The last command must be a stop.
 */
internal fun recordJournal(
    trace: Trace,
    commands: List<Command>,
    file: Path,
    runId: String = "run-1"
) {
    val start = trace.records.first().time
    fun timeOf(command: Command) =
        start + (command.atSeconds * NANOS_PER_SECOND).toLong().nanoseconds
    val pending = ArrayDeque(commands)
    RecordingSession(runIds = {
        RunId(runId)
    }, journal = JournalWriter.create(file)).use { session ->
        session.handle(EngineInput.ConfigureWorkout(WorkoutSettings(autoLapMeters = null)))
        check(session.handle(EngineInput.Prepare(start)))
        check(session.handle(EngineInput.Start(start)))
        for (record in trace.records) {
            while (pending.isNotEmpty() && timeOf(pending.first()) < record.time) {
                val command = pending.removeFirst()
                check(session.handle(command.input(timeOf(command)))) {
                    "Command at ${command.atSeconds} rejected"
                }
            }
            if (record is TraceRecord.SampleRecord) {
                session.handle(
                    EngineInput.SampleReceived(record.sample)
                )
            }
            session.handle(EngineInput.Tick(record.time))
        }
        while (pending.isNotEmpty()) {
            val command = pending.removeFirst()
            check(session.handle(command.input(timeOf(command))))
        }
    }
}

internal const val ORIGIN_NANOS = 100_000_000_000L
internal const val ORIGIN_WALL_MILLIS = 1_764_064_800_000L

/** [seconds] after an arbitrary origin, on both clocks (plus [wallJumpMillis] on the wall clock only). */
internal fun at(seconds: Double, wallJumpMillis: Long = 0): Timestamp = Timestamp(
    elapsedRealtimeNanos = ORIGIN_NANOS + Math.round(seconds * NANOS_PER_SECOND),
    wallClockMillis = ORIGIN_WALL_MILLIS + Math.round(seconds * 1000) + wallJumpMillis
)

internal val WATCH = SourceId.HEALTH_SERVICES
internal val STRAP = SourceId.ble("AA:BB:CC:DD:EE:FF")
internal val FOOTPOD = SourceId.ble("11:22:33:44:55:66")

/**
 * A short run with every kind of journal record: all sample types from three sources, every run event
 * (auto and manual pauses, laps of every trigger, source switches both ways, sensor loss and reconnect),
 * every sensor event (GATT 133 included) and checkpoints. Strap samples after the switch are not admitted
 * by the engine yet (#19), so the switch events must survive even though the totals stay on the wrist.
 *
 * [wallJumpMillis] moves the wall clock (only) forward from second 30 on.
 */
internal fun everyRecordJournal(file: Path, wallJumpMillis: Long = 0) {
    val t = { seconds: Double -> at(seconds, if (seconds >= WALL_JUMP_S) wallJumpMillis else 0) }
    val events = everyEventBySecond(t)
    val records = buildList {
        add(JournalRecord.RunStarted(RunId("every-record"), t(0.0)))
        for (second in 0 until EVERY_RECORD_SECONDS) {
            add(JournalRecord.Samples(samplesAt(second, t)))
            addAll(events[second].orEmpty())
        }
        add(JournalRecord.Event(RunEvent.SensorLost(t(59.7), FOOTPOD, SensorLossReason.NO_DATA)))
        add(JournalRecord.Event(RunEvent.Stopped(t(60.0), StopReason.LOW_BATTERY)))
    }
    JournalWriter.create(file).use { writer -> records.forEach(writer::append) }
}

private const val WALL_JUMP_S = 30
private const val EVERY_RECORD_SECONDS = 60

/** One sample of every type in second [s]; a barometric elevation every 10 s. */
private fun samplesAt(s: Int, t: (Double) -> Timestamp): List<Sample> {
    val time = s.toDouble()
    val samples = listOf(
        HeartRateSample(t(time), WATCH, HeartRate(120 + s)),
        LocationSample(
            t(time + 0.2),
            WATCH,
            latitude = 45.464_2 + s * 1e-5,
            longitude = 9.19 + s * 1e-5,
            horizontalAccuracy = Distance(4.0),
            altitude = Elevation(120.0 + s * 0.1)
        ),
        DistanceSample(t(time + 0.3), WATCH, Distance(s * 3.0)),
        SpeedSample(t(time + 0.3), WATCH, Speed(3.0)),
        CadenceSample(t(time + 0.4), FOOTPOD, Cadence(171.3)),
        PowerSample(t(time + 0.4), FOOTPOD, Power(250 + s)),
        AccelerationSample(t(time + 0.5), SourceId.WATCH_IMU, Acceleration(0.1, -9.7, 1.2)),
        HeartRateSample(t(time + 0.6), STRAP, HeartRate(125 + s))
    )
    val elevation = ElevationSample(t(time + 0.7), WATCH, Elevation(119.0 + s))
    return if (s % 10 == 0) samples + elevation else samples
}

/** The run and sensor events journaled after the samples of each second. */
private fun everyEventBySecond(t: (Double) -> Timestamp): Map<Int, List<JournalRecord>> {
    fun run(event: RunEvent) = JournalRecord.Event(event)
    fun sensor(event: SensorEvent) = JournalRecord.Sensor(event)
    return mapOf(
        5 to listOf(sensor(SensorEvent.Connected(t(5.1), STRAP))),
        6 to listOf(run(RunEvent.SensorConnected(t(6.1), STRAP))),
        8 to listOf(
            run(
                RunEvent.SourceSwitched(
                    t(8.1),
                    Metric.HEART_RATE,
                    WATCH,
                    STRAP,
                    SwitchReason.HIGHER_PRIORITY
                )
            )
        ),
        10 to listOf(run(RunEvent.LapMarked(t(10.8), LapTrigger.MANUAL))),
        15 to listOf(run(RunEvent.Paused(t(15.8), PauseReason.AUTO))),
        18 to listOf(run(RunEvent.Resumed(t(18.8)))),
        20 to listOf(run(RunEvent.LapMarked(t(20.8), LapTrigger.DISTANCE))),
        22 to listOf(sensor(SensorEvent.Disconnected(t(22.1), STRAP, gattStatus = 133))),
        23 to listOf(
            run(RunEvent.SensorLost(t(23.1), STRAP, SensorLossReason.DISCONNECTED)),
            run(
                RunEvent.SourceSwitched(
                    t(23.2),
                    Metric.HEART_RATE,
                    STRAP,
                    WATCH,
                    SwitchReason.STALE
                )
            )
        ),
        25 to listOf(sensor(SensorEvent.GpsLost(t(25.1), WATCH))),
        27 to listOf(sensor(SensorEvent.GpsAcquired(t(27.1), WATCH))),
        30 to listOf(run(RunEvent.LapMarked(t(30.8), LapTrigger.TIME))),
        35 to listOf(run(RunEvent.Paused(t(35.8), PauseReason.MANUAL))),
        40 to listOf(run(RunEvent.Resumed(t(40.8)))),
        45 to
            listOf(
                run(
                    RunEvent.SourceSwitched(
                        t(45.1),
                        Metric.CADENCE,
                        FOOTPOD,
                        null,
                        SwitchReason.USER
                    )
                )
            ),
        50 to listOf(JournalRecord.Checkpoint(t(50.9)))
    )
}
