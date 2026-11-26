package com.runpro.wear

import android.app.Service
import android.util.Log
import androidx.core.app.ServiceCompat
import com.runpro.core.recording.RunState
import com.runpro.core.recording.service.ServicePermissions

/**
 * Puts [service] in and out of the foreground. It requests only the service types whose permissions are
 * granted ([ServicePermissions]) and turns a refusal by the system into `false`, so a missing permission
 * never crashes the run.
 */
class ForegroundController(
    private val service: Service,
    private val notification: RunNotification
) {
    var inForeground = false
        private set

    /** False when no type is allowed or the system refuses the foreground now. */
    fun enter(state: RunState, fromBackground: Boolean): Boolean {
        val granted = AndroidPermissions.granted(service)
        val types = ServicePermissions.foregroundTypes(granted, fromBackground)
        val flags = AndroidPermissions.serviceTypeFlags(types)
        if (flags == 0) {
            Log.w(TAG, "no foreground service type allowed: $types")
            return false
        }
        return try {
            val shown = notification.build(state)
            ServiceCompat.startForeground(service, RunNotification.NOTIFICATION_ID, shown, flags)
            inForeground = true
            Log.i(TAG, "foreground with $types")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground refused for $types", e)
            false
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
            Log.e(TAG, "startForeground not allowed now", e)
            false
        }
    }

    fun leave() {
        if (inForeground) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
    }

    private companion object {
        const val TAG = "RecordingService"
    }
}
