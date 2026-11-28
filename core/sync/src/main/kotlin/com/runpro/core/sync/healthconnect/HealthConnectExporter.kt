package com.runpro.core.sync.healthconnect

import com.runpro.core.fit.FitDecoder
import com.runpro.core.fit.FitFormatException
import com.runpro.core.sync.sha256
import com.runpro.core.sync.syncDirectory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Platform seam for writing a mapped [HealthConnectRun] to Health Connect.
 * Implemented by `:mobile` on top of `HealthConnectClient`.
 */
interface HealthConnectPort {
    enum class Availability {
        AVAILABLE,
        UPDATE_REQUIRED,
        UNAVAILABLE
    }

    sealed interface WriteOutcome {
        data object Written : WriteOutcome
        data class MissingPermissions(val missing: Set<String>) : WriteOutcome
        data class Failed(val reason: String) : WriteOutcome
    }

    fun availability(): Availability

    suspend fun write(run: HealthConnectRun): WriteOutcome
}

/**
 * Coordinates exporting received FIT files from [runsDir] (`files/runs`) to Health Connect without
 * ever modifying or deleting the FIT files themselves (Strava and history read the same directory).
 *
 * A small atomic ledger in [stateDir] tracks each exported file's SHA-256 and client record version
 * so unchanged runs are skipped and replaced FIT files update in place with a bumped version.
 */
class HealthConnectExporter(
    private val runsDir: Path,
    private val stateDir: Path,
    private val port: HealthConnectPort,
    private val zoneOffsetAt: (Instant) -> ZoneOffset? = { null }
) {
    sealed interface FileOutcome {
        data class Exported(val runKey: String, val version: Long) : FileOutcome
        data class AlreadyExported(val fileName: String) : FileOutcome
        data class Unavailable(val availability: HealthConnectPort.Availability) : FileOutcome
        data class MissingPermissions(val missing: Set<String>) : FileOutcome
        data class Failed(val fileName: String, val reason: String) : FileOutcome
    }

    data class BatchOutcome(
        val availability: HealthConnectPort.Availability,
        val exported: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val missingPermissions: Set<String> = emptySet(),
        val failed: Map<String, String> = emptyMap()
    )

    private val ledger = HealthConnectExportLedger(stateDir)

    /** Exports every pending or updated `.fit` file in [runsDir]. */
    suspend fun exportPending(): BatchOutcome {
        val availability = port.availability()
        if (availability != HealthConnectPort.Availability.AVAILABLE) {
            return BatchOutcome(availability = availability)
        }
        return exportBatch(availability, listFitFiles())
    }

    /** Exports a single received FIT file if its current bytes have not been exported yet. */
    suspend fun exportFile(fitFile: Path): FileOutcome {
        val availability = port.availability()
        return if (availability != HealthConnectPort.Availability.AVAILABLE) {
            FileOutcome.Unavailable(availability)
        } else {
            exportFileInternal(fitFile)
        }
    }

    private suspend fun exportBatch(
        initialAvailability: HealthConnectPort.Availability,
        fitFiles: List<Path>
    ): BatchOutcome {
        val exported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        var availability = initialAvailability
        var missingPermissions: Set<String> = emptySet()

        for (file in fitFiles) {
            var stopBatch = false
            when (val outcome = exportFileInternal(file)) {
                is FileOutcome.Exported -> exported += outcome.runKey

                is FileOutcome.AlreadyExported -> skipped += outcome.fileName

                is FileOutcome.Failed -> failed[outcome.fileName] = outcome.reason

                is FileOutcome.Unavailable -> {
                    availability = outcome.availability
                    stopBatch = true
                }

                is FileOutcome.MissingPermissions -> {
                    missingPermissions = outcome.missing
                    stopBatch = true
                }
            }
            if (stopBatch) break
        }

        return BatchOutcome(
            availability = availability,
            exported = exported,
            skipped = skipped,
            missingPermissions = missingPermissions,
            failed = failed
        )
    }

    private suspend fun exportFileInternal(fitFile: Path): FileOutcome {
        val fileName = fitFile.name
        val hash = readHash(fitFile).getOrElse { error ->
            return FileOutcome.Failed(fileName, "read failed: ${error.message}")
        }
        val previous = ledger.get(fileName)
        return if (previous != null && previous.sha256 == hash) {
            FileOutcome.AlreadyExported(fileName)
        } else {
            writeMappedFile(fitFile, fileName, hash, (previous?.version ?: 0L) + 1L)
        }
    }

    private suspend fun writeMappedFile(
        fitFile: Path,
        fileName: String,
        hash: String,
        nextVersion: Long
    ): FileOutcome {
        val mapped = decodeAndMap(fitFile, nextVersion).getOrElse { error ->
            return FileOutcome.Failed(fileName, error.message ?: error.javaClass.simpleName)
        }
        return when (val result = port.write(mapped)) {
            HealthConnectPort.WriteOutcome.Written -> {
                ledger.record(fileName, hash, nextVersion)
                FileOutcome.Exported(mapped.runKey, nextVersion)
            }

            is HealthConnectPort.WriteOutcome.MissingPermissions ->
                FileOutcome.MissingPermissions(result.missing)

            is HealthConnectPort.WriteOutcome.Failed ->
                FileOutcome.Failed(fileName, result.reason)
        }
    }

    private fun readHash(fitFile: Path): Result<String> = try {
        Result.success(sha256(fitFile))
    } catch (e: IOException) {
        Result.failure(e)
    }

    private fun decodeAndMap(fitFile: Path, version: Long): Result<HealthConnectRun> = try {
        val activity = FitDecoder.decode(Files.readAllBytes(fitFile))
        val fallbackKey = fitFile.name.removeSuffix(FIT_SUFFIX)
        Result.success(
            FitToHealthConnectMapper.map(
                activity = activity,
                fallbackRunKey = fallbackKey,
                clientRecordVersion = version,
                zoneOffsetAt = zoneOffsetAt
            )
        )
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: FitFormatException) {
        Result.failure(e)
    } catch (e: HealthConnectMappingException) {
        Result.failure(e)
    }

    private fun listFitFiles(): List<Path> = if (!Files.isDirectory(runsDir)) {
        emptyList()
    } else {
        try {
            runsDir.listDirectoryEntries("*$FIT_SUFFIX")
                .filter { it.isRegularFile() }
                .sortedBy { it.name }
        } catch (@Suppress("SwallowedException") e: IOException) {
            emptyList()
        }
    }

    private companion object {
        const val FIT_SUFFIX = ".fit"
    }
}

