package com.runpro.core.recording

import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.Elevation
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Lap
import com.runpro.core.model.Pace
import com.runpro.core.model.Run
import com.runpro.core.model.Sample
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** Idle → Preparing → Running ⇄ Paused → Stopped (Reset goes back to Idle). */
enum class RunPhase {
    IDLE,
    PREPARING,
    RUNNING,
    PAUSED,
    STOPPED
}

/**
 * What the engine publishes after each batch of inputs. Immutable; the UI only reads it.
 *
 * @property run the run as recorded so far; null while idle or preparing.
 * @property laps one entry per lap of [run], in order; the last one is open until the run stops.
 * @property current the latest readings, also while preparing (e.g. heart rate before the start).
 */
data class RunState(
    val phase: RunPhase = RunPhase.IDLE,
    val run: Run? = null,
    val totals: Totals = Totals(),
    val laps: List<LapTotals> = emptyList(),
    val current: CurrentValues = CurrentValues(),
    val resources: ResourceState = ResourceState()
)

data class LapTotals(val lap: Lap, val totals: Totals)

/**
 * Totals of a run or a lap.
 *
 * @property elapsed monotonic time from the start, pauses included.
 * @property timer [elapsed] without pauses (FIT `total_timer_time`).
 * @property moving the part of [timer] spent moving, see [EngineConfig.minMovingSpeed].
 * @property averageHeartRate time-weighted over the timer time with a known heart rate; 0 bpm (no skin
 *   contact) is ignored.
 * @property averageCadence time-weighted over the timer time with a non-zero cadence.
 * @property ascent total climb, with the [EngineConfig.elevationThreshold] hysteresis.
 * @property descent total descent (positive), same hysteresis.
 */
data class Totals(
    val elapsed: Duration = Duration.ZERO,
    val timer: Duration = Duration.ZERO,
    val moving: Duration = Duration.ZERO,
    val distance: Distance = Distance.ZERO,
    val averageHeartRate: HeartRate? = null,
    val maxHeartRate: HeartRate? = null,
    val averageCadence: Cadence? = null,
    val maxCadence: Cadence? = null,
    val ascent: Distance = Distance.ZERO,
    val descent: Distance = Distance.ZERO
) {
    /** Distance over timer time; null before any timer time. */
    val averageSpeed: Speed?
        get() = if (timer.isPositive()) {
            Speed(distance.meters / timer.toDouble(DurationUnit.SECONDS))
        } else {
            null
        }

    /** Pace of [averageSpeed]; null without distance. */
    val averagePace: Pace? get() = averageSpeed?.pace()
}

/** The latest reading of each metric from its active source. */
data class CurrentValues(
    val heartRate: HeartRate? = null,
    val cadence: Cadence? = null,
    val speed: Speed? = null,
    val elevation: Elevation? = null
) {
    val pace: Pace? get() = speed?.pace()

    internal fun updatedWith(sample: Sample): CurrentValues = when (sample) {
        // 0 bpm means the sensor lost skin contact: no current heart rate.
        is HeartRateSample -> copy(heartRate = sample.heartRate.takeIf { it.bpm > 0 })

        is CadenceSample -> copy(cadence = sample.cadence)

        is SpeedSample -> copy(speed = sample.speed)

        is ElevationSample -> copy(elevation = sample.elevation)

        else -> this
    }
}

/** Latest device readings. Warnings remain observable even if the UI misses intermediate states. */
data class ResourceState(val batteryPercent: Int? = null, val availableBytes: Long? = null) {
    val lowBattery: Boolean get() = batteryPercent?.let { it <= BATTERY_WARNING_PERCENT } == true
    val lowStorage: Boolean get() = availableBytes?.let { it < STORAGE_WARNING_BYTES } == true
    val canStart: Boolean get() =
        batteryPercent?.let { it > BATTERY_STOP_PERCENT } != false && availableBytes != 0L

    companion object {
        const val BATTERY_WARNING_PERCENT = 15
        const val BATTERY_STOP_PERCENT = 3
        const val STORAGE_WARNING_BYTES = 50_000_000L
    }
}
