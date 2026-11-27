package com.runpro.core.journal

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A monotonic clock in nanoseconds. On the watch, inject `SystemClock::elapsedRealtimeNanos`. */
fun interface MonotonicClock {
    fun nanos(): Long

    companion object {
        val SYSTEM: MonotonicClock = MonotonicClock(System::nanoTime)
    }
}

/**
 * Where a journal goes. [write] hands bytes to the operating system (they survive the death of the
 * process); [sync] makes everything written so far durable on storage (it survives a power loss or a reboot).
 */
interface JournalOutput : Closeable {
    /** True after reserved capacity was needed; the recording must stop immediately. */
    val storageFull: Boolean get() = false

    /** Remaining ordinary capacity; memory/test outputs are unbounded by default. */
    val availableBytes: Long get() = Long.MAX_VALUE

    fun write(bytes: ByteArray, offset: Int, length: Int)

    fun sync()
}

/**
 * When buffered records reach storage.
 *
 * @property flushInterval buffered records are written at most this long after the previous write.
 * @property syncInterval written records are synced at most this long after the previous sync.
 * @property maxBufferedBytes the buffer is written at once when it grows beyond this.
 */
data class JournalPolicy(
    val flushInterval: Duration = 1.seconds,
    val syncInterval: Duration = 5.seconds,
    val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES
) {
    init {
        require(flushInterval.isPositive() && syncInterval >= flushInterval) {
            "Need 0 < flushInterval <= syncInterval, got $flushInterval and $syncInterval"
        }
        require(maxBufferedBytes > 0) { "maxBufferedBytes must be positive" }
    }

    private companion object {
        const val DEFAULT_MAX_BUFFERED_BYTES = 64 * 1024
    }
}

/**
 * Appends [JournalRecord]s to a journal (format: [JournalFormat], ADR 0006).
 *
 * Durability:
 * - [JournalRecord.RunStarted] and every [JournalRecord.Event] (pause, resume, lap, source switch, sensor
 *   status, stop) are written and synced before [append] returns. This is R1 for the start tap, and keeps
 *   the rare, important events safe at once.
 * - Samples and sensor events are buffered in memory, written at least every [JournalPolicy.flushInterval]
 *   and synced at least every [JournalPolicy.syncInterval]. Process death loses at most the last
 *   flush interval, a power loss at most the last sync interval (R2 allows 5 s).
 * - The intervals are checked on every [append] and [tick]. The owner must call [tick] at least once per
 *   flush interval (e.g. from the recording thread on every engine tick), or records can wait in the
 *   buffer until the next append.
 *
 * Thread safe. A failed write or sync ([IOException], e.g. storage full) ends the journal: it is rethrown, and
 * every later [append] throws [IllegalStateException]. What reached storage before stays readable.
 * [close] writes and syncs the rest and closes the output.
 */
class JournalWriter private constructor(
    private val output: JournalOutput,
    private val clock: MonotonicClock,
    private val policy: JournalPolicy,
    writeHeader: Boolean
) : Closeable {
    constructor(
        output: JournalOutput,
        clock: MonotonicClock = MonotonicClock.SYSTEM,
        policy: JournalPolicy = JournalPolicy()
    ) : this(output, clock, policy, true)

    val storageFull: Boolean get() = output.storageFull
    val availableBytes: Long get() = output.availableBytes

    private val lock = Any()
    private val buffer = ByteArrayOutputStream()
    private var lastFlush = clock.nanos()
    private var lastSync = lastFlush
    private var unsynced = false
    private var hasRunStarted = !writeHeader
    private var closed = false
    private var failed: IOException? = null

    init {
        if (writeHeader) buffer.write(JournalFormat.header())
    }

    /**
     * Adds [record] to the journal; see the class documentation for when it reaches storage.
     *
     * @throws IOException when writing to storage fails.
     * @throws IllegalStateException after [close] or after a failure.
     */
    fun append(record: JournalRecord) {
        val framed = JournalFormat.frame(JournalCodec.encode(record))
        synchronized(lock) {
            checkUsable()
            if (record is JournalRecord.RunStarted) hasRunStarted = true
            buffer.write(framed)
            if (record is JournalRecord.RunStarted || record is JournalRecord.Event) {
                guarded {
                    writeBuffer()
                    syncOutput()
                }
            } else {
                guarded { flushIfDue() }
            }
        }
    }

    /** Writes and syncs what is due under the [policy]. Call it at least once per flush interval. */
    fun tick() = synchronized(lock) {
        if (!closed && failed == null) guarded { flushIfDue() }
    }

    /** Writes the buffered records to the output now (they survive process death). */
    fun flush() = synchronized(lock) {
        checkUsable()
        guarded { writeBuffer() }
    }

    /** Writes and syncs everything now (it survives a power loss). */
    fun sync() = synchronized(lock) {
        checkUsable()
        guarded {
            writeBuffer()
            syncOutput()
        }
    }

    /** Writes and syncs the rest and closes the output. After a failure it only closes the output. */
    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        output.use {
            if (failed == null) {
                writeBuffer()
                syncOutput()
            }
        }
    }

    /**
     * Discards memory buffers and closes the output, as when the recording process dies.
     * Removes a newly created file only if [JournalRecord.RunStarted] was never appended (R3).
     */
    fun abort() = synchronized(lock) {
        if (!closed) {
            closed = true
            buffer.reset()
            if (!hasRunStarted && output is FileJournalOutput) {
                output.discard()
            } else {
                output.close()
            }
        }
    }

    private fun flushIfDue() {
        val now = clock.nanos()
        val flushDue = now - lastFlush >= policy.flushInterval.inWholeNanoseconds
        if (buffer.size() > 0 &&
            (flushDue || buffer.size() >= policy.maxBufferedBytes)
        ) {
            writeBuffer()
        }
        if (unsynced && now - lastSync >= policy.syncInterval.inWholeNanoseconds) syncOutput()
    }

    private fun writeBuffer() {
        if (buffer.size() > 0) {
            // Clear the buffer before writing: after a failed write the journal is over anyway, and a retry
            // must never write the same bytes twice.
            val bytes = buffer.toByteArray()
            buffer.reset()
            output.write(bytes, 0, bytes.size)
            unsynced = true
        }
        lastFlush = clock.nanos()
    }

    private fun syncOutput() {
        if (unsynced) {
            output.sync()
            unsynced = false
        }
        lastSync = clock.nanos()
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            failed = e
            throw e
        }
    }

    private fun checkUsable() {
        check(!closed) { "The journal is closed" }
        failed?.let {
            throw IllegalStateException("The journal failed and is closed to writes", it)
        }
    }

    companion object {
        /**
         * Creates the journal [file] and returns its writer. Refuses to overwrite an existing file (a journal is
         * deleted only after its run was exported and verified, R3).
         *
         * @throws java.nio.file.FileAlreadyExistsException when [file] exists.
         */
        fun create(
            file: Path,
            clock: MonotonicClock = MonotonicClock.SYSTEM,
            policy: JournalPolicy = JournalPolicy()
        ): JournalWriter = JournalWriter(FileJournalOutput.create(file), clock, policy)

        /**
         * Continues an unfinished journal after removing only its damaged tail. The previous writer must
         * be closed. Unknown records and completed/unstarted files are refused without changing the file.
         */
        fun resume(
            file: Path,
            clock: MonotonicClock = MonotonicClock.SYSTEM,
            policy: JournalPolicy = JournalPolicy()
        ): JournalWriter = JournalWriter(FileJournalOutput.resume(file), clock, policy, false)
    }
}
