package com.runpro.tools.replay

import com.runpro.core.recording.Totals
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A golden file of `testdata/traces/golden/`: the commands a runner gives while [trace] is replayed, and
 * the totals the recording engine must produce, for the run and for every lap, within [tolerance].
 */
@Serializable
internal data class GoldenFile(
    val trace: String,
    val description: String,
    val commands: List<GoldenCommand>,
    val tolerance: GoldenTolerance,
    val run: GoldenTotals,
    val laps: List<GoldenTotals>
) {
    companion object {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        fun read(file: Path): GoldenFile = json.decodeFromString(serializer(), file.readText())
    }
}

/**
 * A runner command at [atSeconds] after the first record of the trace. It applies after every record up
 * to and including that time, so a sample exactly at a lap mark is the last one of the lap before.
 */
@Serializable
internal data class GoldenCommand(@SerialName("at_s") val atSeconds: Double, val input: GoldenInput)

@Serializable
internal enum class GoldenInput {
    @SerialName("lap")
    LAP,

    @SerialName("pause")
    PAUSE,

    @SerialName("resume")
    RESUME,

    @SerialName("stop")
    STOP
}

/** Allowed differences; heart rates are whole bpm, so [heartRateBpm] 0 means an exact match. */
@Serializable
internal data class GoldenTolerance(
    @SerialName("duration_s") val durationSeconds: Double,
    @SerialName("distance_m") val distanceMeters: Double,
    @SerialName("heart_rate_bpm") val heartRateBpm: Int,
    @SerialName("pace_s_per_km") val paceSecondsPerKm: Double,
    @SerialName("cadence_spm") val cadenceSpm: Double,
    @SerialName("elevation_m") val elevationMeters: Double
)

/** One set of [Totals] in golden-file units; null where the engine has no value. */
@Serializable
internal data class GoldenTotals(
    @SerialName("elapsed_s") val elapsedSeconds: Double,
    @SerialName("timer_s") val timerSeconds: Double,
    @SerialName("moving_s") val movingSeconds: Double,
    @SerialName("distance_m") val distanceMeters: Double,
    @SerialName("avg_hr_bpm") val averageHeartRate: Int?,
    @SerialName("max_hr_bpm") val maxHeartRate: Int?,
    @SerialName("avg_pace_s_per_km") val averagePace: Double?,
    @SerialName("avg_cadence_spm") val averageCadence: Double?,
    @SerialName("max_cadence_spm") val maxCadence: Double?,
    @SerialName("ascent_m") val ascentMeters: Double,
    @SerialName("descent_m") val descentMeters: Double
) {
    /** The differences from [actual] beyond [tolerance], one line each; empty when it matches. */
    fun mismatches(actual: GoldenTotals, tolerance: GoldenTolerance): List<String> = buildList {
        fun check(name: String, golden: Double?, value: Double?, within: Double) {
            val matches = if (golden == null || value == null) {
                golden == value
            } else {
                abs(golden - value) <= within
            }
            if (!matches) add("$name: golden $golden, actual $value (tolerance $within)")
        }
        check("elapsed_s", elapsedSeconds, actual.elapsedSeconds, tolerance.durationSeconds)
        check("timer_s", timerSeconds, actual.timerSeconds, tolerance.durationSeconds)
        check("moving_s", movingSeconds, actual.movingSeconds, tolerance.durationSeconds)
        check("distance_m", distanceMeters, actual.distanceMeters, tolerance.distanceMeters)
        val bpm = tolerance.heartRateBpm.toDouble()
        check("avg_hr_bpm", averageHeartRate?.toDouble(), actual.averageHeartRate?.toDouble(), bpm)
        check("max_hr_bpm", maxHeartRate?.toDouble(), actual.maxHeartRate?.toDouble(), bpm)
        check("avg_pace_s_per_km", averagePace, actual.averagePace, tolerance.paceSecondsPerKm)
        check("avg_cadence_spm", averageCadence, actual.averageCadence, tolerance.cadenceSpm)
        check("max_cadence_spm", maxCadence, actual.maxCadence, tolerance.cadenceSpm)
        check("ascent_m", ascentMeters, actual.ascentMeters, tolerance.elevationMeters)
        check("descent_m", descentMeters, actual.descentMeters, tolerance.elevationMeters)
    }

    companion object {
        private const val SECONDS_DECIMALS = 3
        private const val VALUE_DECIMALS = 2

        /** [totals] in golden-file units, rounded so a printed value can go into a golden file. */
        fun of(totals: Totals) = GoldenTotals(
            elapsedSeconds = seconds(totals.elapsed),
            timerSeconds = seconds(totals.timer),
            movingSeconds = seconds(totals.moving),
            distanceMeters = value(totals.distance.meters),
            averageHeartRate = totals.averageHeartRate?.bpm,
            maxHeartRate = totals.maxHeartRate?.bpm,
            averagePace = totals.averagePace?.secondsPerKilometer?.let(::value),
            averageCadence = totals.averageCadence?.stepsPerMinute?.let(::value),
            maxCadence = totals.maxCadence?.stepsPerMinute?.let(::value),
            ascentMeters = value(totals.ascent.meters),
            descentMeters = value(totals.descent.meters)
        )

        private fun seconds(duration: Duration): Double =
            round(duration.toDouble(DurationUnit.SECONDS), SECONDS_DECIMALS)

        private fun value(value: Double): Double = round(value, VALUE_DECIMALS)

        private fun round(value: Double, decimals: Int): Double =
            BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()
    }
}
