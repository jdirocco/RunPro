package com.runpro.core.sync

import com.runpro.core.fit.ExportVerificationException
import com.runpro.core.fit.FitActivity
import com.runpro.core.fit.FitDecoder
import com.runpro.core.fit.FitExporter
import com.runpro.core.fit.FitFormatException
import com.runpro.core.journal.JournalContents
import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalRecord
import com.runpro.core.model.Cadence
import com.runpro.core.model.Distance
import com.runpro.core.model.HeartRate
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.Pace
import com.runpro.core.model.RunId
import com.runpro.core.recording.EngineConfig
import com.runpro.core.recording.RecordedRun
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration

/** Sync state of a finished run stored on the watch, derived from the files on disk. */
enum class RunSyncState {
    /** Stopped journal on disk; FIT file has not been exported yet. */
    PENDING_EXPORT,

    /** Stopped journal and FIT export on disk; waiting for the phone to acknowledge it. */
    PENDING_SYNC,

    /** Acknowledged by the phone; the FIT file stays on the watch and the journal was removed (R3). */
    SYNCED,

    /** Stopped journal could not be rebuilt or exported; kept untouched on disk. */
    EXPORT_FAILED
}

/** Totals shown on the watch history list and run detail screen. */
data class RunSummary(
    val timer: Duration,
    val elapsed: Duration,
    val distance: Distance,
    val averagePace: Pace?,
    val averageHeartRate: HeartRate?,
    val maxHeartRate: HeartRate?,
    val averageCadence: Cadence?,
    val ascent: Distance,
    val descent: Distance
)

/** Per-lap totals shown on the watch run detail screen. */
data class RunLapSummary(
    val index: Int,
    val timer: Duration,
    val distance: Distance,
    val averagePace: Pace?,
    val averageHeartRate: HeartRate?,
    val trigger: LapTrigger?
)

/** One finished run in watch history, built from its journal (if unsynced) or FIT file (if synced). */
data class RunHistoryEntry(
    val runId: RunId,
    val startedAtMillis: Long,
    val syncState: RunSyncState,
    val summary: RunSummary?,
    val laps: List<RunLapSummary>,
    val problem: String? = null
) {
    /** True while a journal remains on the watch and can be exported or synced again. */
    val canRetryExport: Boolean get() = syncState != RunSyncState.SYNCED

    /** True while the run has not been acknowledged by the phone yet. */
    val isUnsynced: Boolean get() = syncState != RunSyncState.SYNCED
}

/** Outcome of an explicit export retry for a run in history. */
sealed interface RetryExportResult {
    /** Verified FIT file is present on disk and ready to sync to the phone. */
    data class Exported(val runId: RunId, val fit: Path) : RetryExportResult

    /** Run is already synced (no journal left on disk). */
    data class AlreadySynced(val runId: RunId) : RetryExportResult

    /** No stopped journal or FIT file found for this run. */
    data class NotFound(val runId: RunId) : RetryExportResult

    /** Export or verification failed; the journal was left untouched on disk (R3). */
    data class Failed(val runId: RunId, val reason: String) : RetryExportResult
}

/**
 * Watch run history backed directly by [journals] and [exports]: no separate index or database that could
 * disagree with the files on disk. Disk work: call on a worker thread, never on the main or recording
 * thread (R6).
 */
