package com.runpro.wear.health

import android.util.Log
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseCapabilities
import androidx.health.services.client.data.ExerciseInfo
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseUpdate
import com.google.common.util.concurrent.ListenableFuture
import com.runpro.core.recording.exercise.ExerciseInspection
import com.runpro.core.recording.exercise.ExercisePlan
import com.runpro.core.recording.exercise.ExercisePort
import com.runpro.core.recording.exercise.ExercisePortListener
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

/**
 * [ExercisePort] over the Health Services [ExerciseClient]. Futures complete and update callbacks run on
 * [executor], never on the main thread (R6). No decision is taken here: that is `ExerciseController`.
 */
class HealthServicesExercisePort(
    private val client: ExerciseClient,
    private val executor: Executor
) : ExercisePort {
    @Volatile private var callback: ExerciseUpdateCallback? = null

    override fun inspect(done: (Result<ExerciseInspection>) -> Unit) {
        call(done, { client.getCapabilitiesAsync() }) { capabilities: ExerciseCapabilities ->
            val mapped = HealthServicesMapping.capabilities(capabilities)
            call(done, { client.getCurrentExerciseInfoAsync() }) { info: ExerciseInfo ->
                ExerciseInspection(
                    mapped,
                    HealthServicesMapping.ownership(info.exerciseTrackedStatus)
                ).also {
                    Log.i(TAG, "inspected: $it")
                    done(Result.success(it))
                }
            }
        }
    }

    override fun setListener(listener: ExercisePortListener) {
        val callback = UpdateCallback(listener)
        this.callback = callback
        client.setUpdateCallback(executor, callback)
    }

    override fun clearListener() {
        val callback = callback ?: return
        this.callback = null
        try {
            client.clearUpdateCallbackAsync(callback).addListener({}, executor)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "clearUpdateCallback failed", e)
        }
    }

    override fun prepare(plan: ExercisePlan, done: (Result<Unit>) -> Unit) =
        unit(done) { client.prepareExerciseAsync(HealthServicesMapping.warmUpConfig(plan)) }

    override fun start(plan: ExercisePlan, done: (Result<Unit>) -> Unit) =
        unit(done) { client.startExerciseAsync(HealthServicesMapping.exerciseConfig(plan)) }

    override fun pause(done: (Result<Unit>) -> Unit) = unit(done) { client.pauseExerciseAsync() }

    override fun resume(done: (Result<Unit>) -> Unit) = unit(done) { client.resumeExerciseAsync() }

    override fun end(done: (Result<Unit>) -> Unit) = unit(done) { client.endExerciseAsync() }

    private fun unit(done: (Result<Unit>) -> Unit, send: () -> ListenableFuture<Void>) =
        call<Void?, Unit>(done, send) { done(Result.success(Unit)) }

    /** Sends a request and hands its value to [then]; any failure, thrown or in the future, goes to [done]. */
    // Binder and Health Services errors must not crash the run.
    @Suppress("TooGenericExceptionCaught")
    private fun <T, V> call(
        done: (Result<V>) -> Unit,
        send: () -> ListenableFuture<out T>,
        then: (T) -> Unit
    ) {
        val future = try {
            send()
        } catch (e: RuntimeException) {
            Log.w(TAG, "request refused", e)
            done(Result.failure(e))
            return
        }
        future.addListener({
            val value = try {
                future.get()
            } catch (e: ExecutionException) {
                Log.w(TAG, "request failed", e.cause ?: e)
                done(Result.failure(e.cause ?: e))
                return@addListener
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
                done(Result.failure(e))
                return@addListener
            }
            then(value)
        }, executor)
    }

    private class UpdateCallback(private val listener: ExercisePortListener) :
        ExerciseUpdateCallback {
        override fun onRegistered() {
            Log.i(TAG, "update callback registered")
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.w(TAG, "update callback registration failed", throwable)
            listener.onRegistrationFailed(throwable)
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val readings = HealthServicesReadings.of(update.latestMetrics)
            if (readings.isNotEmpty()) listener.onReadings(readings)
            val info = update.exerciseStateInfo
            HealthServicesMapping.state(info.state)?.let { state ->
                val reason = if (info.state.isEnded) {
                    HealthServicesMapping.endReason(
                        info.endReason
                    )
                } else {
                    null
                }
                listener.onState(state, reason)
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            val data = HealthServicesMapping.exerciseData(dataType) ?: return
            listener.onAvailability(data, HealthServicesMapping.availability(availability))
        }
    }

    private companion object {
        const val TAG = "HealthServices"
    }
}
