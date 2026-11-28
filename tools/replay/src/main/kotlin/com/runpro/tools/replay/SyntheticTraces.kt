package com.runpro.tools.replay

import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Elevation
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LocationSample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The synthetic traces committed to `testdata/traces/`, generated deterministically (StrictMath, no
 * randomness) so a test can check the committed files against this code. Regenerate them with
 * `./gradlew :tools:replay:generateSyntheticTraces`.
 *
 * Every trace has, once per second, wrist heart rate, location, distance, speed and cadence from Health
 * Services, and chest strap heart rate 250 ms later (when the strap is connected).
 */
@Suppress("MagicNumber") // The numbers are the scenarios.
object SyntheticTraces {
    const val EASY_RUN = "easy-run-30min.jsonl.gz"
    const val INTERVALS = "intervals-6x3min.jsonl.gz"
    const val STRAP_DROPOUT = "strap-dropout.jsonl.gz"
    const val SCREEN_OFF = "screen-off-batching.jsonl.gz"

    /**
     * Seconds from the start of [SCREEN_OFF] during which the screen is off, as on the watch in #108
     * (`PowerManagerService` timeline of run `946d6346`, rounded to whole seconds).
     */
    val SCREEN_OFF_WINDOWS: List<IntRange> = listOf(
        3 until 20,
        23 until 38,
        71 until 100,
        124 until 296
    )

    /** The simulated chest strap. */
    val STRAP: SourceId = SourceId.ble("C0:FF:EE:00:00:01")
    private val WATCH = SourceId.HEALTH_SERVICES

    /** 2025-11-25 10:00 UTC, one hour after the watch booted. */
    private val START =
        Timestamp(elapsedRealtimeNanos = 3_600_000_000_000, wallClockMillis = 1_764_064_800_000)

    /** File name to generator, in a stable order. */
    val all: Map<String, () -> Trace> = linkedMapOf(
        EASY_RUN to ::easyRun,
        INTERVALS to ::intervals,
        STRAP_DROPOUT to ::strapDropout,
        SCREEN_OFF to ::screenOff
    )

    /**
     * When the platform delivered a [SCREEN_OFF] record measured at [time]: Health Services holds its
     * updates back while the screen is off and delivers them as one batch when it turns on again.
     */
    fun screenOffDelivery(time: Timestamp): Timestamp {
        val second = (time.elapsedSince(START).inWholeSeconds).toInt()
        val window = SCREEN_OFF_WINDOWS.firstOrNull { second in it } ?: return time
        return START + (window.last + 1).seconds
    }

    private const val EASY = 1000.0 / 360 // 6:00/km
    private const val FAST = 1000.0 / 240 // 4:00/km
    private const val JOG = 1000.0 / 390 // 6:30/km

    /** 30 min at an easy 6:00/km with a gentle wobble; no dropouts. */
    fun easyRun(): Trace = simulate(
        TraceHeader(
            name = "easy-run-30min",
            description = "30 min easy run at about 6:00/km, strap and wrist HR, no dropouts",
            device = "synthetic"
        ),
        seconds = 30 * 60,
        targetSpeed = { EASY }
    )

    /** 10 min warm-up, 6 × (3 min at 4:00/km + 2 min jog at 6:30/km), 5 min cool-down: 45 min. */
    fun intervals(): Trace = simulate(
        TraceHeader(
            name = "intervals-6x3min",
            description = "10 min warm-up, 6 x (3 min at 4:00/km, 2 min jog at 6:30/km), " +
                "5 min cool-down",
            device = "synthetic"
        ),
        seconds = 45 * 60,
        targetSpeed = { second ->
            val inReps = second - 10 * 60
            when {
                inReps < 0 -> EASY
                inReps >= 6 * 5 * 60 -> EASY
                inReps % (5 * 60) < 3 * 60 -> FAST
                else -> JOG
            }
        }
    )

    /**
     * 20 min easy run where the strap goes silent for 30 s at 8:00 (connected, no notifications), disconnects
     * at 12:00 for 45 s (GATT status 8, connection timeout) and loses skin contact (0 bpm) for 5 s at 16:40.
     * Wrist HR continues throughout.
     */
    fun strapDropout(): Trace = simulate(
        TraceHeader(
            name = "strap-dropout",
            description = "20 min easy run; strap silent 8:00-8:30, disconnected 12:00-12:45, " +
                "0 bpm 16:40-16:45",
            device = "synthetic"
        ),
        seconds = 20 * 60,
        targetSpeed = { EASY },
        strap = { second ->
            when (second) {
                in 480 until 510 -> StrapState.SILENT
                in 720 until 765 -> StrapState.DISCONNECTED
                in 1000 until 1005 -> StrapState.NO_CONTACT
                else -> StrapState.STREAMING
            }
        }
    )

