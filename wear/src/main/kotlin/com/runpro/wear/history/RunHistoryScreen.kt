package com.runpro.wear.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.runpro.core.model.RunId
import com.runpro.core.sync.RunHistoryEntry
import com.runpro.core.sync.RunSyncState
import com.runpro.wear.R
import com.runpro.wear.WideButton
import kotlinx.coroutines.flow.StateFlow

private sealed interface HistoryRoute {
    data object List : HistoryRoute
    data class Detail(val runId: RunId) : HistoryRoute
    data class ConfirmDelete(val runId: RunId) : HistoryRoute
}

@Composable
fun RunHistoryHost(
    historyState: StateFlow<HistoryUiState>,
    onRetryExport: (RunId) -> Unit,
    onDelete: (RunId) -> Unit,
    onClearFeedback: () -> Unit,
    onClose: () -> Unit,
    formatDate: (Long) -> String = ::formatRunDate
) {
    val state by historyState.collectAsState()
    var route by remember { mutableStateOf<HistoryRoute>(HistoryRoute.List) }
    val selectedId = when (val current = route) {
        HistoryRoute.List -> null
        is HistoryRoute.Detail -> current.runId
        is HistoryRoute.ConfirmDelete -> current.runId
    }
    val selectedEntry = selectedId?.let { id -> state.runs.firstOrNull { it.runId == id } }
    val effectiveRoute = if (selectedId != null && selectedEntry == null) {
        HistoryRoute.List
    } else {
        route
    }

    BackHandler {
        when (effectiveRoute) {
            HistoryRoute.List -> onClose()

            is HistoryRoute.Detail -> {
                onClearFeedback()
                route = HistoryRoute.List
            }

            is HistoryRoute.ConfirmDelete -> route = HistoryRoute.Detail(effectiveRoute.runId)
        }
    }

    MaterialTheme {
        ScreenScaffold {
            when (effectiveRoute) {
                HistoryRoute.List -> HistoryListScreen(
                    state = state,
                    formatDate = formatDate,
                    onOpenRun = { id ->
                        onClearFeedback()
                        route = HistoryRoute.Detail(id)
                    },
                    onClose = onClose
                )

                is HistoryRoute.Detail -> HistoryDetailScreen(
                    entry = requireNotNull(selectedEntry),
                    busy = state.busyRunId == selectedEntry.runId,
                    feedback = state.feedback?.takeIf { it.matches(selectedEntry.runId) },
                    formatDate = formatDate,
                    onRetryExport = onRetryExport,
                    onRequestDelete = { route = HistoryRoute.ConfirmDelete(it) },
                    onBack = {
                        onClearFeedback()
                        route = HistoryRoute.List
                    }
                )

                is HistoryRoute.ConfirmDelete -> DeleteConfirmScreen(
                    entry = requireNotNull(selectedEntry),
                    onConfirm = { id ->
                        onDelete(id)
                        route = HistoryRoute.List
                    },
                    onCancel = { route = HistoryRoute.Detail(effectiveRoute.runId) }
                )
            }
        }
    }
}

@Composable
private fun HistoryListScreen(
    state: HistoryUiState,
    formatDate: (Long) -> String,
    onOpenRun: (RunId) -> Unit,
    onClose: () -> Unit
) {
    ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleMedium
            )
        }
        when {
            state.loading && state.runs.isEmpty() -> {
                item { CenteredText(stringResource(R.string.history_loading)) }
            }

            state.runs.isEmpty() -> {
                item { CenteredText(stringResource(R.string.history_empty)) }
            }

            else -> items(state.runs, key = { it.runId.value }) { entry ->
                RunHistoryRow(entry, formatDate) { onOpenRun(entry.runId) }
            }
        }
        item { WideButton(stringResource(R.string.history_back), onClick = onClose) }
    }
}

@Composable
private fun RunHistoryRow(
    entry: RunHistoryEntry,
    formatDate: (Long) -> String,
    onClick: () -> Unit
) {
    val metrics = entry.summary?.let { summary ->
        stringResource(
            R.string.history_row_metrics,
            formatDistance(summary.distance),
            formatDuration(summary.timer)
        )
    } ?: entry.runId.value
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(formatDate(entry.startedAtMillis), textAlign = TextAlign.Center)
            Text(metrics, textAlign = TextAlign.Center)
            Text(syncStateText(entry.syncState), textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun CenteredText(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
internal fun syncStateText(state: RunSyncState): String = stringResource(
    when (state) {
        RunSyncState.PENDING_EXPORT -> R.string.history_sync_pending_export
        RunSyncState.PENDING_SYNC -> R.string.history_sync_waiting_phone
        RunSyncState.SYNCED -> R.string.history_sync_synced
        RunSyncState.EXPORT_FAILED -> R.string.history_sync_export_failed
    }
)

private fun HistoryFeedback.matches(runId: RunId): Boolean = when (this) {
    is HistoryFeedback.Exported -> this.runId == runId
    is HistoryFeedback.AlreadySynced -> this.runId == runId
    is HistoryFeedback.ExportFailed -> this.runId == runId
}
