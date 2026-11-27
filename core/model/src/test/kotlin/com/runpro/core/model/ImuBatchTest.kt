package com.runpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class ImuBatchTest {
    private val t0 =
        Timestamp(elapsedRealtimeNanos = 5_000_000_000, wallClockMillis = 1_764_064_800_000)

    private fun batch(vararg offsets: Long) = ImuBatch(
        t0,
        SourceId.WATCH_IMU,
        ImuSensor.ACCELEROMETER,
        offsets.map { ImuReading(it, 0.1f, -9.7f, 1.2f) }
    )

    @Test
    fun `reading times follow the batch start on both clocks`() {
        val batch = batch(0, 20_000_000, 40_000_000)
        assertEquals(t0, batch.timeOf(0))
        assertEquals(t0 + 40.milliseconds, batch.timeOf(2))
        assertEquals(t0 + 40.milliseconds, batch.end)
        assertEquals(40.milliseconds, batch.span)
    }

    @Test
    fun `a batch needs readings in time order that start at its time`() {
        assertFailsWith<IllegalArgumentException> { batch() }
        assertFailsWith<IllegalArgumentException> { batch(10, 20) }
        assertFailsWith<IllegalArgumentException> { batch(0, 20, 10) }
        batch(0, 0, 20) // Equal timestamps happen in sensor FIFOs and are kept.
    }

    @Test
    fun `readings must be finite`() {
        assertFailsWith<IllegalArgumentException> { ImuReading(0, Float.NaN, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { ImuReading(0, 0f, Float.POSITIVE_INFINITY, 0f) }
    }

    @Test
    fun `a batch survives a JSON round trip with its sensor name`() {
        val batch = ImuBatch(
            t0,
            SourceId.WATCH_IMU,
            ImuSensor.GYROSCOPE,
            listOf(ImuReading(0, 0.5f, -0.25f, 1f), ImuReading(20_000_000, 0f, 0f, 0f))
        )
        val text = ModelJson.json.encodeToString(ImuBatch.serializer(), batch)
        assertEquals(batch, ModelJson.json.decodeFromString(ImuBatch.serializer(), text))
        assert("\"sensor\":\"gyroscope\"" in text) { text }
    }

    @Test
    fun `listeners that do not care about the IMU ignore batches`() {
        val listener = object : SensorListener {
            override fun onSample(sample: Sample) = Unit

            override fun onEvent(event: SensorEvent) = Unit
        }
        listener.onImu(batch(0)) // The default does nothing and never throws.
    }
}
