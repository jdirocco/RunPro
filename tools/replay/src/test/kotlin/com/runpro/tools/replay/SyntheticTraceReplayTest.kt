package com.runpro.tools.replay

import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Metric
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SourceId
import com.runpro.core.model.SpeedSample
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Replays the committed traces in `testdata/traces/` into fake consumers. */
class SyntheticTraceReplayTest {
    private val strap = SyntheticTraces.STRAP
    private val watch = SourceId.HEALTH_SERVICES

    private fun committed(name: String): Trace {
        val dir = Paths.get(
            checkNotNull(System.getProperty("runpro.traces")) {
                "runpro.traces not set"
            }
        )
        val file: Path = dir.resolve(name)
        assertTrue(
            file.exists(),
            "$file is missing: run ./gradlew :tools:replay:generateSyntheticTraces"
        )
        return Trace.read(file)
    }

    /** Replays [trace] with one fake consumer per source; returns them by source. */
    private fun replay(trace: Trace): Map<SourceId, CollectingListener> {
        val replayer = Replayer(trace)
        val listeners = replayer.sources.associate { it.id to CollectingListener() }
        replayer.sources.forEach { it.start(listeners.getValue(it.id)) }
        val result = replayer.run()
        assertEquals(trace.records.size, result.delivered)
        assertEquals(0, result.dropped)
        return listeners
    }

    private fun CollectingListener.count(metric: Metric) = samples.count { it.metric == metric }

    @Test
    fun `committed traces are exactly what the generator produces`() {
        SyntheticTraces.all.forEach { (file, generate) ->
            val trace = committed(file)
            assertFalse(trace.truncated, file)
            assertEquals(
                generate(),
                trace,
                "$file is stale: run ./gradlew :tools:replay:generateSyntheticTraces"
            )
        }
    }

    @Test
    fun `easy run replays 30 minutes of samples from strap and watch`() {
        val trace = committed(SyntheticTraces.EASY_RUN)
        // One sample per second per stream; the strap reports 250 ms after the watch.
        assertEquals(30.minutes - 1.seconds + 250.milliseconds, trace.duration)

        val listeners = replay(trace)

        val strapOut = listeners.getValue(strap)
        assertEquals(1_800, strapOut.count(Metric.HEART_RATE))
        assertEquals(1_800, strapOut.samples.size)
        assertEquals(listOf("sensor_connected"), strapOut.events.map(::typeName))

        val watchOut = listeners.getValue(watch)
        for (metric in listOf(
            Metric.HEART_RATE,
            Metric.LOCATION,
            Metric.DISTANCE,
            Metric.SPEED,
            Metric.CADENCE
        )) {
            assertEquals(1_800, watchOut.count(metric), metric.name)
        }
        assertEquals(listOf("gps_acquired"), watchOut.events.map(::typeName))
        assertEquals(10_800, listeners.values.sumOf { it.samples.size })
    }

    @Test
    fun `intervals replay 45 minutes with six fast reps`() {
        val trace = committed(SyntheticTraces.INTERVALS)
        val listeners = replay(trace)

        val watchOut = listeners.getValue(watch)
        assertEquals(2_700, watchOut.count(Metric.SPEED))
        assertEquals(2_700, listeners.getValue(strap).count(Metric.HEART_RATE))
        assertEquals(16_200, listeners.values.sumOf { it.samples.size })

        // Six blocks of fast running (faster than 4:15/km), one per rep.
        val fast = watchOut.samples.filterIsInstance<SpeedSample>()
            .map { it.speed.metersPerSecond > FAST_REP_SPEED }
        val reps = fast.zipWithNext().count { (before, now) -> !before && now }
        assertEquals(6, reps)
    }

    @Test
    fun `strap dropout replays a stall, a disconnect and contact loss`() {
        val trace = committed(SyntheticTraces.STRAP_DROPOUT)
        val listeners = replay(trace)

        val strapOut = listeners.getValue(strap)
        // 20 min at 1 Hz, minus a 30 s silent stall and a 45 s disconnection.
        assertEquals(1_200 - 30 - 45, strapOut.count(Metric.HEART_RATE))
        assertEquals(
            listOf("sensor_connected", "sensor_disconnected", "sensor_connected"),
            strapOut.events.map(::typeName)
        )
        val zeros = strapOut.samples.filterIsInstance<HeartRateSample>().count {
            it.heartRate.bpm ==
                0
        }
        assertEquals(5, zeros, "strap lost skin contact for 5 s")

        val gaps = strapOut.samples.zipWithNext { a, b -> b.time.elapsedSince(a.time) }
            .filter { it > 1.seconds }
        assertEquals(listOf(31.seconds, 46.seconds), gaps)

        // The wrist keeps reporting throughout: the fallback has data (R4).
        assertEquals(1_200, listeners.getValue(watch).count(Metric.HEART_RATE))
    }

    private fun typeName(event: SensorEvent): String = when (event) {
        is SensorEvent.Connected -> "sensor_connected"
        is SensorEvent.Disconnected -> "sensor_disconnected"
        is SensorEvent.GpsLost -> "gps_lost"
        is SensorEvent.GpsAcquired -> "gps_acquired"
    }

    private companion object {
        const val FAST_REP_SPEED = 1000.0 / 255.0
    }
}
