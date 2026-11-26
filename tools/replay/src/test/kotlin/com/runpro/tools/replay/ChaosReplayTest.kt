package com.runpro.tools.replay

import com.runpro.core.model.Metric
import com.runpro.core.model.SensorEvent
import com.runpro.tools.replay.Fixtures.strap
import com.runpro.tools.replay.Fixtures.t0
import com.runpro.tools.replay.Fixtures.watch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ChaosReplayTest {
    @Test
    fun `disconnect and GATT 133 suppress one source until reconnect`() {
        for (status in listOf(null, 133)) {
            val out = CollectingListener()
            val replayer = Replayer(
                Fixtures.trace,
                chaos = ChaosPlan(ReplayFault.BleDisconnect(1.seconds, 1.seconds, strap, status))
            )
            replayer.sources.forEach { it.start(out) }
            val result = replayer.run()
            assertFalse(result.cancelled)
            assertEquals(1, out.samples.count { it.source == strap })
            assertEquals(4, out.samples.count { it.source == watch })
            assertTrue(SensorEvent.Disconnected(t0 + 1.seconds, strap, status) in out.events)
            assertTrue(SensorEvent.Connected(t0 + 2.seconds, strap) in out.events)
        }
    }

    @Test
    fun `silent stall removes samples without disconnect events`() {
        val out = CollectingListener()
        val replayer = Replayer(
            Fixtures.trace,
            chaos = ChaosPlan(ReplayFault.SilentStall(1.seconds, 500.milliseconds, strap))
        )
        replayer.sources.forEach { it.start(out) }
        replayer.run()
        assertEquals(Fixtures.trace.events, out.events)
        assertEquals(1, out.samples.count { it.source == strap })
        assertEquals(4, out.samples.count { it.source == watch })
    }

    @Test
    fun `GPS loss removes GPS metrics while wrist HR and cadence continue`() {
        val trace = SyntheticTraces.easyRun()
        val out = CollectingListener()
        val replayer =
            Replayer(trace, chaos = ChaosPlan(ReplayFault.GpsLoss(1.seconds, 2.seconds, watch)))
        replayer.sources.forEach { it.start(out) }
        replayer.run()
        val origin = trace.records.first().time
        val inGap = out.samples.filter { it.time.elapsedSince(origin) in 1.seconds..<3.seconds }
        assertTrue(inGap.isNotEmpty())
        assertTrue(
            inGap.none {
                it.metric in setOf(Metric.LOCATION, Metric.DISTANCE, Metric.SPEED)
            }
        )
        assertEquals(2, inGap.count { it.source == watch && it.metric == Metric.HEART_RATE })
        assertEquals(2, inGap.count { it.metric == Metric.CADENCE })
        assertTrue(SensorEvent.GpsLost(origin + 1.seconds, watch) in out.events)
        assertTrue(SensorEvent.GpsAcquired(origin + 3.seconds, watch) in out.events)
        assertTrue(
            out.samples.any {
                it.time == origin + 3.seconds && it.metric == Metric.LOCATION
            }
        )
    }

    @Test
    fun `clock jumps accumulate on wall time without changing monotonic pacing`() {
        val clock = VirtualReplayClock()
        val out = CollectingListener()
        val replayer = Replayer(
            Fixtures.trace,
            ReplaySpeed.REAL_TIME,
            clock,
            ChaosPlan(
                ReplayFault.ClockJump(1.seconds, 60.seconds),
                ReplayFault.ClockJump(2.seconds, (-120).seconds)
            )
        )
        replayer.sources.forEach { it.start(out) }
        replayer.run()
        assertEquals(3.seconds, clock.slept)
        assertEquals(
            listOf(0L, 61_000L, -58_000L, -57_000L),
            out.samples.filter {
                it.source ==
                    watch
            }
                .map { it.time.wallClockMillis - t0.wallClockMillis }
        )
        assertEquals(
            listOf(0.seconds, 1.seconds, 2.seconds, 3.seconds),
            out.samples.filter {
                it.source ==
                    watch
            }
                .map { it.time.elapsedSince(t0) }
        )
    }

    @Test
    fun `faults run in gaps and before same-time samples with stable declaration order`() {
        val clock = VirtualReplayClock()
        val calls = mutableListOf<Pair<ReplayFault, Long>>()
        val first = ReplayFault.ProcessDeath(500.milliseconds)
        val second = ReplayFault.StorageFull(500.milliseconds)
        val replayer = Replayer(
            Fixtures.trace,
            ReplaySpeed.times(2.0),
            clock,
            ChaosPlan(first, second),
            ReplayFaultHandler { fault, time ->
                assertEquals(t0 + 500.milliseconds, time)
                calls += fault to clock.nanoTime()
            }
        )
        replayer.run()
        assertEquals(listOf(first to 250_000_000L, second to 250_000_000L), calls)
    }

    @Test
    fun `invalid plans fail before replay`() {
        assertFailsWith<IllegalArgumentException> {
            ChaosPlan(ReplayFault.ProcessDeath((-1).seconds))
        }
        assertFailsWith<IllegalArgumentException> {
            ChaosPlan(ReplayFault.SilentStall(0.seconds, 0.seconds, strap))
        }
        assertFailsWith<IllegalArgumentException> {
            Replayer(Fixtures.trace, chaos = ChaosPlan(ReplayFault.ProcessDeath(1.seconds)))
        }
        assertFailsWith<IllegalArgumentException> {
            Replayer(
                Fixtures.trace,
                chaos = ChaosPlan(
                    ReplayFault.GpsLoss(
                        0.seconds,
                        1.seconds,
                        com.runpro.core.model.SourceId.WATCH_IMU
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChaosPlan(
                ReplayFault.BleDisconnect(0.seconds, 2.seconds, strap),
                ReplayFault.SilentStall(1.seconds, 2.seconds, strap)
            )
        }
    }
}
