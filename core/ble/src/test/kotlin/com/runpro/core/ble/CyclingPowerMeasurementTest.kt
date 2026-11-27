package com.runpro.core.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Cycling Power Measurement (0x2A63), Cycling Power Service 1.1 / GATT Specification Supplement 3.65.
 * Stryd reports running power through it. Flags are uint16; instantaneous power is sint16 in watts; the
 * optional fields follow in flag order.
 */
class CyclingPowerMeasurementTest {
    @Test
    fun `instantaneous power only, as Stryd sends it`() {
        val cp = parsed(CyclingPowerMeasurement.parse(bytes(0x00, 0x00, 0xFA, 0x00)))

        assertEquals(CyclingPowerMeasurement(instantaneousPowerWatts = 250), cp)
    }

    @Test
    fun `instantaneous power is signed`() {
        assertEquals(
            -10,
            parsed(
                CyclingPowerMeasurement.parse(bytes(0x00, 0x00, 0xF6, 0xFF))
            ).instantaneousPowerWatts
        )
        assertEquals(
            32_767,
            parsed(
                CyclingPowerMeasurement.parse(bytes(0x00, 0x00, 0xFF, 0x7F))
            ).instantaneousPowerWatts
        )
    }

    @Test
    fun `crank revolution data`() {
        val cp =
            parsed(
                CyclingPowerMeasurement.parse(bytes(0x20, 0x00, 0x2C, 0x01, 0x0A, 0x00, 0x00, 0x04))
            )

        assertEquals(300, cp.instantaneousPowerWatts)
        assertEquals(
            CrankRevolutions(cumulative = 10, lastEventTime1024 = 1024),
            cp.crankRevolutions
        )
        assertNull(cp.wheelRevolutions)
    }

    @Test
    fun `every field except extreme torque`() {
        val value = bytes(
            0x7F, 0x1F, // flags: bits 0-6 and 8-12
            0xC8, 0x00, // power 200 W
            0x64, // pedal power balance 100 / 2 = 50 %
            0x40, 0x01, // accumulated torque 320 / 32 = 10 Nm
            0x45, 0x23, 0x01, 0x00, 0x00, 0x08, // wheel revs 0x12345, last event 2048 / 2048 s
            0x02, 0x01, 0x00, 0x02, // crank revolutions 258, last event 512 / 1024 s
            0xF4, 0x01, 0x9C, 0xFF, // extreme force: max 500 N, min -100 N
            0xB4, 0xA0, 0x05, // extreme angles: max 180 deg, min 90 deg (two uint12)
            0x0A, 0x00, // top dead spot 10 deg
            0xBE, 0x00, // bottom dead spot 190 deg
            0x64, 0x00 // accumulated energy 100 kJ
        )

        assertEquals(
            CyclingPowerMeasurement(
                instantaneousPowerWatts = 200,
                pedalPowerBalance = PedalPowerBalance(
                    percent = 50.0,
                    reference = BalanceReference.LEFT
                ),
                accumulatedTorque = AccumulatedTorque(torque32 = 320, source = TorqueSource.CRANK),
                wheelRevolutions = WheelRevolutions(cumulative = 0x12345, lastEventTime2048 = 2048),
                crankRevolutions = CrankRevolutions(cumulative = 258, lastEventTime1024 = 512),
                extremeForceNewtons = ExtremeValues(maximum = 500, minimum = -100),
                extremeTorque32 = null,
                extremeAnglesDegrees = ExtremeValues(maximum = 180, minimum = 90),
                topDeadSpotAngleDegrees = 10,
                bottomDeadSpotAngleDegrees = 190,
                accumulatedEnergyKilojoules = 100,
                offsetCompensation = true
            ),
            parsed(CyclingPowerMeasurement.parse(value))
        )
        assertEquals(
            10.0,
            parsed(CyclingPowerMeasurement.parse(value)).accumulatedTorque?.newtonMeters
        )
    }

    @Test
    fun `balance and torque references default to unknown and wheel`() {
        val cp =
            parsed(CyclingPowerMeasurement.parse(bytes(0x05, 0x00, 0x64, 0x00, 0x65, 0x20, 0x00)))

        assertEquals(
            PedalPowerBalance(percent = 50.5, reference = BalanceReference.UNKNOWN),
            cp.pedalPowerBalance
        )
        assertEquals(
            AccumulatedTorque(torque32 = 32, source = TorqueSource.WHEEL),
            cp.accumulatedTorque
        )
        assertEquals(1.0, cp.accumulatedTorque?.newtonMeters)
        assertFalse(cp.offsetCompensation)
    }

    @Test
    fun `extreme torque magnitudes are signed in 1 over 32 Nm`() {
        val cp =
            parsed(
                CyclingPowerMeasurement.parse(bytes(0x80, 0x00, 0x64, 0x00, 0x40, 0x06, 0xC0, 0xFF))
            )

        assertEquals(ExtremeValues(maximum = 1600, minimum = -64), cp.extremeTorque32)
        assertNull(cp.extremeForceNewtons)
    }

    @Test
    fun `revolution counters are unsigned`() {
        val cp = parsed(
            CyclingPowerMeasurement.parse(
                bytes(0x30, 0x00, 0x00, 0x00, *IntArray(10) { 0xFF })
            )
        )

        assertEquals(
            WheelRevolutions(cumulative = 4_294_967_295L, lastEventTime2048 = 65_535),
            cp.wheelRevolutions
        )
        assertEquals(
            CrankRevolutions(cumulative = 65_535, lastEventTime1024 = 65_535),
            cp.crankRevolutions
        )
    }

    @Test
    fun `extreme angles are packed as two uint12`() {
        val cp =
            parsed(CyclingPowerMeasurement.parse(bytes(0x00, 0x01, 0x00, 0x00, 0xFF, 0x2F, 0xAB)))

        assertEquals(ExtremeValues(maximum = 0xFFF, minimum = 0xAB2), cp.extremeAnglesDegrees)
    }

    @Test
    fun `reserved flag bits are ignored`() {
        assertEquals(
            CyclingPowerMeasurement(instantaneousPowerWatts = 250),
            parsed(CyclingPowerMeasurement.parse(bytes(0x00, 0xE0, 0xFA, 0x00)))
        )
    }

    @Test
    fun `truncated values are malformed`() {
        malformed(CyclingPowerMeasurement.parse(bytes()))
        malformed(CyclingPowerMeasurement.parse(bytes(0x00, 0x00, 0xFA)))
        malformed(CyclingPowerMeasurement.parse(bytes(0x20, 0x00, 0xFA, 0x00, 0x0A, 0x00)))
        malformed(CyclingPowerMeasurement.parse(bytes(0x00, 0x01, 0xFA, 0x00, 0xB4, 0xA0)))
        malformed(CyclingPowerMeasurement.parse(bytes(0x00, 0x08, 0xFA, 0x00, 0x64)))
    }
}
