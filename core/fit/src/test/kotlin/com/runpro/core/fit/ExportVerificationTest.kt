package com.runpro.core.fit

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.StopReason
import com.runpro.core.recording.JournalRecovery
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.RecoveryMode
import com.runpro.tools.replay.SyntheticTraces
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * R3: a journal is deleted only after its FIT file is decoded and matches it. These tests prove the half that
 * belongs to export: a [VerifiedExport] exists only for a file that was written to storage, read back,
 * decoded by the Garmin FIT SDK and found equal to the journal; any other outcome leaves no FIT file and
 * never changes the journal. Journal deletion after the phone acknowledgement is #30.
 */
class ExportVerificationTest {
    @Test
    fun `the recorded 30 min easy run exports to a verified FIT file with the watch totals`(
        @TempDir dir: Path
    ) {
        val journal = dir.resolve("easy.journal")
        recordJournal(
            SyntheticTraces.easyRun(),
            listOf(lap(600.0), pause(900.0), resume(930.0), lap(1200.0), stop(1800.0)),
            journal
        )
        val export = FitExporter.export(journal, dir.resolve("easy.fit"))

        val run = RecordedRun.from(JournalReader.read(journal))
        val fit = FitDecoder.decode(Files.readAllBytes(export.fit))
        assertEquals(run.id, export.runId)
        assertEquals(run.id.value, fit.runId)
        // The golden totals of testdata/traces/golden/easy-run-30min.json, now read from the FIT file.
        val session = assertNotNull(fit.session)
        assertEquals(1800.0, session.elapsedSeconds!!, 0.01)
        assertEquals(1770.0, session.timerSeconds!!, 0.01)
        assertEquals(1769.0, session.movingSeconds!!, 0.01)
        assertEquals(4913.89, session.distanceMeters!!, 0.05)
        assertEquals(139, session.averageHeartRateBpm)
        assertEquals(147, session.maxHeartRateBpm)
        assertEquals(172.23, session.averageCadenceStepsPerMinute!!, 0.05)
        assertEquals(
            listOf(LapTrigger.MANUAL, LapTrigger.MANUAL, LapTrigger.STOP),
            fit.laps.map { it.lapTrigger }
        )
        assertEquals(
            listOf(1666.66, 1583.34, 1663.89),
            fit.laps.map {
                round2(it.distanceMeters!!)
            }
        )
        // One record per second of the trace, the last one at the stop.
        assertEquals(1800, fit.records.size)
        assertEquals(
            listOf(
                "TIMER/START",
                "LAP/MARKER",
                "TIMER/STOP_ALL",
                "TIMER/START",
                "LAP/MARKER",
                "TIMER/STOP_ALL"
            ),
            fit.events.map { it.type }
        )
    }

    @Test
    fun `the intervals and strap dropout traces export verified`(@TempDir dir: Path) {
        val reps = (0 until 6).flatMap { rep -> listOf(600.0 + rep * 300, 780.0 + rep * 300) }
        val cases = mapOf(
            "intervals" to Pair(SyntheticTraces.intervals(), reps.map(::lap) + stop(2700.0)),
            "dropout" to Pair(SyntheticTraces.strapDropout(), listOf(stop(1200.0)))
        )
        for ((name, case) in cases) {
            val journal = dir.resolve("$name.journal")
            recordJournal(case.first, case.second, journal)
            val export = FitExporter.export(journal, dir.resolve("$name.fit"))
            val fit = FitDecoder.decode(Files.readAllBytes(export.fit))
            val run = RecordedRun.from(JournalReader.read(journal))
            assertEquals(run.state.laps.size, fit.laps.size, name)
            assertEquals(
                run.state.totals.distance.meters,
                fit.session!!.distanceMeters!!,
                0.05,
                name
            )
        }
    }

