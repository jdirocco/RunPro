package com.runpro.tools.replay

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class FaultInjectingOutputStreamTest {
    @Test
    fun `full storage rejects both write forms and retains the committed prefix`() {
        val bytes = ByteArrayOutputStream()
        FaultInjectingOutputStream(bytes).use { storage ->
            storage.write(byteArrayOf(1, 2, 3))
            storage.full = true
            assertFailsWith<IOException> { storage.write(4) }
            assertFailsWith<IOException> { storage.write(byteArrayOf(5, 6), 0, 2) }
            storage.flush()
            assertContentEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
            storage.full = false
            storage.write(7)
            assertContentEquals(byteArrayOf(1, 2, 3, 7), bytes.toByteArray())
        }
    }
}
