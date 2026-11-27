package com.runpro.wear

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import com.runpro.core.model.Metric
import com.runpro.core.recording.RunState

/** Source comes from the engine's decision, including fallback and silence while paused. */
@Composable
internal fun HeartRateSource(state: RunState) {
    val source = state.activeSources[Metric.HEART_RATE]
    val label = when {
        source == null -> R.string.source_no_heart_rate
        source.isBle -> R.string.source_strap
        else -> R.string.source_wrist
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (source != null) {
            Image(
                painterResource(if (source.isBle) R.drawable.ic_strap else R.drawable.ic_wrist),
                contentDescription = stringResource(label),
                modifier = Modifier.size(24.dp)
            )
        }
        val bpm = state.current.heartRate?.bpm
        Text(
            if (bpm ==
                null
            ) {
                stringResource(label)
            } else {
                stringResource(R.string.source_heart_rate, bpm)
            }
        )
    }
}
