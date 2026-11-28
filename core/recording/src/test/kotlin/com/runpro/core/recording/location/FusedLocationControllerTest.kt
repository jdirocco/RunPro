package com.runpro.core.recording.location

import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.ImuBatch
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.TestTime
import com.runpro.core.recording.TestTime.at
import com.runpro.core.recording.exercise.ExerciseStatus
import com.runpro.core.recording.exercise.Readiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #96 (ADR 0019): the fused location source follows the run and never decides anything for it. */
class FusedLocationControllerTest {
    private val port = FakeLocationPort()
    private val listener = RecordingListener()
    private var now: Timestamp = at(0)
    private var allowed = true

    private val controller = FusedLocationController(port, { allowed }, { now }).also {
        it.start(listener)
    }

    @Test
    fun `updates run from the warm-up to the stop, once`() {
        controller.follow(RunPhase.PREPARING)
        assertEquals(Readiness.WAITING, controller.status.value.readiness)
        controller.follow(RunPhase.RUNNING)
        controller.follow(RunPhase.PAUSED)
        controller.follow(RunPhase.RUNNING)
        assertEquals(1, port.requests.size)
        assertEquals(0, port.removals)
        controller.follow(RunPhase.STOPPED)
        controller.follow(RunPhase.IDLE)
        assertEquals(1, port.removals)
        assertEquals(LocationStatus(), controller.status.value)
    }

    @Test
    fun `without the location permission nothing is requested and the run is unaffected`() {
        allowed = false
        controller.follow(RunPhase.PREPARING)
        controller.follow(RunPhase.RUNNING)
        assertTrue(port.requests.isEmpty())
        assertEquals(Readiness.NOT_REQUESTED, controller.status.value.readiness)
        assertTrue(listener.samples.isEmpty())
    }

    @Test
    fun `admitted fixes reach the engine, coarse ones never do`() {
        controller.follow(RunPhase.PREPARING)
        val receiver = port.requests.single()
        receiver.deliver(fix(0.0, accuracy = 90.0), at(0.2))
        assertTrue(listener.samples.isEmpty())
        assertEquals(Readiness.WAITING, controller.status.value.readiness)
        assertEquals(mapOf(FixRejection.COARSE to 1), controller.status.value.rejected)

        receiver.deliver(fix(1.0, accuracy = 14.0), at(1.2))
        assertEquals(
            listOf(LocationSample::class, DistanceSample::class),
            listener.samples.map { it::class }
        )
        assertTrue(listener.samples.all { it.source == SourceId.FUSED_LOCATION })
        assertEquals<List<SensorEvent>>(
            listOf(SensorEvent.GpsAcquired(at(1.2), FUSED)),
            listener.events
        )
        assertEquals(Readiness.READY, controller.status.value.readiness)
        assertEquals(Distance(14.0), controller.status.value.accuracy)
    }

    @Test
    fun `ten seconds of rejected fixes lose the fix until a precise one returns`() {
        controller.follow(RunPhase.RUNNING)
        val receiver = port.requests.single()
        receiver.deliver(fix(0.0), at(0))
        (1..9).forEach { s -> receiver.deliver(fix(s.toDouble(), accuracy = 60.0), at(s)) }
        assertEquals(Readiness.READY, controller.status.value.readiness)
        receiver.deliver(fix(10.0, accuracy = 60.0), at(10))
        assertEquals(Readiness.WAITING, controller.status.value.readiness)
        receiver.deliver(fix(11.0), at(11))
        assertEquals(
            listOf(
                SensorEvent.GpsAcquired(at(0), FUSED),
                SensorEvent.GpsLost(at(10), FUSED),
                SensorEvent.GpsAcquired(at(11), FUSED)
            ),
            listener.events
        )
    }

