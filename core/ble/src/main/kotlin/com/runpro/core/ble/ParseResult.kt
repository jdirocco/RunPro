package com.runpro.core.ble

/**
 * The outcome of decoding one characteristic value. Parsers never throw on bad input: a malformed
 * notification is reported (for the sensor log) and dropped at the edge, never reaching the recording path.
 */
sealed interface ParseResult<out T> {
    data class Parsed<out T>(val value: T) : ParseResult<T>

    data class Malformed(val reason: String) : ParseResult<Nothing>

    fun getOrNull(): T? = (this as? Parsed<T>)?.value
}

/** Every value the parsers decode. */
sealed interface BleMeasurement
