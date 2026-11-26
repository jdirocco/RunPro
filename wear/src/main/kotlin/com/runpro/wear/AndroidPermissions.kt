package com.runpro.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.runpro.core.recording.service.ForegroundType
import com.runpro.core.recording.service.RunPermission

/** Maps the platform-independent [RunPermission]s to the Android permissions of the running SDK. */
object AndroidPermissions {
    /** Health Connect style heart rate permission that replaces `BODY_SENSORS` from API 36. */
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"

    private const val API_HEART_RATE_PERMISSION = 36

    /**
     * The Android permissions behind [permission]; empty when it is granted at install time.
     * The constants are compile-time strings, only used when [sdk] is high enough.
     */
    @SuppressLint("InlinedApi")
    fun platform(permission: RunPermission, sdk: Int = Build.VERSION.SDK_INT): List<String> =
        when (permission) {
            RunPermission.LOCATION -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            RunPermission.HEART_RATE -> if (sdk >= API_HEART_RATE_PERMISSION) {
                listOf(READ_HEART_RATE)
            } else {
                listOf(Manifest.permission.BODY_SENSORS)
            }

            RunPermission.ACTIVITY_RECOGNITION -> listOf(Manifest.permission.ACTIVITY_RECOGNITION)

            RunPermission.BLUETOOTH -> if (sdk >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyList()
            }

            RunPermission.NOTIFICATIONS -> if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            }

            RunPermission.BACKGROUND_LOCATION -> listOf(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        }

    fun granted(context: Context): Set<RunPermission> = RunPermission.entries.filterTo(
        mutableSetOf()
    ) { permission ->
        platform(permission).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** `ServiceInfo` flags for [types]; `health` exists only from API 34. */
    @SuppressLint("InlinedApi")
    fun serviceTypeFlags(types: Set<ForegroundType>, sdk: Int = Build.VERSION.SDK_INT): Int =
        types.fold(0) { flags, type ->
            flags or when (type) {
                ForegroundType.HEALTH -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                } else {
                    0
                }

                ForegroundType.LOCATION -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

                ForegroundType.CONNECTED_DEVICE ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        }
}
