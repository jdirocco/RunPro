package com.runpro.core.ble

/** A little-endian cursor over a characteristic value, as every GATT field is little endian. */
internal class ByteReader(private val bytes: ByteArray) {
    private var position = 0

    val remaining: Int get() = bytes.size - position

    fun uint8(field: String): Int {
        need(1, field)
        return bytes[position++].toInt() and BYTE_MASK
    }

    fun uint16(field: String): Int {
        need(2, field)
        val low = bytes[position].toInt() and BYTE_MASK
        val high = bytes[position + 1].toInt() and BYTE_MASK
        position += 2
        return low or (high shl BITS_PER_BYTE)
    }

    fun sint16(field: String): Int = uint16(field).toShort().toInt()

    /** Three bytes, returned unsigned in an Int. */
    fun uint24(field: String): Int {
        val low = uint16(field)
        return low or (uint8(field) shl (2 * BITS_PER_BYTE))
    }

    fun uint32(field: String): Long {
        val low = uint16(field).toLong()
        val high = uint16(field).toLong()
        return low or (high shl (2 * BITS_PER_BYTE))
    }

    private fun need(count: Int, field: String) {
        if (remaining < count) throw TruncatedValue(field, position, bytes.size)
    }

    companion object {
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8
    }
}

/** A value the specification does not allow; [decoding] turns it into [ParseResult.Malformed]. */
internal open class InvalidValue(message: String) : Exception(message)

internal class TruncatedValue(field: String, offset: Int, size: Int) :
    InvalidValue("$field truncated at byte $offset of $size")

/** Runs [decode] and turns a value that ends too early or breaks the specification into [ParseResult.Malformed]. */
internal inline fun <T> decoding(
    name: String,
    bytes: ByteArray,
    decode: ByteReader.() -> T
): ParseResult<T> = try {
    ParseResult.Parsed(ByteReader(bytes).decode())
} catch (e: InvalidValue) {
    ParseResult.Malformed("$name: ${e.message}")
}

internal fun Int.hasBit(bit: Int): Boolean = (this shr bit) and 1 == 1
