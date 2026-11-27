package com.runpro.core.ble

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** 16-bit assigned numbers expand on the Bluetooth base UUID; values are parsed by characteristic. */
class GattUuidsTest {
    @Test
    fun `assigned numbers expand on the Bluetooth base UUID`() {
        assertEquals(
            "0000180d-0000-1000-8000-00805f9b34fb",
            GattUuids.HEART_RATE_SERVICE.toString()
        )
        assertEquals(
            "00002a37-0000-1000-8000-00805f9b34fb",
            GattUuids.HEART_RATE_MEASUREMENT.toString()
        )
        assertEquals(
            "00001814-0000-1000-8000-00805f9b34fb",
            GattUuids.RUNNING_SPEED_AND_CADENCE_SERVICE.toString()
        )
        assertEquals("00002a53-0000-1000-8000-00805f9b34fb", GattUuids.RSC_MEASUREMENT.toString())
        assertEquals(
            "00001818-0000-1000-8000-00805f9b34fb",
            GattUuids.CYCLING_POWER_SERVICE.toString()
        )
        assertEquals(
            "00002a63-0000-1000-8000-00805f9b34fb",
            GattUuids.CYCLING_POWER_MEASUREMENT.toString()
        )
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", GattUuids.BATTERY_SERVICE.toString())
        assertEquals("00002a19-0000-1000-8000-00805f9b34fb", GattUuids.BATTERY_LEVEL.toString())
        assertEquals(
            "00002902-0000-1000-8000-00805f9b34fb",
            GattUuids.CLIENT_CHARACTERISTIC_CONFIGURATION.toString()
        )
    }

    @Test
    fun `values are parsed by their characteristic`() {
        assertIs<HeartRateMeasurement>(
            parsed(GattParsers.parse(GattUuids.HEART_RATE_MEASUREMENT, bytes(0x00, 0x48)))
        )
        assertIs<RscMeasurement>(
            parsed(GattParsers.parse(GattUuids.RSC_MEASUREMENT, bytes(0x00, 0x00, 0x03, 0x5A)))
        )
        assertIs<CyclingPowerMeasurement>(
            parsed(
                GattParsers.parse(
                    GattUuids.CYCLING_POWER_MEASUREMENT,
                    bytes(0x00, 0x00, 0xFA, 0x00)
                )
            )
        )
        assertIs<BatteryLevel>(parsed(GattParsers.parse(GattUuids.BATTERY_LEVEL, bytes(0x55))))
        malformed(GattParsers.parse(GattUuids.HEART_RATE_MEASUREMENT, bytes()))
    }

    @Test
    fun `unknown characteristics are not parsed`() {
        assertNull(
            GattParsers.parse(UUID.fromString("c4d40001-0000-0000-0000-000000000000"), bytes(0x00))
        )
    }
}