private data class ExportedEntry(val sha256: String, val version: Long)

/**
 * Atomic tab-separated ledger (`fileName\tsha256\tversion`) stored in `files/health-connect/`.
 * Loss of the ledger is harmless: Health Connect deduplicates upserts via `clientRecordId`.
 */
private class HealthConnectExportLedger(private val stateDir: Path) {
    private val file: Path = stateDir.resolve(LEDGER_FILE)

    fun get(fileName: String): ExportedEntry? = synchronized(LOCK) {
        readAll()[fileName]
    }

    fun record(fileName: String, hash: String, version: Long) = synchronized(LOCK) {
        val entries = readAll().toMutableMap()
        entries[fileName] = ExportedEntry(hash, version)
        writeAll(entries)
    }

    private fun readAll(): Map<String, ExportedEntry> {
        val lines = readLedgerLines()
        return lines.mapNotNull(::parseEntry).toMap()
    }

    private fun readLedgerLines(): List<String> = if (!file.isRegularFile()) {
        emptyList()
    } else {
        try {
            Files.readAllLines(file, Charsets.UTF_8)
        } catch (@Suppress("SwallowedException") e: IOException) {
            emptyList()
        }
    }

    private fun parseEntry(line: String): Pair<String, ExportedEntry>? {
        val parts = line.split('\t')
        val version = parts.getOrNull(2)?.toLongOrNull()
        return if (parts.size == COLUMNS && version != null) {
            parts[0] to ExportedEntry(parts[1], version)
        } else {
            null
        }
    }

    private fun writeAll(entries: Map<String, ExportedEntry>) {
        Files.createDirectories(stateDir)
        val temp = Files.createTempFile(stateDir, ".ledger-", ".tmp")
        try {
            val body = entries.toSortedMap().entries.joinToString("\n") { (name, entry) ->
                "$name\t${entry.sha256}\t${entry.version}"
            }
            Files.writeString(temp, if (body.isEmpty()) "" else "$body\n", Charsets.UTF_8)
            moveIntoPlace(temp, file)
            syncDirectory(stateDir)
        } finally {
            Files.deleteIfExists(temp)
        }
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
        val LOCK = Any()
        const val LEDGER_FILE = "exported.tsv"
        const val COLUMNS = 3
    }
}
