package com.runpro.core.recording

import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.SourceId
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import kotlin.time.Duration.Companion.seconds

/** Only consecutive fresh, admitted speed readings can pause a run; silence never does. */
internal class AutoPause {
    private var slowSince: Timestamp? = null
    private var previous: Timestamp? = null
    private var source: SourceId? = null

    fun reset() {
        slowSince = null
        previous = null
        source = null
    }

    fun command(sample: SpeedSample, state: RunState, now: Timestamp): EngineInput.Command? {
        // Ticks and other sensors can lead ordinary Health Services delivery by about a second.
        // Allow that latency, but never make automatic decisions from an old screen-off batch.
        if (now.elapsedSince(sample.time) > MAX_GAP) {
            reset()
            return null
        }
        val time = sample.time
        val gap = previous?.let { time.elapsedSince(it) }
        if (source != sample.source || gap == null || gap > MAX_GAP) slowSince = null
        previous = time
        source = sample.source
        val speed = sample.speed.metersPerSecond
        return when {
            state.phase == RunPhase.PAUSED -> {
                slowSince = null
                val pause = state.run?.events?.filterIsInstance<RunEvent.Paused>()?.lastOrNull()
                if (pause?.reason == PauseReason.AUTO && speed >= RESUME_SPEED) {
                    EngineInput.Resume(time)
                } else {
                    null
                }
            }

            state.phase == RunPhase.RUNNING && speed < PAUSE_SPEED -> pauseIfDue(time)

            else -> {
                slowSince = null
                null
            }
        }
    }

    private fun pauseIfDue(time: Timestamp): EngineInput.Command? {
        val since = slowSince ?: time.also { slowSince = it }
        return if (time.elapsedSince(since) >= SLOW_PERIOD) {
            reset()
            EngineInput.Pause(time, PauseReason.AUTO)
        } else {
            null
        }
    }

    private companion object {
        const val PAUSE_SPEED = 0.5
        const val RESUME_SPEED = 1.0
        val SLOW_PERIOD = 5.seconds
        val MAX_GAP = 3.seconds
    }
}
