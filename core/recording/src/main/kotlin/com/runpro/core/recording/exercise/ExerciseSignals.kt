package com.runpro.core.recording.exercise

import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp

/**
 * Availability of the warm-up sensors in one exercise: readiness for the UI and the GPS fix changes as
 * [SensorEvent]s. A reading counts as available too. Not thread safe (used under the controller lock).
 */
internal class ExerciseSignals(private val source: SourceId) {
    private val availability = mutableMapOf<ExerciseData, DataAvailability>()
    private var gpsFix = false

    fun received(samples: List<Sample>) {
        for (sample in samples) {
            val data = when (sample) {
                is HeartRateSample -> ExerciseData.HEART_RATE
                is LocationSample -> ExerciseData.LOCATION
                else -> continue
            }
            availability[data] = DataAvailability.AVAILABLE
        }
    }

    /** Records [value] for [data]; returns the GPS event when the fix was acquired or lost. */
    fun changed(data: ExerciseData, value: DataAvailability, now: Timestamp): SensorEvent? {
        availability[data] = value
        if (data != ExerciseData.LOCATION) return null
        return fix(value == DataAvailability.AVAILABLE, now)
    }

    /** The exercise ended: a GPS fix, if any, is gone. */
    fun lost(now: Timestamp): SensorEvent? = fix(false, now)

    fun readiness(data: ExerciseData, plan: ExercisePlan?): Readiness {
        if (plan?.dataTypes?.contains(data) != true) return Readiness.NOT_REQUESTED
        return when (availability[data] ?: DataAvailability.UNKNOWN) {
            DataAvailability.AVAILABLE -> Readiness.READY

            DataAvailability.UNKNOWN, DataAvailability.ACQUIRING -> Readiness.WAITING

            DataAvailability.UNAVAILABLE, DataAvailability.OFF_BODY, DataAvailability.NO_GNSS ->
                Readiness.UNAVAILABLE
        }
    }

    private fun fix(fix: Boolean, now: Timestamp): SensorEvent? {
        if (fix == gpsFix) return null
        gpsFix = fix
        return if (fix) SensorEvent.GpsAcquired(now, source) else SensorEvent.GpsLost(now, source)
    }
}
