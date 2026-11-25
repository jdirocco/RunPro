package com.runpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceIdTest {
    @Test
    fun `BLE source ids carry the normalised device address`() {
        val strap = SourceId.ble("aa:bb:cc:dd:ee:0f")
        assertEquals("ble:AA:BB:CC:DD:EE:0F", strap.value)
        assertTrue(strap.isBle)
        assertEquals("AA:BB:CC:DD:EE:0F", strap.bleAddress)
    }

    @Test
    fun `watch sources are not BLE`() {
        assertFalse(SourceId.HEALTH_SERVICES.isBle)
        assertNull(SourceId.WATCH_IMU.bleAddress)
    }

    @Test
    fun `invalid ids are rejected`() {
        assertFailsWith<IllegalArgumentException> { SourceId("") }
        assertFailsWith<IllegalArgumentException> { SourceId("has space") }
        assertFailsWith<IllegalArgumentException> { SourceId.ble("not-an-address") }
    }
}
