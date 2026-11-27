package com.runpro.core.recording

import com.runpro.core.journal.JournalOutput
import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.RunId
import com.runpro.core.model.SensorEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir

class RecordingJournalTest {
    @Test
    fun `start input reaches a synced file within one second on the engine thread`(
        @TempDir dir: Path
    ) = runBlocking {
        repeat(10) { index ->
            val file = dir.resolve("$index.journal")
            val tap = TimeSource.Monotonic.markNow()
            RecordingEngine(sessionFactory = {
                RecordingSession(journal = JournalWriter.create(file))
            }).use { engine ->
                engine.submit(prepare(0))
                engine.submit(start(0))
                withTimeout(1.seconds) { engine.state.first { it.phase == RunPhase.RUNNING } }
                assertTrue(tap.elapsedNow() < 1.seconds)
                assertEquals(
                    listOf(
                        JournalRecord.RunStarted(
                            engine.state.value.run!!.id,
                            TestTime.at(0),
                            engine.state.value.workoutSettings
                        )
                    ),
                    JournalReader.read(file).records
                )
            }
        }
    }

    @Test
    fun `replayed run journals exactly its accepted samples and events and closes on stop`() {
        val output = Output()
        val baseline = machine()
        val expected = mutableListOf<com.runpro.core.model.Sample>()
        RecordingEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = {
                RecordingSession(runIds = { RunId("run-1") }, journal = JournalWriter(output))
            }
        ).use { engine ->
            val inputs = SyntheticTraces.easyThirtyMinutes().toMutableList()
            inputs.add(80, hr(-20, 200, STRAP)) // Rejected competing source / old reading.
            inputs += hr(2000, 200) // Rejected after stop.
            for (input in inputs) {
                if (baseline.handle(input) && input is EngineInput.SampleReceived &&
                    baseline.state.run != null
                ) {
                    expected += input.sample
                }
                engine.submit(input)
            }
            val contents = output.read()
            assertEquals(baseline.state.run!!.events, contents.runEvents)
            assertEquals(
                expected,
                contents.records.filterIsInstance<JournalRecord.Samples>().flatMap {
                    it.samples
                }
            )
            assertEquals(baseline.state.totals, RecordedRun.from(contents).state.totals)
            assertTrue(output.closed)
            assertTrue(engine.state.value.journalSaved)
            // A closed journal does not close the engine.
            assertTrue(engine.submit(tick(2001)))
        }
    }

    @Test
    fun `adapter events are retained while running or paused but not outside a run`() {
        val output = Output()
        val session = RecordingSession(journal = JournalWriter(output))
        val connected = SensorEvent.Connected(TestTime.at(1), STRAP)
        val disconnected = SensorEvent.Disconnected(TestTime.at(3), STRAP, 133)
        session.handle(prepare(0))
        assertFalse(session.handle(EngineInput.SensorReceived(connected)))
        session.handle(start(0))
        assertTrue(session.handle(EngineInput.SensorReceived(connected)))
        session.handle(pause(2))
        assertTrue(session.handle(EngineInput.SensorReceived(disconnected)))
        session.handle(resume(4))
        session.handle(stop(5))
        assertFalse(session.handle(EngineInput.SensorReceived(connected)))
        assertEquals(listOf(connected, disconnected), RecordedRun.from(output.read()).sensorEvents)
        assertEquals(RunPhase.STOPPED, session.state.phase)
    }

    @Test
    fun `ticks flush samples at one second and sync at five during sensor silence`() {
        val output = Output()
        var now = 0L
        val session = RecordingSession(journal = JournalWriter(output, { now }))
        session.handle(prepare(0))
        session.handle(start(0))
        val sample = hr(0, 140)
        session.handle(sample)
        assertTrue(output.read().records.none { it is JournalRecord.Samples })
        now = 1.seconds.inWholeNanoseconds
        session.handle(tick(1))
        assertEquals(
            listOf(sample.sample),
            output.read().records.filterIsInstance<JournalRecord.Samples>().flatMap {
                it.samples
            }
        )
        assertTrue(output.readSynced().records.none { it is JournalRecord.Samples })
        now = 5.seconds.inWholeNanoseconds
        session.handle(tick(5))
        assertEquals(
            listOf(sample.sample),
            output.readSynced().records.filterIsInstance<JournalRecord.Samples>().flatMap {
                it.samples
            }
        )
        session.abort()
    }

    @Test
    fun `reset after a stopped file session creates a new journal without overwriting the first`(
        @TempDir dir: Path
    ) {
        val directory = dir.resolve("journals")
        RecordingEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = { RecordingSession.create(directory) }
        ).use { engine ->
            assertFalse(Files.exists(directory))
            engine.submit(prepare(0))
            assertFalse(Files.exists(directory))
            engine.submit(start(0))
            val first = engine.state.value.run!!.id
            engine.submit(stop(1))
            val file = directory.resolve("$first.journal")
            val bytes = Files.readAllBytes(file)
            engine.submit(EngineInput.Reset)
            engine.submit(prepare(2))
            engine.submit(start(2))
            assertEquals(RunPhase.RUNNING, engine.state.value.phase)
            val second = engine.state.value.run!!.id
            assertTrue(first != second)
            engine.submit(stop(3))
            assertTrue(engine.state.value.journalSaved)
            assertTrue(bytes.contentEquals(Files.readAllBytes(file)))
            assertTrue(JournalReader.read(directory.resolve("$second.journal")).isStopped)
        }
    }

    @Test
    fun `a cancelled warm-up writes nothing and the next start journals one run`(
        @TempDir dir: Path
    ) {
        val directory = dir.resolve("journals")
        RecordingEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = { RecordingSession.create(directory) }
        ).use { engine ->
            engine.submit(prepare(0))
            engine.submit(EngineInput.Reset)
            assertEquals(RunPhase.IDLE, engine.state.value.phase)
            assertFalse(Files.exists(directory))

            engine.submit(prepare(1))
            engine.submit(start(1))
            val run = engine.state.value.run!!.id
            engine.submit(stop(2))
            assertTrue(engine.state.value.journalSaved)
            assertEquals(
                listOf("$run.journal"),
                Files.list(directory).use { files ->
                    files.map { it.fileName.toString() }.toList()
                }
            )
        }
    }

    @Test
    fun `a recording run refuses reset, so its journal is never dropped`(@TempDir dir: Path) {
        val directory = dir.resolve("journals")
        RecordingEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = { RecordingSession.create(directory) }
        ).use { engine ->
            engine.submit(prepare(0))
            engine.submit(start(0))
            engine.submit(EngineInput.Reset)
            assertEquals(RunPhase.RUNNING, engine.state.value.phase)
        }
    }

    private class Output : JournalOutput {
        private val bytes = ByteArrayOutputStream()
        private var synced = byteArrayOf()
        var closed = false
        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            this.bytes.write(bytes, offset, length)
        override fun sync() {
            synced = bytes.toByteArray()
        }
        override fun close() {
            closed = true
        }
        fun read() = JournalReader.read(ByteArrayInputStream(bytes.toByteArray()))
        fun readSynced() = JournalReader.read(ByteArrayInputStream(synced))
    }
}
