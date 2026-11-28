package com.runpro.core.sync.healthconnect

import com.runpro.core.fit.FitExporter
import com.runpro.core.sync.writeStoppedRun
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class HealthConnectExporterTest {
    @Test
    fun `exports pending runs once, skips unchanged files, and leaves FIT files untouched`(
        @TempDir temp: Path
    ) {
        val journals = temp.resolve("journals").also(Files::createDirectories)
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("hc")

        val fit1 = exportRun(journals, runs, run = 1)
        val fit2 = exportRun(journals, runs, run = 2)
        val original1 = Files.readAllBytes(fit1)
        val original2 = Files.readAllBytes(fit2)

        val port = FakeHealthConnectPort()
        val exporter = HealthConnectExporter(runs, stateDir, port)

        val first = runSuspend { exporter.exportPending() }
        assertEquals(2, first.exported.size)
        assertTrue(first.skipped.isEmpty())
        assertTrue(first.failed.isEmpty())
        assertEquals(2, port.written.size)
        assertEquals(listOf("run-01", "run-02"), port.written.map { it.runKey })
        assertTrue(port.written.all { it.clientRecordVersion == 1L })

        // FIT files in files/runs are never modified or removed (needed for Strava and user history).
        assertContentEquals(original1, Files.readAllBytes(fit1))
        assertContentEquals(original2, Files.readAllBytes(fit2))

        // Second pass skips both runs without calling Health Connect again, even across a fresh instance.
        val restarted = HealthConnectExporter(runs, stateDir, port)
        val second = runSuspend { restarted.exportPending() }
        assertTrue(second.exported.isEmpty())
        assertEquals(2, second.skipped.size)
        assertEquals(2, port.written.size)
    }

    @Test
    fun `re-exports with incremented version when a FIT file is replaced`(@TempDir temp: Path) {
        val journals = temp.resolve("journals").also(Files::createDirectories)
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("hc")

        val fit = exportRun(journals, runs, run = 1, seconds = 60)
        val port = FakeHealthConnectPort()
        val exporter = HealthConnectExporter(runs, stateDir, port)

        runSuspend { exporter.exportPending() }
        assertEquals(1, port.written.size)
        assertEquals(1L, port.written.last().clientRecordVersion)

        // Replace run-01.fit with updated content (e.g. phone inbox received a newer FIT for the same run).
        val replacementJournal = temp.resolve("replacement.journal")
        val updated = writeStoppedRun(
            temp.resolve("journals2").also(Files::createDirectories),
            run = 1,
            seconds = 90
        )
        Files.deleteIfExists(replacementJournal)
        FitExporter.export(updated, fit)

        val second = runSuspend { exporter.exportPending() }
        assertEquals(1, second.exported.size)
        assertEquals(2, port.written.size)
        assertEquals("run-01", port.written.last().runKey)
        assertEquals(2L, port.written.last().clientRecordVersion)
    }

    @Test
    fun `stops early when unavailable or missing permissions and exports once granted`(
        @TempDir temp: Path
    ) {
        val journals = temp.resolve("journals").also(Files::createDirectories)
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("hc")
        exportRun(journals, runs, run = 1)
        exportRun(journals, runs, run = 2)

        val port = FakeHealthConnectPort(
            availability = HealthConnectPort.Availability.UNAVAILABLE
        )
        val exporter = HealthConnectExporter(runs, stateDir, port)

        val unavailable = runSuspend { exporter.exportPending() }
        assertEquals(HealthConnectPort.Availability.UNAVAILABLE, unavailable.availability)
        assertTrue(port.written.isEmpty())

        port.availability = HealthConnectPort.Availability.AVAILABLE
        port.nextOutcome = {
            HealthConnectPort.WriteOutcome.MissingPermissions(
                setOf("android.permission.health.WRITE_EXERCISE")
            )
        }
        val missing = runSuspend { exporter.exportPending() }
        assertEquals(setOf("android.permission.health.WRITE_EXERCISE"), missing.missingPermissions)
        assertEquals(1, port.attempts)

        // Once permissions are granted, both runs export cleanly.
        port.nextOutcome = { HealthConnectPort.WriteOutcome.Written }
        val granted = runSuspend { exporter.exportPending() }
        assertEquals(2, granted.exported.size)
        assertEquals(2, port.written.size)
    }

    @Test
    fun `keeps going past corrupt FIT or transient failure and retries failed runs`(
        @TempDir temp: Path
    ) {
        val journals = temp.resolve("journals").also(Files::createDirectories)
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("hc")

        Files.write(runs.resolve("00-corrupt.fit"), byteArrayOf(1, 2, 3, 4))
        exportRun(journals, runs, run = 1)
        exportRun(journals, runs, run = 2)

        var failFirstRun = true
        val port = FakeHealthConnectPort { run ->
            if (run.runKey == "run-01" && failFirstRun) {
                failFirstRun = false
                HealthConnectPort.WriteOutcome.Failed("binder busy")
            } else {
                HealthConnectPort.WriteOutcome.Written
            }
        }
        val exporter = HealthConnectExporter(runs, stateDir, port)

        val first = runSuspend { exporter.exportPending() }
        assertEquals(listOf("run-02"), first.exported)
        assertEquals(2, first.failed.size) // 00-corrupt.fit and transient run-01

        val retry = runSuspend { exporter.exportPending() }
        assertEquals(listOf("run-01"), retry.exported)
        assertEquals(1, retry.skipped.size) // run-02 already exported
        assertEquals(1, retry.failed.size) // 00-corrupt.fit still corrupt
    }

    @Test
    fun `exportFile returns specific outcome for a single run`(@TempDir temp: Path) {
        val journals = temp.resolve("journals").also(Files::createDirectories)
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("hc")
        val fit = exportRun(journals, runs, run = 1)

        val port = FakeHealthConnectPort()
        val exporter = HealthConnectExporter(runs, stateDir, port)

        val first = runSuspend { exporter.exportFile(fit) }
        assertIs<HealthConnectExporter.FileOutcome.Exported>(first)
        assertEquals("run-01", first.runKey)

        val second = runSuspend { exporter.exportFile(fit) }
        assertIs<HealthConnectExporter.FileOutcome.AlreadyExported>(second)
    }

    private fun exportRun(journals: Path, runs: Path, run: Int, seconds: Int = 60): Path {
        val journal = writeStoppedRun(journals, run = run, seconds = seconds)
        val fit = runs.resolve("run-%02d.fit".format(run))
        FitExporter.export(journal, fit)
        return fit
    }

    private class FakeHealthConnectPort(
        var availability: HealthConnectPort.Availability = HealthConnectPort.Availability.AVAILABLE,
        var nextOutcome: (HealthConnectRun) -> HealthConnectPort.WriteOutcome = {
            HealthConnectPort.WriteOutcome.Written
        }
    ) : HealthConnectPort {
        val written = mutableListOf<HealthConnectRun>()
        var attempts = 0

        override fun availability(): HealthConnectPort.Availability = availability

        override suspend fun write(run: HealthConnectRun): HealthConnectPort.WriteOutcome {
            attempts++
            val outcome = nextOutcome(run)
            if (outcome is HealthConnectPort.WriteOutcome.Written) {
                written += run
            }
            return outcome
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return requireNotNull(outcome) {
            "suspend block did not complete synchronously"
        }.getOrThrow()
    }
}
