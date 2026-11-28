package com.runpro.core.recording.location

/**
 * Platform-free view of the fused location provider (`FusedLocationProviderClient` in `:wear`,
 * `PRIORITY_HIGH_ACCURACY` at 1 Hz). Calls never block; callbacks arrive on the adapter's own thread.
 */
interface LocationPort {
    /** Starts updates to [receiver], replacing any earlier request. */
    fun request(receiver: LocationReceiver)

    /** Stops the updates of the current request. Its receiver may still get late callbacks. */
    fun remove()
}

/** What the provider reports for one request. */
interface LocationReceiver {
    fun fix(fix: LocationFix)

    /** The provider says whether it can currently produce fixes (`LocationAvailability`). */
    fun availability(available: Boolean)

    /** The request could not be made or was ended by the platform (no Play services, permission revoked). */
    fun failed(message: String)
}
