package com.runpro.core.fit

import com.runpro.core.journal.JournalReader
import com.runpro.core.model.RunId
import com.runpro.core.recording.EngineConfig
import com.runpro.core.recording.RecordedRun
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** The FIT file did not match its journal; the file was removed and the journal is untouched. */
class ExportVerificationException(val problems: List<String>) :
    Exception("FIT export does not match the journal:\n" + problems.joinToString("\n"))

/**
 * Proof that [fit] was written to storage, read back, decoded and matched [journal] (R3). Only
 * [FitExporter] creates it; deleting a journal (after the phone acknowledged the file, #30) must require one.
 *
 * @property sha256 hex digest of the verified file content, to detect a later change before deletion.
 */
class VerifiedExport internal constructor(
    val runId: RunId,
    val journal: Path,
    val fit: Path,
    val sizeBytes: Long,
    val sha256: String
)

/**
 * Turns a stopped run's journal into a verified FIT file. Disk work: call it on a worker thread, never on
 * the recording or main thread. It never modifies or deletes the journal.
 */
object FitExporter {
    private const val TEMP_SUFFIX = ".tmp"

    /**
     * Writes the FIT file of [journal] to [fit] and verifies it. The file is written next to [fit] under a
     * temporary name, forced to storage, then renamed, so [fit] is either absent or complete. The verified
     * bytes are the ones read back from [fit] after the rename.
     *
     * @throws ExportVerificationException when the file read back does not match; [fit] is removed.
     * @throws FitFormatException when the file read back does not decode; [fit] is removed.
     * @throws IllegalArgumentException when the journal is not a complete stopped run.
     * @throws IOException on storage errors; [fit] may be absent, the journal is unchanged.
     */
    fun export(
        journal: Path,
        fit: Path,
        config: EngineConfig = EngineConfig(),
        utcOffsetSeconds: Int = 0
    ): VerifiedExport = export(journal, fit, config) { FitEncoder.encode(it, utcOffsetSeconds) }

    /** [export] with another encoder, so tests can check that a wrong file is caught and removed. */
    internal fun export(
        journal: Path,
        fit: Path,
        config: EngineConfig,
        encode: (RecordedRun) -> ByteArray
    ): VerifiedExport {
        val run = RecordedRun.from(JournalReader.read(journal), config)
        writeDurably(fit, encode(run))
        val written = Files.readAllBytes(fit)
        val verification = try {
            ExportVerifier.verify(run, FitDecoder.decode(written))
        } catch (e: FitFormatException) {
            Files.deleteIfExists(fit)
            throw e
        }
        if (!verification.isVerified) {
            Files.deleteIfExists(fit)
            throw ExportVerificationException(verification.problems)
        }
        return VerifiedExport(run.id, journal, fit, written.size.toLong(), sha256(written))
    }

    /**
     * Reads [export]'s file again and checks it still has the verified content, e.g. before deleting the
     * journal long after the export.
     */
    fun isUnchanged(export: VerifiedExport): Boolean = Files.isRegularFile(export.fit) &&
        sha256(Files.readAllBytes(export.fit)) == export.sha256

    private fun writeDurably(target: Path, bytes: ByteArray) {
        val temp = target.resolveSibling(target.fileName.toString() + TEMP_SUFFIX)
        try {
            FileChannel.open(
                temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (@Suppress("SwallowedException") e: AtomicMoveNotSupportedException) {
                // Some file systems cannot rename atomically; the verification below reads the result.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
