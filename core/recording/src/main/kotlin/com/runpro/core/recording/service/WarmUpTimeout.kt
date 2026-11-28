package com.runpro.core.recording.service

import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Ends a forgotten warm-up (#82): after **Get ready** the service keeps GPS, heart rate and its notification
 * on until the runner taps Start or Back. Fed with the phase at every service tick, it asks once for a cancel
 * (`EngineInput.Reset`) when the warm-up has lasted [limit] without a start.
 *
 * R1: it never answers true outside [RunPhase.PREPARING], and the engine ignores a reset while the run records,
 * so a reset racing with a start cannot discard a run.
 *
 * Not thread-safe: call it from one thread (the service ticker).
 */
class WarmUpTimeout(val limit: Duration = DEFAULT_LIMIT) {
    private var since: Timestamp? = null
    private var cancelled = false

    /** True once per warm-up, at the first tick [limit] after its first tick. */
    fun shouldCancel(phase: RunPhase, now: Timestamp): Boolean {
        if (phase != RunPhase.PREPARING) {
            since = null
            cancelled = false
            return false
        }
        val start = since ?: now.also { since = it }
        val waited = (now.elapsedRealtimeNanos - start.elapsedRealtimeNanos).nanoseconds
        val cancel = !cancelled && waited >= limit
        if (cancel) cancelled = true
        return cancel
    }

    companion object {
        val DEFAULT_LIMIT: Duration = 10.minutes
    }
}
