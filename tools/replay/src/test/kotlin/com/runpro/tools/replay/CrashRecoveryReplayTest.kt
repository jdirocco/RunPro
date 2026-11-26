package com.runpro.tools.replay

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.journal.MonotonicClock
import com.runpro.core.model.RunEvent
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.JournalRecovery
import com.runpro.core.recording.RecordingEngine
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RecoveryMode
import com.runpro.core.recording.RunPhase
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.io.TempDir

/** R2: real framed journal bytes, real engines, unflushed buffers discarded at death. */
class CrashRecoveryReplayTest {
    @Test
    fun `twenty seeded random process deaths lose at most five seconds and continue the same run`(
        @TempDir dir: Path
    ) {
        val trace = Trace.read(dirOfTraces().resolve(SyntheticTraces.EASY_RUN))
        val start = trace.records.first().time
        val random = Random(9)
        // One death in each of twenty separate windows, including fractional seconds between samples.
        val deaths = (0 until 20).map {
            (it * 90_000L + random.nextLong(1_100, 89_000)).milliseconds
        }
        val baselineSamples: Set<Sample>
        val baselineDistance: Double
        RecoveringConsumer(dir.resolve("baseline.journal"), start).use { baseline ->
            val clean = Replayer(trace)
            baseline.bind(clean)
            clean.run()
            baseline.engine.submit(EngineInput.Stop(start + 1800.seconds))
            baselineDistance = baseline.engine.state.value.totals.distance.meters
            baselineSamples = JournalReader.read(baseline.file).records
                .filterIsInstance<JournalRecord.Samples>().flatMap { it.samples }.toSet()
        }
        RecoveringConsumer(dir.resolve("run.journal"), start, baselineSamples).use { consumer ->
            val replayer =
                Replayer(
                    trace,
                    chaos = ChaosPlan(*deaths.map(ReplayFault::ProcessDeath).toTypedArray()),
                    faultHandler = consumer
                )
            consumer.bind(replayer)
            replayer.run()
            assertEquals(20, consumer.restarts)
            assertEquals(RunPhase.RUNNING, consumer.engine.state.value.phase)
            assertTrue(consumer.engine.submit(EngineInput.Stop(start + 1800.seconds)))
            assertEquals(RunPhase.STOPPED, consumer.engine.state.value.phase)
            val contents = JournalReader.read(consumer.file)
            assertTrue(contents.isStopped)
            assertEquals(1, contents.records.filterIsInstance<JournalRecord.RunStarted>().size)
            assertEquals(1800.seconds, consumer.engine.state.value.totals.timer)
            assertEquals(baselineDistance, consumer.engine.state.value.totals.distance.meters, 0.01)
            assertEquals(
                1,
                contents.runEvents.count {
                    it is RunEvent.Stopped
                }
            )
        }
    }

    private fun dirOfTraces(): Path = Path.of(System.getProperty("runpro.traces"))

    private class RecoveringConsumer(
        val file: Path,
        private val start: Timestamp,
        private val baselineSamples: Set<Sample> = emptySet()
    ) : SensorListener,
        ReplayFaultHandler,
        AutoCloseable {
        private val sinceRestart = mutableListOf<Sample>()
        private var now = start.elapsedRealtimeNanos
        private val clock = MonotonicClock { now }
        private lateinit var replayer: Replayer
        var restarts = 0
            private set
        var engine = newEngine { RecordingSession(journal = JournalWriter.create(file, clock)) }
            private set

        init {
            engine.submit(EngineInput.Prepare(start))
            engine.submit(EngineInput.Start(start))
        }

        fun bind(replayer: Replayer) {
            this.replayer = replayer
            replayer.sources.forEach { it.start(this) }
        }

        override fun onSample(sample: Sample) {
            if (sample in baselineSamples) sinceRestart += sample
            now = maxOf(now, sample.time.elapsedRealtimeNanos)
            assertTrue(engine.submit(EngineInput.SampleReceived(sample)))
            assertTrue(engine.submit(EngineInput.Tick(sample.time)))
        }

        override fun onEvent(event: SensorEvent) = Unit

        override fun onFault(fault: ReplayFault, time: Timestamp) {
            assertTrue(fault is ReplayFault.ProcessDeath)
            now = time.elapsedRealtimeNanos
            replayer.sources.forEach { it.stop() }
            val before = engine.state.value
            val id = before.run!!.id
            engine.close() // Abort: must not flush the memory buffer as an orderly close would.
            assertFalse(engine.submit(EngineInput.Tick(time)))
            engine =
                newEngine {
                    JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT, clock = clock)
                }
            val restored = engine.state.value
            val loss = before.totals.elapsed - restored.totals.elapsed
            assertTrue(loss >= 0.seconds && loss <= 5.seconds, "death at $time lost $loss")
            assertEquals(id, restored.run!!.id)
            // Every sample older than the loss budget is retained, not merely the last timestamp.
            val saved = JournalReader.read(
                file
            ).records.filterIsInstance<JournalRecord.Samples>().flatMap {
                it.samples
            }
            val savedSet = saved.toSet()
            sinceRestart.filter { time.elapsedSince(it.time) >= 5.seconds }.forEach {
                assertTrue(it in savedSet, "death at $time lost old sample $it")
            }
            sinceRestart.clear()
            assertTrue(saved.isNotEmpty())
            assertTrue(time.elapsedSince(saved.maxOf { it.time }) <= 5.seconds)
            replayer.sources.forEach { it.start(this) }
            restarts++
        }

        private fun newEngine(factory: () -> RecordingSession) = RecordingEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = factory
        )

        override fun close() = engine.close()
    }
}
