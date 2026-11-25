package com.runpro.tools.replay

import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.Serializable

/**
 * The first line of every trace. [version] is the line format version; readers reject versions newer
 * than [CURRENT_VERSION]. [device] says where the trace comes from (`synthetic`, a watch model).
 */
@Serializable
data class TraceHeader(
    val version: Int = CURRENT_VERSION,
    val name: String,
    val description: String? = null,
    val device: String? = null
) {
    init {
        require(name.isNotBlank()) { "A trace needs a name" }
        require(version >= 1) { "Invalid trace version: $version" }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** One line of a trace after the header: a reading or a sensor event, as delivered by its source. */
sealed interface TraceRecord {
    val time: Timestamp
    val source: SourceId

    data class SampleRecord(val sample: Sample) : TraceRecord {
        override val time: Timestamp get() = sample.time
        override val source: SourceId get() = sample.source
    }

    data class EventRecord(val event: SensorEvent) : TraceRecord {
        override val time: Timestamp get() = event.time
        override val source: SourceId get() = event.source
    }
}

/**
 * A replay trace: what the sensors of one run delivered, in delivery order (see `docs/adr/0004`).
 *
 * Records are kept in the order they were delivered, which is not always timestamp order: platform
 * sources deliver batches late. [truncated] is true when the file ended in the middle of a record
 * (the recorder was killed); every complete record before that point is kept. [skippedRecords] counts
 * records of types this reader does not know (written by a newer version).
 */
data class Trace(
    val header: TraceHeader,
    val records: List<TraceRecord>,
    val truncated: Boolean = false,
    val skippedRecords: Int = 0
) {
    /** Every source in the trace, in order of first appearance. */
    val sources: List<SourceId> get() = records.map { it.source }.distinct()

    val samples: List<Sample>
        get() = records.filterIsInstance<TraceRecord.SampleRecord>().map { it.sample }

    val events: List<SensorEvent>
        get() = records.filterIsInstance<TraceRecord.EventRecord>().map { it.event }

    /** Monotonic time from the earliest to the latest record. */
    val duration: Duration
        get() {
            val nanos = records.map { it.time.elapsedRealtimeNanos }
            return if (nanos.isEmpty()) Duration.ZERO else (nanos.max() - nanos.min()).nanoseconds
        }

    fun sampleCountBySource(): Map<SourceId, Int> = samples.groupingBy { it.source }.eachCount()

    /** Writes this trace as JSONL, gzip-compressed if [compress], and closes [output]. */
    fun writeTo(output: OutputStream, compress: Boolean = true) {
        TraceRecorder(output, header, compress).use { recorder ->
            records.forEach(recorder::record)
        }
    }

    /** Writes this trace to [file], compressed when its name ends in `.gz`. */
    fun writeTo(file: Path) =
        writeTo(file.outputStream(), compress = file.name.endsWith(GZIP_SUFFIX))

    companion object {
        /** File suffix of a compressed trace, the form committed to `testdata/traces/`. */
        const val FILE_SUFFIX = ".jsonl.gz"
        private const val GZIP_SUFFIX = ".gz"

        /** Reads a trace file, compressed or not. */
        fun read(file: Path): Trace = file.inputStream().use(TraceReader::read)
    }
}