    @Test
    fun `every journal record kind survives the export, source switches and GATT errors included`(
        @TempDir dir: Path
    ) {
        val journal = dir.resolve("every.journal")
        everyRecordJournal(journal)
        val export = FitExporter.export(journal, dir.resolve("every.fit"))
        val fit = FitDecoder.decode(Files.readAllBytes(export.fit))
        val run = RecordedRun.from(JournalReader.read(journal))

        assertEquals(run.run.events, fit.events.mapNotNull { it.runEvent })
        assertEquals(run.sensorEvents, fit.events.mapNotNull { it.sensorEvent })
        val switches = fit.events.mapNotNull { it.runEvent as? RunEvent.SourceSwitched }
        assertEquals(3, switches.size)
        assertEquals(
            133,
            fit.events.firstNotNullOf { it.sensorEvent as? SensorEvent.Disconnected }.gattStatus
        )
        assertEquals("TIMER/STOP_ALL", fit.events.single { it.runEvent is RunEvent.Stopped }.type)
        assertEquals("AUTO", fit.events.first { it.runEvent is RunEvent.Paused }.timerTrigger)
        assertEquals(
            listOf(LapTrigger.MANUAL, LapTrigger.DISTANCE, LapTrigger.TIME, LapTrigger.STOP),
            fit.laps.map { it.lapTrigger }
        )
        // Every source is a device, with its BLE address as descriptor.
        val descriptors = fit.devices.mapNotNull { it.descriptor }.toSet()
        assertEquals(
            setOf(WATCH, FOOTPOD, STRAP, com.runpro.core.model.SourceId.WATCH_IMU).map {
                it.value
            }.toSet(),
            descriptors
        )
        assertEquals(
            "BLUETOOTH_LOW_ENERGY",
            fit.devices.single { it.descriptor == STRAP.value }.sourceType
        )
        // Records carry power, cadence and the barometric elevation when a second has one.
        val first = fit.records.first()
        assertEquals(250, first.powerWatts)
        assertEquals(171.3, first.cadenceStepsPerMinute!!, 0.02)
        assertEquals(119.0, first.altitudeMeters!!, 0.11)
        assertEquals(120.1, fit.records[1].altitudeMeters!!, 0.11)
        assertEquals(45.4642, first.latitude!!, 1e-6)
    }

    @Test
    fun `FIT times follow the monotonic clock across a wall clock jump`(@TempDir dir: Path) {
        val journal = dir.resolve("jump.journal")
        // The wall clock jumps an hour ahead 30 s into the run (NTP or manual change).
        everyRecordJournal(journal, wallJumpMillis = 3_600_000)
        val fit = FitDecoder.decode(
            Files.readAllBytes(FitExporter.export(journal, dir.resolve("jump.fit")).fit)
        )
        val times = fit.records.map { it.timeMillis!! }
        assertEquals(times.sorted(), times)
        assertEquals(59_000L, times.last() - times.first())
        assertEquals(ORIGIN_WALL_MILLIS + 60_000, fit.session!!.endMillis)
    }

    @Test
    fun `a run saved by crash recovery exports verified`(@TempDir dir: Path) {
        val journal = dir.resolve("crash.journal")
        val full = dir.resolve("full.journal")
        recordJournal(SyntheticTraces.easyRun(), listOf(stop(1800.0)), full)
        // The process died 10 minutes in: keep the journal up to the first record after 600 s.
        val contents = JournalReader.read(full)
        val cut = contents.records.indexOfFirst { record ->
            record is JournalRecord.Samples &&
                record.samples.first().time.elapsedSince(
                    contents.runEvents.first().time
                ).inWholeSeconds >=
                600
        }
        JournalWriter.create(journal).use { writer ->
            contents.records.take(cut).forEach(writer::append)
        }
        JournalRecovery.restore(journal, RecoveryMode.SAVE).close()

        val export = FitExporter.export(journal, dir.resolve("crash.fit"))
        val fit = FitDecoder.decode(Files.readAllBytes(export.fit))
        assertEquals(
            StopReason.RECOVERY,
            (
                fit.events.last {
                    it.runEvent != null
                }.runEvent as RunEvent.Stopped
                ).reason
        )
        assertEquals(600.0, fit.session!!.timerSeconds!!, 1.0)
    }

    @Test
    fun `export never changes the journal and leaves no temporary file`(@TempDir dir: Path) {
        val journal = dir.resolve("every.journal")
        everyRecordJournal(journal)
        val before = Files.readAllBytes(journal)
        val fit = dir.resolve("every.fit")
        Files.write(fit, byteArrayOf(1, 2, 3)) // A stale file from an earlier attempt is replaced.

        val export = FitExporter.export(journal, fit)

        assertContentEquals(before, Files.readAllBytes(journal))
        assertEquals(
            setOf("every.journal", "every.fit"),
            dir.listDirectoryEntries().map {
                it.name
            }.toSet()
        )
        assertEquals(Files.size(fit), export.sizeBytes)
        assertTrue(FitExporter.isUnchanged(export))
        Files.write(fit, byteArrayOf(1), java.nio.file.StandardOpenOption.APPEND)
        assertFalse(FitExporter.isUnchanged(export))
    }

