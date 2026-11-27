package com.runpro.core.ble

import com.runpro.core.model.Distance
import com.runpro.core.model.Speed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RSC Measurement (0x2A53), Running Speed and Cadence Service 1.0 / GATT Specification Supplement 3.188.
 * Flags: bit 0 stride length, bit 1 total distance, bit 2 walking (0) or running (1).
 * Speed uint16 in 1/256 m/s, cadence uint8 per minute, stride length uint16 in cm, total distance uint32 in dm.
 */
class RscMeasurementTest {
    @Test
    fun `mandatory fields only`() {
        val rsc = parsed(RscMeasurement.parse(bytes(0x00, 0x00, 0x03, 0x5A)))

        assertEquals(
            RscMeasurement(
                speed = Speed(3.0),
                cadencePerMinute = 90,
                strideLength = null,
                totalDistance = null,
                motion = MotionStatus.WALKING
            ),
            rsc
        )
    }

    @Test
    fun `running with stride length and total distance`() {
        val rsc = parsed(
            RscMeasurement.parse(bytes(0x07, 0x80, 0x03, 0xB4, 0x82, 0x00, 0x10, 0x27, 0x00, 0x00))
        )

        assertEquals(Speed(3.5), rsc.speed)
        assertEquals(180, rsc.cadencePerMinute)
        assertEquals(Distance(1.30), rsc.strideLength)
        assertEquals(Distance(1000.0), rsc.totalDistance)
        assertEquals(MotionStatus.RUNNING, rsc.motion)
    }

    @Test
    fun `total distance without stride length`() {
        val rsc =
            parsed(RscMeasurement.parse(bytes(0x06, 0x00, 0x04, 0xAA, 0x39, 0x30, 0x00, 0x00)))

        assertEquals(Speed(4.0), rsc.speed)
        assertEquals(170, rsc.cadencePerMinute)
        assertNull(rsc.strideLength)
        assertEquals(Distance(1234.5), rsc.totalDistance)
    }

    @Test
    fun `stride length without total distance`() {
        val rsc = parsed(RscMeasurement.parse(bytes(0x05, 0x40, 0x03, 0xAC, 0x78, 0x00)))

        assertEquals(Speed(3.25), rsc.speed)
        assertEquals(172, rsc.cadencePerMinute)
        assertEquals(Distance(1.20), rsc.strideLength)
        assertNull(rsc.totalDistance)
    }

    @Test
    fun `fields are unsigned up to their maximum`() {
        val rsc = parsed(
            RscMeasurement.parse(bytes(0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
        )

        assertEquals(Speed(65_535 / 256.0), rsc.speed)
        assertEquals(255, rsc.cadencePerMinute)
        assertEquals(Distance(655.35), rsc.strideLength)
        assertEquals(Distance(429_496_729.5), rsc.totalDistance)
    }

    @Test
    fun `reserved flag bits are ignored`() {
        val rsc = parsed(RscMeasurement.parse(bytes(0xF8, 0x00, 0x03, 0x5A)))

        assertEquals(Speed(3.0), rsc.speed)
        assertNull(rsc.strideLength)
        assertNull(rsc.totalDistance)
    }

    @Test
    fun `truncated values are malformed`() {
        malformed(RscMeasurement.parse(bytes()))
        malformed(RscMeasurement.parse(bytes(0x00, 0x00, 0x03)))
        malformed(RscMeasurement.parse(bytes(0x01, 0x00, 0x03, 0x5A, 0x82)))
        malformed(RscMeasurement.parse(bytes(0x02, 0x00, 0x03, 0x5A, 0x10, 0x27, 0x00)))
    }
}
