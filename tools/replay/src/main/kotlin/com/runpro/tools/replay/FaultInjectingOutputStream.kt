package com.runpro.tools.replay

import java.io.IOException
import java.io.OutputStream

/**
 * A journal/storage test port. A storage-full handler sets [full]; subsequent writes fail before writing
 * any bytes, until the test clears it. Already written bytes remain readable. Flush/close still reach
 * [delegate]. This models a write failure, not a real filesystem quota or a production journal.
 */
class FaultInjectingOutputStream(private val delegate: OutputStream) : OutputStream() {
    @Volatile
    var full: Boolean = false

    override fun write(value: Int) {
        checkSpace()
        delegate.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkSpace()
        delegate.write(bytes, offset, length)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun checkSpace() {
        if (full) throw IOException("No space left on device (injected)")
    }
}
