package com.runpro.tools.replay

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Metric
import com.runpro.core.model.RunEvent
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.SwitchReason
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.location.FusedLocationController
import com.runpro.core.recording.location.LocationFix
import com.runpro.core.recording.location.LocationFixGate
import com.runpro.core.recording.location.LocationPort
import com.runpro.core.recording.location.LocationReceiver
import java.nio.file.Path
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.io.TempDir

/**
 * Issue #96 (ADR 0019): the field timeline near buildings. Health Services is still acquiring when the run
 * starts while the fused provider has a phone-assisted fix, mixed with coarse Wi-Fi/cell fixes 80-150 m
 * away. The runner waits 2 min, runs 400 s at 3 m/s (Health Services gets its own fix at 200 s and loses
 * it at 360 s) and waits 200 s again. Through the real gate, engine and journal: the waits add no distance
 * and no lap, every hand-over is a recorded switch (R5), the run never pauses (R4), and recovery rebuilds
 * the same run (R2/R3).
 */
class FusedLocationReplayTest {
    private val origin = Timestamp(100_000_000_000L, 1_764_064_800_000L)

    private var clock = origin

    private fun at(seconds: Double) = origin + Math.round(seconds * 1000).milliseconds

    @Test
    fun `coarse fixes add no distance or lap and Health Services hand-overs are recorded`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("fused.journal")
        val session = RecordingSession(journal = JournalWriter.create(file))
        val port = CapturingPort()
        val fused = FusedLocationController(port, { true }, { clock })
        fused.start(object : SensorListener {
            override fun onSample(sample: Sample) {
                session.handle(EngineInput.SampleReceived(sample))
            }

            override fun onEvent(event: SensorEvent) {
                session.handle(EngineInput.SensorReceived(event))
            }
        })
        session.handle(EngineInput.Prepare(at(-1.0)))
        fused.follow(RunPhase.PREPARING)
        session.handle(EngineInput.Start(at(0.0)))
        fused.follow(RunPhase.RUNNING)
        val receiver = checkNotNull(port.receiver)

        var distanceAfterFirstWait = Distance.ZERO
        var distanceAfterRun = Distance.ZERO
        for (s in 0 until END) {
            val north = northAt(s)
            feedHealthServices(session, s, north)
            // The fused provider: a coarse network fix every third second, precise fixes otherwise.
            clock = at(s + 0.3)
            receiver.fix(fusedFix(s, north))
            clock = at(s + 0.5)
            session.handle(EngineInput.Tick(clock))
            assertEquals(RunPhase.RUNNING, session.state.phase)
            if (s == RUN_FROM - 1) distanceAfterFirstWait = session.state.totals.distance
            if (s == RUN_UNTIL) distanceAfterRun = session.state.totals.distance
        }
        val stop = at(END.toDouble())
        session.use { session.handle(EngineInput.Stop(stop)) }
        fused.follow(RunPhase.STOPPED)

        // Standing still with jitter and coarse jumps: nothing counted, before or after the run.
        assertEquals(Distance.ZERO, distanceAfterFirstWait)
        assertEquals(distanceAfterRun, session.state.totals.distance)
        // 1200 m run, less the uncounted seconds at the two switch boundaries.
        val meters = session.state.totals.distance.meters
        assertTrue(meters in 1185.0..1200.0, "distance $meters")
        val events = session.state.run!!.events
        assertEquals(1, events.count { it is RunEvent.LapMarked }, "only the 1 km auto-lap")
        assertTrue(events.none { it is RunEvent.Paused || it is RunEvent.Resumed })

        assertHandOvers(events)

