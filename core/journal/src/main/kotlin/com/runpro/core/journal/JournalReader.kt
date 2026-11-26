package com.runpro.core.journal

import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/** How the readable part of a journal ended. */
sealed interface JournalEnd {
    /** The file ends exactly after the last record. */
    data object Clean : JournalEnd

    /** The file ends inside a record (or inside the header): the writer was cut off while writing. */
    data object Truncated : JournalEnd

    /** The record at [offset] is damaged (bad length or CRC); it and everything after it are ignored. */
    data class Corrupt(val offset: Long, val reason: String) : JournalEnd
}

/**
 * What [JournalReader] could read from a journal.
 *
 * @property records every intact record before the first damaged one, in file order.
 * @property validLength the byte length of the intact part (header and [records]); a writer that continues
 *   the journal must cut the file to this length first.
 * @property skippedRecords intact records that could not be decoded (unknown kind or invalid content,
 *   e.g. from a newer writer). They are skipped, not treated as damage.
 */
data class JournalContents(
    val records: List<JournalRecord>,
    val end: JournalEnd,
    val validLength: Long,
    val skippedRecords: Int = 0
) {
    val runId: RunId? get() = records.firstNotNullOfOrNull {
        (it as? JournalRecord.RunStarted)?.runId
    }

    /** The run start followed by the other run events, in recording order. */
    val runEvents: List<RunEvent>
        get() = records.mapNotNull { record ->
            when (record) {
                is JournalRecord.RunStarted -> record.event
                is JournalRecord.Event -> record.event
                else -> null
            }
        }

    /** False for a run that was interrupted (process death, reboot): it needs recovery. */
    val isStopped: Boolean get() = records.any {
        it is JournalRecord.Event &&
            it.event is RunEvent.Stopped
    }
}

/**
 * Reads journals written by [JournalWriter]. It keeps every record before the first damaged one: a cut tail
 * (the writer died mid-write) or a damaged record never makes reading fail, it only ends it.
 *
 * @throws JournalFormatException when the input is not a journal or has a newer format version.
 */
object JournalReader {
    private const val BUFFER_SIZE = 64 * 1024

    fun read(file: Path): JournalContents = Files.newInputStream(file).use(::read)

    /** Reads the whole journal from [input]; does not close it. */
    fun read(input: InputStream): JournalContents {
        val stream = BufferedInputStream(input, BUFFER_SIZE)
        val header = stream.readNBytes(JournalFormat.HEADER_SIZE)
        if (header.size < JournalFormat.HEADER_SIZE) {
            checkMagic(header)
            return JournalContents(emptyList(), JournalEnd.Truncated, validLength = 0)
        }
        checkMagic(header)
        val version = ByteBuffer.wrap(header).getInt(JournalFormat.MAGIC.size)
        if (version !in 1..JournalFormat.VERSION) {
            throw JournalFormatException(
                "Unsupported journal version $version (this reader supports up to ${JournalFormat.VERSION})"
            )
        }
        return readRecords(stream)
    }

    private fun checkMagic(header: ByteArray) {
        val present = minOf(header.size, JournalFormat.MAGIC.size)
        if (!header.copyOf(present).contentEquals(JournalFormat.MAGIC.copyOf(present))) {
            throw JournalFormatException("Not a RunPro journal")
        }
    }

    private fun readRecords(stream: InputStream): JournalContents {
        val records = mutableListOf<JournalRecord>()
        var skipped = 0
        var offset = JournalFormat.HEADER_SIZE.toLong()
        while (true) {
            val end = when (val next = readRecord(stream, offset)) {
                is Next.Payload -> {
                    decode(next.payload)?.let(records::add) ?: skipped++
                    offset += JournalFormat.RECORD_HEADER_SIZE + next.payload.size
                    null
                }

                is Next.End -> next.end
            }
            if (end != null) return JournalContents(records, end, offset, skipped)
        }
    }

    private fun readRecord(stream: InputStream, offset: Long): Next {
        val header = stream.readNBytes(JournalFormat.RECORD_HEADER_SIZE)
        if (header.size < JournalFormat.RECORD_HEADER_SIZE) {
            return Next.End(if (header.isEmpty()) JournalEnd.Clean else JournalEnd.Truncated)
        }
        val buffer = ByteBuffer.wrap(header)
        val length = buffer.getInt()
        val crc = buffer.getInt()
        return if (length in 1..JournalFormat.MAX_PAYLOAD_SIZE) {
            readPayload(stream, offset, length, crc)
        } else {
            Next.End(JournalEnd.Corrupt(offset, "Invalid record length $length"))
        }
    }

    private fun readPayload(stream: InputStream, offset: Long, length: Int, crc: Int): Next {
        val payload = stream.readNBytes(length)
        return when {
            payload.size < length -> Next.End(JournalEnd.Truncated)

            JournalFormat.crc(
                payload
            ) != crc -> Next.End(JournalEnd.Corrupt(offset, "CRC mismatch"))

            else -> Next.Payload(payload)
        }
    }

    // The CRC matched, so the payload is what a writer wrote: a record that does not decode comes from a
    // newer (or buggy) writer, not from damage. It is skipped and counted, and reading goes on.
    @Suppress("SwallowedException")
    private fun decode(payload: ByteArray): JournalRecord? = try {
        JournalCodec.decode(payload)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IllegalStateException) {
        null
    }

    private sealed interface Next {
        class Payload(val payload: ByteArray) : Next

        class End(val end: JournalEnd) : Next
    }
}
