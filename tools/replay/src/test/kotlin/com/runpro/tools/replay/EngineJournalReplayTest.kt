package com.runpro.tools.replay

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.RecordingEngine
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RecordingStateMachine
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.io.TempDir

class EngineJournalReplayTest {
    @Test
    fun `dropout replay reads back identical samples sensor reports and run events`(
        @TempDir dir: Path
    ) {
        val trace = Trace.read(
            Path.of(System.getProperty("runpro.traces"), SyntheticTraces.STRAP_DROPOUT)
        )
        val start = trace.records.first().time
        val id = RunId("replay")
        var now = start.elapsedRealtimeNanos
        val baseline = RecordingStateMachine(runIds = { id })
        val samples = mutableListOf<Sample>()
        val sensors = mutableListOf<SensorEvent>()
        val file = dir.resolve("$id.journal")
        RecordingEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = {
                RecordingSession(runIds = { id }, journal = JournalWriter.create(file, { now }))
            }
        ).use { engine ->
            fun accept(input: EngineInput) {
                if (baseline.handle(input)) {
                    when (input) {
                        is EngineInput.SampleReceived -> samples += input.sample
                        is EngineInput.SensorReceived -> sensors += input.event
                        else -> Unit
                    }
                }
                assertTrue(engine.submit(input))
            }
            accept(EngineInput.Prepare(start))
            accept(EngineInput.Start(start))
            val commands = ArrayDeque(
                listOf(
                    EngineInput.Pause(start + 100.seconds),
                    EngineInput.Resume(start + 110.seconds),
                    EngineInput.MarkLap(start + 200.seconds)
                )
            )
            val replay = Replayer(trace)
            replay.sources.forEach { source ->
                source.start(object : SensorListener {
                    override fun onSample(sample: Sample) {
                        now = maxOf(now, sample.time.elapsedRealtimeNanos)
                        while (commands.firstOrNull()?.time?.let { it <= sample.time } == true) {
                            accept(commands.removeFirst())
                        }
                        accept(EngineInput.SampleReceived(sample))
                        accept(EngineInput.Tick(sample.time))
                    }
                    override fun onEvent(event: SensorEvent) {
                        now = maxOf(now, event.time.elapsedRealtimeNanos)
                        accept(EngineInput.SensorReceived(event))
                    }
                })
            }
            replay.run()
            accept(EngineInput.Stop(trace.records.maxOf { it.time }))
            assertNull(engine.failure.value)
            assertTrue(engine.state.value.journalSaved)
            val contents = JournalReader.read(file)
            assertEquals(baseline.state.run!!.events, contents.runEvents)
            assertEquals(
                samples,
                contents.records.filterIsInstance<JournalRecord.Samples>().flatMap {
                    it.samples
                }
            )
            assertTrue(sensors.isNotEmpty())
            val recorded = RecordedRun.from(contents)
            assertEquals(sensors, recorded.sensorEvents)
            assertEquals(baseline.state.totals, recorded.state.totals)
            assertEquals(baseline.state.laps, recorded.state.laps)
        }
    }
}