        // The journal holds no coarse fix, and recovery rebuilds the same run (R2/R3).
        assertTrue(session.state.journalSaved)
        val recorded = RecordedRun.from(JournalReader.read(file))
        val locations = recorded.samples.map { it.sample }.filterIsInstance<LocationSample>()
        assertTrue(locations.any { it.source == FUSED })
        assertTrue(locations.all { it.horizontalAccuracy!! <= LocationFixGate.MAX_ACCURACY })
        assertEquals(events, recorded.run.events)
        assertEquals(session.state.totals, recorded.state.totals)
    }

    /** Health Services: its own cumulative counter while it has a fix, then the loss of the fix. */
    private fun feedHealthServices(session: RecordingSession, s: Int, north: Double) {
        if (s in HS_FIX until HS_LOST) {
            clock = at(s + 0.1)
            val time = at(s.toDouble())
            val hsDistance = 3.0 * (s - HS_FIX)
            session.handle(EngineInput.SampleReceived(location(time, WATCH, north, 0.0, 4.0)))
            session.handle(
                EngineInput.SampleReceived(DistanceSample(time, WATCH, Distance(hsDistance)))
            )
            session.handle(EngineInput.SampleReceived(SpeedSample(time, WATCH, Speed(3.0))))
        }
        if (s == HS_LOST) {
            clock = at(s + 0.1)
            session.handle(EngineInput.SensorReceived(SensorEvent.GpsLost(clock, WATCH)))
        }
    }

    /** Fused first, Health Services once it reports, fused again when it loses the fix (R5). */
    private fun assertHandOvers(events: List<RunEvent>) {
        for (metric in listOf(Metric.LOCATION, Metric.DISTANCE, Metric.SPEED)) {
            val switches = events.filterIsInstance<RunEvent.SourceSwitched>()
                .filter { it.metric == metric }
            assertEquals(
                listOf(
                    Triple(null, FUSED, SwitchReason.HIGHER_PRIORITY),
                    Triple(FUSED, WATCH, SwitchReason.HIGHER_PRIORITY),
                    Triple(WATCH, FUSED, SwitchReason.STALE)
                ),
                switches.map { Triple(it.from, it.to, it.reason) },
                "$metric switches"
            )
            assertEquals(at(HS_FIX.toDouble()), switches[1].time)
            assertEquals(at(HS_LOST + 0.1), switches[2].time)
        }
    }

    /** Meters north of the start: still, then 3 m/s, then still. */
    private fun northAt(s: Int): Double = 3.0 * (s.coerceIn(RUN_FROM, RUN_UNTIL) - RUN_FROM)

    private fun fusedFix(s: Int, north: Double): LocationFix {
        val running = s in RUN_FROM + 1..RUN_UNTIL
        return if (s % 3 == 0) {
            fix(s, north + 80.0 + s % 70, -60.0, accuracy = 60.0 + s % 60, speed = Double.NaN)
        } else {
            // Up to 6 m of jitter inside a reported 10 m; Doppler speed only near zero when still.
            val jitterNorth = 6.0 * sin(s / 7.0)
            val jitterEast = 6.0 * cos(s / 3.0) * cos(s / 11.0)
            val speed = if (running) 3.0 else 0.2
            val accuracy = if (running) 8.0 else 10.0
            fix(s, north + if (running) 0.0 else jitterNorth, jitterEast / 3, accuracy, speed)
        }
    }

    private fun fix(s: Int, north: Double, east: Double, accuracy: Double, speed: Double) =
        LocationFix(
            elapsedNanos = at(s.toDouble()).elapsedRealtimeNanos,
            latitude = LAT + north / METERS_PER_DEGREE,
            longitude = LON + east / (METERS_PER_DEGREE * cos(Math.toRadians(LAT))),
            horizontalAccuracyMeters = accuracy,
            speedMetersPerSecond = speed
        )

    private fun location(
        time: Timestamp,
        source: SourceId,
        north: Double,
        east: Double,
        accuracy: Double
    ) = LocationSample(
        time,
        source,
        LAT + north / METERS_PER_DEGREE,
        LON + east / (METERS_PER_DEGREE * cos(Math.toRadians(LAT))),
        Distance(accuracy)
    )

    private class CapturingPort : LocationPort {
        var receiver: LocationReceiver? = null

        override fun request(receiver: LocationReceiver) {
            this.receiver = receiver
        }

        override fun remove() = Unit
    }

    private companion object {
        val WATCH = SourceId.HEALTH_SERVICES
        val FUSED = SourceId.FUSED_LOCATION
        const val RUN_FROM = 120
        const val RUN_UNTIL = 520
        const val HS_FIX = 200
        const val HS_LOST = 360
        const val END = 720
        const val LAT = 45.47
        const val LON = 9.18
        val METERS_PER_DEGREE = LocationFixGate.EARTH_RADIUS_METERS * Math.PI / 180.0
    }
}
