package com.runpro.tools.replay

import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.tools.replay.Fixtures.strap
import com.runpro.tools.replay.Fixtures.t0
import com.runpro.tools.replay.Fixtures.watch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ChaosTimelineTest {
    @Test
    fun `late batches keep file order and pre-jump wall timestamps`() {
        val original = Fixtures.trace.records
        val late = Fixtures.trace.copy(
            records = listOf(original[2], original[6], original[4], original[7])
        )
        val output = mutableListOf<Sample>()
        val order = mutableListOf<String>()
        val replayer = Replayer(
            late,
            chaos = ChaosPlan(
                ReplayFault.ClockJump(2.seconds, 60.seconds),
                ReplayFault.ProcessDeath(1500.milliseconds)
            ),
            faultHandler = ReplayFaultHandler { _, _ -> order += "death" }
        )
        replayer.source(watch).start(object : SensorListener {
            override fun onSample(sample: Sample) {
                output += sample
                order += "sample"
            }
            override fun onEvent(event: SensorEvent) = Unit
        })
        replayer.run()
        assertEquals(listOf("sample", "death", "sample", "sample", "sample"), order)
        assertEquals(
            late.samples.map {
                it.time.elapsedRealtimeNanos
            },
            output.map { it.time.elapsedRealtimeNanos }
        )
        assertEquals(
            listOf(0L, 62_000L, 1_000L, 63_000L),
            output.map {
                it.time.wallClockMillis -
                    t0.wallClockMillis
            }
        )
    }

    @Test
    fun `callbacks precede samples at the exact boundary and can cancel the remaining replay`() {
        lateinit var replayer: Replayer
        val out = CollectingListener()
        replayer = Replayer(
            Fixtures.trace,
            chaos = ChaosPlan(ReplayFault.ProcessDeath(1.seconds)),
            faultHandler = ReplayFaultHandler { _, time ->
                assertEquals(t0 + 1.seconds, time)
                assertEquals(2, out.samples.size)
                replayer.cancel()
            }
        )
        replayer.sources.forEach { it.start(out) }
        assertTrue(replayer.run().cancelled)
        assertEquals(2, out.samples.size)
        assertFailsWith<IllegalStateException> { replayer.run() }
    }

    @Test
    fun `recovery boundaries after the last sample are paced and events count as records`() {
        val clock = VirtualReplayClock()
        val out = CollectingListener()
        val replayer = Replayer(
            Fixtures.trace,
            ReplaySpeed.times(2.0),
            clock,
            ChaosPlan(ReplayFault.BleDisconnect(1.seconds, 4.seconds, strap))
        )
        replayer.sources.forEach { it.start(out) }
        val result = replayer.run()
        assertEquals(2500.milliseconds, clock.slept)
        assertEquals(SensorEvent.Connected(t0 + 5.seconds, strap), out.events.last())
        assertEquals(out.samples.size + out.events.size, result.delivered)
        assertEquals(0, result.dropped)
        assertFalse(result.cancelled)
    }

    @Test
    fun `a silent trace still advances time and unstarted injected sources drop their events`() {
        val clock = VirtualReplayClock()
        val plan = ChaosPlan(
            ReplayFault.SilentStall(0.seconds, 4.seconds, strap),
            ReplayFault.GpsLoss(0.seconds, 4.seconds, watch)
        )
        val replayer = Replayer(Fixtures.trace, ReplaySpeed.REAL_TIME, clock, plan)
        val result = replayer.run()
        assertEquals(4.seconds, clock.slept)
        assertEquals(0, result.delivered)
        // Original BLE connection/disconnection plus two GPS events.
        assertEquals(4, result.dropped)
    }

    @Test
    fun `empty non-fault replay is valid and malformed fault plans are rejected`() {
        assertEquals(ReplayResult(0, 0, false), Replayer(Trace(Fixtures.header, emptyList())).run())
        assertFailsWith<IllegalArgumentException> {
            Replayer(
                Trace(Fixtures.header, emptyList()),
                chaos = ChaosPlan(ReplayFault.ClockJump(0.seconds, 1.seconds))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChaosPlan(ReplayFault.ProcessDeath(Duration.INFINITE))
        }
        assertFailsWith<IllegalArgumentException> {
            ChaosPlan(ReplayFault.ClockJump(0.seconds, Duration.INFINITE))
        }
        assertFailsWith<IllegalArgumentException> {
            Replayer(
                Fixtures.trace,
                chaos = ChaosPlan(ReplayFault.SilentStall(0.seconds, 1.seconds, SourceId.WATCH_IMU))
            )
        }
    }

    @Test
    fun `handler failures propagate instead of silently completing a replay`() {
        val replayer = Replayer(
            Fixtures.trace,
            chaos = ChaosPlan(ReplayFault.ProcessDeath(1.seconds)),
            faultHandler = ReplayFaultHandler { _, _ -> error("Recovery failed") }
        )
        assertFailsWith<IllegalStateException> { replayer.run() }
    }

    @Test
    fun `abutting outages end before the next outage begins even when declared backwards`() {
        val out = CollectingListener()
        val replayer = Replayer(
            Fixtures.trace,
            chaos = ChaosPlan(
                ReplayFault.BleDisconnect(2.seconds, 1.seconds, strap, 133),
                ReplayFault.BleDisconnect(1.seconds, 1.seconds, strap)
            )
        )
        replayer.source(strap).start(out)
        replayer.run()
        assertEquals(
            listOf(
                SensorEvent.Connected(t0 + 2.seconds, strap),
                SensorEvent.Disconnected(t0 + 2.seconds, strap, 133)
            ),
            out.events.filter { it.time == t0 + 2.seconds }
        )
    }
}
