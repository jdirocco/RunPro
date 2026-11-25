package com.runpro.tools.replay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** A trace that cannot be read. [line] is the 1-based line where reading failed. */
class TraceFormatException(val line: Int, message: String, cause: Throwable? = null) :
    IOException("Trace line $line: $message", cause)

/**
 * Reads traces written by [TraceRecorder], gzip-compressed or plain (detected from the content).
 *
 * A trace from a recorder that was killed ends with a cut line or a damaged gzip stream: every complete
 * record before the cut is kept and [Trace.truncated] is set. A bad line anywhere else is an error.
 */
object TraceReader {
    private const val GZIP_MAGIC_1 = 0x1f
    private const val GZIP_MAGIC_2 = 0x8b
    private const val BUFFER_SIZE = 64 * 1024

    /** Reads the whole trace from [input]; does not close it. */
    fun read(input: InputStream): Trace {
        val lines = LineReader(decompressed(input))
        val header = readHeader(lines)
        val records = mutableListOf<TraceRecord>()
        var skipped = 0
        var cut = false
        for (line in lines.asSequence().filterNot { it.text.isBlank() }) {
            when (val decoded = decode(line)) {
                is Decoded.Record -> records += decoded.record
                Decoded.Unknown -> skipped++
                Decoded.Cut -> cut = true // Only the last line can be cut.
            }
        }
        return Trace(header, records, truncated = cut || lines.damaged, skippedRecords = skipped)
    }

    private fun readHeader(lines: LineReader): TraceHeader {
        val first = lines.asSequence().firstOrNull { it.text.isNotBlank() }
            ?: throw TraceFormatException(1, "The trace is empty: no header")
        return try {
            TraceCodec.decodeHeader(first.text)
        } catch (e: IllegalArgumentException) {
            throw TraceFormatException(first.number, e.message ?: "Invalid header", e)
        }
    }

    private fun decode(line: Line): Decoded = try {
        TraceCodec.decodeRecord(line.text)?.let(Decoded::Record) ?: Decoded.Unknown
    } catch (e: IllegalArgumentException) {
        if (line.complete) throw TraceFormatException(line.number, e.message ?: "Invalid record", e)
        Decoded.Cut
    }

    private fun decompressed(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, BUFFER_SIZE)
        buffered.mark(2)
        val gzip = buffered.read() == GZIP_MAGIC_1 && buffered.read() == GZIP_MAGIC_2
        buffered.reset()
        return if (gzip) {
            BufferedInputStream(
                GZIPInputStream(buffered, BUFFER_SIZE),
                BUFFER_SIZE
            )
        } else {
            buffered
        }
    }

    private sealed interface Decoded {
        data class Record(val record: TraceRecord) : Decoded

        data object Unknown : Decoded

        data object Cut : Decoded
    }

    /** A line of text; [complete] is false for a last line without its newline. */
    private class Line(val number: Int, val text: String, val complete: Boolean)

    /**
     * Splits bytes into UTF-8 lines. Works on bytes, not on a `Reader`, so that the data delivered before a
     * damaged gzip stream fails is never lost in a decoder buffer. [damaged] is set when the stream ended
     * with an error instead of a clean end of file.
     */
    private class LineReader(private val input: InputStream) {
        var damaged = false
            private set
        private var number = 0
        private var ended = false

        fun asSequence(): Sequence<Line> = generateSequence { next() }

        private fun next(): Line? {
            if (ended) return null
            val bytes = ByteArrayOutputStream()
            var byte = readByte()
            while (byte != -1 && byte != '\n'.code) {
                bytes.write(byte)
                byte = readByte()
            }
            ended = byte == -1
            val nothingLeft = ended && bytes.size() == 0
            return if (nothingLeft) null else Line(++number, bytes.toString(Charsets.UTF_8), !ended)
        }

        // A cut or damaged gzip stream is the expected end of a trace whose recorder was killed:
        // it is reported as Trace.truncated, not as an error.
        @Suppress("SwallowedException")
        private fun readByte(): Int = try {
            input.read()
        } catch (e: EOFException) {
            damaged = true
            -1
        } catch (e: ZipException) {
            damaged = true
            -1
        }
    }
}
