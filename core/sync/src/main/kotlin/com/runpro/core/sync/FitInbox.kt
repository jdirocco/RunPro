package com.runpro.core.sync

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Phone side of the sync: receives FIT files into [runs], one file per run id (`RunFiles.fitName`).
 *
 * A file is acknowledged only after it was received completely, its SHA-256 matches the offer, it was forced
 * to storage and renamed into place. Receiving the same run again (the watch never got the ack) stores
 * nothing new and acknowledges again, so a run is never duplicated. Disk work: call on a worker thread.
 */
class FitInbox(private val runs: Path) {
    sealed interface Outcome {
        /** [file] now holds the run; [replaced] when an older, different file of the same run was there. */
        data class Stored(val file: Path, val replaced: Boolean) : Outcome

        /** The same content was already stored; acknowledged again. */
        data class AlreadyStored(val file: Path) : Outcome

        /** The content was refused (hash mismatch, storage error); a negative ack was sent if possible. */
        data class Rejected(val reason: String) : Outcome

        /** The stream ended or broke before the file was complete; nothing was stored or acknowledged. */
        data class Interrupted(val cause: IOException) : Outcome
    }

    init {
        Files.createDirectories(runs)
    }

    /** The stored run files, by name. */
    fun stored(): List<Path> = synchronized(LOCK) {
        runs.listDirectoryEntries("*.fit").filter { it.isRegularFile() }.sortedBy { it.name }
    }

    /** Handles one offer on [channel] and answers it. Does not close [channel]. */
    fun receive(channel: SyncChannel): Outcome = synchronized(LOCK) {
        removeIncomplete()
        val input = DataInputStream(channel.input)
        val offer = try {
            SyncProtocol.readOffer(input)
        } catch (e: SyncProtocolException) {
            return Outcome.Rejected(e.message.orEmpty())
        } catch (e: IOException) {
            return Outcome.Interrupted(e)
        }
        val temp = Files.createTempFile(runs, INCOMING_PREFIX, INCOMING_SUFFIX)
        val outcome = try {
            store(offer, input, temp)
        } finally {
            Files.deleteIfExists(temp)
        }
        when (outcome) {
            is Outcome.Stored, is Outcome.AlreadyStored -> answer(
                channel,
                FitAck(offer.runId, offer.sha256, true)
            )

            is Outcome.Rejected -> answer(
                channel,
                FitAck(offer.runId, offer.sha256, false, outcome.reason)
            )

            is Outcome.Interrupted -> Unit
        }
        outcome
    }

    private fun store(offer: FitOffer, input: DataInputStream, temp: Path): Outcome = try {
        if (copy(offer.sizeBytes, input, temp) == offer.sha256) {
            place(offer, temp)
        } else {
            Outcome.Rejected("content does not match its SHA-256")
        }
    } catch (e: StorageFailure) {
        Outcome.Rejected("storage error: ${e.cause?.message}")
    } catch (e: IOException) {
        // The stream broke: the watch went away and there is no one to answer.
        Outcome.Interrupted(e)
    }

    /**
     * Copies [size] bytes of [input] to [temp], forced to storage, and returns their SHA-256.
     *
     * @throws StorageFailure when writing fails; the rest of the file is read so the ack can be sent.
     * @throws IOException when reading fails or the stream ends early.
     */
    private fun copy(size: Long, input: DataInputStream, temp: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER)
        FileChannel.open(temp, StandardOpenOption.WRITE).use { file ->
            var remaining = size
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("stream ended, $remaining bytes missing")
                digest.update(buffer, 0, read)
                remaining -= read
                storage({ drain(input, remaining, buffer) }) {
                    val chunk = ByteBuffer.wrap(buffer, 0, read)
                    while (chunk.hasRemaining()) file.write(chunk)
                }
            }
            storage { file.force(true) }
        }
        return SyncProtocol.bytesToHex(digest.digest())
    }

    private inline fun storage(onFailure: () -> Unit = {}, action: () -> Unit) {
        try {
            action()
        } catch (e: IOException) {
            onFailure()
            throw StorageFailure(e)
        }
    }

    private fun place(offer: FitOffer, temp: Path): Outcome {
        val target = runs.resolve(RunFiles.fitName(offer.runId))
        val existed = target.isRegularFile()
        return if (existed && sha256(target) == offer.sha256) {
            Outcome.AlreadyStored(target)
        } else {
            try {
                moveIntoPlace(temp, target)
                syncDirectory(runs)
                Outcome.Stored(target, replaced = existed)
            } catch (e: IOException) {
                Outcome.Rejected("storage error: ${e.message}")
            }
        }
    }

    /** Writing to the phone's storage failed (not the stream from the watch). */
    private class StorageFailure(cause: IOException) : Exception(cause)

    private fun answer(channel: SyncChannel, ack: FitAck) {
        try {
            SyncProtocol.writeAck(channel.output, ack)
        } catch (@Suppress("SwallowedException") e: IOException) {
            // The watch left before the ack; it sends the run again and gets the ack then.
        }
    }

    /** Reads the rest of the file so the watch can read the negative ack instead of a broken stream. */
    @Suppress("SwallowedException")
    private fun drain(input: DataInputStream, bytes: Long, buffer: ByteArray) {
        var remaining = bytes
        try {
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) return
                remaining -= read
            }
        } catch (e: IOException) {
            // The watch left; the caller's ack write fails quietly as well.
        }
    }

    /** Partial files left by a phone process that died while receiving. */
    private fun removeIncomplete() {
        runs.listDirectoryEntries(
            "$INCOMING_PREFIX*$INCOMING_SUFFIX"
        ).forEach(Files::deleteIfExists)
    }

    private fun moveIntoPlace(temp: Path, target: Path) {
        try {
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (@Suppress("SwallowedException") e: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        /** One inbox directory per app; receives from several listener instances must not interleave. */
        val LOCK = Any()
        const val INCOMING_PREFIX = ".incoming-"
        const val INCOMING_SUFFIX = ".part"
        const val COPY_BUFFER = 64 * 1024
    }
}

internal fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return SyncProtocol.bytesToHex(digest.digest())
}

// Without this a power loss can lose the renamed file. Linux (Android) supports fsync on a directory; other
// systems may refuse to open one, which is harmless there.
@Suppress("SwallowedException")
internal fun syncDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (e: IOException) {
        // Best effort: the file data itself was forced before the rename.
    }
}
