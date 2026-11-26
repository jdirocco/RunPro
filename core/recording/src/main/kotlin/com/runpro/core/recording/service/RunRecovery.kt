package com.runpro.core.recording.service

import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RecordingEngine

/**
 * Restores a run into a fresh [RecordingEngine] after the process was killed during it (R2).
 * `RecordingService` calls it on a `START_STICKY` restart; the journal-based implementation is issue #9.
 */
fun interface RunRecovery {
    /** Returns true when a run was restored and is recording again in [engine]. */
    fun recover(engine: RecordingEngine, now: Timestamp): Boolean

    companion object {
        /** Until the journal is written during a run (#60) there is nothing to restore. */
        val NONE = RunRecovery { _, _ -> false }
    }
}
