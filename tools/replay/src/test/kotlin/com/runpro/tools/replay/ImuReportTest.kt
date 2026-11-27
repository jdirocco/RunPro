package com.runpro.tools.replay

import com.runpro.core.journal.ImuStream
import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ImuReading
import com.runpro.core.model.ImuSensor
import com.runpro.core.model.RunId
import com.runpro.core.model.SourceId
import com.runpro.tools.replay.Fixtures.t0
import java.io.StringWriter
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class ImuReportTest {
    @TempDir
    lateinit var dir: Path

    private val run = RunId("run-1")

    /** 50 Hz batches of one second starting at [seconds]. */
    private fun second(seconds: Int, sensor: ImuSensor = ImuSensor.ACCELEROMETER) = ImuBatch(
        t0 + seconds.seconds,
        SourceId.WATCH_IMU,
        sensor,
        (0 until 50).map { ImuReading(it * 20_000_000L, 0.5f, -9.75f, it / 4f) }
    )

    private fun write(vararg batches: ImuBatch) {
        ImuStream.create(dir, run, t0).use { writer -> batches.forEach(writer::append) }
    }

    @Test
    fun `the summary gives rate, gaps and the longest interval per sensor`() {
        // Seconds 0-9 except 5: one gap of 1.02 s in the accelerometer.
        write(*((0..9) - 5).map { second(it) }.toTypedArray(), second(0, ImuSensor.GYROSCOPE))
        val summaries = ImuReport.summarize(ImuStream.readRun(dir, run).single())
        val accelerometer = summaries.first { it.sensor == ImuSensor.ACCELEROMETER }
        assertEquals(450, accelerometer.readings)
        assertEquals(1, accelerometer.gaps)
        assertEquals(1020.milliseconds, accelerometer.longestInterval)
        assertEquals(9.98.seconds, accelerometer.span)
        val gyroscope = summaries.first { it.sensor == ImuSensor.GYROSCOPE }
        assertEquals(0, gyroscope.gaps)
        assertEquals(50.0, gyroscope.rateHz, 0.01)
    }

    @Test
    fun `the CSV has one line per reading on both clocks`() {
        write(second(0), second(1))
        val text = StringWriter().also {
            ImuReport.writeCsv(ImuStream.readRun(dir, run).single(), it)
        }.toString()
        val lines = text.lines().filter(String::isNotEmpty)
        assertEquals("sensor,elapsed_nanos,wall_millis,x,y,z", lines[0])
        assertEquals(101, lines.size)
        assertEquals("accelerometer,10020000000,1764064800020,0.5,-9.75,0.25", lines[2])
    }

    @Test
    fun `the command line reports a directory and writes CSV files`() {
        write(second(0))
        val out = dir.resolve("csv")
        ImuReport.runCommandLine(arrayOf(dir.toString(), "--csv", out.toString()))
        val lines = out.resolve("run-1.csv").readLines()
        assertEquals(51, lines.size)
        val report = ImuReport.describe(
            dir.resolve("run-1.imu"),
            ImuStream.read(dir.resolve("run-1.imu"))
        )
        assertTrue("50 readings" in report && "50.0 Hz" in report, report)
    }
}
