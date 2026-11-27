package com.runpro.core.recording

import com.runpro.core.model.CadenceSample
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Metric
import com.runpro.core.model.Sample
import com.runpro.core.model.Timestamp
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Totals of one run and of its laps, fed with the samples of the active sources.
 *
 * Every contribution goes to the run and to the current lap. Distance, moving time and ascent between two
 * samples go to the lap in which the second sample arrives; held values (heart rate, cadence) are split
 * exactly at the lap mark.
 */
internal class RunMetrics(config: EngineConfig, private val startedAt: Timestamp) {
    private val timeline = RunTimeline(startedAt.elapsedRealtimeNanos)
    private val run = TotalsAccumulator()
    private var lap = TotalsAccumulator()
    private var lapStart = startedAt
    private val closedLaps = mutableListOf<Totals>()
    private var stopped = false

    private val heartRateHold = HeldValue(config.maxSampleHold.inWholeNanoseconds)
    private val cadenceHold = HeldValue(config.maxSampleHold.inWholeNanoseconds)
    private val distance = DistanceTracker(config.minMovingSpeed.metersPerSecond)
    private val elevation = ElevationTracker(config.elevationThreshold.meters)

    fun onSample(sample: Sample) {
        val time = sample.time.elapsedRealtimeNanos
        when (sample) {
            is HeartRateSample ->
                onHeldSample(heartRateHold, time, sample.heartRate.bpm.toDouble()) { it.heartRate }

            is CadenceSample ->
                onHeldSample(cadenceHold, time, sample.cadence.stepsPerMinute) { it.cadence }

            is DistanceSample -> distance.update(time, sample.total.meters, timeline)?.let { step ->
                both {
                    meters += step.meters
                    movingNanos += step.movingNanos
                }
            }

            is ElevationSample -> {
                val change = elevation.update(time, sample.elevation.meters, timeline)
                if (change > 0.0) both { ascent += change }
                if (change < 0.0) both { descent -= change }
            }

            else -> Unit
        }
    }

    fun sourceSwitched(metric: Metric, time: Timestamp) {
        val timer = timeline.timerAt(time.elapsedRealtimeNanos)
        when (metric) {
            Metric.DISTANCE -> distance.reset()

            Metric.ELEVATION -> elevation.reset()

            Metric.HEART_RATE -> {
                heartRateHold.advanceTo(timer) { value, nanos ->
                    both { heartRate.add(value, nanos) }
                }
                heartRateHold.clear()
            }

            Metric.CADENCE -> {
                cadenceHold.advanceTo(timer) { value, nanos -> both { cadence.add(value, nanos) } }
                cadenceHold.clear()
            }

            else -> Unit
        }
    }

    fun pause(time: Timestamp) = timeline.pause(time.elapsedRealtimeNanos)

    fun resume(time: Timestamp) = timeline.resume(time.elapsedRealtimeNanos)

    /** Closes the current lap at [time] and opens the next one. */
    fun markLap(time: Timestamp) {
        val timer = timeline.timerAt(time.elapsedRealtimeNanos)
        heartRateHold.advanceTo(timer) { value, nanos -> both { heartRate.add(value, nanos) } }
        cadenceHold.advanceTo(timer) { value, nanos -> both { cadence.add(value, nanos) } }
        closedLaps += lapTotals(time)
        lap = TotalsAccumulator()
        lapStart = time
    }

    /** Ends the timeline at [time] and closes the last lap. */
    fun stop(time: Timestamp) {
        timeline.stop(time.elapsedRealtimeNanos)
        markLap(time)
        stopped = true
    }

    fun totals(now: Timestamp): Totals = run.totals(
        elapsed = now.elapsedSince(startedAt),
        timer = timeline.timerAt(now.elapsedRealtimeNanos).nanoseconds
    )

    /** The totals of every lap; the last one is the open lap, up to [now], unless the run is stopped. */
    fun laps(now: Timestamp): List<Totals> = if (stopped) {
        closedLaps.toList()
    } else {
        closedLaps +
            lapTotals(now)
    }

    private fun lapTotals(now: Timestamp): Totals = lap.totals(
        elapsed = now.elapsedSince(lapStart),
        timer = (
            timeline.timerAt(now.elapsedRealtimeNanos) -
                timeline.timerAt(lapStart.elapsedRealtimeNanos)
            ).nanoseconds
    )

    private fun onHeldSample(
        held: HeldValue,
        time: Long,
        value: Double,
        stats: (TotalsAccumulator) -> WeightedStats
    ) {
        held.advanceTo(timeline.timerAt(time)) { v, nanos -> both { stats(this).add(v, nanos) } }
        // A sample during a pause, or a zero (no skin contact, standing still), ends the hold.
        if (timeline.isActiveAt(time) && value > 0.0) {
            held.hold(value, timeline.timerAt(time))
            both { stats(this).observe(value) }
        } else {
            held.clear()
        }
    }

    private inline fun both(block: TotalsAccumulator.() -> Unit) {
        run.block()
        lap.block()
    }
}
