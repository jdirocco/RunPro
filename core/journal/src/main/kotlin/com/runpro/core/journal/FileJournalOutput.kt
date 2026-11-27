package com.runpro.core.journal

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A [JournalOutput] on a file: [write] goes to the OS page cache, [sync] is `fsync` (`FileChannel.force`).
 * Without a [reserve] (IMU streams, ADR 0012) a full storage is an ordinary I/O failure.
 */
class FileJournalOutput internal constructor(
    private val channel: FileChannel,
    private val reserve: StorageReserve?,
    private val file: Path = requireNotNull(reserve).file,
    private val writeChannel: (ByteBuffer) -> Int = channel::write,
    private val syncChannel: () -> Unit = { channel.force(false) }
) : JournalOutput {
    private var deletableOnDiscard: Boolean = true

    override var storageFull: Boolean = false
        private set

    override val availableBytes: Long
        get() = if (storageFull) 0 else Files.getFileStore(file).usableSpace

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val position = channel.position()
        withReserve(onFull = {
            // A failed write may have left part of a frame: retry from the original boundary.
            channel.truncate(position)
            channel.position(position)
        }) {
            val source = ByteBuffer.wrap(bytes, offset, length)
            while (source.hasRemaining()) writeChannel(source)
        }
    }

    override fun sync() = withReserve { syncChannel() }

    private inline fun withReserve(onFull: () -> Unit = {}, action: () -> Unit) {
        try {
            action()
        } catch (e: IOException) {
            if (reserve == null || storageFull || !e.isStorageFull()) throw e
            onFull()
            reserve.release()
            storageFull = true
            action() // Exactly one retry. Further failures remain observable to the engine.
        }
    }

    override fun close() {
        channel.use { reserve?.close() }
    }

    /** Closes the output and deletes a newly created file that never received RunStarted. */
    internal fun discard() {
        var failure: IOException? = null
        try {
            close()
        } catch (e: IOException) {
            failure = e
        }
        if (deletableOnDiscard) {
            try {
                Files.deleteIfExists(file)
            } catch (e: IOException) {
                if (failure != null) failure.addSuppressed(e) else failure = e
            }
        }
        failure?.let { throw it }
    }

    companion object {
        internal fun resume(file: Path): FileJournalOutput {
            val channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)
            var opened = false
            try {
                val contents = JournalReader.read(Channels.newInputStream(channel))
                require(contents.runId != null && !contents.isStopped) { "Need an unfinished run" }
                require(contents.skippedRecords == 0) {
                    "Cannot continue a journal with unknown records"
                }
                channel.truncate(contents.validLength)
                channel.position(contents.validLength)
                channel.force(true)
                val output = FileJournalOutput(channel, StorageReserve.create(file)).apply {
                    deletableOnDiscard = false
                }
                opened = true
                return output
            } finally {
                if (!opened) channel.close()
            }
        }

        /** Creates [file], which must not exist, and makes its directory entry durable. */
        fun create(file: Path): FileJournalOutput = create(file, reserved = true)

        /** Creates [file] like [create], without a storage reserve. */
        internal fun create(file: Path, reserved: Boolean): FileJournalOutput {
            val channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            try {
                val reserve = if (reserved) StorageReserve.create(file) else null
                syncDirectory(file.toAbsolutePath().parent)
                return FileJournalOutput(channel, reserve, file)
            } catch (e: IOException) {
                try {
                    channel.close()
                } catch (closing: IOException) {
                    e.addSuppressed(closing)
                }
                try {
                    Files.deleteIfExists(file)
                } catch (deleting: IOException) {
                    e.addSuppressed(deleting)
                }
                throw e
            }
        }

        // Without this a power loss can lose the new file itself, not only its tail. Linux (Android) supports
        // fsync on a directory; other systems may refuse to open one, which is harmless there.
        @Suppress("SwallowedException")
        private fun syncDirectory(directory: Path?) {
            if (directory == null) return
            try {
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            } catch (e: IOException) {
                // Best effort: the file data is still synced by the writer.
            }
        }
    }
}

/** JVM FileChannel reports ENOSPC through IOException; unrelated I/O errors must never be retried. */
private fun IOException.isStorageFull(): Boolean = generateSequence<Throwable>(this) {
    it.cause
}.any {
    val message = it.message.orEmpty()
    message.contains("No space left on device", ignoreCase = true) || message.contains("ENOSPC")
}
