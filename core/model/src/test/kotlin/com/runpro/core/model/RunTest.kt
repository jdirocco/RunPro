package com.runpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RunTest {
    private val t0 =
        Timestamp(elapsedRealtimeNanos = 1_000_000_000, wallClockMillis = 1_764_064_800_000)
    private val id = RunId("run-1")

    @Test
    fun `a new run has one open lap`() {
        val run = Run(id, listOf(RunEvent.Started(t0)))
        assertEquals(t0, run.startedAt)
        assertFalse(run.isStopped)
        assertNull(run.stoppedAt)
        assertEquals(listOf(Lap(index = 0, start = t0)), run.laps)
        assertTrue(run.laps.single().isOpen)
        assertNull(run.laps.single().elapsed)
    }

    @Test
    fun `laps are derived from lap marks and the stop`() {
        val run = Run(id, listOf(RunEvent.Started(t0)))
            .plus(RunEvent.Paused(t0 + 1.minutes))
            .plus(RunEvent.Resumed(t0 + 2.minutes))
            .plus(RunEvent.LapMarked(t0 + 5.minutes, LapTrigger.MANUAL))
            .plus(RunEvent.LapMarked(t0 + 10.minutes, LapTrigger.DISTANCE))
            .plus(RunEvent.Stopped(t0 + 12.minutes, StopReason.USER))

        assertTrue(run.isStopped)
        assertEquals(t0 + 12.minutes, run.stoppedAt)
        assertEquals(
            listOf(
                Lap(0, t0, t0 + 5.minutes, LapTrigger.MANUAL),
                Lap(1, t0 + 5.minutes, t0 + 10.minutes, LapTrigger.DISTANCE),
                Lap(2, t0 + 10.minutes, t0 + 12.minutes, LapTrigger.STOP)
            ),
            run.laps
        )
        assertEquals(5.minutes, run.laps[0].elapsed)
        assertEquals(12.minutes, run.stoppedAt!!.elapsedSince(run.startedAt))
    }

    @Test
    fun `sensor and source events do not split laps`() {
        val strap = SourceId.ble("AA:BB:CC:DD:EE:FF")
        val run = Run(
            id,
            listOf(
                RunEvent.Started(t0),
                RunEvent.SensorConnected(t0 + 1.seconds, strap),
                RunEvent.SensorLost(t0 + 30.seconds, strap, SensorLossReason.NO_DATA),
                RunEvent.SourceSwitched(
                    t0 + 31.seconds,
                    Metric.HEART_RATE,
                    from = strap,
                    to = SourceId.HEALTH_SERVICES,
                    reason = SwitchReason.STALE
                )
            )
        )
        assertEquals(1, run.laps.size)
    }

    @Test
    fun `a run must start with a single start event`() {
        assertFailsWith<IllegalArgumentException> { Run(id, emptyList()) }
        assertFailsWith<IllegalArgumentException> { Run(id, listOf(RunEvent.Resumed(t0))) }
        assertFailsWith<IllegalArgumentException> {
            Run(id, listOf(RunEvent.Started(t0), RunEvent.Started(t0 + 1.seconds)))
        }
    }

    @Test
    fun `nothing can follow the stop`() {
        val stopped =
            Run(id, listOf(RunEvent.Started(t0), RunEvent.Stopped(t0 + 1.minutes, StopReason.USER)))
        assertFailsWith<IllegalArgumentException> { stopped + RunEvent.Resumed(t0 + 2.minutes) }
    }

    @Test
    fun `run ids are unique and non blank`() {
        assertFailsWith<IllegalArgumentException> { RunId(" ") }
        assertFalse(RunId.random() == RunId.random())
    }
}
