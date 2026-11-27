package com.runpro.core.journal

import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ImuReading
import com.runpro.core.model.ImuSensor
import com.runpro.core.model.RunId
import com.runpro.core.model.SourceId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class ImuStreamTest {
    @TempDir
    lateinit var dir: Path

    private val clock = FakeClock()

    private fun batch(second: Int, sensor: ImuSensor = ImuSensor.ACCELEROMETER) = ImuBatch(
        at(second),
        SourceId.WATCH_IMU,
        sensor,
        (0 until 50).map { ImuReading(it * 20_000_000L, it / 8f, -9.75f, 0.5f) }
    )

    @Test
    fun `a stream holds the run and its batches in order`() {
        val batches = listOf(batch(1), batch(1, ImuSensor.GYROSCOPE), batch(2))
        ImuStream.create(dir, RUN_ID, at(0), clock).use { writer ->
            batches.forEach(writer::append)
        }
        val streams = ImuStream.readRun(dir, RUN_ID)
        assertEquals(1, streams.size)
        val stream = streams.single()
        assertEquals(RUN_ID, stream.runId)
        assertEquals(at(0), stream.startedAt)
        assertEquals(batches, stream.batches)
        assertEquals(JournalEnd.Clean, stream.end)
        assertEquals(150, stream.readingCount)
    }

    @Test
    fun `IMU streams are separate from journals and keep no storage reserve`() {
        ImuStream.create(dir, RUN_ID, at(0), clock).use { it.append(batch(1)) }
        assertEquals(listOf("run-1.imu"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a restarted capture opens a new segment and never overwrites`() {
        ImuStream.create(dir, RUN_ID, at(0), clock).use { it.append(batch(1)) }
        val before = Files.readAllBytes(dir.resolve("run-1.imu"))
        ImuStream.create(dir, RUN_ID, at(60), clock).use { it.append(batch(61)) }
        ImuStream.create(dir, RunId("other"), at(0), clock).close()
        assertEquals(
            listOf("run-1.imu", "run-1.2.imu"),
            ImuStream.segments(dir, RUN_ID).map { it.name }
        )
        assertTrue(before.contentEquals(Files.readAllBytes(dir.resolve("run-1.imu"))))
        assertEquals(
            listOf(listOf(batch(1)), listOf(batch(61))),
            ImuStream.readRun(dir, RUN_ID).map { it.batches }
        )
    }

    @Test
    fun `segment order is numeric`() {
        repeat(11) { ImuStream.create(dir, RUN_ID, at(it), clock).close() }
        assertEquals(
            (0 until 11).map { at(it) },
            ImuStream.readRun(dir, RUN_ID).map { it.startedAt }
        )
    }

    @Test
    fun `batches reach the file with the journal policy and survive process death`() {
        val writer = ImuStream.create(dir, RUN_ID, at(0), clock)
        writer.append(batch(1))
        clock.advance(1.seconds)
        writer.append(batch(2)) // The flush interval elapsed: both are handed to the OS.
        writer.append(batch(3)) // Still buffered.
        writer.abort()
        val stream = ImuStream.readRun(dir, RUN_ID).single()
        assertEquals(listOf(batch(1), batch(2)), stream.batches)
    }

    @Test
    fun `a cut tail keeps every complete batch`() {
        ImuStream.create(dir, RUN_ID, at(0), clock).use { writer ->
            (1..3).forEach { writer.append(batch(it)) }
        }
        val file = dir.resolve("run-1.imu")
        val bytes = Files.readAllBytes(file)
        file.writeBytes(bytes.copyOf(bytes.size - 7))
        val stream = ImuStream.read(file)
        assertEquals(listOf(batch(1), batch(2)), stream.batches)
        assertEquals(JournalEnd.Truncated, stream.end)
    }

    @Test
    fun `only IMU records are accepted`() {
        ImuStream.create(dir, RUN_ID, at(0), clock).close()
        val journal = dir.resolve("run.journal")
        JournalWriter.create(journal, clock).use { it.append(started()) }
        assertFailsWith<JournalFormatException> { ImuStream.read(journal) }
    }

    @Test
    fun `pruning removes the oldest runs first and never the current one`() {
        val runs = (1..4).map { RunId("run-$it") }
        runs.forEachIndexed { index, run ->
            ImuStream.create(dir, run, at(index), clock).use { writer ->
                repeat(20) { writer.append(batch(it)) }
            }
            dir.resolve("${run.value}.imu").let {
                Files.setLastModifiedTime(it, FileTime.fromMillis(1_000L * (index + 1)))
            }
        }
        // The current run is the oldest file: it is kept even though it is over the limit.
        Files.setLastModifiedTime(dir.resolve("run-4.imu"), FileTime.fromMillis(1))
        val size = dir.resolve("run-1.imu").fileSize()
        val deleted = ImuStream.prune(dir, maxBytes = size * 2, keep = runs[3])
        assertEquals(listOf("run-1.imu", "run-2.imu"), deleted.map { it.name })
        assertEquals(
            setOf("run-3.imu", "run-4.imu"),
            dir.listDirectoryEntries().map { it.name }.toSet()
        )
    }

    @Test
    fun `pruning ignores other files and a missing directory`() {
        dir.resolve("notes.txt").writeBytes(ByteArray(10_000))
        ImuStream.create(dir, RUN_ID, at(0), clock).close()
        assertEquals(listOf("run-1.imu"), ImuStream.prune(dir, maxBytes = 0).map { it.name })
        assertTrue(Files.exists(dir.resolve("notes.txt")))
        assertEquals(emptyList(), ImuStream.prune(dir.resolve("missing"), maxBytes = 0))
    }
}
