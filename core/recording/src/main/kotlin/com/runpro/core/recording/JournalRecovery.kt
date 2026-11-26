package com.runpro.core.recording

import com.runpro.core.journal.JournalPolicy
import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.journal.MonotonicClock
import com.runpro.core.model.StopReason
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Resume requires the service to have verified that the journal belongs to the current boot. */
enum class RecoveryMode {
    RESUME_SAME_BOOT,

    /** Offer to save on ordinary app launch, after reboot, or when boot continuity is unknown. */
    SAVE
}

data class RecoveryScan(val pending: List<Path>, val unreadable: Map<Path, String>)

/** Startup/recovery disk work; invoke on a worker, e.g. RecordingEngine's sessionFactory. Never deletes files. */
object JournalRecovery {
    /** Finds .journal files with a start and no stop, isolating invalid/newer files for diagnostics. */
    fun scan(directory: Path): RecoveryScan {
        if (!Files.exists(directory)) return RecoveryScan(emptyList(), emptyMap())
        val pending = mutableListOf<Path>()
        val unreadable = linkedMapOf<Path, String>()
        Files.list(directory).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".journal") }
                .sorted().forEach { file ->
                    try {
                        val contents = JournalReader.read(file)
                        if (contents.runId != null && !contents.isStopped) pending.add(file)
                    } catch (e: IOException) {
                        unreadable[file] = e.message ?: e.javaClass.simpleName
                    }
                }
        }
        return RecoveryScan(pending, unreadable)
    }

    /**
     * Replays intact records without writing them again, then continues after the valid prefix. SAVE
     * closes at the latest retained timestamp, never at a timestamp from the new boot or the wall clock.
     * No writer may be using [file]. Unknown records are refused before any mutation.
     */
    fun restore(
        file: Path,
        mode: RecoveryMode = RecoveryMode.SAVE,
        config: EngineConfig = EngineConfig(),
        clock: MonotonicClock = MonotonicClock.SYSTEM,
        policy: JournalPolicy = JournalPolicy()
    ): RecordingSession {
        val contents = JournalReader.read(file)
        require(contents.skippedRecords == 0) { "Cannot recover a journal with unknown records" }
        require(!contents.isStopped) { "The run is already stopped" }
        val start = contents.records.firstOrNull() as? JournalRecord.RunStarted
        requireNotNull(start) { "The journal must begin with a run start" }
        // Validate/rebuild before opening for writes so invalid histories remain untouched.
        val rebuilt = RecordingStateMachine(config) { start.runId }
        rebuilt.handle(EngineInput.Prepare(start.time))
        rebuilt.handle(EngineInput.Start(start.time))
        val last = replayJournal(contents, rebuilt)
        val session = RecordingSession(rebuilt, JournalWriter.resume(file, clock, policy))
        if (mode == RecoveryMode.SAVE) {
            try {
                session.handle(EngineInput.Stop(last, StopReason.RECOVERY))
            } catch (e: IOException) {
                session.abort()
                throw e
            }
        }
        return session
    }
}
