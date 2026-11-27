package com.runpro.core.recording.imu

import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ImuSensor
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ImuBatcherTest {
    private var now = Timestamp(10_000_000_000, 1_764_064_800_000)
    private val batches = mutableListOf<ImuBatch>()
    private val batcher = ImuBatcher(
        SourceId.WATCH_IMU,
        ImuSensor.ACCELEROMETER,
        span = 1.seconds,
        clock = { now },
        emit = batches::add
    )

    private fun reading(nanos: Long, x: Float = 1f) = batcher.add(nanos, x, 2f, 3f)

    @Test
    fun `50 Hz readings make one batch a second`() {
        (0 until 150).forEach { reading(9_000_000_000 + it * 20_000_000L) }
        assertEquals(2, batches.size)
        assertEquals(listOf(50, 50), batches.map { it.readings.size })
        assertEquals(980_000_000L, batches[0].readings.last().offsetNanos)
        assertEquals(9_000_000_000, batches[0].time.elapsedRealtimeNanos)
        assertEquals(10_000_000_000, batches[1].time.elapsedRealtimeNanos)
        batcher.flush()
        assertEquals(listOf(50, 50, 50), batches.map { it.readings.size })
        batcher.flush()
        assertEquals(3, batches.size)
    }

    @Test
    fun `the wall clock of a batch comes from the clock when it begins`() {
        // The platform delivers readings late (FIFO batching): the first one is 1.5 s old.
        reading(8_500_000_000)
        batcher.flush()
        assertEquals(now.wallClockMillis - 1_500, batches.single().time.wallClockMillis)
    }

    @Test
    fun `readings out of order or not finite are dropped and counted`() {
        reading(1_000_000_000)
        reading(1_020_000_000)
        reading(1_010_000_000)
        reading(1_040_000_000, x = Float.NaN)
        reading(-5)
        reading(1_020_000_000) // Same time as the last reading: kept.
        batcher.flush()
        assertEquals(3, batches.single().readings.size)
        assertEquals(3, batcher.dropped)
    }

    @Test
    fun `a gap starts a new batch`() {
        reading(1_000_000_000)
        reading(4_000_000_000)
        batcher.flush()
        assertEquals(listOf(1, 1), batches.map { it.readings.size })
    }
}
