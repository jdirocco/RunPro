package com.runpro.tools.replay

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.AlertSettings
import com.runpro.core.model.HeartRateZones
import com.runpro.core.model.HrZoneMethod
import com.runpro.core.model.Pace
import com.runpro.core.model.PaceZones
import com.runpro.core.model.RunSettings
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.UserProfile
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.AlertKind
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class AlertsReplayTest {
    @Test
    fun `strap dropout trace fires sensor lost and back for silence disconnect and lost contact`(
        @TempDir dir: Path
    ) {
        val trace = readTrace(SyntheticTraces.STRAP_DROPOUT)
        val file = dir.resolve("dropout-alerts.journal")
        val settings = RunSettings(alerts = AlertSettings(cooldownSeconds = 30))
        val state = replay(trace, ChaosPlan(), file, settings)

        val sensorLost = state.alerts.filter { it.kind == AlertKind.SENSOR_LOST }
        val sensorBack = state.alerts.filter { it.kind == AlertKind.SENSOR_BACK }
        assertEquals(3, sensorLost.size)
        assertEquals(3, sensorBack.size)
        assertTrue(sensorLost.all { it.source == SyntheticTraces.STRAP })
        assertTrue(sensorBack.all { it.source == SyntheticTraces.STRAP })
        assertTrue(
            state.alerts.none { it.kind == AlertKind.GPS_LOST || it.kind == AlertKind.GPS_BACK }
        )

        val rebuilt = RecordedRun.from(JournalReader.read(file))
        assertEquals(state.run, rebuilt.run)
        assertEquals(state.totals, rebuilt.state.totals)
        assertTrue(rebuilt.state.alerts.isEmpty())
    }

    @Test
    fun `intervals trace fires rate-limited hr and pace alerts from configured zones`(
        @TempDir dir: Path
    ) {
        val trace = readTrace(SyntheticTraces.INTERVALS)
        val file = dir.resolve("intervals-alerts.journal")
        val profile = UserProfile(
            maxHeartRate = 190,
            lactateThresholdHeartRate = 170,
            thresholdPace = Pace(300.0)
        )
        val hrZones = HeartRateZones.fromLactateThreshold(170)
        val paceZones = PaceZones.fromThreshold(Pace(300.0))
        // Target HR zone 2 (145..152) and Pace zone 2 (5:42..6:27/km) across 4:00 work / 6:30 jog.
        val alertSettings = AlertSettings(cooldownSeconds = 60)
            .withHrZone(hrZones, 2)
            .withPaceZone(paceZones, 2)
        val settings = RunSettings(
            profile = profile,
            hrZoneMethod = HrZoneMethod.LTHR,
            alerts = alertSettings
        )
        val state = replay(trace, ChaosPlan(), file, settings)

        val byKind = state.alerts.groupBy { it.kind }
        assertTrue(byKind[AlertKind.HR_HIGH].orEmpty().isNotEmpty(), "Expected HR_HIGH alerts")
        assertTrue(byKind[AlertKind.HR_LOW].orEmpty().isNotEmpty(), "Expected HR_LOW alerts")
        assertTrue(
            byKind[AlertKind.PACE_TOO_FAST].orEmpty().isNotEmpty(),
            "Expected PACE_TOO_FAST alerts"
        )
        assertTrue(
            byKind[AlertKind.PACE_TOO_SLOW].orEmpty().isNotEmpty(),
            "Expected PACE_TOO_SLOW alerts"
        )
        // Rate limit invariant: consecutive alerts of the same kind are >= cooldown apart.
        byKind.forEach { (kind, list) ->
            list.zipWithNext().forEach { (first, second) ->
                assertTrue(
                    second.time.elapsedSince(first.time) >= 60.seconds,
                    "Rate limit violated for $kind: ${first.time} -> ${second.time}"
                )
            }
        }
        val rebuilt = RecordedRun.from(JournalReader.read(file))
        assertEquals(settings, rebuilt.state.workoutSettings.runSettings)
    }

    @Test
    fun `chaos ble disconnect stall gps loss and clock jump fire alerts without stopping the run`(
        @TempDir dir: Path
    ) {
        val trace = readTrace(SyntheticTraces.EASY_RUN)
        val file = dir.resolve("chaos-alerts.journal")
        val chaos = ChaosPlan(
            ReplayFault.BleDisconnect(60.seconds, 20.seconds, SyntheticTraces.STRAP, 133),
            ReplayFault.SilentStall(180.seconds, 20.seconds, SyntheticTraces.STRAP),
            ReplayFault.GpsLoss(300.seconds, 30.seconds),
            ReplayFault.ClockJump(310.seconds, (-3600).seconds)
        )
        val state = replay(
            trace,
            chaos,
            file,
            RunSettings(alerts = AlertSettings(cooldownSeconds = 30))
        )

        assertEquals(2, state.alerts.count { it.kind == AlertKind.SENSOR_LOST })
        assertEquals(2, state.alerts.count { it.kind == AlertKind.SENSOR_BACK })
        assertEquals(1, state.alerts.count { it.kind == AlertKind.GPS_LOST })
        assertEquals(1, state.alerts.count { it.kind == AlertKind.GPS_BACK })

        val rebuilt = RecordedRun.from(JournalReader.read(file))
        assertEquals(state.run, rebuilt.run)
        assertEquals(state.totals, rebuilt.state.totals)
    }

    private fun readTrace(name: String): Trace =
        Trace.read(Path.of(System.getProperty("runpro.traces"), name))

    private fun replay(
        trace: Trace,
        chaos: ChaosPlan,
        file: Path,
        settings: RunSettings
    ): RunState {
        val start = trace.records.first().time
        RecordingSession(journal = JournalWriter.create(file)).use { session ->
            session.handle(
                EngineInput.ConfigureWorkout(WorkoutSettings(runSettings = settings))
            )
            session.handle(EngineInput.Prepare(start))
            session.handle(EngineInput.Start(start))
            val listener = object : SensorListener {
                override fun onSample(sample: Sample) {
                    session.handle(EngineInput.SampleReceived(sample))
                    session.handle(EngineInput.Tick(sample.time))
                    assertEquals(RunPhase.RUNNING, session.state.phase)
                }

                override fun onEvent(event: SensorEvent) {
                    session.handle(EngineInput.SensorReceived(event))
                    assertEquals(RunPhase.RUNNING, session.state.phase)
                }
            }
            val replayer = Replayer(trace, chaos = chaos)
            replayer.sources.forEach { it.start(listener) }
            replayer.run()
            val stop = trace.records.maxOf { it.time } + 1.seconds
            session.handle(EngineInput.Stop(stop))
            assertTrue(session.state.journalSaved)
            return session.state
        }
    }
}