class RunHistoryStore(
    private val journals: Path,
    private val exports: Path,
    private val config: EngineConfig = EngineConfig(),
    private val utcOffsetSeconds: () -> Int = { 0 },
    private val storageLock: Any = Any()
) {
    private val exportFailures = mutableMapOf<RunId, String>()

    /** All finished runs, newest first. Open (recording or unrecovered) journals are excluded. */
    fun list(): List<RunHistoryEntry> = synchronized(storageLock) {
        val entries = mutableListOf<RunHistoryEntry>()
        val seenRunIds = mutableSetOf<RunId>()
        val seenFitNames = mutableSetOf<String>()
        scanJournals(entries, seenRunIds, seenFitNames)
        scanExports(entries, seenRunIds, seenFitNames)
        entries.sortedWith(
            compareByDescending<RunHistoryEntry> { it.startedAtMillis }
                .thenByDescending { it.runId.value }
        )
    }

    /** Looks up a single finished run by [runId], or null when absent or still open. */
    fun get(runId: RunId): RunHistoryEntry? = list().firstOrNull { it.runId == runId }

    /**
     * Exports (or re-verifies) the stopped journal of [runId] into [exports]. Never modifies or deletes
     * the journal (R3).
     */
    fun retryExport(runId: RunId): RetryExportResult = synchronized(storageLock) {
        when (val journalState = findJournal(journals, runId)) {
            is JournalLookup.Open -> RetryExportResult.NotFound(runId)

            is JournalLookup.Stopped -> exportJournal(runId, journalState.path)

            JournalLookup.Absent -> if (fitFile(runId).isRegularFile()) {
                exportFailures.remove(runId)
                RetryExportResult.AlreadySynced(runId)
            } else {
                RetryExportResult.NotFound(runId)
            }
        }
    }

    /**
     * Deletes a finished run from the watch after explicit user confirmation. Refuses to delete an open
     * (recording or unrecovered) journal. Returns true when at least one file was removed.
     */
    fun delete(runId: RunId): Boolean = synchronized(storageLock) {
        val journalState = findJournal(journals, runId)
        if (journalState is JournalLookup.Open) return false

        var deleted = false
        if (journalState is JournalLookup.Stopped) {
            val journal = journalState.path
            Files.deleteIfExists(journal.resolveSibling(journal.name + FitOutbox.RESERVE_SUFFIX))
            deleted = Files.deleteIfExists(journal) || deleted
            syncDirectory(journals)
        }
        val fit = fitFile(runId)
        if (Files.deleteIfExists(fit)) {
            deleted = true
            if (Files.isDirectory(exports)) syncDirectory(exports)
        }
        exportFailures.remove(runId)
        deleted
    }

    private fun scanJournals(
        entries: MutableList<RunHistoryEntry>,
        seenRunIds: MutableSet<RunId>,
        seenFitNames: MutableSet<String>
    ) {
        if (!Files.isDirectory(journals)) return
        listFiles(journals, "*${FitOutbox.JOURNAL_SUFFIX}")
            .mapNotNull(::readJournalOrNull)
            .forEach { contents ->
                val runId = contents.runId ?: return@forEach
                seenRunIds += runId
                seenFitNames += RunFiles.fitName(runId.value)
                if (contents.isStopped) entries += entryFromJournal(runId, contents)
            }
    }

    private fun scanExports(
        entries: MutableList<RunHistoryEntry>,
        seenRunIds: MutableSet<RunId>,
        seenFitNames: MutableSet<String>
    ) {
        if (!Files.isDirectory(exports)) return
        listFiles(exports, "*$FIT_SUFFIX")
            .filter { it.name !in seenFitNames }
            .forEach { file ->
                val entry = entryFromExport(file, seenRunIds)
                if (entry != null) {
                    exportFailures.remove(entry.runId)
                    entries += entry
                }
            }
    }

    private fun entryFromJournal(runId: RunId, contents: JournalContents): RunHistoryEntry {
        val fitExists = fitFile(runId).isRegularFile()
        val rebuilt = try {
            RecordedRun.from(contents, config)
        } catch (e: IllegalArgumentException) {
            val started = (contents.records.firstOrNull() as? JournalRecord.RunStarted)
                ?.time?.wallClockMillis ?: 0L
            return RunHistoryEntry(
                runId = runId,
                startedAtMillis = started,
                syncState = RunSyncState.EXPORT_FAILED,
                summary = null,
                laps = emptyList(),
                problem = e.message ?: e.javaClass.simpleName
            )
        }
        val failure = if (fitExists) {
            exportFailures.remove(runId)
            null
        } else {
            exportFailures[runId]
        }
        val state = when {
            fitExists -> RunSyncState.PENDING_SYNC
            failure != null -> RunSyncState.EXPORT_FAILED
            else -> RunSyncState.PENDING_EXPORT
        }
        return RunHistoryEntry(
            runId = runId,
            startedAtMillis = rebuilt.startedAt.wallClockMillis,
            syncState = state,
            summary = summaryFromTotals(rebuilt.state.totals),
            laps = rebuilt.state.laps.map(::lapFromTotals),
            problem = failure
        )
    }

    private fun exportJournal(runId: RunId, journal: Path): RetryExportResult {
        val fit = fitFile(runId)
        return try {
            Files.createDirectories(exports)
            val verified = exportOrVerify(journal, fit)
            exportFailures.remove(runId)
            RetryExportResult.Exported(runId, verified.fit)
        } catch (e: IOException) {
            recordFailure(runId, e.message ?: "I/O error")
        } catch (e: IllegalArgumentException) {
            recordFailure(runId, e.message ?: "Not exportable")
        } catch (e: ExportVerificationException) {
            recordFailure(runId, e.problems.firstOrNull() ?: "Export verification failed")
        } catch (e: FitFormatException) {
            recordFailure(runId, e.message ?: "FIT format error")
        }
    }

    private fun exportOrVerify(journal: Path, fit: Path) = if (fit.isRegularFile()) {
        try {
            FitExporter.verifyExisting(journal, fit, config)
        } catch (@Suppress("SwallowedException") e: ExportVerificationException) {
            FitExporter.export(journal, fit, config, utcOffsetSeconds())
        } catch (@Suppress("SwallowedException") e: FitFormatException) {
            FitExporter.export(journal, fit, config, utcOffsetSeconds())
        }
    } else {
        FitExporter.export(journal, fit, config, utcOffsetSeconds())
    }

    private fun recordFailure(runId: RunId, reason: String): RetryExportResult.Failed {
        exportFailures[runId] = reason
        return RetryExportResult.Failed(runId, reason)
    }

    private fun fitFile(runId: RunId): Path = exports.resolve(RunFiles.fitName(runId.value))
}

