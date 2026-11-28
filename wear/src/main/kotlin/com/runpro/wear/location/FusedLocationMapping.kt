package com.runpro.wear.location

import android.location.Location
import android.os.Build
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.runpro.core.recording.location.LocationFix

/** Translation between Play services fused location and the platform-free [LocationFix] (ADR 0019). */
internal object FusedLocationMapping {
    private const val INTERVAL_MILLIS = 1_000L

    /** GNSS-quality fixes once a second, delivered at once (no batching), without waiting for accuracy. */
    fun request(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(INTERVAL_MILLIS)
            // A delay of one interval means no batching: each fix is delivered when it is measured.
            .setMaxUpdateDelayMillis(INTERVAL_MILLIS)
            .setWaitForAccurateLocation(false)
            .build()

    /** Missing accuracy and speed become NaN; the gate rejects a fix without accuracy. */
    fun fixOf(location: Location): LocationFix = LocationFix(
        elapsedNanos = location.elapsedRealtimeNanos,
        latitude = location.latitude,
        longitude = location.longitude,
        horizontalAccuracyMeters = orNaN(location.hasAccuracy(), location.accuracy),
        speedMetersPerSecond = orNaN(location.hasSpeed(), location.speed),
        mock = isMock(location)
    )

    private fun orNaN(present: Boolean, value: Float): Double =
        if (present) value.toDouble() else Double.NaN

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        return if (modern) location.isMock else location.isFromMockProvider
    }
}
