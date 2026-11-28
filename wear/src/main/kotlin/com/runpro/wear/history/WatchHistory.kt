package com.runpro.wear.history

import android.content.Context
import com.runpro.core.model.RunId
import com.runpro.core.sync.RetryExportResult
import com.runpro.core.sync.RunHistoryEntry
import com.runpro.core.sync.RunHistoryStore
import com.runpro.wear.sync.PhoneSync
import com.runpro.wear.sync.WatchStorage
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One-shot outcome shown on the run detail screen after tapping Retry export. */
sealed interface HistoryFeedback {
    data class Exported(val runId: RunId) : HistoryFeedback
    data class AlreadySynced(val runId: RunId) : HistoryFeedback
    data class ExportFailed(val runId: RunId, val reason: String) : HistoryFeedback
}

/** UI state observed by the watch history screens. */
data class HistoryUiState(
    val loading: Boolean = true,
    val runs: List<RunHistoryEntry> = emptyList(),
    val busyRunId: RunId? = null,
    val feedback: HistoryFeedback? = null
)

/**
 * Coordinates watch history disk work on its own worker thread, never blocking the main or recording
 * thread (R6).
 */
class WatchHistory(
    private val store: RunHistoryStore,
    private val requestSync: () -> Unit,
    private val executor: Executor,
    private val onClose: () -> Unit = {}
) : AutoCloseable {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state

    fun refresh() {
        executor.execute {
            val runs = store.list()
            _state.update { it.copy(loading = false, runs = runs) }
        }
    }

    fun retryExport(runId: RunId) {
        _state.update { it.copy(busyRunId = runId, feedback = null) }
        executor.execute {
            val feedback = when (val result = store.retryExport(runId)) {
                is RetryExportResult.Exported -> {
                    requestSync()
                    HistoryFeedback.Exported(runId)
                }

                is RetryExportResult.AlreadySynced -> HistoryFeedback.AlreadySynced(runId)

                is RetryExportResult.Failed -> HistoryFeedback.ExportFailed(runId, result.reason)

                is RetryExportResult.NotFound -> null
            }
            val runs = store.list()
            _state.update {
                it.copy(loading = false, runs = runs, busyRunId = null, feedback = feedback)
            }
        }
    }

    fun delete(runId: RunId) {
        _state.update { it.copy(busyRunId = runId, feedback = null) }
        executor.execute {
            store.delete(runId)
            val runs = store.list()
            _state.update {
                it.copy(loading = false, runs = runs, busyRunId = null, feedback = null)
            }
        }
    }

    fun clearFeedback() {
        _state.update { it.copy(feedback = null) }
    }

    override fun close() = onClose()

    companion object {
        private const val MILLIS_PER_SECOND = 1000

        fun forContext(context: Context): WatchHistory {
            val app = context.applicationContext
            val worker = Executors.newSingleThreadExecutor { task ->
                Thread(task, "runpro-history").apply { isDaemon = true }
            }
            val store = RunHistoryStore(
                journals = WatchStorage.journals(app).toPath(),
                exports = WatchStorage.exports(app).toPath(),
                utcOffsetSeconds = {
                    TimeZone.getDefault().getOffset(System.currentTimeMillis()) / MILLIS_PER_SECOND
                },
                storageLock = WatchStorage.lock
            )
            return WatchHistory(
                store = store,
                requestSync = { PhoneSync.request(app) },
                executor = worker,
                onClose = worker::shutdown
            )
        }
    }
}
