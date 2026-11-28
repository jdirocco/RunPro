package com.runpro.core.journal

import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** A file that is not a journal, or a journal written by a newer, incompatible format version. */
class JournalFormatException(message: String) : IOException(message)

/**
 * The journal file layout (ADR 0006), all integers big-endian:
 *
 * ```
 * header:  magic "RPJN" (4 bytes; "RPIM" for an IMU stream) | format version (int32)
 * record:  payload length (int32) | CRC32 of the payload (int32) | payload (protobuf `Record`, see JournalProto)
 * ```
 *
 * A record is valid when its length is in `1..MAX_PAYLOAD_SIZE` and its CRC matches. Zero is never a valid
 * length, so the zero-filled blocks a file system can leave after a power loss are not read as records.
 */
internal object JournalFormat {
    val MAGIC: ByteArray = "RPJN".toByteArray(Charsets.US_ASCII)

    /** IMU streams (ADR 0012) share the layout but not the magic, so neither is read as the other. */
    val IMU_MAGIC: ByteArray = "RPIM".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val HEADER_SIZE = 8
    const val RECORD_HEADER_SIZE = 8

    /** Far above any real record (a batch of 1 Hz samples is a few kilobytes). */
    const val MAX_PAYLOAD_SIZE = 1 shl 20

    fun header(magic: ByteArray = MAGIC): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).put(magic).putInt(VERSION).array()

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size in 1..MAX_PAYLOAD_SIZE) { "Invalid record size: ${payload.size}" }
        return ByteBuffer.allocate(RECORD_HEADER_SIZE + payload.size)
            .putInt(payload.size)
            .putInt(crc(payload))
            .put(payload)
            .array()
    }

    fun crc(payload: ByteArray, offset: Int = 0, length: Int = payload.size): Int =
        CRC32().apply { update(payload, offset, length) }.value.toInt()
}
