package com.runpro.core.recording

import com.runpro.core.journal.JournalOutput
import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.journal.MonotonicClock
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.SourceId
import com.runpro.core.model.StopReason
import com.runpro.core.recording.TestTime.at
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class JournalRecoveryTest {
    @Test
    fun `invalid histories are refused before changing the file`(@TempDir dir: Path) {
        val file = dir.resolve("invalid.journal")
        JournalWriter.create(file).use {
            it.append(JournalRecord.RunStarted(RunId("first"), at(0)))
            it.append(JournalRecord.RunStarted(RunId("second"), at(1)))
        }
        val before = Files.readAllBytes(file)
        assertFailsWith<IllegalStateException> { JournalRecovery.restore(file) }
        assertContentEquals(before, Files.readAllBytes(file))
    }

    @Test
    fun `saving keeps event metadata and uses the newest retained time after a late batch`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("late.journal")
        val events = listOf(
            RunEvent.LapMarked(at(10), LapTrigger.DISTANCE),
            RunEvent.SensorConnected(at(11), SourceId.HEALTH_SERVICES),
            RunEvent.Paused(at(20), PauseReason.AUTO)
        )
        JournalWriter.create(file).use {
            it.append(JournalRecord.RunStarted(RunId("run"), at(0)))
            events.forEach { event -> it.append(JournalRecord.Event(event)) }
            it.append(JournalRecord.Checkpoint(at(100)))
            it.append(JournalRecord.Samples(listOf(hr(50, 140).sample)))
        }
        JournalRecovery.restore(file).use {
            assertEquals(events, it.state.run!!.events.drop(1).dropLast(1))
            assertEquals(
                RunEvent.Stopped(at(100), StopReason.RECOVERY),
                it.state.run!!.events.last()
            )
            assertEquals(20.seconds, it.state.totals.timer)
            assertEquals(100.seconds, it.state.totals.elapsed)
        }
    }

    @Test
    fun `warmup samples cannot change the recovered run's source choices`(@TempDir dir: Path) {
        val file = dir.resolve("warmup.journal")
        val warmup = HeartRateSample(
            at(0),
            SourceId.ble("AA:BB:CC:DD:EE:FF"),
            HeartRate(150)
        )
        val live = machine()
        val session =
            RecordingSession(journal = JournalWriter.create(file), runIds = { RunId("run-1") })
        val warmupInputs = listOf(prepare(0), EngineInput.SampleReceived(warmup), start(1))
        warmupInputs.forEach(session::handle)
        warmupInputs.forEach(live::handle)
        val before = session.state
        session.abort()
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use { recovered ->
            assertEquals(before, recovered.state)
            assertTrue(recovered.handle(hr(2, 140)))
            assertTrue(live.handle(hr(2, 140)))
            assertEquals(live.state, recovered.state)
        }
    }

    @Test
    fun `twenty power losses recover only synced bytes with at most five seconds lost`(
        @TempDir dir: Path
    ) {
        val random = Random(9)
        val deaths = (0 until 20).map { it * 40 + random.nextInt(1, 40) }.toSet()
        val output = DurableOutput()
        var nanos = 0L
        RecordingSession(journal = JournalWriter(output, MonotonicClock { nanos })).use { session ->
            session.handle(prepare(0))
            session.handle(start(0))
            for (quarter in 1..800) {
                val elapsed = (quarter * 250).milliseconds
                nanos = elapsed.inWholeNanoseconds
                session.handle(EngineInput.Tick(at(0) + elapsed))
                if (quarter in deaths) {
                    val file = dir.resolve("power-$quarter.journal")
                    Files.write(file, output.synced)
                    JournalRecovery.restore(file).use { recovered ->
                        val loss = elapsed - recovered.state.totals.elapsed
                        assertTrue(
                            loss >= 0.seconds && loss <= 5.seconds,
                            "power loss at $elapsed lost $loss"
                        )
                        assertEquals(RunPhase.STOPPED, recovered.state.phase)
                    }
                }
            }
        }
        assertEquals(20, deaths.size)
    }

    private class DurableOutput : JournalOutput {
        private val bytes = ByteArrayOutputStream()
        var synced = ByteArray(0)
            private set
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            this.bytes.write(bytes, offset, length)
        }
        override fun sync() {
            synced = bytes.toByteArray()
        }
        override fun close() = Unit
    }

    @Test
    fun `ticks retain the timer during a long sensor silence`(@TempDir dir: Path) {
        val file = dir.resolve("silent.journal")
        var nanos = 0L
        val session =
            RecordingSession(journal = JournalWriter.create(file, MonotonicClock { nanos }))
        session.handle(prepare(0))
        session.handle(start(0))
        for (second in 1..29) {
            nanos = second * 1_000_000_000L
            session.handle(tick(second))
        }
        session.abort()
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use {
            assertEquals(29.seconds, it.state.totals.timer)
        }
    }

    @Test
    fun `recovery restores pauses laps totals and source gates without rewriting history`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("run.journal")
        val inputs = listOf(
            prepare(0),
            start(0),
            dist(0, 0.0),
            hr(0, 140),
            dist(10, 30.0),
            lap(10),
            pause(12),
            dist(14, 50.0),
            resume(20),
            dist(21, 60.0),
            dist(22, 63.0),
            hr(22, 145),
            pause(25)
        )
        val expected = machine().feed(inputs)
        RecordingSession(journal = JournalWriter.create(file), runIds = {
            RunId("run-1")
        }).use { session ->
            inputs.forEach(session::handle)
        }
        val prefix = Files.readAllBytes(file)
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use { session ->
            assertEquals(expected, session.state)
            assertFalse(session.handle(hr(1, 150))) // The source gate's last time survived.
            assertFalse(session.handle(lap(26)))
            assertTrue(session.handle(resume(30)))
            assertTrue(session.handle(dist(31, 70.0)))
            assertTrue(session.handle(dist(32, 73.0)))
            assertTrue(session.handle(stop(35)))
        }
        val recovered = JournalReader.read(file)
        assertEquals(1, recovered.records.filterIsInstance<JournalRecord.RunStarted>().size)
        assertContentEquals(prefix, Files.readAllBytes(file).copyOf(prefix.size))
        assertTrue(recovered.isStopped)
    }

    @Test
    fun `saving after a reboot uses the last old boot time even if new uptime is larger`(
        @TempDir dir: Path
    ) {
        for (newUptime in listOf(1.seconds, 10_000.seconds)) {
            val file = dir.resolve("boot-${newUptime.inWholeSeconds}.journal")
            JournalWriter.create(file).use {
                it.append(JournalRecord.RunStarted(RunId("run-1"), at(100)))
                it.append(JournalRecord.Samples(listOf(dist(110, 30.0).sample)))
            }
            JournalRecovery.restore(
                file,
                RecoveryMode.SAVE,
                clock = MonotonicClock {
                    newUptime.inWholeNanoseconds
                }
            ).use {
                assertEquals(RunPhase.STOPPED, it.state.phase)
                assertEquals(
                    RunEvent.Stopped(at(110), StopReason.RECOVERY),
                    it.state.run!!.events.last()
                )
                assertEquals(10.seconds, it.state.totals.elapsed)
            }
            assertTrue(JournalReader.read(file).isStopped)
        }
        assertTrue(JournalRecovery.scan(dir).pending.isEmpty())
    }

    @Test
    fun `startup finds unfinished journals and reports unreadable files without changing them`(
        @TempDir dir: Path
    ) {
        val pending = dir.resolve("pending.journal")
        JournalWriter.create(pending).use {
            it.append(JournalRecord.RunStarted(RunId("pending"), at(0)))
        }
        JournalWriter.create(dir.resolve("stopped.journal")).use {
            it.append(JournalRecord.RunStarted(RunId("done"), at(0)))
            it.append(JournalRecord.Event(RunEvent.Stopped(at(1), StopReason.USER)))
        }
        JournalWriter.create(dir.resolve("empty.journal")).close()
        val bad = dir.resolve("bad.journal")
        val bytes = byteArrayOf(1, 2, 3, 4)
        Files.write(bad, bytes)
        Files.write(dir.resolve("unrelated.txt"), bytes)
        val result = JournalRecovery.scan(dir)
        assertEquals(listOf(pending), result.pending)
        assertEquals(setOf(bad), result.unreadable.keys)
        assertContentEquals(bytes, Files.readAllBytes(bad))
        assertTrue(JournalRecovery.scan(dir.resolve("missing")).pending.isEmpty())
    }
}
