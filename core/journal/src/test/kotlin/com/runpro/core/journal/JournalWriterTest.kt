package com.runpro.core.journal

import com.runpro.core.model.LapTrigger
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.StopReason
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class JournalWriterTest {
    private val clock = FakeClock()
    private val output = FakeOutput()
    private val writer = JournalWriter(output, clock)

    @Test
    fun `samples wait for the next flush, one second after the previous one`() {
        writer.append(started())
        val afterStart = output.written.size

        clock.advanceTo(200.milliseconds)
        writer.append(samples(hr(0.2, 140)))
        clock.advanceTo(999.milliseconds)
        writer.tick()
        assertEquals(afterStart, output.written.size)

        clock.advanceTo(1.seconds)
        writer.tick()
        assertEquals(listOf(started(), samples(hr(0.2, 140))), read(output.written).records)
    }

    @Test
    fun `an append flushes by itself once the flush interval has passed`() {
        writer.append(started())
        clock.advanceTo(1.seconds)
        writer.append(samples(hr(1, 140)))
        assertEquals(listOf(started(), samples(hr(1, 140))), read(output.written).records)
    }

    @Test
    fun `flushed data is synced every five seconds`() {
        writer.append(started())
        val syncedAtStart = output.syncCount
        for (tenth in 1..49) {
            clock.advanceTo((tenth * 100).milliseconds)
            writer.append(samples(hr(tenth / 10.0, 140)))
            writer.tick()
        }
        assertEquals(syncedAtStart, output.syncCount)
        assertEquals(listOf(started()), read(output.synced).records)

        clock.advanceTo(5.seconds)
        writer.tick()
        assertEquals(syncedAtStart + 1, output.syncCount)
        assertContentEquals(output.written, output.synced)
        assertEquals(50, read(output.synced).records.size)
    }

    @Test
    fun `nothing is written or synced when there is nothing new`() {
        writer.append(started())
        val writes = output.writeCount
        val syncs = output.syncCount
        for (second in 1..30) {
            clock.advanceTo(second.seconds)
            writer.tick()
        }
        assertEquals(writes, output.writeCount)
        assertEquals(syncs, output.syncCount)
    }

    @Test
    fun `run events are synced before append returns`() {
        writer.append(started())
        val runEvents = listOf(
            RunEvent.Paused(at(1), PauseReason.AUTO),
            RunEvent.Resumed(at(2)),
            RunEvent.LapMarked(at(3), LapTrigger.MANUAL),
            everyRunEvent.first { it is RunEvent.SourceSwitched },
            everyRunEvent.first { it is RunEvent.SensorLost },
            RunEvent.Stopped(at(9), StopReason.USER)
        )
        for (runEvent in runEvents) {
            writer.append(samples(hr(runEvent.time.elapsedSince(at(0)).inWholeSeconds, 150)))
            val syncs = output.syncCount
            writer.append(event(runEvent))
            assertEquals(syncs + 1, output.syncCount, "$runEvent")
            assertEquals(event(runEvent), read(output.synced).records.last())
        }
    }

    @Test
    fun `sensor events follow the timed policy like samples`() {
        writer.append(started())
        val syncs = output.syncCount
        writer.append(JournalRecord.Sensor(SensorEvent.Disconnected(at(1), STRAP, 133)))
        assertEquals(syncs, output.syncCount)
        assertEquals(listOf(started()), read(output.written).records)
    }

    @Test
    fun `a full buffer is flushed without waiting for the interval`() {
        val small = JournalWriter(output, clock, JournalPolicy(maxBufferedBytes = 1024))
        small.append(started())
        val batch = samples(*Array(40) { hr(it, 140) })
        small.append(batch)
        assertEquals(listOf(started(), batch), read(output.written).records)
    }

    @Test
    fun `process death loses at most one second and power loss at most five`() {
        writer.append(started())
        val appended = mutableListOf<Pair<Double, JournalRecord>>()
        for (quarter in 1..240) {
            val now = quarter / 4.0
            clock.advanceTo(now.secondsDuration)
            if (quarter % 3 != 0) {
                val record = samples(hr(now, 140 + quarter % 20))
                writer.append(record)
                appended += now to record
            }
            writer.tick()

            val survivesKill = read(output.written).records
            val survivesPowerLoss = read(output.synced).records
            for ((time, record) in appended) {
                if (time <=
                    now - 1.0
                ) {
                    assertTrue(record in survivesKill, "kill at $now loses $time")
                }
                if (time <=
                    now - 5.0
                ) {
                    assertTrue(record in survivesPowerLoss, "power loss at $now loses $time")
                }
            }
        }
    }

    @Test
    fun `close writes and syncs everything, then closes the output`() {
        writer.append(started())
        writer.append(samples(hr(0.5, 141)))
        writer.close()
        assertContentEquals(output.written, output.synced)
        assertEquals(listOf(started(), samples(hr(0.5, 141))), read(output.synced).records)
        assertEquals(JournalEnd.Clean, read(output.synced).end)
        assertTrue(output.closed)
        writer.close()
        assertFailsWith<IllegalStateException> { writer.append(samples(hr(2, 140))) }
    }

    @Test
    fun `explicit flush and sync push the buffer out at once`() {
        writer.append(started())
        writer.append(samples(hr(0.1, 140)))
        writer.flush()
        assertEquals(2, read(output.written).records.size)
        assertEquals(1, read(output.synced).records.size)
        writer.sync()
        assertEquals(2, read(output.synced).records.size)
    }

    @Test
    fun `a failed write ends the journal and keeps what was written before`() {
        writer.append(started())
        writer.append(samples(hr(0.5, 140)))
        output.failAfterBytes = 10
        clock.advanceTo(1.seconds)
        assertFailsWith<IOException> { writer.append(samples(hr(1, 140))) }
        assertFailsWith<IllegalStateException> { writer.append(samples(hr(2, 140))) }
        val contents = read(output.written)
        assertEquals(listOf(started()), contents.records)
        assertEquals(JournalEnd.Truncated, contents.end)
        writer.close()
        assertTrue(output.closed)
    }

    @Test
    fun `appends from several threads never interleave`() {
        writer.append(started())
        val threads = 4
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads + 1)
        val go = CountDownLatch(1)
        repeat(threads) { thread ->
            pool.execute {
                go.await()
                repeat(perThread) { writer.append(samples(hr(it, 100 + thread))) }
            }
        }
        pool.execute {
            go.await()
            repeat(perThread) {
                clock.advance(10.milliseconds)
                writer.tick()
            }
        }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        writer.close()

        val contents = read(output.written)
        assertEquals(JournalEnd.Clean, contents.end)
        assertEquals(1 + threads * perThread, contents.records.size)
    }

    @Test
    fun `a journal file is never overwritten`(@TempDir dir: Path) {
        val file = dir.resolve("run.journal")
        JournalWriter.create(file).use { it.append(started()) }
        assertFailsWith<FileAlreadyExistsException> { JournalWriter.create(file) }
        assertEquals(listOf(started()), JournalReader.read(file).records)
    }

    @Test
    fun `a journal file round trips every record`(@TempDir dir: Path) {
        val file = dir.resolve("run.journal")
        JournalWriter.create(file).use { journal -> everyRecord.forEach(journal::append) }
        val contents = JournalReader.read(file)
        assertEquals(everyRecord, contents.records)
        assertEquals(JournalEnd.Clean, contents.end)
        assertEquals(Files.size(file), contents.validLength)
    }
}
