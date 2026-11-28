package com.runpro.core.sync

import com.runpro.core.fit.FitTotals
import com.runpro.core.model.Cadence
import com.runpro.core.model.Distance
import com.runpro.core.model.HeartRate
import com.runpro.core.model.Pace
import com.runpro.core.model.Speed
import com.runpro.core.recording.LapTotals
import com.runpro.core.recording.Totals
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

private const val MILLIS_PER_SECOND = 1000.0

internal fun summaryFromTotals(totals: Totals) = RunSummary(
    timer = totals.timer,
    elapsed = totals.elapsed,
    distance = totals.distance,
    averagePace = totals.averagePace,
    averageHeartRate = totals.averageHeartRate,
    maxHeartRate = totals.maxHeartRate,
    averageCadence = totals.averageCadence,
    ascent = totals.ascent,
    descent = totals.descent
)

internal fun lapFromTotals(lap: LapTotals) = RunLapSummary(
    index = lap.lap.index,
    timer = lap.totals.timer,
    distance = lap.totals.distance,
    averagePace = lap.totals.averagePace,
    averageHeartRate = lap.totals.averageHeartRate,
    trigger = lap.lap.trigger
)

internal fun summaryFromFit(fit: FitTotals): RunSummary {
    val timer = durationOf(fit.timerSeconds)
    val distance = distanceOf(fit.distanceMeters)
    return RunSummary(
        timer = timer,
        elapsed = durationOf(fit.elapsedSeconds),
        distance = distance,
        averagePace = paceOf(timer, distance, fit.averageSpeedMetersPerSecond),
        averageHeartRate = heartRateOf(fit.averageHeartRateBpm),
        maxHeartRate = heartRateOf(fit.maxHeartRateBpm),
        averageCadence = cadenceOf(fit.averageCadenceStepsPerMinute),
        ascent = elevationDistanceOf(fit.ascentMeters),
        descent = elevationDistanceOf(fit.descentMeters)
    )
}

internal fun lapFromFit(index: Int, fit: FitTotals): RunLapSummary {
    val timer = durationOf(fit.timerSeconds)
    val distance = distanceOf(fit.distanceMeters)
    return RunLapSummary(
        index = index,
        timer = timer,
        distance = distance,
        averagePace = paceOf(timer, distance, fit.averageSpeedMetersPerSecond),
        averageHeartRate = heartRateOf(fit.averageHeartRateBpm),
        trigger = fit.lapTrigger
    )
}

private fun durationOf(seconds: Double?): Duration = seconds
    ?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let { (it * MILLIS_PER_SECOND).roundToLong().milliseconds }
    ?: Duration.ZERO

private fun distanceOf(meters: Double?): Distance = meters
    ?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let(::Distance)
    ?: Distance.ZERO

private fun elevationDistanceOf(meters: Int?): Distance = meters
    ?.takeIf { it >= 0 }
    ?.toDouble()
    ?.let(::Distance)
    ?: Distance.ZERO

private fun heartRateOf(bpm: Int?): HeartRate? = bpm
    ?.takeIf { it in 1..HeartRate.MAX_BPM }
    ?.let(::HeartRate)

private fun cadenceOf(spm: Double?): Cadence? = spm
    ?.takeIf { it.isFinite() && it > 0.0 }
    ?.let(::Cadence)

private fun paceOf(timer: Duration, distance: Distance, fallbackSpeed: Double?): Pace? =
    if (timer.isPositive() && distance.meters > 0.0) {
        Speed(distance.meters / timer.toDouble(DurationUnit.SECONDS)).pace()
    } else {
        fallbackSpeed?.takeIf { it.isFinite() && it > 0.0 }?.let(::Speed)?.pace()
    }
