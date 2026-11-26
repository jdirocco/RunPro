package com.runpro.core.recording.service

/**
 * The runtime permissions a run can use, independent of Android API levels. The Android adapter maps each one
 * to the platform permissions of the running SDK (for example [HEART_RATE] is `BODY_SENSORS` up to API 35 and
 * `health.READ_HEART_RATE` from API 36). Entries are in the order the app asks for them.
 */
enum class RunPermission {
    /** Precise location while the app is in use: GPS track, distance, pace. */
    LOCATION,

    /** Wrist heart rate (Health Services). */
    HEART_RATE,

    /** Steps and cadence; also lets the `health` service type start from the background. */
    ACTIVITY_RECOGNITION,

    /** Scan for and connect to BLE straps and footpods. */
    BLUETOOTH,

    /** The ongoing notification; without it the run continues but has no notification to return to. */
    NOTIFICATIONS,

    /** Location "all the time": GPS after the service restarts on its own, with no app on screen. */
    BACKGROUND_LOCATION
}

/** The foreground service types of `RecordingService` (`health|location|connectedDevice` in the manifest). */
enum class ForegroundType {
    HEALTH,
    LOCATION,
    CONNECTED_DEVICE
}

/**
 * What the permission screen shows and whether a run can start.
 *
 * @property missingForeground permissions to ask in one request, in [RunPermission] order.
 * @property canAskBackgroundLocation location is granted, background location is not; Android accepts this
 *   request only on its own, after location.
 * @property canStart the service can enter the foreground with at least one type.
 */
data class PermissionReadiness(
    val missingForeground: List<RunPermission>,
    val canAskBackgroundLocation: Boolean,
    val canStart: Boolean
) {
    /** Nothing left to ask: also a restart after a kill keeps every sensor. */
    val complete: Boolean get() = missingForeground.isEmpty() && !canAskBackgroundLocation
}

/**
 * Which foreground service types the service may request.
 *
 * From Android 14 `startForeground` throws a `SecurityException` when a requested type lacks its runtime
 * permission, and a service started from the background (a `START_STICKY` restart after the process was
 * killed) does not get while-in-use permissions (location, body sensors). Requesting only the types allowed
 * here keeps the service alive with the sensors it can still use instead of crashing it.
 */
object ServicePermissions {
    fun foregroundTypes(granted: Set<RunPermission>, fromBackground: Boolean): Set<ForegroundType> =
        buildSet {
            val heartRate = RunPermission.HEART_RATE in granted && !fromBackground
            if (heartRate || RunPermission.ACTIVITY_RECOGNITION in granted) {
                add(ForegroundType.HEALTH)
            }
            val backgroundOk = !fromBackground || RunPermission.BACKGROUND_LOCATION in granted
            if (RunPermission.LOCATION in granted && backgroundOk) add(ForegroundType.LOCATION)
            if (RunPermission.BLUETOOTH in granted) add(ForegroundType.CONNECTED_DEVICE)
        }

    fun readiness(granted: Set<RunPermission>): PermissionReadiness = PermissionReadiness(
        missingForeground = RunPermission.entries.filter {
            it != RunPermission.BACKGROUND_LOCATION && it !in granted
        },
        canAskBackgroundLocation = RunPermission.LOCATION in granted &&
            RunPermission.BACKGROUND_LOCATION !in granted,
        canStart = foregroundTypes(granted, fromBackground = false).isNotEmpty()
    )
}
