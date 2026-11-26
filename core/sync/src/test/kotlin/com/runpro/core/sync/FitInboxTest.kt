package com.runpro.core.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** The phone acknowledges a run only once it is complete, intact and durably stored, and stores it once. */
class FitInboxTest {
    private val body = ByteArray(10_000) { (it * 31).toByte() }
    private val offer = FitOffer("run-1", body.size.toLong(), hex(body))

    @Test
    fun `a complete file is stored and acknowledged, a second offer stores nothing new`(
        @TempDir dir: Path
    ) {
        val inbox = FitInbox(dir)

        val first = BufferChannel(request(offer, body))
        val stored = assertIs<FitInbox.Outcome.Stored>(inbox.receive(first))
        val second = BufferChannel(request(offer, body))
        assertIs<FitInbox.Outcome.AlreadyStored>(inbox.receive(second))

        assertEquals(false, stored.replaced)
        assertContentEquals(body, Files.readAllBytes(dir.resolve("run-1.fit")))
        for (channel in listOf(first, second)) {
            assertTrue(
                SyncProtocol.readAck(
                    ByteArrayInputStream(channel.reply.toByteArray())
                ).confirms(offer)
            )
        }
        assertEquals(listOf("run-1.fit"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a cut transfer stores and acknowledges nothing`(@TempDir dir: Path) {
        val inbox = FitInbox(dir)
        val full = request(offer, body)
        for (length in listOf(0, 3, 40, full.size / 2, full.size - 1)) {
            val channel = BufferChannel(full.copyOf(length))
            assertIs<FitInbox.Outcome.Interrupted>(inbox.receive(channel), "cut at $length")
            assertEquals(0, channel.reply.size())
        }
        assertEquals(emptyList(), dir.listDirectoryEntries())
    }

    @Test
    fun `content that does not match its hash is refused with a negative ack`(@TempDir dir: Path) {
        val inbox = FitInbox(dir)
        val changed = body.copyOf().also { it[5000] = 0 }
        val channel = BufferChannel(request(offer, changed))

        assertIs<FitInbox.Outcome.Rejected>(inbox.receive(channel))

        val ack = SyncProtocol.readAck(ByteArrayInputStream(channel.reply.toByteArray()))
        assertEquals(false, ack.stored)
        assertEquals(emptyList(), dir.listDirectoryEntries())
    }

    @Test
    fun `new content for a stored run replaces it`(@TempDir dir: Path) {
        val inbox = FitInbox(dir)
        inbox.receive(BufferChannel(request(offer, body)))
        val newer = body.copyOf(body.size + 1)

        val outcome = inbox.receive(
            BufferChannel(request(FitOffer("run-1", newer.size.toLong(), hex(newer)), newer))
        )

        assertEquals(true, assertIs<FitInbox.Outcome.Stored>(outcome).replaced)
        assertContentEquals(newer, Files.readAllBytes(dir.resolve("run-1.fit")))
    }

    @Test
    fun `garbage is refused, partial files of a dead process are removed`(@TempDir dir: Path) {
        Files.write(dir.resolve(".incoming-123.part"), ByteArray(10))
        val inbox = FitInbox(dir)

        assertIs<FitInbox.Outcome.Rejected>(
            inbox.receive(BufferChannel("hello world, not RunPro".toByteArray()))
        )

        assertEquals(emptyList(), dir.listDirectoryEntries())
    }

    @Test
    fun `offers and acks round trip, malformed fields are refused`() {
        val bytes = request(offer, body)
        val parsed = SyncProtocol.readOffer(java.io.DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(offer, parsed)
        assertFailsWith<SyncProtocolException> { SyncProtocol.hexToBytes("xyz") }
        assertFailsWith<java.io.IOException> {
            SyncProtocol.writeOffer(
                ByteArrayOutputStream(),
                offer.copy(sizeBytes = 1),
                ByteArrayInputStream(body)
            )
        }
        val ack = FitAck("run-1", offer.sha256, stored = false, reason = "disk full")
        val out = ByteArrayOutputStream().also { SyncProtocol.writeAck(it, ack) }
        assertEquals(ack, SyncProtocol.readAck(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `run ids map to safe, distinct file names`() {
        assertEquals("run-2026-11-26_07.30.fit", RunFiles.fitName("run-2026-11-26_07.30"))
        val odd = listOf("../etc/passwd", ".hidden", "a/b", "a b", "x".repeat(101), "~abc")
        val names = odd.map(RunFiles::fitName)
        assertTrue(
            names.all {
                it.startsWith("~") && !it.contains('/') && it.length < 80
            },
            names.toString()
        )
        assertEquals(names.size, names.toSet().size)
    }

    private fun request(offer: FitOffer, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        // writeOffer checks the size, so build mismatching content by hand when needed.
        if (content.size.toLong() == offer.sizeBytes) {
            SyncProtocol.writeOffer(out, offer, ByteArrayInputStream(content))
        } else {
            SyncProtocol.writeOffer(
                out,
                offer.copy(sizeBytes = content.size.toLong()),
                ByteArrayInputStream(content)
            )
        }
        return out.toByteArray()
    }

    private fun hex(bytes: ByteArray) =
        SyncProtocol.bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
