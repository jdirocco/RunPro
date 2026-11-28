package com.runpro.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotaryPagesTest {
    @Test
    fun `one page per detent burst with immediate reversal and ignored zero`() {
        val rotary = RotaryPages()
        assertNull(rotary.direction(0f, 0))
        assertEquals(1, rotary.direction(10f, 0))
        assertNull(rotary.direction(10f, 100))
        assertEquals(1, rotary.direction(10f, 250))
        assertEquals(-1, rotary.direction(-10f, 251))
        assertNull(rotary.direction(Float.NaN, 500))
    }
}
