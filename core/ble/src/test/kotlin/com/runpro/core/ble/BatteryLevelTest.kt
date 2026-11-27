package com.runpro.core.ble

import kotlin.test.Test
import kotlin.test.assertEquals

/** Battery Level (0x2A19), Battery Service 1.0: uint8 percentage, 101-255 reserved. */
class BatteryLevelTest {
    @Test
    fun `percentage from 0 to 100`() {
        assertEquals(BatteryLevel(0), parsed(BatteryLevel.parse(bytes(0x00))))
        assertEquals(BatteryLevel(85), parsed(BatteryLevel.parse(bytes(0x55))))
        assertEquals(BatteryLevel(100), parsed(BatteryLevel.parse(bytes(0x64))))
    }

    @Test
    fun `reserved values and empty values are malformed`() {
        malformed(BatteryLevel.parse(bytes(0x65)))
        malformed(BatteryLevel.parse(bytes(0xFF)))
        malformed(BatteryLevel.parse(bytes()))
    }
}
