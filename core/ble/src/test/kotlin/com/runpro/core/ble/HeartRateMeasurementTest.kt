package com.runpro.core.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Heart Rate Measurement (0x2A37), Heart Rate Service 1.0 / GATT Specification Supplement 3.116.
 * Flags: bit 0 value format (uint8/uint16), bits 1-2 sensor contact, bit 3 energy expended, bit 4 RR intervals.
 */
class HeartRateMeasurementTest {
    @Test
    fun `uint8 heart rate without optional fields`() {
        val hr = parsed(HeartRateMeasurement.parse(bytes(0x00, 0x48)))

        assertEquals(72, hr.heartRateBpm)
        assertEquals(SensorContact.NOT_SUPPORTED, hr.sensorContact)
        assertNull(hr.energyExpendedKilojoules)
        assertTrue(hr.rrIntervals1024.isEmpty())
    }

    @Test
    fun `uint16 heart rate is little endian`() {
        assertEquals(72, parsed(HeartRateMeasurement.parse(bytes(0x01, 0x48, 0x00))).heartRateBpm)
        assertEquals(300, parsed(HeartRateMeasurement.parse(bytes(0x01, 0x2C, 0x01))).heartRateBpm)
        assertEquals(
            65_535,
            parsed(HeartRateMeasurement.parse(bytes(0x01, 0xFF, 0xFF))).heartRateBpm
        )
    }

    @Test
    fun `uint8 heart rate is unsigned`() {
        assertEquals(255, parsed(HeartRateMeasurement.parse(bytes(0x00, 0xFF))).heartRateBpm)
    }

    @Test
    fun `sensor contact status uses bit 2 as supported and bit 1 as detected`() {
        fun contact(flags: Int) =
            parsed(HeartRateMeasurement.parse(bytes(flags, 0x50))).sensorContact

        assertEquals(SensorContact.NOT_SUPPORTED, contact(0x00))
        assertEquals(SensorContact.NOT_SUPPORTED, contact(0x02))
        assertEquals(SensorContact.NOT_DETECTED, contact(0x04))
        assertEquals(SensorContact.DETECTED, contact(0x06))
    }

    @Test
    fun `energy expended follows the heart rate value`() {
        val hr = parsed(HeartRateMeasurement.parse(bytes(0x08, 0x5A, 0x10, 0x01)))

        assertEquals(90, hr.heartRateBpm)
        assertEquals(272, hr.energyExpendedKilojoules)
    }

    @Test
    fun `RR intervals are in units of 1 over 1024 s`() {
        val hr = parsed(HeartRateMeasurement.parse(bytes(0x16, 0x50, 0x00, 0x04, 0x80, 0x03)))

        assertEquals(80, hr.heartRateBpm)
        assertEquals(SensorContact.DETECTED, hr.sensorContact)
        assertEquals(listOf(1024, 896), hr.rrIntervals1024)
        assertEquals(listOf(1.0, 0.875), hr.rrIntervalsSeconds)
    }

    @Test
    fun `every field present with a uint16 heart rate`() {
        val hr = parsed(
            HeartRateMeasurement.parse(bytes(0x1F, 0x96, 0x00, 0xE8, 0x03, 0x00, 0x02, 0xA0, 0x01))
        )

        assertEquals(
            HeartRateMeasurement(
                heartRateBpm = 150,
                sensorContact = SensorContact.DETECTED,
                energyExpendedKilojoules = 1000,
                rrIntervals1024 = listOf(512, 416)
            ),
            hr
        )
        assertEquals(listOf(0.5, 0.40625), hr.rrIntervalsSeconds)
    }

    @Test
    fun `nine RR intervals fill a 20 byte notification`() {
        val rr = (0 until 9).flatMap { listOf(0x00, 0x04) }.toIntArray()
        val hr = parsed(HeartRateMeasurement.parse(bytes(0x10, 0x3C, *rr)))

        assertEquals(60, hr.heartRateBpm)
        assertEquals(List(9) { 1024 }, hr.rrIntervals1024)
    }

    @Test
    fun `RR flag without intervals gives an empty list`() {
        assertTrue(parsed(HeartRateMeasurement.parse(bytes(0x10, 0x3C))).rrIntervals1024.isEmpty())
    }

    @Test
    fun `a dangling half RR interval is ignored and keeps the heart rate`() {
        val hr = parsed(HeartRateMeasurement.parse(bytes(0x10, 0x3C, 0x00, 0x04, 0x80)))

        assertEquals(60, hr.heartRateBpm)
        assertEquals(listOf(1024), hr.rrIntervals1024)
    }

    @Test
    fun `reserved flag bits are ignored`() {
        val hr = parsed(HeartRateMeasurement.parse(bytes(0xE0, 0x48)))

        assertEquals(72, hr.heartRateBpm)
        assertEquals(SensorContact.NOT_SUPPORTED, hr.sensorContact)
    }

    @Test
    fun `truncated values are malformed`() {
        malformed(HeartRateMeasurement.parse(bytes()))
        malformed(HeartRateMeasurement.parse(bytes(0x00)))
        malformed(HeartRateMeasurement.parse(bytes(0x01, 0x48)))
        malformed(HeartRateMeasurement.parse(bytes(0x08, 0x48, 0x10)))
    }
}
