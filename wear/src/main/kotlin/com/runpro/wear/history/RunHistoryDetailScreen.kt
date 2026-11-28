package com.runpro.wear.history

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.runpro.core.model.RunId
import com.runpro.core.sync.RunHistoryEntry
import com.runpro.core.sync.RunLapSummary
import com.runpro.core.sync.RunSummary
import com.runpro.wear.R
import com.runpro.wear.WideButton
import kotlin.math.roundToInt

@Composable
internal fun HistoryDetailScreen(
    entry: RunHistoryEntry,
    busy: Boolean,
    feedback: HistoryFeedback?,
    formatDate: (Long) -> String,
    onRetryExport: (RunId) -> Unit,
    onRequestDelete: (RunId) -> Unit,
    onBack: () -> Unit
) {
    ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text(
                formatDate(entry.startedAtMillis),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        }
        item { CenteredText(syncStateText(entry.syncState)) }
        entry.problem?.let { problem ->
            item { CenteredText(problem) }
        }
        feedback?.let { message ->
            item { CenteredText(feedbackText(message)) }
        }
        entry.summary?.let { summary -> summaryItems(summary) }
        lapItems(entry.laps)
        detailActionItems(entry, busy, onRetryExport, onRequestDelete, onBack)
    }
}

private fun ScalingLazyListScope.lapItems(laps: List<RunLapSummary>) {
    if (laps.isEmpty()) return
    item {
        Text(
            stringResource(R.string.history_laps),
            style = MaterialTheme.typography.titleSmall
        )
    }
    items(laps, key = { it.index }) { lap ->
        CenteredText(lapText(lap))
    }
}

private fun ScalingLazyListScope.detailActionItems(
    entry: RunHistoryEntry,
    busy: Boolean,
    onRetryExport: (RunId) -> Unit,
    onRequestDelete: (RunId) -> Unit,
    onBack: () -> Unit
) {
    if (entry.canRetryExport) {
        item {
            WideButton(
                label = stringResource(
                    if (busy) R.string.history_exporting else R.string.history_retry_export
                ),
                enabled = !busy
            ) { onRetryExport(entry.runId) }
        }
    }
    item {
        WideButton(
            label = stringResource(R.string.history_delete),
            enabled = !busy
        ) { onRequestDelete(entry.runId) }
    }
    item { WideButton(stringResource(R.string.history_back), onClick = onBack) }
}

private fun ScalingLazyListScope.summaryItems(summary: RunSummary) {
    item {
        CenteredText(
            stringResource(
                R.string.history_summary_distance_time,
                formatDistance(summary.distance),
                formatDuration(summary.timer)
            )
        )
    }
    summary.averagePace?.let { pace ->
        item { CenteredText(stringResource(R.string.history_summary_pace, formatPace(pace))) }
    }
    summary.averageHeartRate?.let { avg ->
        val max = summary.maxHeartRate
        item {
            CenteredText(
                if (max != null) {
                    stringResource(R.string.history_summary_hr_avg_max, avg.bpm, max.bpm)
                } else {
                    stringResource(R.string.history_summary_hr_avg, avg.bpm)
                }
            )
        }
    }
    summary.averageCadence?.let { cadence ->
        item {
            CenteredText(
                stringResource(
                    R.string.history_summary_cadence,
                    cadence.stepsPerMinute.roundToInt()
                )
            )
        }
    }
    if (summary.ascent.meters > 0.0 || summary.descent.meters > 0.0) {
        item {
            CenteredText(
                stringResource(
                    R.string.history_summary_elevation,
                    summary.ascent.meters.roundToInt(),
                    summary.descent.meters.roundToInt()
                )
            )
        }
    }
    if (summary.elapsed > summary.timer) {
        item {
            CenteredText(
                stringResource(R.string.history_summary_elapsed, formatDuration(summary.elapsed))
            )
        }
    }
}

@Composable
internal fun DeleteConfirmScreen(
    entry: RunHistoryEntry,
    onConfirm: (RunId) -> Unit,
    onCancel: () -> Unit
) {
    ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text(
                stringResource(R.string.history_delete_confirm_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        }
        item {
            CenteredText(
                stringResource(
                    if (entry.isUnsynced) {
                        R.string.history_delete_confirm_unsynced
                    } else {
                        R.string.history_delete_confirm_synced
                    }
                )
            )
        }
        item {
            WideButton(stringResource(R.string.history_delete_confirm_action)) {
                onConfirm(entry.runId)
            }
        }
        item { WideButton(stringResource(R.string.history_cancel), onClick = onCancel) }
    }
}

@Composable
private fun feedbackText(feedback: HistoryFeedback): String = when (feedback) {
    is HistoryFeedback.Exported -> stringResource(R.string.history_feedback_exported)

    is HistoryFeedback.AlreadySynced -> stringResource(R.string.history_feedback_already_synced)

    is HistoryFeedback.ExportFailed -> stringResource(
        R.string.history_feedback_export_failed,
        feedback.reason
    )
}

@Composable
private fun lapText(lap: RunLapSummary): String {
    val parts = mutableListOf(
        stringResource(
            R.string.history_lap_row,
            lap.index + 1,
            formatDistance(lap.distance),
            formatDuration(lap.timer)
        )
    )
    lap.averagePace?.let { parts += formatPace(it) }
    lap.averageHeartRate?.let { parts += stringResource(R.string.source_heart_rate, it.bpm) }
    return parts.joinToString(" · ")
}
