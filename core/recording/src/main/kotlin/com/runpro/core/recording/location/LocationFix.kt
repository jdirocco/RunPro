package com.runpro.core.recording.location

import com.runpro.core.model.Sample

/**
 * One fix of the fused location provider, as the platform reported it (ADR 0019).
 *
 * @property elapsedNanos when the fix was measured, on the monotonic boot clock
 *   (`Location.elapsedRealtimeNanos`).
 * @property horizontalAccuracyMeters radius of 68% confidence; NaN when the provider gave none.
 * @property speedMetersPerSecond Doppler speed over ground; NaN when the provider gave none.
 * @property mock the fix comes from a mock location provider.
 */
data class LocationFix(
    val elapsedNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double,
    val speedMetersPerSecond: Double = Double.NaN,
    val mock: Boolean = false
)

/** Why the gate kept a fix out of the run. */
enum class FixRejection {
    /** Coordinates out of range, NaN, or no valid measurement time. */
    INVALID,

    /** From a mock location provider. */
    MOCK,

    /** The provider reported no horizontal accuracy. */
    NO_ACCURACY,

    /** Accuracy worse than the limit: Wi-Fi or cell triangulation, not a satellite fix. */
    COARSE,

    /** Measured too long before it arrived: a cached last location. */
    STALE,

    /** Not newer than the last admitted fix. */
    OUT_OF_ORDER,

    /** Implies an impossible speed from the last admitted fix and was not confirmed. */
    JUMP
}

/** The gate's decision on one [LocationFix]. */
sealed interface FixVerdict {
    /** The fix enters the run as these samples, all of `watch:fused-location`. */
    data class Accepted(val samples: List<Sample>) : FixVerdict

    data class Rejected(val reason: FixRejection) : FixVerdict
}
