package com.runpro.core.recording.exercise

import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Elevation
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LocationSample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.exercise.ExerciseReading.HeartRateQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExerciseSampleMapperTest {
    private val source = SourceId.HEALTH_SERVICES

    // The update arrives at boot time 100 s, wall clock 1_000_000 ms.
    private val anchor = Timestamp(seconds(100), 1_000_000)
    private val mapper = ExerciseSampleMapper(source)

    @Test
    fun `readings become samples on both clocks`() {
        val samples = mapper.map(
            listOf(
                ExerciseReading.HeartRate(seconds(98), 151.6, HeartRateQuality.HIGH),
                ExerciseReading.Location(
                    seconds(99),
                    45.0,
                    9.0,
                    altitudeMeters = 120.5,
                    horizontalAccuracyMeters = 4.0
                ),
                ExerciseReading.Speed(seconds(99), 3.2),
                ExerciseReading.StepsPerMinute(seconds(99), 172),
                ExerciseReading.Elevation(seconds(99), 121.0)
            ),
            anchor
        )

        val at98 = Timestamp(seconds(98), 998_000)
        val at99 = Timestamp(seconds(99), 999_000)
        assertEquals(
            listOf(
                HeartRateSample(at98, source, HeartRate(152)),
                LocationSample(at99, source, 45.0, 9.0, Distance(4.0), Elevation(120.5)),
                SpeedSample(at99, source, Speed(3.2)),
                CadenceSample(at99, source, com.runpro.core.model.Cadence(172.0)),
                ElevationSample(at99, source, Elevation(121.0))
            ),
            samples
        )
    }

    @Test
    fun `distance deltas add up to the cumulative distance at the end of each interval`() {
        val first = mapper.map(
            listOf(
                ExerciseReading.DistanceDelta(seconds(90), seconds(95), 12.5),
                ExerciseReading.DistanceDelta(seconds(95), seconds(99), 10.0)
            ),
            anchor
        )
        val second = mapper.map(
            listOf(ExerciseReading.DistanceDelta(seconds(99), seconds(104), 14.0)),
            anchor
        )

        assertEquals(
            listOf(12.5, 22.5, 36.5),
            (first + second).map { (it as DistanceSample).total.meters }
        )
        assertEquals(seconds(104), second.single().time.elapsedRealtimeNanos)
    }

    @Test
    fun `invalid readings are dropped at the edge`() {
        val samples = mapper.map(
            listOf(
                ExerciseReading.HeartRate(seconds(1), Double.NaN, HeartRateQuality.HIGH),
                ExerciseReading.HeartRate(seconds(1), 0.0, HeartRateQuality.HIGH),
                ExerciseReading.HeartRate(seconds(1), 350.0, HeartRateQuality.HIGH),
                ExerciseReading.HeartRate(seconds(1), 140.0, HeartRateQuality.NO_CONTACT),
                ExerciseReading.HeartRate(seconds(1), 140.0, HeartRateQuality.UNRELIABLE),
                ExerciseReading.Location(seconds(1), 91.0, 9.0),
                ExerciseReading.Location(seconds(1), 45.0, Double.NaN),
                ExerciseReading.DistanceDelta(seconds(1), seconds(2), -3.0),
                ExerciseReading.DistanceDelta(seconds(1), seconds(2), Double.POSITIVE_INFINITY),
                ExerciseReading.Speed(seconds(1), -0.1),
                ExerciseReading.StepsPerMinute(seconds(1), -5),
                ExerciseReading.Elevation(seconds(1), Double.NaN),
                ExerciseReading.Speed(-1, 3.0)
            ),
            anchor
        )

        assertTrue(samples.isEmpty(), "unexpected samples: $samples")
    }

    @Test
    fun `missing altitude and accuracy are left out of a location`() {
        val sample = mapper.map(
            listOf(
                ExerciseReading.Location(
                    seconds(99),
                    45.0,
                    9.0,
                    altitudeMeters = Double.NaN,
                    horizontalAccuracyMeters = -1.0
                )
            ),
            anchor
        ).single() as LocationSample

        assertEquals(null, sample.altitude)
        assertEquals(null, sample.horizontalAccuracy)
    }

    @Test
    fun `a heart rate of unknown quality is kept`() {
        val samples = mapper.map(
            listOf(ExerciseReading.HeartRate(seconds(99), 120.0, HeartRateQuality.UNKNOWN)),
            anchor
        )

        assertEquals(HeartRate(120), (samples.single() as HeartRateSample).heartRate)
    }

    private fun seconds(value: Int): Long = value * 1_000_000_000L
}
