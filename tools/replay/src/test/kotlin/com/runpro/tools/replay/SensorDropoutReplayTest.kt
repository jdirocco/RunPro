package com.runpro.tools.replay

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Metric
import com.runpro.core.model.RunEvent
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.SwitchReason
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RunPhase
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class SensorDropoutReplayTest {
    @Test
    fun `committed dropout trace falls back for silence disconnect and lost contact`(
        @TempDir dir: Path
    ) {
        val trace = Trace.read(
            Path.of(System.getProperty("runpro.traces"), SyntheticTraces.STRAP_DROPOUT)
        )
        verify(trace, ChaosPlan(), dir.resolve("dropout.journal"), 3)
    }

    @Test
    fun `GATT 133 silent stall and GPS loss keep recording and recover sources`(
        @TempDir dir: Path
    ) {
        val trace = Trace.read(
            Path.of(System.getProperty("runpro.traces"), SyntheticTraces.EASY_RUN)
        )
        verify(
            trace,
            ChaosPlan(
                ReplayFault.BleDisconnect(30.seconds, 20.seconds, SyntheticTraces.STRAP, 133),
                ReplayFault.SilentStall(60.seconds, 20.seconds, SyntheticTraces.STRAP),
                ReplayFault.GpsLoss(90.seconds, 20.seconds)
            ),
            dir.resolve("chaos.journal"),
            2
        )
    }

    private fun verify(trace: Trace, chaos: ChaosPlan, file: Path, fallbacks: Int) {
        val start = trace.records.first().time
        val session = RecordingSession(journal = JournalWriter.create(file))
        session.handle(EngineInput.Prepare(start))
        session.handle(EngineInput.Start(start))
        val replayer = Replayer(trace, chaos = chaos)
        replayer.sources.forEach { source ->
            source.start(object : SensorListener {
                override fun onSample(sample: Sample) {
                    session.handle(EngineInput.SampleReceived(sample))
                    session.handle(EngineInput.Tick(sample.time))
                    assertEquals(RunPhase.RUNNING, session.state.phase)
                }
                override fun onEvent(event: SensorEvent) {
                    session.handle(EngineInput.SensorReceived(event))
                    assertEquals(RunPhase.RUNNING, session.state.phase)
                }
            })
        }
        session.use {
            replayer.run()
            val stop = trace.records.maxOf { it.time } + 1.seconds
            session.handle(EngineInput.Stop(stop))
            assertEquals(stop.elapsedSince(start), session.state.totals.timer)
        }
        val events = session.state.run!!.events
        assertFalse(events.any { it is RunEvent.Paused || it is RunEvent.Resumed })
        val hrSwitches = events.filterIsInstance<RunEvent.SourceSwitched>()
            .filter { it.metric == Metric.HEART_RATE }
        assertEquals(
            fallbacks,
            hrSwitches.count {
                it.from == SyntheticTraces.STRAP && it.to == SourceId.HEALTH_SERVICES &&
                    it.reason == SwitchReason.STALE
            }
        )
        assertEquals(
            fallbacks + 1,
            hrSwitches.count {
                it.to == SyntheticTraces.STRAP && it.reason == SwitchReason.HIGHER_PRIORITY
            }
        )
        assertTrue(session.state.journalSaved)
        val recorded = RecordedRun.from(JournalReader.read(file))
        assertEquals(events, recorded.run.events)
        assertEquals(session.state.totals, recorded.state.totals)
        assertEquals(session.state.laps, recorded.state.laps)
    }
}
