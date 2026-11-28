package com.runpro.core.sync

import com.runpro.core.fit.ExportVerificationException
import com.runpro.core.fit.FitExporter
import com.runpro.core.fit.FitFormatException
import com.runpro.core.fit.VerifiedExport
import com.runpro.core.journal.JournalReader
import com.runpro.core.recording.EngineConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Watch side of the sync. Every stopped journal in [journals] is exported to a verified FIT file in
 * [exports] and offered to the phone; the journal is deleted only after the phone acknowledged exactly that
 * content and the file on the watch still has it (R3). Everything else keeps the journal, so a lost ack, a
 * broken transfer or a restart only means the run is sent again later; the phone recognises it and stores
 * it once.
 *
 * The outbox has no state of its own: the journals still on disk are the runs left to send. FIT files stay
 * in [exports] after delivery (watch history, #29). Disk and network work: call on a worker thread.
 */
class FitOutbox(
    private val journals: Path,
    private val exports: Path,
    private val config: EngineConfig = EngineConfig(),
    private val utcOffsetSeconds: () -> Int = { 0 },
    private val storageLock: Any = Any()
) {
    /** The result of one [sync] pass. */
    data class Report(
        /** Run ids acknowledged by the phone whose journals were deleted in this pass. */
        val delivered: List<String>,
        /** Verified runs still waiting for an ack; [sync] should run again later when this is not zero. */
        val waiting: Int,
        /** Whether a phone could be reached at all. */
        val phoneReachable: Boolean,
        /** What went wrong, one line per run or transfer, for the log. */
        val problems: List<String>
    ) {
        val needsRetry: Boolean get() = waiting > 0
    }

    private val verified = mutableMapOf<Path, VerifiedExport>()

    init {
        Files.createDirectories(journals)
        Files.createDirectories(exports)
    }

    /**
     * Offers every stopped journal, oldest name first, one channel per run. Journals still recording,
     * awaiting recovery or not exportable are kept and skipped. The pass ends early when the phone is not
     * reachable or a transfer breaks.
     */
    @Synchronized
    fun sync(link: PhoneLink): Report {
        val problems = mutableListOf<String>()
        val ready = synchronized(storageLock) {
            journals.listDirectoryEntries("*$JOURNAL_SUFFIX")
                .filter { it.isRegularFile() }
                .sortedBy { it.name }
                .mapNotNull { journal -> prepare(journal, problems) }
        }
        val delivered = mutableListOf<String>()
        var reachable = true
        for (export in ready) {
            val result = deliver(export, link)
            when (result) {
                Delivery.Acknowledged -> {
                    deleteJournal(export.journal)
                    delivered += export.runId.value
                }

                Delivery.Unreachable -> reachable = false

                is Delivery.Failed -> problems += "${export.runId}: ${result.reason}"
            }
            if (result == Delivery.Unreachable ||
                (result as? Delivery.Failed)?.linkBroken == true
            ) {
                break
            }
        }
        return Report(delivered, ready.size - delivered.size, reachable, problems)
    }

    /** A verified export of [journal], or null when it is not a stopped run that can be sent. */
    private fun prepare(journal: Path, problems: MutableList<String>): VerifiedExport? =
        verified[journal]?.takeIf(FitExporter::isUnchanged) ?: try {
            // Null while still recording or awaiting recovery.
            stoppedRunId(journal)?.let { runId ->
                exportOf(journal, exports.resolve(RunFiles.fitName(runId)))
                    .also { verified[journal] = it }
            }
        } catch (e: IOException) {
            problems += "${journal.name}: cannot read or export: ${e.message}"
            null
        } catch (e: IllegalArgumentException) {
            problems += "${journal.name}: not exportable, kept: ${e.message}"
            null
        } catch (e: ExportVerificationException) {
            problems += "${journal.name}: export does not verify, kept: ${e.problems.firstOrNull()}"
            null
        } catch (e: FitFormatException) {
            problems += "${journal.name}: export does not decode, kept: ${e.message}"
            null
        }

    private fun stoppedRunId(journal: Path): String? {
        val contents = JournalReader.read(journal)
        return contents.runId?.value?.takeIf { contents.isStopped }
    }

    /** Reuses an earlier file so its hash stays the same; a file that no longer verifies is written again. */
    private fun exportOf(journal: Path, fit: Path): VerifiedExport {
        if (fit.isRegularFile()) {
            try {
                return FitExporter.verifyExisting(journal, fit, config)
            } catch (@Suppress("SwallowedException") e: ExportVerificationException) {
                // Removed by verifyExisting; written again below.
            } catch (@Suppress("SwallowedException") e: FitFormatException) {
                // Removed by verifyExisting; written again below.
            }
        }
        return FitExporter.export(journal, fit, config, utcOffsetSeconds())
    }

    private sealed interface Delivery {
        data object Acknowledged : Delivery
        data object Unreachable : Delivery
        data class Failed(val reason: String, val linkBroken: Boolean) : Delivery
    }

    private fun deliver(export: VerifiedExport, link: PhoneLink): Delivery {
        val offer = FitOffer(export.runId.value, export.sizeBytes, export.sha256)
        val ack = try {
            exchange(link, export, offer)
        } catch (e: IOException) {
            return Delivery.Failed("transfer failed: ${e.message}", linkBroken = true)
        }
        return when {
            ack == null -> Delivery.Unreachable

            !ack.stored -> Delivery.Failed("phone refused: ${ack.reason}", linkBroken = false)

            !ack.confirms(
                offer
            ) -> Delivery.Failed("ack for another file: $ack", linkBroken = false)

            // The ack is for the verified content; the journal goes only if the file still has it.
            !FitExporter.isUnchanged(export) -> {
                verified.remove(export.journal)
                Delivery.Failed("FIT file changed after verification", linkBroken = false)
            }

            else -> Delivery.Acknowledged
        }
    }

    /** Sends [offer] and the file on a new channel; null when no phone is reachable. */
    private fun exchange(link: PhoneLink, export: VerifiedExport, offer: FitOffer): FitAck? =
        link.open()?.use { channel ->
            Files.newInputStream(export.fit).use { body ->
                SyncProtocol.writeOffer(channel.output, offer, body)
            }
            SyncProtocol.readAck(channel.input)
        }

    private fun deleteJournal(journal: Path) = synchronized(storageLock) {
        verified.remove(journal)
        // The storage reserve of the recording (ADR 0008) first: a journal without it is still a valid run.
        Files.deleteIfExists(journal.resolveSibling(journal.name + RESERVE_SUFFIX))
        Files.deleteIfExists(journal)
        syncDirectory(journals)
    }

    companion object {
        const val JOURNAL_SUFFIX: String = ".journal"
        internal const val RESERVE_SUFFIX: String = ".reserve"
    }
}
