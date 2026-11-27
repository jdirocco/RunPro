package com.runpro.tools.replay

import com.runpro.core.journal.ImuStream
import com.runpro.core.journal.ImuStreamContents
import com.runpro.core.model.ImuSensor
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.Locale
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit

/**
 * What one sensor of an IMU stream segment holds: enough to tell whether a field run recorded its IMU
 * completely (rate close to 50 Hz, no gaps) before it is used as a reference run (ADR 0012).
 *
 * @property gaps intervals between consecutive readings longer than [ImuReport.GAP].
 */
data class ImuSensorSummary(
    val sensor: ImuSensor,
    val readings: Int,
    val span: Duration,
    val gaps: Int,
    val longestInterval: Duration
) {
    /** Mean readings per second over [span]. */
    val rateHz: Double
        get() = if (span.isPositive()) (readings - 1) / span.toDouble(DurationUnit.SECONDS) else 0.0
}

/** Summaries and CSV export of IMU streams copied from the watch (`files/imu`). */
object ImuReport {
    /** A reading interval above five 50 Hz periods counts as a gap. */
    val GAP = 100.milliseconds

    fun summarize(contents: ImuStreamContents): List<ImuSensorSummary> =
        contents.batches.groupBy { it.sensor }.toSortedMap().map { (sensor, batches) ->
            val times = batches.flatMap { batch ->
                batch.readings.map { batch.time.elapsedRealtimeNanos + it.offsetNanos }
            }
            val intervals = times.zipWithNext { a, b -> (b - a).nanoseconds }
            ImuSensorSummary(
                sensor,
                times.size,
                (times.last() - times.first()).nanoseconds,
                intervals.count { it > GAP },
                intervals.maxOrNull() ?: Duration.ZERO
            )
        }

    /** One line per reading: `sensor,elapsed_nanos,wall_millis,x,y,z`. */
    fun writeCsv(contents: ImuStreamContents, out: Writer, header: Boolean = true) {
        if (header) out.write("sensor,elapsed_nanos,wall_millis,x,y,z\n")
        for (batch in contents.batches) {
            val sensor = batch.sensor.name.lowercase()
            batch.readings.forEachIndexed { index, r ->
                val time = batch.timeOf(index)
                out.write(
                    "$sensor,${time.elapsedRealtimeNanos},${time.wallClockMillis},${r.x},${r.y},${r.z}\n"
                )
            }
        }
    }

    fun describe(file: Path, contents: ImuStreamContents): String = buildString {
        val bytes = file.fileSize()
        val perReading = if (contents.readingCount >
            0
        ) {
            bytes.toDouble() / contents.readingCount
        } else {
            0.0
        }
        appendLine(
            "${file.name}: run ${contents.runId}, started " +
                "${Instant.ofEpochMilli(
                    contents.startedAt.wallClockMillis
                )}, end ${contents.end}, " +
                "$bytes bytes (%.1f bytes/reading)".format(Locale.ROOT, perReading)
        )
        for (s in summarize(contents)) {
            appendLine(
                "  %-13s %8d readings  %8.1f s  %5.1f Hz  %d gaps > %s, longest interval %s".format(
                    Locale.ROOT,
                    s.sensor.name.lowercase(),
                    s.readings,
                    s.span.toDouble(DurationUnit.SECONDS),
                    s.rateHz,
                    s.gaps,
                    GAP,
                    s.longestInterval
                )
            )
        }
    }

    /**
     * `./gradlew :tools:replay:imuReport --args="<file or directory>... [--csv <dir>]"`: prints a summary
     * of every `.imu` segment and, with `--csv`, writes one CSV per segment into the directory.
     */
    fun runCommandLine(args: Array<String>) {
        val csvIndex = args.indexOf("--csv")
        val csvDir = if (csvIndex >= 0) Paths.get(args.getOrElse(csvIndex + 1) { "" }) else null
        val inputs = args.filterIndexed { i, _ ->
            csvIndex < 0 ||
                (i != csvIndex && i != csvIndex + 1)
        }
        require(inputs.isNotEmpty() && csvDir?.toString() != "") {
            "Usage: imuReport <file or directory>... [--csv <output directory>]"
        }
        val files = inputs.map(Paths::get).flatMap { path ->
            if (path.isDirectory()) {
                path.listDirectoryEntries().filter { it.name.endsWith(ImuStream.SUFFIX) }.sorted()
            } else {
                listOf(path)
            }
        }
        csvDir?.let(Files::createDirectories)
        for (file in files) {
            val contents = ImuStream.read(file)
            print(describe(file, contents))
            csvDir?.let { dir ->
                val csv = dir.resolve(file.name.removeSuffix(ImuStream.SUFFIX) + ".csv")
                Files.newBufferedWriter(csv).use { writeCsv(contents, it) }
                println("  -> $csv")
            }
        }
    }
}

fun main(args: Array<String>) = ImuReport.runCommandLine(args)