    @Test
    fun `a FIT file that does not match the journal is removed and no proof is given`(
        @TempDir dir: Path
    ) {
        val journal = dir.resolve("every.journal")
        everyRecordJournal(journal)
        val before = Files.readAllBytes(journal)
        // A different run: one more distance sample changes the totals and the records.
        val otherRun = RecordedRun.from(
            JournalReader.read(journal).let { contents ->
                contents.copy(
                    records = contents.records.dropLast(1) +
                        JournalRecord.Samples(
                            listOf(DistanceSample(at(59.9), WATCH, Distance(500.0)))
                        ) +
                        contents.records.last()
                )
            }
        )
        val fit = dir.resolve("every.fit")

        val failure = assertFailsWith<ExportVerificationException> {
            FitExporter.export(journal, fit, com.runpro.core.recording.EngineConfig()) {
                FitEncoder.encode(otherRun)
            }
        }

        assertTrue(
            failure.problems.any {
                it.startsWith("session distance")
            },
            failure.problems.toString()
        )
        assertFalse(fit.exists())
        assertContentEquals(before, Files.readAllBytes(journal))
    }

    @Test
    fun `a damaged FIT file fails to decode and is removed`(@TempDir dir: Path) {
        val journal = dir.resolve("every.journal")
        everyRecordJournal(journal)
        val fit = dir.resolve("every.fit")
        val damaged: (RecordedRun) -> ByteArray = { run ->
            FitEncoder.encode(run).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        }

        assertFailsWith<FitFormatException> {
            FitExporter.export(journal, fit, com.runpro.core.recording.EngineConfig(), damaged)
        }
        assertFalse(fit.exists())
        assertFailsWith<FitFormatException> { FitDecoder.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `an unstopped or unknown journal is refused before any file is written`(
        @TempDir dir: Path
    ) {
        val journal = dir.resolve("open.journal")
        JournalWriter.create(journal).use {
            it.append(JournalRecord.RunStarted(RunId("open"), at(0.0)))
        }
        val fit = dir.resolve("open.fit")
        assertFailsWith<IllegalArgumentException> { FitExporter.export(journal, fit) }
        assertFalse(fit.exists())
        assertEquals(listOf("open.journal"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `the verifier reports every difference it finds`(@TempDir dir: Path) {
        val journal = dir.resolve("every.journal")
        everyRecordJournal(journal)
        val run = RecordedRun.from(JournalReader.read(journal))
        val fit = FitDecoder.decode(FitEncoder.encode(run))
        assertTrue(ExportVerifier.verify(run, fit).isVerified)

        val tampered = fit.copy(
            runId = "someone-else",
            records = fit.records.dropLast(1),
            laps = fit.laps.map { it.copy(timerSeconds = it.timerSeconds!! + 1) },
            events = fit.events.filter { it.runEvent !is RunEvent.SourceSwitched }
        )
        val problems = ExportVerifier.verify(run, tampered).problems
        assertTrue(problems.any { it.startsWith("run id") }, problems.toString())
        assertTrue(problems.any { it.startsWith("record count") }, problems.toString())
        assertTrue(problems.any { it.startsWith("lap 0 timer") }, problems.toString())
        assertTrue(problems.any { it.startsWith("run event count") }, problems.toString())
    }

    @Test
    fun `writes sample FIT files for validator and Strava checks`(@TempDir dir: Path) {
        // Owner check (docs/FIELD_TESTING.md): upload these to the Garmin FIT validator and to Strava.
        val out = Paths.get(System.getProperty("runpro.fitSamples") ?: return)
        Files.createDirectories(out)
        val journal = dir.resolve("easy.journal")
        recordJournal(
            SyntheticTraces.easyRun(),
            listOf(lap(600.0), pause(900.0), resume(930.0), lap(1200.0), stop(1800.0)),
            journal
        )
        FitExporter.export(journal, out.resolve("easy-run-30min.fit"))
        val every = dir.resolve("every.journal")
        everyRecordJournal(every)
        FitExporter.export(every, out.resolve("every-record.fit"))
        assertTrue(out.resolve("easy-run-30min.fit").exists())
    }

    private fun round2(value: Double): Double = Math.round(value * 100) / 100.0
}
