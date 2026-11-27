package com.runpro.core.ble

import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Power
import com.runpro.core.model.PowerSample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parsed measurements become validated model samples; impossible readings are dropped at the edge. */
class BleSamplesTest {
    private val time =
        Timestamp(elapsedRealtimeNanos = 5_000_000_000, wallClockMillis = 1_764_000_000_000)
    private val strap = SourceId.ble("AA:BB:CC:DD:EE:01")

    @Test
    fun `heart rate with skin contact`() {
        val hr = HeartRateMeasurement(heartRateBpm = 142, sensorContact = SensorContact.DETECTED)

        assertEquals(
            listOf(HeartRateSample(time, strap, HeartRate(142))),
            BleSamples.from(hr, time, strap)
        )
    }

    @Test
    fun `heart rate from a strap without contact detection`() {
        val hr =
            HeartRateMeasurement(heartRateBpm = 142, sensorContact = SensorContact.NOT_SUPPORTED)

        assertEquals(
            listOf(HeartRateSample(time, strap, HeartRate(142))),
            BleSamples.from(hr, time, strap)
        )
    }

    @Test
    fun `lost skin contact becomes 0 bpm whatever the strap reports`() {
        val hr = HeartRateMeasurement(heartRateBpm = 80, sensorContact = SensorContact.NOT_DETECTED)

        assertEquals(
            listOf(HeartRateSample(time, strap, HeartRate(0))),
            BleSamples.from(hr, time, strap)
        )
    }

    @Test
    fun `impossible heart rate is dropped`() {
        val hr = HeartRateMeasurement(heartRateBpm = 301, sensorContact = SensorContact.DETECTED)

        assertTrue(BleSamples.from(hr, time, strap).isEmpty())
    }

    @Test
    fun `RSC gives speed, cadence and cumulative distance`() {
        val rsc = RscMeasurement(
            speed = Speed(3.5),
            cadencePerMinute = 180,
            strideLength = Distance(1.3),
            totalDistance = Distance(1000.0),
            motion = MotionStatus.RUNNING
        )

        assertEquals(
            listOf(
                SpeedSample(time, strap, Speed(3.5)),
                CadenceSample(time, strap, Cadence(180.0)),
                DistanceSample(time, strap, Distance(1000.0))
            ),
            BleSamples.from(rsc, time, strap)
        )
    }

    @Test
    fun `RSC without total distance gives no distance sample`() {
        val rsc =
            RscMeasurement(
                Speed(3.0),
                170,
                strideLength = null,
                totalDistance = null,
                MotionStatus.RUNNING
            )

        assertEquals(
            listOf(
                SpeedSample(time, strap, Speed(3.0)),
                CadenceSample(time, strap, Cadence(170.0))
            ),
            BleSamples.from(rsc, time, strap)
        )
    }

    @Test
    fun `power is kept and negative power is dropped`() {
        assertEquals(
            listOf(PowerSample(time, strap, Power(250))),
            BleSamples.from(CyclingPowerMeasurement(instantaneousPowerWatts = 250), time, strap)
        )
        assertTrue(
            BleSamples.from(
                CyclingPowerMeasurement(instantaneousPowerWatts = -10),
                time,
                strap
            ).isEmpty()
        )
    }

    @Test
    fun `battery level is device status, not a sample`() {
        assertTrue(BleSamples.from(BatteryLevel(85), time, strap).isEmpty())
    }

    @Test
    fun `parsed bytes go straight to samples`() {
        val value =
            parsed(
                GattParsers.parse(GattUuids.HEART_RATE_MEASUREMENT, bytes(0x16, 0x50, 0x00, 0x04))
            )

        assertEquals(
            listOf(HeartRateSample(time, strap, HeartRate(80))),
            BleSamples.from(value, time, strap)
        )
    }
}
