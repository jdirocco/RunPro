package com.runpro.wear.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.location.FusedLocationController
import com.runpro.core.recording.location.LocationPort
import com.runpro.core.recording.location.LocationReceiver
import com.runpro.core.recording.service.RunPermission
import com.runpro.wear.AndroidPermissions
import java.util.concurrent.Executor

/**
 * [LocationPort] over Play services' [FusedLocationProviderClient] (#96, ADR 0019). Every call and every
 * callback runs on [looper] (the `runpro-location` thread): [request] and [remove] only post, so the engine
 * thread that calls the controller never waits for Play services (R6). A refused or failed request is
 * reported to the receiver and never thrown (R4).
 */
internal class FusedLocationPort(
    looper: Looper,
    private val client: () -> FusedLocationProviderClient
) : LocationPort {
    private val looper = looper
    private val handler = Handler(looper)
    private val onThread = Executor { handler.post(it) }

    /** The callback of the current request; location thread only. */
    private var callback: LocationCallback? = null

    override fun request(receiver: LocationReceiver) {
        handler.post { start(receiver) }
    }

    override fun remove() {
        handler.post(::stopCurrent)
    }

    // The controller requests only with the location permission; a revoked one is caught below.
    // Play services and binder errors must not crash the run.
    @SuppressLint("MissingPermission")
    @Suppress("TooGenericExceptionCaught")
    private fun start(receiver: LocationReceiver) {
        stopCurrent()
        val next = Callback(receiver)
        callback = next
        try {
            client().requestLocationUpdates(FusedLocationMapping.request(), next, looper)
                .addOnFailureListener(onThread) { error ->
                    Log.w(TAG, "location request failed", error)
                    receiver.failed(describe(error))
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "location request refused", e)
            callback = null
            receiver.failed(describe(e))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun stopCurrent() {
        val current = callback ?: return
        callback = null
        try {
            client().removeLocationUpdates(current)
        } catch (e: RuntimeException) {
            Log.w(TAG, "removing location updates failed", e)
        }
    }

    private class Callback(private val receiver: LocationReceiver) : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { receiver.fix(FusedLocationMapping.fixOf(it)) }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            receiver.availability(availability.isLocationAvailable)
        }
    }

    private companion object {
        const val TAG = "RunProLocation"

        fun describe(error: Throwable): String = error.message ?: error.javaClass.simpleName
    }
}

/** Builds the fused location source of `RecordingService` on [looper]. */
internal object WatchLocation {
    fun create(context: Context, looper: Looper, clock: () -> Timestamp): FusedLocationController {
        val app = context.applicationContext
        val client by lazy { LocationServices.getFusedLocationProviderClient(app) }
        return FusedLocationController(
            port = FusedLocationPort(looper) { client },
            locationAllowed = { RunPermission.LOCATION in AndroidPermissions.granted(app) },
            clock = clock
        )
    }
}
