package com.runpro.core.journal

import com.runpro.core.model.ImuBatch
import com.runpro.core.model.RunId
import com.runpro.core.model.Timestamp
import java.io.Closeable
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * What [ImuStream.read] could read from one IMU stream segment.
 *
 * @property startedAt when the capture of this segment started.
 * @property skippedRecords intact records that are not IMU batches (from a newer writer).
 */
data class ImuStreamContents(
    val runId: RunId,
    val startedAt: Timestamp,
    val batches: List<ImuBatch>,
    val end: JournalEnd,
    val skippedRecords: Int = 0
) {
    val readingCount: Int get() = batches.sumOf { it.readings.size }
}

/**
 * Appends [ImuBatch]es to one IMU stream segment. Same durability as journal samples: written every
 * second, synced every five ([JournalPolicy]); the run start is synced at once. Thread safe.
 * A failed write ends the segment ([IOException]); the run journal is never affected (ADR 0012).
 */
class ImuStreamWriter internal constructor(val file: Path, private val writer: JournalWriter) :
    Closeable {
    /** Free space on the stream's file system. */
    val availableBytes: Long get() = writer.availableBytes

    /** @throws IOException when writing fails; @throws IllegalStateException after close or failure. */
    fun append(batch: ImuBatch) = writer.append(JournalRecord.Imu(batch))

    /** Writes and syncs what is due under the policy. */
    fun tick() = writer.tick()

    /** Writes the buffered batches now, e.g. when the capture pauses. */
    fun flush() = writer.flush()

    /** Writes and syncs the rest and closes the file. */
    override fun close() = writer.close()

    /** Discards unwritten batches and closes the file, as a dying process would. */
    fun abort() = writer.abort()
}

/**
 * Per-run IMU streams (ADR 0012): files in the journal layout with the `RPIM` magic, a run start record and
 * then [JournalRecord.Imu] batches. They live in their own directory, apart from the run journals: phone
 * sync never deletes them, recovery never reads them, and a failure while writing them cannot reach a
 * journal. A capture that restarts during a run (process death) opens a new segment:
 * `<run id>.imu`, `<run id>.2.imu`, `<run id>.3.imu`, ...
 */
object ImuStream {
    const val SUFFIX = ".imu"

    /**
     * Creates the next free segment of [runId] in [directory] and syncs its run start. Never overwrites a file.
     */
    fun create(
        directory: Path,
        runId: RunId,
        time: Timestamp,
        clock: MonotonicClock = MonotonicClock.SYSTEM,
        policy: JournalPolicy = JournalPolicy()
    ): ImuStreamWriter {
        Files.createDirectories(directory)
        var index = segmentIndices(directory, runId).maxOrNull() ?: 0
        while (true) {
            index++
            val file = directory.resolve(segmentName(runId, index))
            val output = try {
                FileJournalOutput.create(file, reserved = false)
            } catch (_: FileAlreadyExistsException) {
                continue
            }
            val writer = JournalWriter.imu(output, clock, policy)
            try {
                writer.append(JournalRecord.RunStarted(runId, time))
            } catch (e: IOException) {
                writer.abort()
                throw e
            }
            return ImuStreamWriter(file, writer)
        }
    }

    /** The segments of [runId] in [directory], in capture order. */
    fun segments(directory: Path, runId: RunId): List<Path> = segmentIndices(directory, runId)
        .sorted()
        .map { directory.resolve(segmentName(runId, it)) }

    /** Every segment of [runId] in [directory], in capture order. */
    fun readRun(directory: Path, runId: RunId): List<ImuStreamContents> =
        segments(directory, runId).map(::read)

    /**
     * Reads one segment, keeping every batch before a damaged or cut tail.
     *
     * @throws JournalFormatException when [file] is not an IMU stream or has no intact run start.
     */
    fun read(file: Path): ImuStreamContents {
        val contents = Files.newInputStream(file).use {
            JournalReader.read(it, JournalFormat.IMU_MAGIC)
        }
        val start = contents.records.firstOrNull() as? JournalRecord.RunStarted
            ?: throw JournalFormatException("The IMU stream has no run start: $file")
        val batches = contents.records.drop(1).filterIsInstance<JournalRecord.Imu>().map {
            it.batch
        }
        val other = contents.records.size - 1 - batches.size
        return ImuStreamContents(
            start.runId,
            start.time,
            batches,
            contents.end,
            contents.skippedRecords + other
        )
    }

    /**
     * Deletes the least recently written segments until the streams in [directory] take at most [maxBytes].
     * Segments of [keep] (the run being recorded) are never deleted. Returns the deleted files.
     */
    fun prune(directory: Path, maxBytes: Long, keep: RunId? = null): List<Path> {
        if (!directory.isDirectory()) return emptyList()
        val files = directory.listDirectoryEntries()
            .filter { it.name.endsWith(SUFFIX) }
            .sortedBy { it.getLastModifiedTime() }
        var total = files.sumOf { it.fileSize() }
        val kept = keep?.let { segments(directory, it).toSet() }.orEmpty()
        val deleted = mutableListOf<Path>()
        for (file in files.filterNot { it in kept }) {
            if (total <= maxBytes) break
            val size = file.fileSize()
            if (Files.deleteIfExists(file)) {
                total -= size
                deleted.add(file)
            }
        }
        return deleted
    }

    private fun segmentName(runId: RunId, index: Int): String =
        if (index == 1) "${runId.value}$SUFFIX" else "${runId.value}.$index$SUFFIX"

    private fun segmentIndices(directory: Path, runId: RunId): List<Int> {
        if (!directory.isDirectory()) return emptyList()
        val prefix = runId.value
        return directory.listDirectoryEntries().mapNotNull { file ->
            val name = file.name
            if (!name.startsWith(prefix) || !name.endsWith(SUFFIX)) return@mapNotNull null
            val middle = name.substring(prefix.length, name.length - SUFFIX.length)
            when {
                middle.isEmpty() -> 1
                middle.startsWith(".") -> middle.drop(1).toIntOrNull()?.takeIf { it >= 2 }
                else -> null
            }
        }
    }
}
