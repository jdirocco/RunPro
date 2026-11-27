package com.runpro.wear.health

import android.content.Context
import androidx.health.services.client.HealthServices
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.exercise.ExerciseController
import com.runpro.wear.AndroidPermissions
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Builds the [ExerciseController] of a run over the watch's Health Services. */
object HealthServicesExercise {
    /** One thread for every Health Services callback of the app, so none runs on the main thread (R6). */
    private val executor: Executor by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "runpro-health").apply {
                isDaemon =
                    true
            }
        }
    }

    fun create(context: Context, clock: () -> Timestamp): ExerciseController {
        val app = context.applicationContext
        val client = HealthServices.getClient(app).exerciseClient
        return ExerciseController(
            port = HealthServicesExercisePort(client, executor),
            grantedPermissions = { AndroidPermissions.granted(app) },
            clock = clock
        )
    }
}
