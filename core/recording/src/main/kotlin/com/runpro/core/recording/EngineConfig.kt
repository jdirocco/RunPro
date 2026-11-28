package com.runpro.core.recording

import com.runpro.core.model.Distance
import com.runpro.core.model.Speed
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning of the derived metrics. The defaults are starting points; field tests (M1) may change them.
 *
 * @property minMovingSpeed below this speed (from distance deltas) the runner is standing still and the
 *   interval does not count as moving time.
 * @property maxSampleHold how long a heart rate or cadence value stays valid without a new sample, in timer
 *   time. Averages are weighted by time, so a gap in the data must not stretch the last value forever.
 * @property elevationThreshold hysteresis for ascent and descent: changes smaller than this are noise.
 */
data class EngineConfig(
    val minMovingSpeed: Speed = Speed(DEFAULT_MIN_MOVING_SPEED),
    val maxSampleHold: Duration = DEFAULT_MAX_SAMPLE_HOLD_SECONDS.seconds,
    val elevationThreshold: Distance = Distance(DEFAULT_ELEVATION_THRESHOLD),
    val sourcePolicy: SourcePolicy = SourcePolicy()
) {
    init {
        require(maxSampleHold.isPositive()) { "maxSampleHold must be positive: $maxSampleHold" }
        require(elevationThreshold.meters > 0.0) {
            "elevationThreshold must be positive: $elevationThreshold"
        }
    }

    private companion object {
        const val DEFAULT_MIN_MOVING_SPEED = 0.5
        const val DEFAULT_MAX_SAMPLE_HOLD_SECONDS = 10
        const val DEFAULT_ELEVATION_THRESHOLD = 2.0
    }
}
