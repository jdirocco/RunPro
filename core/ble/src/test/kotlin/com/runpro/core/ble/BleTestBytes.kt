package com.runpro.core.ble

import kotlin.test.assertIs

/** Builds a characteristic value from unsigned byte literals, as written in the GATT specifications. */
fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

fun <T> parsed(result: ParseResult<T>?): T = assertIs<ParseResult.Parsed<T>>(result).value

fun malformed(result: ParseResult<*>?): String = assertIs<ParseResult.Malformed>(result).reason
