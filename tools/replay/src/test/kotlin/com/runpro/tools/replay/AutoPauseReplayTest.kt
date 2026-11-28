package com.runpro.tools.replay

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

    @Test
    fun `delayed speed pauses and resumes with ticks and HR ahead and preserves moving totals`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("delayed.journal")
        val state = record(file, ChaosPlan(), latency = 700.milliseconds)
        val events = state.run!!.events
        val pause = events.filterIsInstance<RunEvent.Paused>().single()
        assertEquals(PauseReason.AUTO, pause.reason)
        assertEquals(at(5), pause.time)
        assertEquals(at(10), events.filterIsInstance<RunEvent.Resumed>().single().time)
        assertEquals(10.seconds + 700.milliseconds, state.totals.timer)
        assertEquals(5.seconds, state.totals.moving)
        assertEquals(Distance(10.0), state.totals.distance)
        val rebuilt = RecordedRun.from(JournalReader.read(file)).state
        assertEquals(state.run, rebuilt.run)
        assertEquals(state.totals, rebuilt.totals)
        assertEquals(state.laps, rebuilt.laps)
    }

    private fun record(file: Path, chaos: ChaosPlan, latency: Duration = Duration.ZERO): RunState {
        val trace = Trace(
            TraceHeader(name = "Auto-pause at a traffic light"),
            (0..15).flatMap { second ->
                // Establish sources at start, then deliver speed/distance behind ticks and the strap.
                val delivery = at(second) + if (second == 0) Duration.ZERO else latency
                listOf(
                    HeartRateSample(delivery, SourceId("ble:strap"), HeartRate(140)),
                    SpeedSample(
                        at(second),
                        SourceId.HEALTH_SERVICES,
                        Speed(if (second < 10) 0.0 else 2.0)
                    ),
                    DistanceSample(
                        at(second),
                        SourceId.HEALTH_SERVICES,
                        Distance(maxOf(0, second - 10) * 2.0)
                    )
                ).map(TraceRecord::SampleRecord)
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
                    if (sample is HeartRateSample) session.handle(EngineInput.Tick(sample.time))
                    session.handle(EngineInput.SampleReceived(sample))
                }
                override fun onEvent(event: SensorEvent) {
                    session.handle(EngineInput.SensorReceived(event))
                }
            }
            val replayer = Replayer(trace, chaos = chaos)
            replayer.sources.forEach { it.start(listener) }
            replayer.run()
            session.handle(EngineInput.Stop(at(15) + latency))
            assertTrue(session.state.journalSaved)
            return session.state
        }
    }

    private fun at(seconds: Int) = Timestamp(seconds * 1_000_000_000L, seconds * 1000L)
}
