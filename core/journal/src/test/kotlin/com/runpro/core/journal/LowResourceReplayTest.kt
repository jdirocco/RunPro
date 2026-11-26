package com.runpro.core.journal

import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.RunEvent
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.StopReason
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingEngine
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RunPhase
import com.runpro.tools.replay.ChaosPlan
import com.runpro.tools.replay.ReplayFault
import com.runpro.tools.replay.ReplayFaultHandler
import com.runpro.tools.replay.Replayer
import com.runpro.tools.replay.Trace
import com.runpro.tools.replay.TraceHeader
import com.runpro.tools.replay.TraceRecord
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.io.TempDir

/** R7: replay through real engines, writers, reserved file outputs and synced journal bytes. */
class LowResourceReplayTest {
    private val trace = Trace(
        TraceHeader(name = "low-resource"),
        (0..10).map {
            TraceRecord.SampleRecord(
                HeartRateSample(at(it), SourceId.HEALTH_SERVICES, HeartRate(140 + it))
            )
        }
    )

    @Test
    fun `battery warnings and critical stop save every accepted sample while running or paused`(
        @TempDir dir: Path
    ) {
        for (paused in listOf(false, true)) {
            val file = dir.resolve("battery-$paused.journal")
            var now = at(0).elapsedRealtimeNanos
            val engine = RecordingEngine(
                dispatcher = Dispatchers.Unconfined,
                sessionFactory = { RecordingSession(journal = JournalWriter.create(file, { now })) }
            )
            engine.use {
                engine.submit(EngineInput.Prepare(at(0)))
                engine.submit(EngineInput.Resources(at(0), batteryPercent = 16))
                engine.submit(EngineInput.Start(at(0)))
                val replay = Replayer(trace)
                replay.sources.forEach { source ->
                    source.start(
                        listener { sample ->
                            now = sample.time.elapsedRealtimeNanos
                            when (sample.time) {
                                at(1) -> engine.submit(EngineInput.MarkLap(at(1)))

                                at(2) -> {
                                    engine.submit(EngineInput.Resources(at(2), batteryPercent = 15))
                                    assertTrue(engine.state.value.resources.lowBattery)
                                    assertEquals(RunPhase.RUNNING, engine.state.value.phase)
                                }

                                at(4) -> {
                                    engine.submit(EngineInput.Resources(at(4), batteryPercent = 4))
                                    if (paused) engine.submit(EngineInput.Pause(at(4)))
                                }

                                at(6) -> {
                                    engine.submit(EngineInput.Resources(at(6), batteryPercent = 3))
                                }
                            }
                            engine.submit(EngineInput.SampleReceived(sample))
                            engine.submit(EngineInput.Tick(sample.time))
                        }
                    )
                }
                replay.run()
                assertNull(engine.failure.value)
                assertEquals(RunPhase.STOPPED, engine.state.value.phase)
                assertEquals((if (paused) 4 else 6).seconds, engine.state.value.totals.timer)
                assertEquals(2, engine.state.value.laps.size)
                assertSaved(file, StopReason.LOW_BATTERY, trace.samples.filter { it.time < at(6) })
            }
        }
    }

    @Test
    fun `storage full retries a partial write using reserve then stops and syncs the complete run`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("full.journal")
        var now = at(0).elapsedRealtimeNanos
        var full = false
        var failedWrites = 0
        val reserve = StorageReserve.create(file)
        val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        val output = FileJournalOutput(channel, reserve, writeChannel = { buffer ->
            if (full && Files.size(reserve.file) > 0) {
                // Exercise rollback after actual bytes have already reached the file.
                val partial = buffer.duplicate().apply { limit(position() + minOf(7, remaining())) }
                channel.write(partial)
                failedWrites++
                throw IOException("ENOSPC")
            }
            channel.write(buffer)
        })
        val engine = RecordingEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = { RecordingSession(journal = JournalWriter(output, { now })) }
        )
        engine.use {
            engine.submit(EngineInput.Prepare(at(0)))
            engine.submit(EngineInput.Start(at(0)))
            val replay = Replayer(
                trace,
                chaos = ChaosPlan(ReplayFault.StorageFull(2500.milliseconds)),
                faultHandler = ReplayFaultHandler { _, _ -> full = true }
            )
            replay.sources.forEach { source ->
                source.start(
                    listener { sample ->
                        now = sample.time.elapsedRealtimeNanos
                        engine.submit(EngineInput.SampleReceived(sample))
                        engine.submit(EngineInput.Tick(sample.time))
                    }
                )
            }
            replay.run()
            assertEquals(1, failedWrites)
            assertNull(engine.failure.value)
            assertEquals(RunPhase.STOPPED, engine.state.value.phase)
            assertEquals(3.seconds, engine.state.value.totals.timer)
            assertSaved(file, StopReason.STORAGE_FULL, trace.samples.filter { it.time <= at(3) })
        }
    }

    @Test
    fun `exhausted reserve exposes failure and never publishes a successful stop`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("exhausted.journal")
        val reserve = StorageReserve.create(file)
        val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        var full = false
        var failedWrites = 0
        val output = FileJournalOutput(channel, reserve, writeChannel = { buffer ->
            if (full) {
                failedWrites++
                throw IOException("ENOSPC")
            }
            channel.write(buffer)
        })
        RecordingEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = { RecordingSession(journal = JournalWriter(output)) }
        ).use { engine ->
            engine.submit(EngineInput.Prepare(at(0)))
            engine.submit(EngineInput.Start(at(0)))
            full = true
            engine.submit(EngineInput.Resources(at(1), batteryPercent = 3))
            assertEquals(2, failedWrites)
            assertIs<IOException>(engine.failure.value)
            assertEquals(RunPhase.RUNNING, engine.state.value.phase)
            assertFalse(engine.submit(EngineInput.Tick(at(2))))
            assertFalse(JournalReader.read(file).isStopped)
        }
    }

    private fun assertSaved(file: Path, reason: StopReason, samples: List<Sample>) {
        // Read before orderly engine shutdown: stop must already have flushed and fsynced everything.
        val contents = JournalReader.read(file)
        assertEquals(JournalEnd.Clean, contents.end)
        assertTrue(contents.isStopped)
        assertEquals(1, contents.runEvents.filterIsInstance<RunEvent.Started>().size)
        assertEquals(
            reason,
            contents.runEvents.filterIsInstance<RunEvent.Stopped>().single().reason
        )
        assertEquals(
            samples,
            contents.records.filterIsInstance<JournalRecord.Samples>().flatMap {
                it.samples
            }
        )
    }

    private fun listener(consume: (Sample) -> Unit) = object : SensorListener {
        override fun onSample(sample: Sample) = consume(sample)
        override fun onEvent(event: SensorEvent) = Unit
    }
}