    @Test
    fun `unavailable location and a failed request are reported, never thrown`() {
        controller.follow(RunPhase.RUNNING)
        val receiver = port.requests.single()
        receiver.deliver(fix(0.0), at(0))
        now = at(2)
        receiver.availability(false)
        assertEquals(Readiness.WAITING, controller.status.value.readiness)
        assertEquals(SensorEvent.GpsLost(at(2), FUSED), listener.events.last())
        receiver.failed("Play services missing")
        assertEquals(Readiness.UNAVAILABLE, controller.status.value.readiness)
        assertEquals("Play services missing", controller.status.value.error)
        assertEquals(2, listener.events.size)
    }

    @Test
    fun `callbacks of a removed request are ignored and a new run starts a new track`() {
        controller.follow(RunPhase.RUNNING)
        val first = port.requests.single()
        first.deliver(fix(0.0, north = 0.0, speed = 3.0), at(0))
        first.deliver(fix(1.0, north = 3.0, speed = 3.0), at(1))
        controller.follow(RunPhase.STOPPED)
        assertEquals(SensorEvent.GpsLost(at(1), FUSED), listener.events.last())
        val delivered = listener.samples.size
        first.deliver(fix(2.0, north = 6.0, speed = 3.0), at(2))
        assertEquals(delivered, listener.samples.size)

        controller.follow(RunPhase.PREPARING)
        port.requests.last().deliver(fix(10.0, north = 6.0, speed = 3.0), at(10))
        val distance = listener.samples.filterIsInstance<DistanceSample>().last()
        assertEquals(Distance.ZERO, distance.total)
    }

    @Test
    fun `starting twice is an error`() {
        assertFailsWith<IllegalStateException> { controller.start(listener) }
        controller.stop()
        controller.follow(RunPhase.RUNNING)
        port.requests.single().deliver(fix(0.0), at(0))
        assertTrue(listener.samples.isEmpty())
    }

    @Test
    fun `warm-up GPS readiness is the better of Health Services and fused location`() {
        val acquiring = ExerciseStatus(gps = Readiness.WAITING, heartRate = Readiness.READY)
        assertEquals(Readiness.READY, acquiring.withLocation(Readiness.READY).gps)
        assertTrue(acquiring.withLocation(Readiness.READY).warmedUp)
        assertEquals(Readiness.WAITING, acquiring.withLocation(Readiness.NOT_REQUESTED).gps)
        val noGnss = ExerciseStatus(gps = Readiness.UNAVAILABLE)
        assertEquals(Readiness.WAITING, noGnss.withLocation(Readiness.WAITING).gps)
        assertEquals(Readiness.UNAVAILABLE, noGnss.withLocation(Readiness.NOT_REQUESTED).gps)
        val ready = ExerciseStatus(gps = Readiness.READY)
        assertEquals(Readiness.READY, ready.withLocation(Readiness.UNAVAILABLE).gps)
        assertNull(ExerciseStatus().withLocation(Readiness.NOT_REQUESTED).error)
    }

    private fun LocationReceiver.deliver(fix: LocationFix, arrival: Timestamp) {
        now = arrival
        fix(fix)
    }

    private fun fix(
        seconds: Double,
        north: Double = 0.0,
        accuracy: Double = 5.0,
        speed: Double = Double.NaN
    ) = LocationFix(
        elapsedNanos = TestTime.at(seconds).elapsedRealtimeNanos,
        latitude = 45.47 + north / 111_195.0,
        longitude = 9.18,
        horizontalAccuracyMeters = accuracy,
        speedMetersPerSecond = speed
    )

    private class FakeLocationPort : LocationPort {
        val requests = mutableListOf<LocationReceiver>()
        var removals = 0

        override fun request(receiver: LocationReceiver) {
            requests += receiver
        }

        override fun remove() {
            removals++
        }
    }

    private class RecordingListener : SensorListener {
        val samples = mutableListOf<Sample>()
        val events = mutableListOf<SensorEvent>()

        override fun onSample(sample: Sample) {
            samples += sample
        }

        override fun onEvent(event: SensorEvent) {
            events += event
        }

        override fun onImu(batch: ImuBatch) = Unit
    }

    private companion object {
        val FUSED = SourceId.FUSED_LOCATION
    }
}
