package com.runpro.core.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** R3 on the watch: a journal is deleted only when the phone acknowledged its verified FIT file. */
class FitOutboxTest {
    @Test
    fun `without a phone nothing is deleted and the verified runs wait`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val files = (1..3).map { writeStoppedRun(journals, it) }
        val before = files.map(Files::readAllBytes)

        val report = FitOutbox(journals, dir.resolve("exports")).sync { null }

        assertFalse(report.phoneReachable)
        assertEquals(3, report.waiting)
        assertTrue(report.needsRetry)
        files.zip(before).forEach { (file, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(file))
        }
        assertEquals(
            listOf("run-01.fit", "run-02.fit", "run-03.fit"),
            dir.resolve("exports").listDirectoryEntries().map { it.name }.sorted()
        )
    }

    @Test
    fun `a run still recording is neither sent nor deleted`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val open = writeOpenRun(journals, "running")
        val link = FaultyLink(FitInbox(dir.resolve("phone"))) { Fault.NONE }

        val report = FitOutbox(journals, dir.resolve("exports")).sync(link)

        assertTrue(open.exists())
        assertEquals(0, report.waiting)
        assertEquals(emptyList(), link.outcomes)
    }

    @Test
    fun `a refused file or an ack for other content keeps the journal`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val journal = writeStoppedRun(journals, 1)
        val outbox = FitOutbox(journals, dir.resolve("exports"))
        val refused = FitAck("run-01", "00".repeat(32), stored = false, reason = "disk full")
        val otherContent = FitAck("run-01", "ab".repeat(32), stored = true)
        val otherRun = FitAck("run-02", "00".repeat(32), stored = true)

        for (ack in listOf(refused, otherContent, otherRun)) {
            val report = outbox.sync { answering(ack) }
            assertTrue(journal.exists(), "deleted after $ack")
            assertEquals(1, report.waiting)
            assertEquals(1, report.problems.size, report.problems.toString())
        }
    }

    @Test
    fun `a FIT file changed after verification keeps the journal and is exported again`(
        @TempDir dir: Path
    ) {
        val journals = dir.resolve("journals").createDirectories()
        val journal = writeStoppedRun(journals, 1)
        val exports = dir.resolve("exports")
        val inbox = FitInbox(dir.resolve("phone"))
        val outbox = FitOutbox(journals, exports)
        val damaging = FaultyLink(inbox) { Fault.NONE }
        damaging.afterPhone = {
            val fit = exports.resolve("run-01.fit")
            val bytes = Files.readAllBytes(fit)
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
            Files.write(fit, bytes)
        }

        val first = outbox.sync(damaging)
        val second = outbox.sync(FaultyLink(inbox) { Fault.NONE })

        assertEquals(emptyList(), first.delivered)
        assertTrue(first.problems.single().contains("changed after verification"))
        assertEquals(listOf("run-01"), second.delivered)
        assertFalse(journal.exists())
        // The rewritten file is the same content the phone already had: stored once.
        assertContentEquals(
            Files.readAllBytes(exports.resolve("run-01.fit")),
            Files.readAllBytes(inbox.stored().single())
        )
    }

    @Test
    fun `after a restart the earlier FIT file is reused, not written again`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        writeStoppedRun(journals, 1)
        val exports = dir.resolve("exports")
        FitOutbox(journals, exports, utcOffsetSeconds = { 0 }).sync { null }
        val firstFile = Files.readAllBytes(exports.resolve("run-01.fit"))
        val inbox = FitInbox(dir.resolve("phone"))

        // Another time zone after the restart would give another file (local timestamps).
        val report = FitOutbox(journals, exports, utcOffsetSeconds = { 7200 })
            .sync(FaultyLink(inbox) { Fault.NONE })

        assertEquals(listOf("run-01"), report.delivered)
        assertContentEquals(firstFile, Files.readAllBytes(inbox.stored().single()))
    }

    @Test
    fun `the storage reserve beside a delivered journal goes with it`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val journal = writeStoppedRun(journals, 1)
        val reserve = journals.resolve("${journal.name}.reserve")
        Files.write(reserve, ByteArray(16))

        FitOutbox(journals, dir.resolve("exports")).sync(
            FaultyLink(FitInbox(dir.resolve("phone"))) {
                Fault.NONE
            }
        )

        assertEquals(emptyList(), journals.listDirectoryEntries())
    }

    /** A phone that reads the offer and answers [ack] whatever it received. */
    private fun answering(ack: FitAck): SyncChannel = object : SyncChannel {
        private val sent = ByteArrayOutputStream()
        override val output: OutputStream = sent
        override val input: InputStream by lazy {
            ByteArrayInputStream(
                ByteArrayOutputStream().also {
                    SyncProtocol.writeAck(it, ack)
                }.toByteArray()
            )
        }
        override fun close() = Unit
    }
}
