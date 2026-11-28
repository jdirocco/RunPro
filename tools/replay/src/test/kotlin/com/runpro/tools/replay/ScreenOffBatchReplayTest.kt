package com.runpro.tools.replay

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Metric
import com.runpro.core.model.RunEvent
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RunPhase
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * Issue #108: with the screen off, Health Services delivers its samples late, in one batch when the screen
 * turns on, while the service keeps ticking. Every batched sample must reach the run and its journal.
 */
class ScreenOffBatchReplayTest {
    @Test
    fun `samples batched while the screen is off are all recorded`(@TempDir dir: Path) {
        val trace = Trace.read(
            Path.of(System.getProperty("runpro.traces"), SyntheticTraces.SCREEN_OFF)
        )
        val file = dir.resolve("screen-off.journal")
        val start = trace.records.first().time
        val session = RecordingSession(journal = JournalWriter.create(file))
        session.handle(EngineInput.Prepare(start))
        session.handle(EngineInput.Start(start))
        var clock = start

        // The service ticks once a second whatever the screen does; a record arrives at its delivery time.
        fun tickUntil(time: Timestamp) {
            while (clock + 1.seconds <= time) {
                clock += 1.seconds
                session.handle(EngineInput.Tick(clock))
            }
        }
        val rejected = mutableListOf<Sample>()
        val replayer = Replayer(trace)
        replayer.source(SourceId.HEALTH_SERVICES).start(object : SensorListener {
            override fun onSample(sample: Sample) {
                tickUntil(SyntheticTraces.screenOffDelivery(sample.time))
                if (!session.handle(EngineInput.SampleReceived(sample))) rejected += sample
                assertEquals(RunPhase.RUNNING, session.state.phase)
            }

            override fun onEvent(event: SensorEvent) {
                tickUntil(SyntheticTraces.screenOffDelivery(event.time))
                session.handle(EngineInput.SensorReceived(event))
            }
        })
        val stop = trace.records.maxOf { it.time } + 1.seconds
        session.use {
            replayer.run()
            tickUntil(stop)
            session.handle(EngineInput.Stop(stop))
        }

        assertEquals(emptyList(), rejected, "batched samples were dropped")
        val distances = trace.samples.filterIsInstance<DistanceSample>()
        val walked = distances.last().total.meters - distances.first().total.meters
        assertEquals(walked, session.state.totals.distance.meters, 1e-6)
        assertEquals(stop.elapsedSince(start), session.state.totals.timer)
        assertEquals(301.seconds, session.state.totals.moving)
        val events = session.state.run!!.events
        assertTrue(events.none { it is RunEvent.Paused || it is RunEvent.Resumed })
        // Each metric selects the watch once and keeps it: screen-off silence is not a switch (R5).
        val switches = events.filterIsInstance<RunEvent.SourceSwitched>()
        assertEquals(
            listOf(
                Metric.HEART_RATE,
                Metric.LOCATION,
                Metric.DISTANCE,
                Metric.SPEED,
                Metric.CADENCE
            )
                .map { it to SourceId.HEALTH_SERVICES },
            switches.map { it.metric to it.to }
        )

        // The journal holds every sample, and recovery rebuilds the same run (R2/R3).
        assertTrue(session.state.journalSaved)
        val recorded = RecordedRun.from(JournalReader.read(file))
        assertEquals(trace.samples.size, recorded.samples.size)
        assertEquals(events, recorded.run.events)
        assertEquals(session.state.totals, recorded.state.totals)
    }
}
