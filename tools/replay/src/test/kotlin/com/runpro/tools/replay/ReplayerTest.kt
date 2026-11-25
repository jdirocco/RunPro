package com.runpro.tools.replay

import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.SensorEvent
import com.runpro.tools.replay.Fixtures.strap
import com.runpro.tools.replay.Fixtures.watch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class ReplayerTest {
    @Test
    fun `replays a trace into one fake consumer per source`() {
        val replayer = Replayer(Fixtures.trace)
        val strapListener = CollectingListener()
        val watchListener = CollectingListener()
        replayer.source(strap).start(strapListener)
        replayer.source(watch).start(watchListener)

        val result = replayer.run()

        assertEquals(ReplayResult(delivered = 8, dropped = 0, cancelled = false), result)
        assertEquals(2, strapListener.samples.size)
        assertEquals(2, strapListener.events.size)
        assertEquals(4, watchListener.samples.size)
        assertEquals(Fixtures.trace.samples.filter { it.source == strap }, strapListener.samples)
    }

    @Test
    fun `records of sources nobody listens to are dropped`() {
        val replayer = Replayer(Fixtures.trace)
        val listener = CollectingListener()
        replayer.source(watch).start(listener)

        val result = replayer.run()

        assertEquals(4, result.delivered)
        assertEquals(4, result.dropped)
    }

    @Test
    fun `a source stopped by its consumer stops delivering`() {
        val replayer = Replayer(Fixtures.trace)
        val strapSource = replayer.source(strap)
        val received = mutableListOf<Any>()
        strapSource.start(
            object : com.runpro.core.model.SensorListener {
                override fun onSample(sample: com.runpro.core.model.Sample) {
                    received += sample
                    strapSource.stop()
                }

                override fun onEvent(event: SensorEvent) {
                    received += event
                }
            }
        )
        replayer.run()
        assertEquals(2, received.size, "connected event and first sample only")
        assertTrue(received[1] is HeartRateSample)
    }

    @Test
    fun `a source cannot be started twice and unknown sources are an error`() {
        val replayer = Replayer(Fixtures.trace)
        replayer.source(strap).start(CollectingListener())
        assertFailsWith<IllegalStateException> {
            replayer.source(strap).start(CollectingListener())
        }
        assertFailsWith<IllegalArgumentException> {
            replayer.source(com.runpro.core.model.SourceId.WATCH_IMU)
        }
    }

    @Test
    fun `a replayer runs once`() {
        val replayer = Replayer(Fixtures.trace)
        replayer.run()
        assertFailsWith<IllegalStateException> { replayer.run() }
    }

    @Test
    fun `real time replay waits as long as the trace lasts`() {
        val clock = VirtualReplayClock()
        Replayer(Fixtures.trace, ReplaySpeed.REAL_TIME, clock).run()
        assertEquals(3.seconds, clock.slept)
    }

    @Test
    fun `accelerated replay divides the waits by the speed`() {
        val clock = VirtualReplayClock()
        val trace = SyntheticTraces.easyRun()
        Replayer(trace, ReplaySpeed.times(60.0), clock).run()
        assertEquals(trace.duration / 60, clock.slept)
        assertTrue(clock.slept < 30.seconds)
    }

    @Test
    fun `unlimited replay never waits`() {
        val clock = VirtualReplayClock()
        Replayer(Fixtures.trace, ReplaySpeed.UNLIMITED, clock).run()
        assertEquals(0.nanoseconds, clock.slept)
    }

    @Test
    fun `each record is delivered when it is due`() {
        val clock = VirtualReplayClock()
        val replayer = Replayer(Fixtures.trace, ReplaySpeed.times(2.0), clock)
        val deliveredAt = mutableListOf<Long>()
        val listener = object : com.runpro.core.model.SensorListener {
            override fun onSample(sample: com.runpro.core.model.Sample) {
                deliveredAt += clock.nanoTime()
            }

            override fun onEvent(event: SensorEvent) = Unit
        }
        replayer.source(watch).start(listener)
        replayer.run()
        assertEquals(listOf(0L, 500_000_000L, 1_000_000_000L, 1_500_000_000L), deliveredAt)
    }

    @Test
    fun `late records keep their file order and the replay clock never goes back`() {
        val records = Fixtures.trace.records
        // A sample batch delivered late: its timestamp is older than the record before it.
        val late = Trace(Fixtures.header, listOf(records[4], records[1], records[7]))
        val clock = VirtualReplayClock()
        val replayer = Replayer(late, ReplaySpeed.REAL_TIME, clock)
        val listener = CollectingListener()
        replayer.sources.forEach { it.start(listener) }
        replayer.run()
        assertEquals(late.samples, listener.samples)
        assertEquals(2.seconds, clock.slept)
    }

    @Test
    fun `a replay can be cancelled by a consumer`() {
        val replayer = Replayer(Fixtures.trace)
        var count = 0
        val listener = object : com.runpro.core.model.SensorListener {
            override fun onSample(sample: com.runpro.core.model.Sample) {
                if (++count == 3) replayer.cancel()
            }

            override fun onEvent(event: SensorEvent) = Unit
        }
        replayer.sources.forEach { it.start(listener) }
        val result = replayer.run()
        assertTrue(result.cancelled)
        assertEquals(3, count)
    }

    @Test
    fun `the system clock really waits at accelerated speed`() {
        val trace = Fixtures.trace // 3 s of trace
        val started = System.nanoTime()
        Replayer(trace, ReplaySpeed.times(30.0), SystemReplayClock).run()
        val took = (System.nanoTime() - started).nanoseconds
        assertTrue(took >= 99_000_000.nanoseconds, "took $took")
        assertTrue(took < 1.minutes, "took $took")
    }

    @Test
    fun `speeds must be positive`() {
        assertFailsWith<IllegalArgumentException> { ReplaySpeed.times(0.0) }
        assertFailsWith<IllegalArgumentException> { ReplaySpeed.times(-2.0) }
        assertFailsWith<IllegalArgumentException> { ReplaySpeed.times(Double.NaN) }
    }
}
