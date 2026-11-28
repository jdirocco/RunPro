package com.runpro.core.sync

import com.runpro.core.fit.FitDecoder
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Elevation
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.SourceId
import com.runpro.core.model.StopReason
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class RunHistoryStoreTest {
    @Test
    fun `missing directories return an empty history list`(@TempDir dir: Path) {
        val store = RunHistoryStore(dir.resolve("journals"), dir.resolve("exports"))

        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `runs are listed newest first with exact sync states`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        val inbox = FitInbox(dir.resolve("phone"))
        val outbox = FitOutbox(journals, exports)

        // Run 3 (newest): exported and acknowledged by the phone -> SYNCED.
        writeStoppedRun(journals, run = 3)
        assertEquals(listOf("run-03"), outbox.sync(FaultyLink(inbox) { Fault.NONE }).delivered)

        // Run 2 (middle): exported, phone unreachable -> PENDING_SYNC.
        writeStoppedRun(journals, run = 2)
        assertEquals(1, outbox.sync { null }.waiting)

        // Run 1 (oldest): stopped journal not exported yet -> PENDING_EXPORT.
        writeStoppedRun(journals, run = 1)

        val entries = RunHistoryStore(journals, exports).list()

        assertEquals(listOf("run-03", "run-02", "run-01"), entries.map { it.runId.value })
        assertEquals(
            listOf(RunSyncState.SYNCED, RunSyncState.PENDING_SYNC, RunSyncState.PENDING_EXPORT),
            entries.map { it.syncState }
        )
        assertFalse(entries[0].canRetryExport)
        assertFalse(entries[0].isUnsynced)
        assertTrue(entries[1].canRetryExport)
        assertTrue(entries[1].isUnsynced)
        assertTrue(entries[2].canRetryExport)
        assertTrue(entries[2].isUnsynced)
    }

    @Test
    fun `open journal is excluded from history and cannot be exported or deleted`(
        @TempDir dir: Path
    ) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        val openFile = writeOpenRun(journals, "active-run")
        val before = Files.readAllBytes(openFile)
        val store = RunHistoryStore(journals, exports)

        assertEquals(emptyList(), store.list())
        assertIs<RetryExportResult.NotFound>(store.retryExport(RunId("active-run")))
        assertFalse(store.delete(RunId("active-run")))
        assertTrue(openFile.exists())
        assertContentEquals(before, Files.readAllBytes(openFile))
    }

    @Test
    fun `summary and laps match between unsynced journal and synced FIT file`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        val runId = RunId("multi-lap")
        writeMultiLapRun(journals, runId)

        val store = RunHistoryStore(journals, exports)
        val beforeSync = store.list().single()
        assertEquals(RunSyncState.PENDING_EXPORT, beforeSync.syncState)
        assertEquals(2, beforeSync.laps.size)

        val inbox = FitInbox(dir.resolve("phone"))
        val report = FitOutbox(journals, exports).sync(FaultyLink(inbox) { Fault.NONE })
        assertEquals(listOf(runId.value), report.delivered)

        val afterSync = store.list().single()
        assertEquals(RunSyncState.SYNCED, afterSync.syncState)
        assertEquals(beforeSync.startedAtMillis / 1000L, afterSync.startedAtMillis / 1000L)

        val beforeSummary = assertNotNull(beforeSync.summary)
        val afterSummary = assertNotNull(afterSync.summary)
        assertEquals(beforeSummary.timer, afterSummary.timer)
        assertEquals(beforeSummary.elapsed, afterSummary.elapsed)
        assertTrue(abs(beforeSummary.distance.meters - afterSummary.distance.meters) < 0.5)
        assertEquals(beforeSummary.averageHeartRate, afterSummary.averageHeartRate)
        assertEquals(beforeSummary.maxHeartRate, afterSummary.maxHeartRate)
        assertNotNull(afterSummary.averagePace)
        assertNotNull(afterSummary.averageCadence)
        assertTrue(abs(beforeSummary.ascent.meters - afterSummary.ascent.meters) <= 1.0)
        assertTrue(abs(beforeSummary.descent.meters - afterSummary.descent.meters) <= 1.0)

        assertEquals(beforeSync.laps.size, afterSync.laps.size)
        beforeSync.laps.zip(afterSync.laps).forEach { (expected, actual) ->
            assertEquals(expected.index, actual.index)
            assertEquals(expected.timer, actual.timer)
            assertTrue(abs(expected.distance.meters - actual.distance.meters) < 0.5)
            assertEquals(expected.averageHeartRate, actual.averageHeartRate)
            assertEquals(expected.trigger, actual.trigger)
        }
    }

    @Test
    fun `retryExport writes verified FIT file, moves to PENDING_SYNC, and keeps journal`(
        @TempDir dir: Path
    ) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        val journal = writeStoppedRun(journals, run = 1)
        val before = Files.readAllBytes(journal)
        val store = RunHistoryStore(journals, exports)

        assertEquals(RunSyncState.PENDING_EXPORT, store.list().single().syncState)

        val result = store.retryExport(RunId("run-01"))
        val exported = assertIs<RetryExportResult.Exported>(result)
        assertTrue(exported.fit.exists())
        assertEquals("run-01", FitDecoder.decode(Files.readAllBytes(exported.fit)).runId)
        // R3: exporting never deletes the journal.
        assertTrue(journal.exists())
        assertContentEquals(before, Files.readAllBytes(journal))
        assertEquals(RunSyncState.PENDING_SYNC, store.list().single().syncState)
    }

    @Test
    fun `retryExport replaces a damaged FIT file while keeping the journal`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        val journal = writeStoppedRun(journals, run = 1)
        val fit = exports.resolve("run-01.fit")
        Files.write(fit, byteArrayOf(1, 2, 3, 4))

        val store = RunHistoryStore(journals, exports)
        val result = store.retryExport(RunId("run-01"))

        val exported = assertIs<RetryExportResult.Exported>(result)
        assertEquals("run-01", FitDecoder.decode(Files.readAllBytes(exported.fit)).runId)
        assertTrue(journal.exists())
    }

    @Test
    fun `unexportable stopped journal appears as EXPORT_FAILED and stays untouched on retry`(
        @TempDir dir: Path
    ) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        val journal = writeStoppedRunWithUnknownRecord(journals, "future-run")
        val before = Files.readAllBytes(journal)
        val store = RunHistoryStore(journals, exports)

        val entry = store.list().single()
        assertEquals(RunId("future-run"), entry.runId)
        assertEquals(RunSyncState.EXPORT_FAILED, entry.syncState)
        assertNotNull(entry.problem)
        assertTrue(entry.canRetryExport)

        val retry = store.retryExport(RunId("future-run"))
        assertIs<RetryExportResult.Failed>(retry)
        assertTrue(journal.exists())
        assertContentEquals(before, Files.readAllBytes(journal))
    }

    @Test
    fun `retryExport returns AlreadySynced when only the FIT file remains`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        writeStoppedRun(journals, run = 1)
        FitOutbox(journals, exports).sync(FaultyLink(FitInbox(dir.resolve("phone"))) { Fault.NONE })

        val result = RunHistoryStore(journals, exports).retryExport(RunId("run-01"))

        assertIs<RetryExportResult.AlreadySynced>(result)
    }

    @Test
    fun `delete removes a synced run FIT file without touching other runs`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        writeStoppedRun(journals, run = 1)
        writeStoppedRun(journals, run = 2)
        FitOutbox(journals, exports).sync(FaultyLink(FitInbox(dir.resolve("phone"))) { Fault.NONE })

        val store = RunHistoryStore(journals, exports)
        assertTrue(store.delete(RunId("run-01")))
        assertFalse(exports.resolve("run-01.fit").exists())
        assertTrue(exports.resolve("run-02.fit").exists())
        assertEquals(listOf(RunId("run-02")), store.list().map { it.runId })
    }

    @Test
    fun `delete removes an unsynced run journal, reserve, and FIT export`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        val journal = writeStoppedRun(journals, run = 1)
        val reserve = journals.resolve("${journal.fileName}.reserve")
        Files.write(reserve, ByteArray(16))
        writeStoppedRun(journals, run = 2)
        FitOutbox(journals, exports).sync { null }

        val store = RunHistoryStore(journals, exports)
        assertTrue(store.delete(RunId("run-01")))
        assertFalse(journal.exists())
        assertFalse(reserve.exists())
        assertFalse(exports.resolve("run-01.fit").exists())
        assertEquals(listOf(RunId("run-02")), store.list().map { it.runId })
    }

    @Test
    fun `corrupt FIT files and temporary artifacts are ignored while valid runs still list`(
        @TempDir dir: Path
    ) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        writeStoppedRun(journals, run = 1)
        FitOutbox(journals, exports).sync(FaultyLink(FitInbox(dir.resolve("phone"))) { Fault.NONE })
        Files.write(exports.resolve("corrupt.fit"), byteArrayOf(0, 1, 2, 3))
        Files.write(exports.resolve("partial.fit.tmp"), byteArrayOf(9, 8, 7))
        Files.write(journals.resolve("orphan.journal.reserve"), ByteArray(8))
        Files.write(journals.resolve("bad.journal"), byteArrayOf(1, 2, 3))

        val entries = RunHistoryStore(journals, exports).list()

        assertEquals(listOf(RunId("run-01")), entries.map { it.runId })
        assertEquals(RunSyncState.SYNCED, entries.single().syncState)
    }

    @Test
    fun `run id needing a hashed filename transitions and deletes cleanly`(@TempDir dir: Path) {
        val journals = dir.resolve("journals").createDirectories()
        val exports = dir.resolve("exports").createDirectories()
        val runId = RunId("morning run / 1")
        val file = journals.resolve("hashed.journal")
        JournalWriter.create(file).use { writer ->
            writer.append(JournalRecord.RunStarted(runId, at(1, 0)))
            writer.append(
                JournalRecord.Samples(
                    listOf(
                        DistanceSample(at(1, 0), SourceId.HEALTH_SERVICES, Distance.ZERO),
                        DistanceSample(at(1, 30), SourceId.HEALTH_SERVICES, Distance(100.0))
                    )
                )
            )
            writer.append(JournalRecord.Event(RunEvent.Stopped(at(1, 30), StopReason.USER)))
        }

        val store = RunHistoryStore(journals, exports)
        assertEquals(RunSyncState.PENDING_EXPORT, store.list().single().syncState)

        assertIs<RetryExportResult.Exported>(store.retryExport(runId))
        assertEquals(RunSyncState.PENDING_SYNC, store.list().single().syncState)

        FitOutbox(journals, exports).sync(FaultyLink(FitInbox(dir.resolve("phone"))) { Fault.NONE })
        assertEquals(RunSyncState.SYNCED, store.list().single().syncState)

        assertTrue(store.delete(runId))
        assertEquals(emptyList(), store.list())
    }

    private fun writeMultiLapRun(journals: Path, runId: RunId): Path {
        val file = journals.resolve("${runId.value}${FitOutbox.JOURNAL_SUFFIX}")
        JournalWriter.create(file).use { writer ->
            writer.append(JournalRecord.RunStarted(runId, at(1, 0)))
            for (s in 0..120) {
                if (s == 50) {
                    writer.append(
                        JournalRecord.Event(RunEvent.Paused(at(1, 50), PauseReason.MANUAL))
                    )
                }
                if (s == 60) {
                    writer.append(JournalRecord.Event(RunEvent.Resumed(at(1, 60))))
                }
                if (s == 70) {
                    writer.append(
                        JournalRecord.Event(RunEvent.LapMarked(at(1, 70), LapTrigger.MANUAL))
                    )
                }
                val active = when {
                    s < 50 -> s
                    s < 60 -> 50
                    else -> s - 10
                }
                val elevation = if (active <= 55) {
                    100.0 + active * 0.4
                } else {
                    122.0 - (active - 55) * 0.3
                }
                writer.append(
                    JournalRecord.Samples(
                        listOf(
                            HeartRateSample(
                                at(1, s),
                                SourceId.HEALTH_SERVICES,
                                HeartRate(140 + (s % 15))
                            ),
                            CadenceSample(
                                at(1, s),
                                SourceId.HEALTH_SERVICES,
                                Cadence(172.0)
                            ),
                            DistanceSample(
                                at(1, s),
                                SourceId.HEALTH_SERVICES,
                                Distance(active * 3.2)
                            ),
                            ElevationSample(
                                at(1, s),
                                SourceId.HEALTH_SERVICES,
                                Elevation(elevation)
                            )
                        )
                    )
                )
            }
            writer.append(JournalRecord.Event(RunEvent.Stopped(at(1, 120), StopReason.USER)))
        }
        return file
    }

    private fun writeStoppedRunWithUnknownRecord(journals: Path, id: String): Path {
        val file = journals.resolve("$id${FitOutbox.JOURNAL_SUFFIX}")
        JournalWriter.create(file).use { writer ->
            writer.append(JournalRecord.RunStarted(RunId(id), at(1, 0)))
            writer.append(JournalRecord.Event(RunEvent.Stopped(at(1, 10), StopReason.USER)))
        }
        val raw = Files.readAllBytes(file)
        // Header is 8 bytes ("RPJN" + int32 version); first record length is at offset 8.
        val firstRecordLen = ByteBuffer.wrap(raw, 8, 4).int
        val split = 8 + 8 + firstRecordLen
        // Intact framed record with an unknown protobuf field number (simulating a newer writer).
        val unknownPayload = byteArrayOf(0x78, 0x01)
        val crc = CRC32().apply { update(unknownPayload) }.value.toInt()
        val unknown = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(unknownPayload.size)
                writeInt(crc)
                write(unknownPayload)
            }
        }.toByteArray()
        Files.write(file, raw.copyOfRange(0, split) + unknown + raw.copyOfRange(split, raw.size))
        return file
    }
}
