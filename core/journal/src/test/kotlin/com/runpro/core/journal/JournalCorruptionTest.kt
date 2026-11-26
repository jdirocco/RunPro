package com.runpro.core.journal

import com.runpro.core.model.RunEvent
import com.runpro.core.model.StopReason
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The reader keeps every record before the first damaged one and never fails on a damaged tail. */
class JournalCorruptionTest {
    private val records = everyRecord
    private val bytes = journalBytes(records)

    /** Byte offset where each record ends, header included. */
    private val recordEnds: List<Int> = buildList {
        var offset = JournalFormat.HEADER_SIZE
        for (record in records) {
            offset += JournalFormat.RECORD_HEADER_SIZE + JournalCodec.encode(record).size
            add(offset)
        }
    }

    @Test
    fun `an intact journal is read completely`() {
        val contents = read(bytes)
        assertEquals(records, contents.records)
        assertEquals(JournalEnd.Clean, contents.end)
        assertEquals(bytes.size.toLong(), contents.validLength)
        assertEquals(0, contents.skippedRecords)
        assertEquals(bytes.size, recordEnds.last())
    }

    @Test
    fun `an empty file is a journal cut before its header`() {
        val contents = read(ByteArray(0))
        assertEquals(emptyList(), contents.records)
        assertEquals(JournalEnd.Truncated, contents.end)
        assertEquals(0, contents.validLength)
    }

    @Test
    fun `a cut header is a journal cut before its header`() {
        for (length in 1 until JournalFormat.HEADER_SIZE) {
            val contents = read(bytes.copyOf(length))
            assertEquals(emptyList(), contents.records)
            assertEquals(JournalEnd.Truncated, contents.end)
            assertEquals(0, contents.validLength)
        }
    }

    @Test
    fun `a file that is not a journal is rejected`() {
        val notJournal = "{\"type\":\"trace_header\"}".toByteArray()
        assertFailsWith<JournalFormatException> { read(notJournal) }
    }

    @Test
    fun `a journal from a newer format version is rejected`() {
        val newer = bytes.copyOf()
        ByteBuffer.wrap(newer).putInt(JournalFormat.MAGIC.size, JournalFormat.VERSION + 1)
        assertFailsWith<JournalFormatException> { read(newer) }
    }

    @Test
    fun `a journal cut at any byte keeps every complete record before the cut`() {
        for (length in JournalFormat.HEADER_SIZE..bytes.size) {
            val contents = read(bytes.copyOf(length))
            val complete = recordEnds.count { it <= length }
            assertEquals(records.take(complete), contents.records, "cut at $length")
            val atBoundary = length == JournalFormat.HEADER_SIZE || length in recordEnds
            assertEquals(if (atBoundary) JournalEnd.Clean else JournalEnd.Truncated, contents.end)
            assertEquals(
                recordEnds.lastOrNull {
                    it <= length
                } ?: JournalFormat.HEADER_SIZE,
                contents.validLength.toInt()
            )
        }
    }

    @Test
    fun `a corrupt tail keeps every record before it`() {
        val damaged = bytes.copyOf()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0x01).toByte()
        val contents = read(damaged)
        assertEquals(records.dropLast(1), contents.records)
        assertIs<JournalEnd.Corrupt>(contents.end)
        assertEquals(recordEnds[recordEnds.lastIndex - 1].toLong(), contents.validLength)
    }

    @Test
    fun `reading stops at the first corrupt record even if later records are intact`() {
        val damagedIndex = 5
        val damaged = bytes.copyOf()
        // The last payload byte of record 5.
        val position = recordEnds[damagedIndex] - 1
        damaged[position] = (damaged[position].toInt() xor 0x40).toByte()
        val contents = read(damaged)
        assertEquals(records.take(damagedIndex), contents.records)
        val end = assertIs<JournalEnd.Corrupt>(contents.end)
        assertEquals(recordEnds[damagedIndex - 1].toLong(), end.offset)
    }

    @Test
    fun `any single damaged byte loses only that record and the ones after it`() {
        for (position in JournalFormat.HEADER_SIZE until bytes.size) {
            val damaged = bytes.copyOf()
            damaged[position] = (damaged[position].toInt() xor 0xff).toByte()
            val contents = read(damaged)
            val damagedRecord = recordEnds.indexOfFirst { position < it }
            assertEquals(records.take(damagedRecord), contents.records, "byte $position")
            assertFalse(contents.end == JournalEnd.Clean, "byte $position")
        }
    }

    @Test
    fun `a zero-filled tail after a power loss is not read as records`() {
        val contents = read(bytes + ByteArray(4096))
        assertEquals(records, contents.records)
        assertIs<JournalEnd.Corrupt>(contents.end)
        assertEquals(bytes.size.toLong(), contents.validLength)
    }

    @Test
    fun `an absurd record length is corruption, not an allocation`() {
        val header = ByteBuffer.allocate(JournalFormat.RECORD_HEADER_SIZE)
            .putInt(Int.MAX_VALUE)
            .putInt(0)
            .array()
        val contents = read(bytes + header + ByteArray(16))
        assertEquals(records, contents.records)
        assertIs<JournalEnd.Corrupt>(contents.end)
    }

    @Test
    fun `an intact record of an unknown kind is skipped and reading goes on`() {
        val unknown = frame(byteArrayOf(0x78, 0x01))
        val stopped = event(RunEvent.Stopped(at(60), StopReason.USER))
        val contents =
            read(journalBytes(listOf(started())) + unknown + frame(JournalCodec.encode(stopped)))
        assertEquals(listOf(started(), stopped), contents.records)
        assertEquals(1, contents.skippedRecords)
        assertEquals(JournalEnd.Clean, contents.end)
    }

    @Test
    fun `an intact record with invalid content is skipped and reading goes on`() {
        // run_stopped with reason code 99: a newer writer or a bug, but not damage.
        val invalid = frame(byteArrayOf(0x42, 0x04, 0x0a, 0x00, 0x10, 99))
        val stopped = event(RunEvent.Stopped(at(60), StopReason.USER))
        val contents =
            read(journalBytes(listOf(started())) + invalid + frame(JournalCodec.encode(stopped)))
        assertEquals(listOf(started(), stopped), contents.records)
        assertEquals(1, contents.skippedRecords)
    }

    @Test
    fun `contents expose the run id, the run events and whether the run stopped`() {
        val contents = read(bytes)
        assertEquals(RUN_ID, contents.runId)
        assertEquals(listOf(RunEvent.Started(at(0))) + everyRunEvent, contents.runEvents)
        assertTrue(contents.isStopped)

        val running = read(journalBytes(listOf(started(), samples(hr(1, 140)))))
        assertFalse(running.isStopped)
        assertEquals(listOf<RunEvent>(RunEvent.Started(at(0))), running.runEvents)
    }

    private fun frame(payload: ByteArray): ByteArray {
        val crc = CRC32().apply { update(payload) }.value.toInt()
        return ByteBuffer.allocate(JournalFormat.RECORD_HEADER_SIZE + payload.size)
            .putInt(payload.size)
            .putInt(crc)
            .put(payload)
            .array()
    }
}
