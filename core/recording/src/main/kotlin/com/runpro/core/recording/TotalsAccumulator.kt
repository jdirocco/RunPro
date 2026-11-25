package com.runpro.core.recording

import com.runpro.core.model.Cadence
import com.runpro.core.model.Distance
import com.runpro.core.model.HeartRate
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Time-weighted mean and maximum of one metric. */
internal class WeightedStats {
    private var weightedSum = 0.0
    private var weight = 0L

    var max: Double? = null
        private set

    val mean: Double? get() = if (weight > 0) weightedSum / weight else null

    fun add(value: Double, nanos: Long) {
        weightedSum += value * nanos
        weight += nanos
    }

    fun observe(value: Double) {
        max = maxOf(max ?: value, value)
    }
}

/** The running sums behind one [Totals] (the run or one lap). Time spans come from the [RunTimeline]. */
internal class TotalsAccumulator {
    var meters = 0.0
    var movingNanos = 0L
    var ascent = 0.0
    var descent = 0.0
    val heartRate = WeightedStats()
    val cadence = WeightedStats()

    fun totals(elapsed: Duration, timer: Duration): Totals = Totals(
        elapsed = elapsed,
        timer = timer,
        moving = movingNanos.nanoseconds,
        distance = Distance(meters),
        averageHeartRate = heartRate.mean?.let { HeartRate(it.roundToInt()) },
        maxHeartRate = heartRate.max?.let { HeartRate(it.roundToInt()) },
        averageCadence = cadence.mean?.let(::Cadence),
        maxCadence = cadence.max?.let(::Cadence),
        ascent = Distance(ascent),
        descent = Distance(descent)
    )
}
