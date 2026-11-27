package com.runpro.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.runpro.core.model.RunId
import com.runpro.core.recording.LapTotals
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.core.recording.WorkoutStage
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Main-thread feedback observes snapshots; it never calls back into or waits on the recording path. */
internal class WorkoutFeedback(context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var runId: RunId? = null
    private var closedLaps = 0
    private var countdown: Pair<Int, Int>? = null

    fun update(state: RunState) {
        val id = state.run?.id ?: return
        val closed = state.laps.count { !it.lap.isOpen }
        if (id != runId) {
            runId = id
            closedLaps = closed
            countdown = null
            return
        }
        val lapChanged = closed > closedLaps
        closedLaps = closed
        val remaining = state.workout?.remainingTime?.let {
            ceil(it.inWholeMilliseconds / MILLIS_PER_SECOND).toInt()
        }
        val key = closed to (remaining ?: 0)
        if (state.phase == RunPhase.RUNNING) {
            if (lapChanged) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, LAP_PULSE, LAP_GAP, LAP_PULSE),
                        -1
                    )
                )
            } else if (remaining in 1..COUNTDOWN_SECONDS && key != countdown) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        COUNTDOWN_PULSE,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            }
        }
        countdown = key
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val COUNTDOWN_SECONDS = 3
        const val COUNTDOWN_PULSE = 60L
        const val LAP_PULSE = 200L
        const val LAP_GAP = 100L
    }
}

@Composable
internal fun WorkoutCountdown(state: RunState) {
    val progress = state.workout ?: return
    if (progress.completed) {
        Text(stringResource(R.string.workout_complete))
        return
    }
    val label = stringResource(
        when (progress.stage) {
            WorkoutStage.WARM_UP -> R.string.workout_warm_up
            WorkoutStage.WORK -> R.string.workout_work
            WorkoutStage.RECOVERY -> R.string.workout_recovery
            WorkoutStage.COOL_DOWN -> R.string.workout_cool_down
        }
    )
    val remaining = progress.remainingTime?.let {
        // Round up so a stage never shows 0:00 before the transition.
        clock(ceil(it.inWholeMilliseconds / 1000.0).toInt().seconds)
    } ?: stringResource(R.string.workout_meters, ceil(progress.remainingMeters ?: 0.0).toInt())
    Text(
        stringResource(
            R.string.workout_progress,
            label,
            progress.repetition,
            progress.repetitions,
            remaining
        )
    )
}

/**
 * Shows the last closed lap for [LAP_POPUP] of run time after it closed. The engine clock (advanced by
 * the service's 1 s ticks) drives the dismissal, so reopening the app later shows no stale summary.
 */
@Composable
internal fun LapSummaryPopup(state: RunState) {
    val lap = lapToSummarize(state)
    var dismissed by remember(state.run?.id) { mutableStateOf<Int?>(null) }
    if (lap == null || lap.lap.index == dismissed) return
    // An in-tree overlay instead of a dialog window: always on top of the run screen, tap dismisses.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable { dismissed = lap.lap.index },
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(
                R.string.workout_lap_summary,
                lap.lap.index + 1,
                lap.totals.distance.meters.toInt(),
                clock(lap.totals.timer)
            ),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
    }
}

internal fun lapToSummarize(state: RunState): LapTotals? {
    val run = state.run?.takeIf { state.phase == RunPhase.RUNNING } ?: return null
    val now = run.startedAt + state.totals.elapsed
    return state.laps.lastOrNull { !it.lap.isOpen }?.takeIf { closed ->
        val age = closed.lap.end?.let(now::elapsedSince)
        age != null && age >= Duration.ZERO && age < LAP_POPUP
    }
}

private val LAP_POPUP = 5.seconds
