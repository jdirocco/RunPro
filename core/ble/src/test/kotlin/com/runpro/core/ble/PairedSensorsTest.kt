package com.runpro.core.ble

import com.runpro.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairedSensorsTest {
    private val strap = PairedSensor("AA:BB:CC:DD:EE:01", BleSensorKind.HEART_RATE, "HRM-Pro|x", 7)
    private val footpod = PairedSensor("AA:BB:CC:DD:EE:02", BleSensorKind.FOOTPOD)

    @Test
    fun `sensors survive an encode and decode round trip`() {
        val text = PairedSensors.encode(listOf(strap, footpod))
        assertEquals(listOf(strap, footpod), PairedSensors.decode(text))
    }

    @Test
    fun `the source id is the stable BLE id of the address`() {
        assertEquals(SourceId.ble("aa:bb:cc:dd:ee:01"), strap.source)
    }

    @Test
    fun `addresses are normalised and invalid ones rejected`() {
        assertEquals(
            "AA:BB:CC:DD:EE:0F",
            PairedSensor.of("aa:bb:cc:dd:ee:0f", BleSensorKind.FOOTPOD).address
        )
        assertFailsWith<IllegalArgumentException> {
            PairedSensor("aa:bb:cc:dd:ee:0f", BleSensorKind.FOOTPOD)
        }
        assertFailsWith<IllegalArgumentException> {
            PairedSensor.of("not-an-address", BleSensorKind.HEART_RATE)
        }
    }

    @Test
    fun `names with line breaks are kept on one line`() {
        val named = strap.copy(name = "Polar\nH10")
        assertEquals(
            "Polar H10",
            PairedSensors.decode(PairedSensors.encode(listOf(named))).single().name
        )
    }

    @Test
    fun `missing or damaged preferences decode to the valid sensors only`() {
        assertTrue(PairedSensors.decode(null).isEmpty())
        assertTrue(PairedSensors.decode("").isEmpty())
        val text =
            "GARBAGE\nHEART_RATE|zz|1|x\nPOWER|AA:BB:CC:DD:EE:03||\n" +
                PairedSensors.encode(listOf(footpod))
        assertEquals(listOf(footpod), PairedSensors.decode(text))
    }

    @Test
    fun `pairing keeps one sensor per kind and forgetting removes it`() {
        val newStrap = PairedSensor("AA:BB:CC:DD:EE:09", BleSensorKind.HEART_RATE, "Polar H10")
        val paired = PairedSensors.pair(listOf(strap, footpod), newStrap)
        assertEquals(listOf(footpod, newStrap), paired)
        assertEquals(listOf(newStrap), PairedSensors.forget(paired, BleSensorKind.FOOTPOD))
        val duplicate = listOf(strap, newStrap).joinToString("\n") {
            PairedSensors.encode(listOf(it))
        }
        assertEquals(listOf(newStrap), PairedSensors.decode(duplicate))
    }

    @Test
    fun `each kind requires the measurements its service streams`() {
        assertEquals(
            listOf(GattUuids.HEART_RATE_MEASUREMENT),
            BleSensorKind.HEART_RATE.measurements
        )
        assertEquals(GattUuids.HEART_RATE_SERVICE, BleSensorKind.HEART_RATE.service)
        assertEquals(listOf(GattUuids.RSC_MEASUREMENT), BleSensorKind.FOOTPOD.measurements)
        assertEquals(GattUuids.RUNNING_SPEED_AND_CADENCE_SERVICE, BleSensorKind.FOOTPOD.service)
    }
}