    /**
     * A 5 min walk at about 12:00/km with watch GPS and wrist HR only (no strap), as in #108. While the
     * screen is off ([SCREEN_OFF_WINDOWS]) the watch samples are held back and delivered in one batch when
     * the screen turns on, with their measurement times, grouped by data type like an `ExerciseUpdate`.
     */
    fun screenOff(): Trace {
        val runner = Runner(WALK)
        val records = mutableListOf<TraceRecord>(
            TraceRecord.EventRecord(SensorEvent.GpsAcquired(START, WATCH))
        )
        val batch = mutableListOf<List<TraceRecord>>()
        for (second in 0 until 302) {
            val time = START + second.seconds
            runner.step(second, WALK)
            val samples = runner.watchSamples(second, time).map(TraceRecord::SampleRecord)
            if (SCREEN_OFF_WINDOWS.any { second in it }) {
                batch += samples
                continue
            }
            // One batched update: every heart rate point, then every location point, and so on.
            records += batch.flatMap { it.withIndex() }.sortedBy { it.index }.map { it.value }
            batch.clear()
            records += samples
        }
        return Trace(
            TraceHeader(
                name = "screen-off-batching",
                description = "5 min walk, watch GPS and wrist HR; screen off 0:03-0:20, " +
                    "0:23-0:38, 1:11-1:40, 2:04-4:56 with Health Services batching",
                device = "synthetic"
            ),
            records
        )
    }

    private const val WALK = 1.4

    private enum class StrapState { STREAMING, SILENT, NO_CONTACT, DISCONNECTED }

    private fun simulate(
        header: TraceHeader,
        seconds: Int,
        targetSpeed: (Int) -> Double,
        strap: (Int) -> StrapState = { StrapState.STREAMING }
    ): Trace {
        val runner = Runner(targetSpeed(0))
        val records = mutableListOf<TraceRecord>(
            TraceRecord.EventRecord(SensorEvent.GpsAcquired(START, WATCH))
        )
        var previous = StrapState.DISCONNECTED
        for (second in 0 until seconds) {
            val time = START + second.seconds
            val state = strap(second)
            val event = strapEvent(time, previous, state)
            if (event != null) records += TraceRecord.EventRecord(event)
            previous = state

            runner.step(second, targetSpeed(second))
            records += runner.watchSamples(second, time).map(TraceRecord::SampleRecord)
            val strapSample = runner.strapSample(second, state, time + STRAP_DELAY)
            if (strapSample != null) records += TraceRecord.SampleRecord(strapSample)
        }
        return Trace(header, records)
    }

    private val STRAP_DELAY = 250.milliseconds

    private fun strapEvent(time: Timestamp, previous: StrapState, now: StrapState): SensorEvent? =
        when {
            previous == now -> null
            now == StrapState.DISCONNECTED -> SensorEvent.Disconnected(time, STRAP, gattStatus = 8)
            previous == StrapState.DISCONNECTED -> SensorEvent.Connected(time, STRAP)
            else -> null
        }

    /** The simulated body and watch: speed follows the target, heart rate follows the effort with a lag. */
    private class Runner(private var speed: Double) {
        private var heartRate = 95.0
        private var wristHeartRate = 90.0
        private var distance = 0.0

        fun step(second: Int, target: Double) {
            speed += (target - speed) * 0.3
            val effort = 70.0 + 24.0 * speed + 0.004 * second // cardiac drift: +7 bpm per 30 min
            heartRate += (effort - heartRate) / 25.0
            // The optical sensor lags the strap.
            wristHeartRate += (heartRate - wristHeartRate) / 6.0
            distance += speed
        }

        fun watchSamples(second: Int, time: Timestamp) = listOf(
            HeartRateSample(
                time,
                WATCH,
                HeartRate(round(wristHeartRate + 3.0 * wave(second, 23.0)))
            ),
            location(second, time),
            DistanceSample(time, WATCH, Distance(round(distance, 2))),
            SpeedSample(time, WATCH, Speed(round(speed + 0.05 * wave(second, 11.0), 3))),
            CadenceSample(
                time,
                WATCH,
                Cadence(round(150.0 + 8.0 * speed + 1.5 * wave(second, 5.0), 1))
            )
        )

        fun strapSample(second: Int, state: StrapState, time: Timestamp): HeartRateSample? =
            when (state) {
                StrapState.STREAMING -> HeartRateSample(
                    time,
                    STRAP,
                    HeartRate(round(heartRate + 1.5 * wave(second, 9.0)))
                )

                StrapState.NO_CONTACT -> HeartRateSample(time, STRAP, HeartRate(0))

                StrapState.SILENT, StrapState.DISCONNECTED -> null
            }

        /** Laps of a 2 km circular loop. */
        private fun location(second: Int, time: Timestamp): LocationSample {
            val angle = distance / LOOP_RADIUS
            val north = LOOP_RADIUS * StrictMath.cos(angle)
            val east = LOOP_RADIUS * StrictMath.sin(angle)
            return LocationSample(
                time,
                WATCH,
                latitude = round(CENTER_LAT + north / METERS_PER_DEGREE, 7),
                longitude = round(
                    CENTER_LON +
                        east / (METERS_PER_DEGREE * StrictMath.cos(Math.toRadians(CENTER_LAT))),
                    7
                ),
                horizontalAccuracy = Distance(round(4.0 + 1.5 * wave(second, 37.0), 1)),
                altitude = Elevation(round(120.0 + 6.0 * StrictMath.sin(distance / 350.0), 1))
            )
        }
    }

    private const val LOOP_RADIUS = 2000.0 / (2 * Math.PI)
    private const val CENTER_LAT = 45.47
    private const val CENTER_LON = 9.18
    private const val METERS_PER_DEGREE = 111_320.0

    private fun wave(second: Int, period: Double): Double = StrictMath.sin(second / period)

    private fun round(value: Double): Int = Math.round(value).toInt()

    private fun round(value: Double, decimals: Int): Double {
        val scale = StrictMath.pow(10.0, decimals.toDouble())
        return Math.round(value * scale) / scale
    }
}
