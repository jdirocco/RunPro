package com.runpro.core.journal

import com.runpro.core.model.RunEvent
import com.runpro.core.model.StopReason
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class JournalResumeTest {
    @Test
    fun `continuing a cut or corrupt tail preserves the prefix and writes no second header`(
        @TempDir dir: Path
    ) {
        for (tail in listOf(byteArrayOf(0, 0), ByteArray(8))) {
            val file = dir.resolve("run-${tail.size}.journal")
            JournalWriter.create(file).use {
                it.append(started())
                it.append(samples(hr(1, 140)))
            }
            val prefix = Files.readAllBytes(file)
            Files.write(file, tail, StandardOpenOption.APPEND)
            JournalWriter.resume(file).use {
                it.append(event(RunEvent.Stopped(at(2), StopReason.RECOVERY)))
            }
            val contents = JournalReader.read(file)
            assertEquals(JournalEnd.Clean, contents.end)
            assertEquals(
                listOf(
                    started(),
                    samples(hr(1, 140)),
                    event(RunEvent.Stopped(at(2), StopReason.RECOVERY))
                ),
                contents.records
            )
            assertContentEquals(prefix, Files.readAllBytes(file).copyOf(prefix.size))
        }
    }

    @Test
    fun `unknown records are preserved and cannot be continued`(@TempDir dir: Path) {
        val file = dir.resolve("future.journal")
        JournalWriter.create(file).use { it.append(started()) }
        Files.write(file, JournalFormat.frame(byteArrayOf(0x78, 0x01)), StandardOpenOption.APPEND)
        val before = Files.readAllBytes(file)
        assertEquals(1, JournalReader.read(file).skippedRecords)
        assertFailsWith<IllegalArgumentException> { JournalWriter.resume(file) }
        assertContentEquals(before, Files.readAllBytes(file))
    }

    @Test
    fun `abort closes without flushing buffered samples`(@TempDir dir: Path) {
        val file = dir.resolve("abort.journal")
        val writer = JournalWriter.create(file, FakeClock())
        writer.append(started())
        writer.append(samples(hr(0.5, 150)))
        writer.abort()
        writer.close()
        assertEquals(listOf(started()), JournalReader.read(file).records)
    }

    @Test
    fun `completed and unstarted journals cannot be continued`(@TempDir dir: Path) {
        for (records in listOf(
            emptyList(),
            listOf(started(), event(RunEvent.Stopped(at(2), StopReason.USER)))
        )) {
            val file = dir.resolve("run-${records.size}.journal")
            JournalWriter.create(file).use { writer -> records.forEach(writer::append) }
            val before = Files.readAllBytes(file)
            assertFailsWith<IllegalArgumentException> { JournalWriter.resume(file) }
            assertContentEquals(before, Files.readAllBytes(file))
        }
    }
}
