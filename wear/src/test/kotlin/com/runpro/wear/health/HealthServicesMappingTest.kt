package com.runpro.wear.health

import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.HeartRateAccuracy
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.LocationAccuracy
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.LocationData
import androidx.health.services.client.data.SampleDataPoint
import com.runpro.core.recording.exercise.DataAvailability
import com.runpro.core.recording.exercise.ExerciseCapabilities
import com.runpro.core.recording.exercise.ExerciseData
import com.runpro.core.recording.exercise.ExerciseOwnership
import com.runpro.core.recording.exercise.ExercisePlan
import com.runpro.core.recording.exercise.ExerciseReading
import com.runpro.core.recording.exercise.ExerciseReading.HeartRateQuality
import com.runpro.core.recording.exercise.PlatformExerciseState
import com.runpro.core.recording.service.RunPermission
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthServicesMappingTest {
    private val bundle = android.os.Bundle()

    @Test
    fun `data points become raw readings with boot time in nanoseconds`() {
        val metrics = DataPointContainer(
            listOf(
                SampleDataPoint(
                    DataType.HEART_RATE_BPM,
                    142.0,
                    Duration.ofSeconds(10),
                    bundle,
                    HeartRateAccuracy(HeartRateAccuracy.SensorStatus.ACCURACY_HIGH)
                ),
                SampleDataPoint(
                    DataType.LOCATION,
                    LocationData(45.0, 9.0, 120.0, 0.0),
                    Duration.ofSeconds(11),
                    bundle,
                    LocationAccuracy(5.0, 3.0)
                ),
                IntervalDataPoint(
                    DataType.DISTANCE,
                    12.5,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(11),
                    bundle,
                    null
                ),
                SampleDataPoint(
                    DataType.STEPS_PER_MINUTE,
                    170L,
                    Duration.ofSeconds(11),
                    bundle,
                    null
                )
            )
        )

        assertEquals(
            listOf(
                ExerciseReading.HeartRate(10_000_000_000, 142.0, HeartRateQuality.HIGH),
                ExerciseReading.Location(11_000_000_000, 45.0, 9.0, 120.0, 5.0),
                ExerciseReading.DistanceDelta(5_000_000_000, 11_000_000_000, 12.5),
                ExerciseReading.StepsPerMinute(11_000_000_000, 170)
            ),
            HealthServicesReadings.of(metrics)
        )
    }

    @Test
    fun `the exercise config requests the planned data, GPS with location and no auto-pause`() {
        val plan = ExercisePlan.of(
            ExerciseCapabilities(runningSupported = true, supported = ExerciseData.entries.toSet()),
            RunPermission.entries.toSet()
        )

        val config = HealthServicesMapping.exerciseConfig(plan)

        assertEquals(ExerciseType.RUNNING, config.exerciseType)
        assertEquals(6, config.dataTypes.size)
        assertTrue(DataType.ABSOLUTE_ELEVATION in config.dataTypes)
        assertTrue(config.isGpsEnabled)
        assertFalse(config.isAutoPauseAndResumeEnabled)
        assertEquals(
            setOf(DataType.HEART_RATE_BPM, DataType.LOCATION),
            HealthServicesMapping.warmUpConfig(plan).dataTypes
        )
    }

    @Test
    fun `availability, ownership, states and end reasons are translated`() {
        assertEquals(
            DataAvailability.AVAILABLE,
            HealthServicesMapping.availability(LocationAvailability.ACQUIRED_UNTETHERED)
        )
        assertEquals(
            DataAvailability.ACQUIRING,
            HealthServicesMapping.availability(LocationAvailability.ACQUIRING)
        )
        assertEquals(
            DataAvailability.OFF_BODY,
            HealthServicesMapping.availability(DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY)
        )
        assertEquals(ExerciseOwnership.OTHER_APP, HealthServicesMapping.ownership(OTHER_APP))
        assertEquals(ExerciseOwnership.OURS, HealthServicesMapping.ownership(OWNED))
        assertEquals(ExerciseOwnership.NONE, HealthServicesMapping.ownership(NONE))
        assertEquals(
            PlatformExerciseState.PAUSED,
            HealthServicesMapping.state(ExerciseState.USER_PAUSED)
        )
        assertEquals(PlatformExerciseState.ENDED, HealthServicesMapping.state(ExerciseState.ENDED))
        assertNull(HealthServicesMapping.state(ExerciseState.USER_PAUSING))
        assertEquals(
            ExerciseData.ELEVATION,
            HealthServicesMapping.exerciseData(DataType.ABSOLUTE_ELEVATION)
        )
        assertEquals(
            com.runpro.core.recording.exercise.ExerciseEndReason.SUPERSEDED,
            HealthServicesMapping.endReason(SUPERSEDED)
        )
    }

    private companion object {
        // ExerciseTrackedStatus and ExerciseEndReason values (their companions are restricted for lint).
        const val OTHER_APP = 1
        const val OWNED = 2
        const val NONE = 3
        const val SUPERSEDED = 5
    }
}
