package com.runpro.wear

import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState

/** Auto-paused runs still need speed updates to detect movement and resume. */
internal fun exercisePhase(state: RunState): RunPhase = if (state.phase == RunPhase.PAUSED &&
    state.run?.events?.filterIsInstance<RunEvent.Paused>()?.lastOrNull()?.reason ==
    PauseReason.AUTO
) {
    RunPhase.RUNNING
} else {
    state.phase
}
