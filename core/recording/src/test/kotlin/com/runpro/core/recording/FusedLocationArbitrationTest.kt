package com.runpro.core.recording

import com.runpro.core.model.Distance
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Metric
import com.runpro.core.model.RunEvent
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SourceId
import com.runpro.core.model.SwitchReason
import com.runpro.core.recording.TestTime.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #96 (ADR 0019): `watch:fused-location` backs up `watch:health-services` for location, distance and
 * speed. Health Services wins while it reports; every hand-over is a recorded switch (R5) and never pauses
 * the run (R4); distance restarts its baseline at each switch, so nothing is counted twice.
 */
class FusedLocationArbitrationTest {
    @Test
    fun `default priority is BLE, then Health Services, then fused location, then the IMU`() {
        val rule = SourcePolicy().forMetric(Metric.DISTANCE)
        val ranked = listOf(
            SourceId.DERIVED,
            SourceId.WATCH_IMU,
            FUSED,
            WATCH,
            FOOTPOD
        ).sortedBy(rule::rank)
        assertEquals(listOf(FOOTPOD, WATCH, FUSED, SourceId.WATCH_IMU, SourceId.DERIVED), ranked)
    }

    @Test
    fun `fused covers Health Services acquiring, hands over on its fix and takes back on loss`() {
        val machine = runningMachine()
        // 0-10 s: Health Services still acquiring (urban canyon); only the fused source reports.
        (0..10).forEach { s -> machine.feed(loc(s, FUSED), dist(s, 3.0 * s, FUSED)) }
        assertEquals(FUSED, machine.state.activeSources[Metric.LOCATION])
        assertEquals(FUSED, machine.state.activeSources[Metric.DISTANCE])
        // 10-20 s: Health Services gets its fix; both report, Health Services wins.
        (10..20).forEach { s ->
            if (s > 10) machine.feed(loc(s, FUSED), dist(s, 3.0 * s, FUSED))
            machine.feed(loc(s), dist(s, 500.0 + 3.0 * (s - 10)), tick(s))
        }
        assertEquals(WATCH, machine.state.activeSources[Metric.LOCATION])
        assertEquals(WATCH, machine.state.activeSources[Metric.DISTANCE])
        // 20.5 s: Health Services loses the fix; the fused source, still fresh, takes over at once.
        machine.handle(EngineInput.SensorReceived(SensorEvent.GpsLost(at(20.5), WATCH)))
        (21..30).forEach { s -> machine.feed(loc(s, FUSED), dist(s, 3.0 * s, FUSED), tick(s)) }
        assertEquals(FUSED, machine.state.activeSources[Metric.LOCATION])
        assertEquals(RunPhase.RUNNING, machine.state.phase)

        val distanceSwitches = machine.switches(Metric.DISTANCE)
        assertEquals(
            listOf(
                Triple(null, FUSED, SwitchReason.HIGHER_PRIORITY),
                Triple(FUSED, WATCH, SwitchReason.HIGHER_PRIORITY),
                Triple(WATCH, FUSED, SwitchReason.STALE)
            ),
            distanceSwitches.map { Triple(it.from, it.to, it.reason) }
        )
        assertEquals(listOf(at(0), at(10), at(20.5)), distanceSwitches.map { it.time })
        assertEquals(
            distanceSwitches.map { it.from to it.to },
            machine.switches(Metric.LOCATION).map { it.from to it.to }
        )
        // Fused 0-10 s (30 m), Health Services 10-20 s (30 m), fused 21-30 s (27 m): the first reading
        // after each switch is a new baseline, so the two counters are never added together.
        assertEquals(87.0, machine.state.totals.distance.meters, 1e-9)
        assertTrue(machine.state.run!!.events.none { it is RunEvent.Paused })
    }

    @Test
    fun `fused location never displaces a footpod for distance`() {
        val machine = runningMachine()
        (0..10).forEach { s ->
            machine.feed(dist(s, 3.1 * s, FOOTPOD), dist(s, 3.0 * s, FUSED), tick(s))
        }
        assertEquals(FOOTPOD, machine.state.activeSources[Metric.DISTANCE])
        assertEquals(
            listOf(null to FOOTPOD),
            machine.switches(Metric.DISTANCE).map {
                it.from to
                    it.to
            }
        )
        assertEquals(31.0, machine.state.totals.distance.meters, 1e-9)
    }

    @Test
    fun `a silent fused source with no replacement records no switch and no distance`() {
        val machine = runningMachine()
        (0..5).forEach { s -> machine.feed(loc(s, FUSED), dist(s, 3.0 * s, FUSED)) }
        // Coarse fixes are rejected by the gate, so nothing arrives for 30 s.
        (6..36).forEach { s -> machine.feed(tick(s)) }
        assertEquals(null, machine.state.activeSources[Metric.DISTANCE])
        assertEquals(1, machine.switches(Metric.DISTANCE).size)
        assertEquals(15.0, machine.state.totals.distance.meters, 1e-9)
        assertEquals(RunPhase.RUNNING, machine.state.phase)
    }

    private fun loc(seconds: Number, source: SourceId = WATCH) = EngineInput.SampleReceived(
        LocationSample(
            TestTime.at(seconds.toDouble()),
            source,
            latitude = 45.47 + seconds.toDouble() * 1e-5,
            longitude = 9.18,
            horizontalAccuracy = Distance(5.0)
        )
    )

    private fun RecordingStateMachine.switches(metric: Metric) =
        state.run!!.events.filterIsInstance<RunEvent.SourceSwitched>().filter {
            it.metric == metric
        }

    private companion object {
        val FUSED = SourceId.FUSED_LOCATION
    }
}
