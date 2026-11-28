package com.runpro.mobile.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import com.runpro.core.sync.healthconnect.HealthConnectRun

/** Health Connect write permissions requested by RunPro (write-only; RunPro never reads data). */
object HealthConnectPermissions {
    val WRITE_EXERCISE: String = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    val WRITE_EXERCISE_ROUTE: String = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
    val WRITE_HEART_RATE: String = HealthPermission.getWritePermission(HeartRateRecord::class)
    val WRITE_DISTANCE: String = HealthPermission.getWritePermission(DistanceRecord::class)
    val WRITE_STEPS: String = HealthPermission.getWritePermission(StepsRecord::class)

    /** Every write permission needed to export a full outdoor run with route, HR, distance, steps. */
    val ALL_WRITE_PERMISSIONS: Set<String> = setOf(
        WRITE_EXERCISE,
        WRITE_EXERCISE_ROUTE,
        WRITE_HEART_RATE,
        WRITE_DISTANCE,
        WRITE_STEPS
    )

    /** Exact subset of permissions required for the records present in [run]. */
    fun requiredFor(run: HealthConnectRun): Set<String> = buildSet {
        add(WRITE_EXERCISE)
        if (run.route.isNotEmpty()) add(WRITE_EXERCISE_ROUTE)
        if (run.heartRateChunks.isNotEmpty()) add(WRITE_HEART_RATE)
        if (run.distanceMeters != null) add(WRITE_DISTANCE)
        if (run.stepsCount != null) add(WRITE_STEPS)
    }
}
