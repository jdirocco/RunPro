package com.runpro.wear.health

import android.annotation.SuppressLint
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseCapabilities
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEndReason
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import com.runpro.core.recording.exercise.DataAvailability
import com.runpro.core.recording.exercise.ExerciseCapabilities as RunProCapabilities
import com.runpro.core.recording.exercise.ExerciseData
import com.runpro.core.recording.exercise.ExerciseEndReason as RunProEndReason
import com.runpro.core.recording.exercise.ExerciseOwnership
import com.runpro.core.recording.exercise.ExercisePlan
import com.runpro.core.recording.exercise.PlatformExerciseState

/** Translation between Health Services types and the platform-free types of `:core:recording` (exercise). */
internal object HealthServicesMapping {
    private val dataTypes: Map<ExerciseData, DeltaDataType<*, *>> = mapOf(
        ExerciseData.HEART_RATE to DataType.HEART_RATE_BPM,
        ExerciseData.LOCATION to DataType.LOCATION,
        ExerciseData.DISTANCE to DataType.DISTANCE,
        ExerciseData.SPEED to DataType.SPEED,
        ExerciseData.STEPS_PER_MINUTE to DataType.STEPS_PER_MINUTE,
        ExerciseData.ELEVATION to DataType.ABSOLUTE_ELEVATION
    )

    fun dataType(data: ExerciseData): DeltaDataType<*, *> = dataTypes.getValue(data)

    fun exerciseData(type: DataType<*, *>): ExerciseData? = dataTypes.entries.firstOrNull {
        it.value ==
            type
    }?.key

    fun capabilities(capabilities: ExerciseCapabilities): RunProCapabilities {
        val running = ExerciseType.RUNNING in capabilities.supportedExerciseTypes
        val supported = if (running) {
            capabilities.getExerciseTypeCapabilities(ExerciseType.RUNNING).supportedDataTypes
                .mapNotNull(::exerciseData)
                .toSet()
        } else {
            emptySet()
        }
        return RunProCapabilities(runningSupported = running, supported = supported)
    }

    // The constants are public API; lint flags their companion because of its IntDef (false positive).
    @SuppressLint("RestrictedApi")
    fun ownership(trackedStatus: Int): ExerciseOwnership = when (trackedStatus) {
        ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> ExerciseOwnership.OURS
        ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> ExerciseOwnership.OTHER_APP
        else -> ExerciseOwnership.NONE
    }

    /** Running, the planned data types, GPS only with location, and no auto-pause (R4: only the runner pauses). */
    fun exerciseConfig(plan: ExercisePlan): ExerciseConfig =
        ExerciseConfig.builder(ExerciseType.RUNNING)
            .setDataTypes(plan.dataTypes.map(::dataType).toSet())
            .setIsGpsEnabled(plan.gpsEnabled)
            .setIsAutoPauseAndResumeEnabled(false)
            .build()

    fun warmUpConfig(plan: ExercisePlan): WarmUpConfig =
        WarmUpConfig(ExerciseType.RUNNING, plan.warmUpTypes.map(::dataType).toSet())

    fun availability(availability: Availability): DataAvailability = when (availability) {
        LocationAvailability.ACQUIRED_TETHERED, LocationAvailability.ACQUIRED_UNTETHERED,
        DataTypeAvailability.AVAILABLE -> DataAvailability.AVAILABLE

        LocationAvailability.ACQUIRING, DataTypeAvailability.ACQUIRING -> DataAvailability.ACQUIRING

        LocationAvailability.NO_GNSS -> DataAvailability.NO_GNSS

        DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> DataAvailability.OFF_BODY

        LocationAvailability.UNAVAILABLE, DataTypeAvailability.UNAVAILABLE ->
            DataAvailability.UNAVAILABLE

        else -> DataAvailability.UNKNOWN
    }

    /** Settled states only; transitional ones (`USER_PAUSING`, `ENDING`, ...) return null. */
    fun state(state: ExerciseState): PlatformExerciseState? = when {
        state.isEnded -> PlatformExerciseState.ENDED
        state == ExerciseState.ACTIVE -> PlatformExerciseState.ACTIVE
        state == ExerciseState.USER_PAUSED -> PlatformExerciseState.PAUSED
        state == ExerciseState.AUTO_PAUSED -> PlatformExerciseState.PAUSED
        state == ExerciseState.PREPARING -> PlatformExerciseState.PREPARING
        else -> null
    }

    @SuppressLint("RestrictedApi") // Public constants of an IntDef companion, as in ownership().
    fun endReason(reason: Int): RunProEndReason = when (reason) {
        ExerciseEndReason.USER_END -> RunProEndReason.USER
        ExerciseEndReason.AUTO_END_SUPERSEDED -> RunProEndReason.SUPERSEDED
        ExerciseEndReason.AUTO_END_PERMISSION_LOST -> RunProEndReason.PERMISSION_LOST
        ExerciseEndReason.AUTO_END_PAUSE_EXPIRED -> RunProEndReason.PAUSE_EXPIRED
        ExerciseEndReason.AUTO_END_PREPARE_EXPIRED -> RunProEndReason.PREPARE_EXPIRED
        ExerciseEndReason.AUTO_END_MISSING_LISTENER -> RunProEndReason.MISSING_LISTENER
        else -> RunProEndReason.UNKNOWN
    }
}
