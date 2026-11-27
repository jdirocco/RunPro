package com.runpro.tools.replay

import com.runpro.core.model.ImuBatch
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import java.io.BufferedWriter
import java.io.Closeable
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

/**
 * Writes a trace line by line: the header at once, then every record it is given, in arrival order.
 * It is a [SensorListener], so it can be attached to real or replayed sources to record what they deliver.
 *
 * Thread safe: sources may call it from different threads; lines never interleave. Compressed output uses
 * gzip sync flush, so after [flush] everything recorded so far is readable even if the process then dies
 * before [close] (the reader reports such a trace as truncated). [close] closes [output].
 */
class TraceRecorder(output: OutputStream, header: TraceHeader, compress: Boolean = true) :
    SensorListener,
    Closeable {
    private val lock = Any()
    private val writer = BufferedWriter(
        OutputStreamWriter(
            if (compress) GZIPOutputStream(output, BUFFER_SIZE, true) else output,
            Charsets.UTF_8
        ),
        BUFFER_SIZE
    )
    private var closed = false
    private var count = 0

    /** Records written so far (the header is not counted). */
    val recordCount: Int get() = synchronized(lock) { count }

    init {
        writer.write(TraceCodec.encodeHeader(header))
        writer.write(NEWLINE)
    }

    override fun onSample(sample: Sample) = record(TraceRecord.SampleRecord(sample))

    override fun onEvent(event: SensorEvent) = record(TraceRecord.EventRecord(event))

    override fun onImu(batch: ImuBatch) = record(TraceRecord.ImuRecord(batch))

    /** @throws IllegalStateException after [close]. */
    fun record(record: TraceRecord) {
        val line = TraceCodec.encodeRecord(record)
        synchronized(lock) {
            check(!closed) { "The trace recorder is closed" }
            writer.write(line)
            writer.write(NEWLINE)
            count++
        }
    }

    /** Pushes everything recorded so far to the output. */
    fun flush() = synchronized(lock) {
        if (!closed) writer.flush()
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            writer.close()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
        const val NEWLINE = "\n"
    }
}
