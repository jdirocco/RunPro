package com.runpro.core.sync

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * One two-way byte stream between the watch and the phone, e.g. a Wearable Data Layer `ChannelClient`
 * channel. The watch writes one [FitOffer] and the file, the phone answers with one [FitAck].
 */
interface SyncChannel : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** Opens a [SyncChannel] to the phone; null when no phone with RunPro is reachable now. */
fun interface PhoneLink {
    @Throws(IOException::class)
    fun open(): SyncChannel?
}

/** The protocol is broken: bad magic, unknown version, a malformed field. */
class SyncProtocolException(message: String) : IOException(message)

/** What the watch announces before the file bytes: which run, how long, which content (SHA-256 hex). */
data class FitOffer(val runId: String, val sizeBytes: Long, val sha256: String)

/** The phone's answer for [runId]/[sha256]: [stored] only when that exact content is durably stored. */
data class FitAck(
    val runId: String,
    val sha256: String,
    val stored: Boolean,
    val reason: String = ""
) {
    fun confirms(offer: FitOffer): Boolean =
        stored && runId == offer.runId && sha256 == offer.sha256
}

/**
 * Wire format (ADR 0010), big endian, strings as `DataOutput.writeUTF`:
 * - offer: magic `RPF1`, version byte, run id, size (long), SHA-256 (32 bytes), then exactly size bytes;
 * - ack: magic `RPA1`, version byte, stored (boolean), run id, SHA-256 (32 bytes), reason.
 */
object SyncProtocol {
    const val VERSION: Int = 1

    /** Data Layer channel path of a FIT transfer. */
    const val CHANNEL_PATH: String = "/runpro/fit"

    /** Data Layer capability the phone app declares; the watch sends only to nodes that have it. */
    const val PHONE_CAPABILITY: String = "runpro_fit_receiver"

    /** Larger than any plausible run (a 24 h run with every sensor is a few MiB). */
    const val MAX_FIT_BYTES: Long = 64L * 1024 * 1024

    private const val OFFER_MAGIC = 0x52504631 // "RPF1"
    private const val ACK_MAGIC = 0x52504131 // "RPA1"
    private const val SHA256_BYTES = 32
    private const val MAX_RUN_ID_CHARS = 200
    private const val COPY_BUFFER = 64 * 1024

    fun writeOffer(output: OutputStream, offer: FitOffer, body: InputStream) {
        val data = DataOutputStream(output)
        data.writeInt(OFFER_MAGIC)
        data.writeByte(VERSION)
        data.writeUTF(offer.runId)
        data.writeLong(offer.sizeBytes)
        data.write(hexToBytes(offer.sha256))
        val copied = body.copyTo(data, COPY_BUFFER)
        if (copied != offer.sizeBytes) {
            throw IOException("file has $copied bytes, offer says ${offer.sizeBytes}")
        }
        data.flush()
    }

    fun readOffer(input: DataInputStream): FitOffer {
        expect(input.readInt() == OFFER_MAGIC) { "not a RunPro FIT offer" }
        val version = input.readUnsignedByte()
        expect(version == VERSION) { "unknown offer version $version" }
        val runId = input.readUTF()
        expect(runId.isNotBlank() && runId.length <= MAX_RUN_ID_CHARS) { "bad run id" }
        val size = input.readLong()
        expect(size in 1..MAX_FIT_BYTES) { "bad file size $size" }
        val sha = ByteArray(SHA256_BYTES).also(input::readFully)
        return FitOffer(runId, size, bytesToHex(sha))
    }

    fun writeAck(output: OutputStream, ack: FitAck) {
        val data = DataOutputStream(output)
        data.writeInt(ACK_MAGIC)
        data.writeByte(VERSION)
        data.writeBoolean(ack.stored)
        data.writeUTF(ack.runId)
        data.write(hexToBytes(ack.sha256))
        data.writeUTF(ack.reason)
        data.flush()
    }

    fun readAck(input: InputStream): FitAck {
        val data = DataInputStream(input)
        expect(data.readInt() == ACK_MAGIC) { "not a RunPro ack" }
        val version = data.readUnsignedByte()
        expect(version == VERSION) { "unknown ack version $version" }
        val stored = data.readBoolean()
        val runId = data.readUTF()
        val sha = ByteArray(SHA256_BYTES).also(data::readFully)
        return FitAck(runId, bytesToHex(sha), stored, data.readUTF())
    }

    internal fun hexToBytes(hex: String): ByteArray {
        expect(hex.length == SHA256_BYTES * 2) { "bad SHA-256 '$hex'" }
        return ByteArray(SHA256_BYTES) { i ->
            val byte = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)
            expect(byte != null) { "bad SHA-256 '$hex'" }
            byte!!.toByte()
        }
    }

    internal fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private inline fun expect(condition: Boolean, message: () -> String) {
        if (!condition) throw SyncProtocolException(message())
    }
}

/**
 * File names of runs, the same on watch and phone. Ids made of letters, digits, `.`, `_` and `-` (not
 * starting with `.`, at most 100) are used as they are; any other id becomes `~` and the SHA-256 of its
 * UTF-8 bytes. A plain id never contains `~`, so a plain and a hashed id never share a file.
 */
object RunFiles {
    private val PLAIN = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}")

    fun baseName(runId: String): String = if (PLAIN.matches(runId)) {
        runId
    } else {
        "~" + SyncProtocol.bytesToHex(
            MessageDigest.getInstance("SHA-256").digest(runId.toByteArray(Charsets.UTF_8))
        )
    }

    fun fitName(runId: String): String = baseName(runId) + ".fit"
}
