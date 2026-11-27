package com.runpro.core.recording

import com.runpro.core.model.HeartRate
import com.runpro.core.model.Metric
import com.runpro.core.model.RunEvent
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SwitchReason
import com.runpro.core.recording.TestTime.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SourceArbitrationTest {
    @Test
    fun `fresh strap wins then falls back and returns without recording commands`() {
        val machine = runningMachine()
        machine.feed(hr(0, 100), hr(0, 150, STRAP), hr(4, 101), tick(5))
        assertEquals(WATCH, machine.state.activeSources[Metric.HEART_RATE])
        assertEquals(RunPhase.RUNNING, machine.state.phase)
        machine.feed(hr(6, 102), hr(7, 151, STRAP))
        assertEquals(STRAP, machine.state.activeSources[Metric.HEART_RATE])
        assertEquals(HeartRate(151), machine.state.current.heartRate)
        assertEquals(
            listOf(null to WATCH, WATCH to STRAP, STRAP to WATCH, WATCH to STRAP),
            machine.state.run!!.events.filterIsInstance<RunEvent.SourceSwitched>().map {
                it.from to it.to
            }
        )
        assertEquals(SwitchReason.STALE, machine.switches()[2].reason)
        assertEquals(SwitchReason.HIGHER_PRIORITY, machine.switches().last().reason)
    }

    @Test
    fun `no data clears the active source and value once even while paused`() {
        val machine = runningMachine()
        machine.feed(hr(0, 150, STRAP), pause(1), tick(5), tick(6))
        assertEquals(RunPhase.PAUSED, machine.state.phase)
        assertNull(machine.state.activeSources[Metric.HEART_RATE])
        assertNull(machine.state.current.heartRate)
        assertEquals(2, machine.switches().size)
        machine.feed(resume(7), hr(8, 140))
        assertEquals(WATCH, machine.state.activeSources[Metric.HEART_RATE])
        assertEquals(2.seconds, machine.state.totals.timer)
    }

    @Test
    fun `priority lists and timeouts are independent for each metric`() {
        val config = EngineConfig(
            sourcePolicy = SourcePolicy(
                mapOf(
                    Metric.HEART_RATE to MetricSourcePolicy(listOf(WATCH, STRAP), 20.seconds),
                    Metric.CADENCE to MetricSourcePolicy(listOf(FOOTPOD, WATCH), 2.seconds)
                )
            )
        )
        val machine = runningMachine(config)
        machine.feed(hr(0, 100), hr(0, 150, STRAP), cad(0, 170.0, FOOTPOD), cad(1, 160.0))
        machine.feed(tick(2))
        assertEquals(WATCH, machine.state.activeSources[Metric.HEART_RATE])
        assertEquals(WATCH, machine.state.activeSources[Metric.CADENCE])
        assertEquals(HeartRate(100), machine.state.current.heartRate)
    }

    @Test
    fun `disconnect and lost contact invalidate data but connect alone cannot restore it`() {
        val machine = runningMachine()
        machine.feed(hr(0, 100), hr(0, 150, STRAP))
        machine.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(1), STRAP, 133)))
        assertEquals(WATCH, machine.state.activeSources[Metric.HEART_RATE])
        machine.handle(EngineInput.SensorReceived(SensorEvent.Connected(at(2), STRAP)))
        assertEquals(WATCH, machine.state.activeSources[Metric.HEART_RATE])
        machine.feed(hr(3, 151, STRAP), hr(4, 101))
        assertFalse(machine.handle(hr(4, 0, STRAP)))
        assertEquals(WATCH, machine.state.activeSources[Metric.HEART_RATE])
        assertTrue(machine.handle(hr(5, 152, STRAP)))
    }

    @Test
    fun `stale and out of order backup samples cannot reclaim a metric`() {
        val machine = runningMachine()
        machine.feed(hr(0, 150, STRAP), hr(5, 100), tick(6))
        assertFalse(machine.handle(hr(1, 160, STRAP)))
        assertEquals(WATCH, machine.state.activeSources[Metric.HEART_RATE])
        assertFalse(machine.handle(hr(4, 90)))
        assertEquals(HeartRate(100), machine.state.current.heartRate)
    }

    @Test
    fun `distance and elevation start new segments across switches with different offsets`() {
        val machine = runningMachine()
        machine.feed(
            dist(0, 1000.0, FOOTPOD), dist(1, 1003.0, FOOTPOD),
            dist(4, 5000.0), tick(6), dist(6, 5006.0), dist(7, 5009.0),
            dist(8, 2000.0, FOOTPOD), dist(9, 2003.0, FOOTPOD),
            elev(10, 100.0), elev(11, 103.0), elev(12, 1000.0, FOOTPOD),
            elev(13, 1003.0, FOOTPOD)
        )
        assertEquals(9.0, machine.state.totals.distance.meters)
        assertEquals(6.0, machine.state.totals.ascent.meters)
    }

    @Test
    fun `a fresh backup slightly older than the last strap reading is eligible on disconnect`() {
        val machine = runningMachine()
        machine.feed(hr(1, 100), hr(1.25, 150, STRAP))
        machine.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(1.5), STRAP)))
        assertEquals(WATCH, machine.state.activeSources[Metric.HEART_RATE])
    }

    @Test
    fun `old disconnect reports do not invalidate newer measurements`() {
        val machine = runningMachine()
        machine.feed(hr(1, 100), hr(2, 150, STRAP))
        machine.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(1), STRAP)))
        assertEquals(STRAP, machine.state.activeSources[Metric.HEART_RATE])
    }

    private fun RecordingStateMachine.switches() =
        state.run!!.events.filterIsInstance<RunEvent.SourceSwitched>()
}
