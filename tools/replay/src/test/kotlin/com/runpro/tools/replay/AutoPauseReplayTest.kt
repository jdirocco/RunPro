package com.runpro.tools.replay

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunSettings
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RunState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class AutoPauseReplayTest {
    @Test
    fun `automatic pause and resume rebuild identically from a real journal`(@TempDir dir: Path) {
        val file = dir.resolve("auto.journal")
        val state = record(file, ChaosPlan())
        val pauses = state.run!!.events.filterIsInstance<RunEvent.Paused>()
        assertEquals(listOf(PauseReason.AUTO), pauses.map { it.reason })
        assertEquals(5.seconds, pauses.single().time.elapsedSince(at(0)))
        assertEquals(10.seconds, state.totals.timer)
        val rebuilt = RecordedRun.from(JournalReader.read(file)).state
        assertEquals(state.run, rebuilt.run)
        assertEquals(state.totals, rebuilt.totals)
        assertEquals(state.laps, rebuilt.laps)
    }

    @Test
    fun `silent speed sensor during low speed cannot trigger automatic pause`(@TempDir dir: Path) {
        val state = record(
            dir.resolve("silent.journal"),
            ChaosPlan(ReplayFault.SilentStall(3.seconds, 5.seconds, SourceId.HEALTH_SERVICES))
        )
        assertTrue(state.run!!.events.none { it is RunEvent.Paused || it is RunEvent.Resumed })
        assertEquals(15.seconds, state.totals.timer)
    }

    private fun record(file: Path, chaos: ChaosPlan): RunState {
        val trace = Trace(
            TraceHeader(name = "Auto-pause at a traffic light"),
            (0..15).map { second ->
                TraceRecord.SampleRecord(
                    SpeedSample(
                        at(second),
                        SourceId.HEALTH_SERVICES,
                        Speed(
                            if (second <
                                10
                            ) {
                                0.0
                            } else {
                                2.0
                            }
                        )
                    )
                )
            }
        )
        RecordingSession(journal = JournalWriter.create(file)).use { session ->
            session.handle(
                EngineInput.ConfigureWorkout(
                    WorkoutSettings(runSettings = RunSettings(autoPause = true))
                )
            )
            session.handle(EngineInput.Prepare(at(0)))
            session.handle(EngineInput.Start(at(0)))
            val listener = object : SensorListener {
                override fun onSample(sample: Sample) {
                    session.handle(EngineInput.SampleReceived(sample))
                }
                override fun onEvent(event: SensorEvent) {
                    session.handle(EngineInput.SensorReceived(event))
                }
            }
            val replayer = Replayer(trace, chaos = chaos)
            replayer.sources.forEach { it.start(listener) }
            replayer.run()
            session.handle(EngineInput.Stop(at(15)))
            assertTrue(session.state.journalSaved)
            return session.state
        }
    }

    private fun at(seconds: Int) = Timestamp(seconds * 1_000_000_000L, seconds * 1000L)
}
