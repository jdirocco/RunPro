package com.runpro.core.journal

import com.runpro.core.model.RunEvent
import com.runpro.core.model.StopReason
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ReservedStorageTest {
    @Test
    fun `partial full write rolls back and uses reserve without duplicating bytes`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("run.journal")
        val reserve = StorageReserve.create(file)
        val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        var full = false
        val output = FileJournalOutput(channel, reserve, writeChannel = { buffer ->
            if (full && Files.size(reserve.file) > 0) {
                channel.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
                throw IOException("No space left on device")
            }
            channel.write(buffer)
        })
        JournalWriter(output).use { writer ->
            writer.append(started())
            full = true
            writer.append(samples(hr(1, 150)))
            writer.append(event(RunEvent.Stopped(at(2), StopReason.STORAGE_FULL)))
            assertTrue(writer.storageFull)
        }
        val contents = JournalReader.read(file)
        assertEquals(JournalEnd.Clean, contents.end)
        assertEquals(3, contents.records.size)
        assertTrue(contents.isStopped)
        assertFalse(Files.exists(reserve.file))
    }

    @Test
    fun `full sync consumes the reserve and retries sync once`(@TempDir dir: Path) {
        val file = dir.resolve("sync.journal")
        val reserve = StorageReserve.create(file)
        val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        var failSync = false
        var failedSyncs = 0
        var synced = byteArrayOf()
        val output = FileJournalOutput(channel, reserve, syncChannel = {
            if (failSync && Files.size(reserve.file) > 0) {
                failedSyncs++
                throw IOException("No space left on device")
            }
            channel.force(false)
            synced = Files.readAllBytes(file)
        })
        JournalWriter(output).use { writer ->
            writer.append(started())
            failSync = true
            writer.append(event(RunEvent.Stopped(at(2), StopReason.LOW_BATTERY)))
            assertTrue(writer.storageFull)
            assertEquals(1, failedSyncs)
            assertTrue(read(synced).isStopped)
            assertEquals(2, read(synced).records.size)
        }
    }

    @Test
    fun `ordinary IO errors do not consume the reserve`(@TempDir dir: Path) {
        val file = dir.resolve("run.journal")
        val reserve = StorageReserve.create(file)
        val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        FileJournalOutput(channel, reserve, writeChannel = {
            throw IOException("device broken")
        }).use { output ->
            kotlin.test.assertFailsWith<IOException> { output.write(byteArrayOf(1), 0, 1) }
            assertFalse(output.storageFull)
            assertTrue(Files.size(reserve.file) > 0)
        }
    }
}
