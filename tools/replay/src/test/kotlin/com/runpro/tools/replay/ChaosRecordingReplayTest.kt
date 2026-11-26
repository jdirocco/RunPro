package com.runpro.tools.replay

import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingEngine
import com.runpro.core.recording.RunPhase
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers

/** Infrastructure samples, using an input-log journal double until #8/#9 supply real persistence. */
class ChaosRecordingReplayTest {
    @Test
    fun `a committed trace runs through the engine with every fault type`() {
        val original = Trace.read(
            Paths.get(System.getProperty("runpro.traces"), SyntheticTraces.EASY_RUN)
        )
        val start = original.records.first().time
        val trace = original.copy(
            records = original.records.takeWhile {
                it.time.elapsedSince(start) <
                    10.seconds
            }
        )
        val faults = listOf(
            ReplayFault.ProcessDeath(2500.milliseconds),
            ReplayFault.BleDisconnect(2.seconds, 2.seconds, SyntheticTraces.STRAP),
            ReplayFault.BleDisconnect(
                2.seconds,
                2.seconds,
                SyntheticTraces.STRAP,
                gattStatus = 133
            ),
            ReplayFault.SilentStall(2.seconds, 2.seconds, SyntheticTraces.STRAP),
            ReplayFault.GpsLoss(2.seconds, 2.seconds),
            ReplayFault.ClockJump(2.seconds, (-3600).seconds),
            ReplayFault.StorageFull(2500.milliseconds)
        )
        for (fault in faults) {
            JournalConsumer(start).use { consumer ->
                val replayer = Replayer(trace, chaos = ChaosPlan(fault), faultHandler = consumer)
                consumer.bind(replayer)
                replayer.run()
                assertEquals(
                    listOf(fault).filterIsInstance<ReplayFault.ProcessDeath>().size,
                    consumer.restarts
                )
                if (fault is ReplayFault.StorageFull) {
                    assertTrue(consumer.writeFailed)
                    assertEquals(RunPhase.STOPPED, consumer.engine.state.value.phase)
                    assertEquals(
                        StopReason.STORAGE_FULL,
                        (consumer.engine.state.value.run!!.events.last() as RunEvent.Stopped).reason
                    )
                    assertTrue(consumer.saved.isNotEmpty(), "the prior journal remains available")
                } else {
                    assertEquals(RunPhase.RUNNING, consumer.engine.state.value.phase, "$fault")
                    assertEquals(
                        9.seconds,
                        consumer.engine.state.value.totals.timer,
                        "$fault"
                    )
                    consumer.stop(start + 10.seconds)
                    assertEquals(10.seconds, consumer.engine.state.value.totals.timer, "$fault")
                }
            }
        }
    }

    @Test
    fun `repeated process death restores the engine from the journal with matching totals`() {
        val trace = SyntheticTraces.easyRun()
        val start = trace.records.first().time
        JournalConsumer(start).use { baseline ->
            val clean = Replayer(trace)
            baseline.bind(clean)
            clean.run()
            JournalConsumer(start).use { recovering ->
                val chaos = Replayer(
                    trace,
                    chaos = ChaosPlan(
                        ReplayFault.ProcessDeath(1.seconds),
                        ReplayFault.ProcessDeath(2.seconds)
                    ),
                    faultHandler = recovering
                )
                recovering.bind(chaos)
                chaos.run()
                assertEquals(2, recovering.restarts)
                assertEquals(baseline.engine.state.value, recovering.engine.state.value)
                assertEquals(
                    baseline.saved,
                    recovering.saved,
                    "recovery must not duplicate journal inputs"
                )
            }
        }
    }

    /** Persists accepted inputs before submitting them. It intentionally models no fsync/data-loss policy. */
    private class JournalConsumer(private val start: Timestamp) :
        SensorListener,
        ReplayFaultHandler,
        AutoCloseable {
        private val bytes = ByteArrayOutputStream()
        private val storage = FaultInjectingOutputStream(bytes)
        val saved = mutableListOf<EngineInput>()
        var engine = newEngine()
            private set
        var restarts = 0
            private set
        var writeFailed = false
            private set
        private lateinit var replayer: Replayer

        init {
            accept(EngineInput.Prepare(start))
            accept(EngineInput.Start(start))
        }

        fun bind(replayer: Replayer) {
            this.replayer = replayer
            replayer.sources.forEach { it.start(this) }
        }

        override fun onSample(sample: Sample) {
            if (writeFailed) return
            try {
                accept(EngineInput.SampleReceived(sample))
            } catch (_: IOException) {
                writeFailed = true
                stop(sample.time, StopReason.STORAGE_FULL)
            }
        }

        override fun onEvent(event: SensorEvent) =
            Unit // The current engine accepts samples, not sensor events.

        override fun onFault(fault: ReplayFault, time: Timestamp) {
            when (fault) {
                is ReplayFault.ProcessDeath -> restart()
                is ReplayFault.StorageFull -> storage.full = true
                else -> error("Unexpected callback: $fault")
            }
        }

        fun stop(time: Timestamp, reason: StopReason = StopReason.USER) {
            assertTrue(engine.submit(EngineInput.Stop(time, reason)))
        }

        private fun accept(input: EngineInput) {
            storage.write(1)
            saved += input
            assertTrue(engine.submit(input))
        }

        private fun restart() {
            replayer.sources.forEach { it.stop() }
            val dead = engine
            val before = dead.state.value
            dead.close()
            assertFalse(dead.submit(EngineInput.Tick(start)))
            engine = newEngine()
            assertNotSame(dead, engine)
            saved.forEach { assertTrue(engine.submit(it)) }
            assertEquals(before, engine.state.value, "restored exclusively from journal inputs")
            replayer.sources.forEach { it.start(this) }
            restarts++
        }

        // Keep the sample synchronous; :core:recording tests production threading separately.
        private fun newEngine() = RecordingEngine(
            runIds = { RunId("chaos-sample") },
            dispatcher = Dispatchers.Unconfined
        )

        override fun close() {
            engine.close()
            storage.close()
        }
    }
}