private const val FIT_SUFFIX = ".fit"

private sealed interface JournalLookup {
    data object Absent : JournalLookup
    data class Open(val path: Path) : JournalLookup
    data class Stopped(val path: Path) : JournalLookup
}

private fun findJournal(journals: Path, runId: RunId): JournalLookup {
    if (!Files.isDirectory(journals)) return JournalLookup.Absent
    val matches = listFiles(journals, "*${FitOutbox.JOURNAL_SUFFIX}").mapNotNull { file ->
        val contents = readJournalOrNull(file)
        if (contents?.runId == runId) file to contents else null
    }
    val open = matches.firstOrNull { !it.second.isStopped }
    return when {
        open != null -> JournalLookup.Open(open.first)
        matches.isNotEmpty() -> JournalLookup.Stopped(matches.last().first)
        else -> JournalLookup.Absent
    }
}

private fun entryFromExport(file: Path, seenRunIds: MutableSet<RunId>): RunHistoryEntry? {
    val activity = decodeFitOrNull(file)
    val session = activity?.session
    val runId = activity?.runId?.takeIf { it.isNotBlank() }?.let(::RunId)
        ?: RunId(file.name.removeSuffix(FIT_SUFFIX))
    return if (activity == null || session == null || !seenRunIds.add(runId)) {
        null
    } else {
        RunHistoryEntry(
            runId = runId,
            startedAtMillis = session.startMillis ?: activity.timeCreatedMillis ?: 0L,
            syncState = RunSyncState.SYNCED,
            summary = summaryFromFit(session),
            laps = activity.laps.mapIndexed(::lapFromFit)
        )
    }
}

private fun listFiles(directory: Path, glob: String): List<Path> = try {
    directory.listDirectoryEntries(glob).filter { it.isRegularFile() }.sortedBy { it.name }
} catch (@Suppress("SwallowedException") e: IOException) {
    emptyList()
}

@Suppress("SwallowedException")
private fun readJournalOrNull(file: Path): JournalContents? = try {
    JournalReader.read(file)
} catch (e: IOException) {
    null
}

@Suppress("SwallowedException")
private fun decodeFitOrNull(file: Path): FitActivity? = try {
    FitDecoder.decode(Files.readAllBytes(file))
} catch (e: IOException) {
    null
} catch (e: FitFormatException) {
    null
}
