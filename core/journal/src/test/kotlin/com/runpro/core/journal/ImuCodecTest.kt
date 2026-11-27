package com.runpro.core.journal

import com.runpro.core.model.Acceleration
import com.runpro.core.model.AccelerationSample
import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ImuReading
import com.runpro.core.model.ImuSensor
import com.runpro.core.model.SourceId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImuCodecTest {
    @Test
    fun `IMU batches survive a round trip`() {
        for (batch in everyImuBatch) {
            val record = JournalRecord.Imu(batch)
            assertEquals(record, JournalCodec.decode(JournalCodec.encode(record)))
        }
    }

    @Test
    fun `values are kept to the stored resolution`() {
        val batch = runningBatch(ImuSensor.ACCELEROMETER, Random(1))
        val decoded = roundTrip(batch)
        assertEquals(batch.time, decoded.time)
        assertEquals(batch.readings.size, decoded.readings.size)
        batch.readings.zip(decoded.readings).forEach { (expected, actual) ->
            assertTrue(abs(expected.offsetNanos - actual.offsetNanos) <= 500, "$expected $actual")
            assertTrue(abs(expected.x - actual.x) <= 0.0005f + 1e-6f, "$expected $actual")
            assertTrue(abs(expected.y - actual.y) <= 0.0005f + 1e-6f, "$expected $actual")
            assertTrue(abs(expected.z - actual.z) <= 0.0005f + 1e-6f, "$expected $actual")
        }
    }

    @Test
    fun `offsets do not drift over a long batch`() {
        // Each offset is rounded on its own, so 60 s of readings 19.999_999 ms apart stay within 0.5 µs.
        val readings = (0 until 3000).map { ImuReading(it * 19_999_999L, 0f, 0f, 9.81f) }
        val batch = ImuBatch(at(0), SourceId.WATCH_IMU, ImuSensor.ACCELEROMETER, readings)
        val decoded = roundTrip(batch)
        assertTrue(abs(decoded.readings.last().offsetNanos - readings.last().offsetNanos) <= 500)
    }

    @Test
    fun `a second of running at 50 Hz is several times smaller than as samples`() {
        val random = Random(7)
        for (sensor in ImuSensor.entries) {
            val batch = runningBatch(sensor, random)
            val compact = JournalFormat.frame(JournalCodec.encode(JournalRecord.Imu(batch))).size
            val samples = batch.readings.mapIndexed { i, r ->
                AccelerationSample(
                    batch.timeOf(i),
                    batch.source,
                    Acceleration(r.x.toDouble(), r.y.toDouble(), r.z.toDouble())
                )
            }
            val asSamples =
                JournalFormat.frame(JournalCodec.encode(JournalRecord.Samples(samples))).size
            // At most 12 bytes a reading: under 4.5 MB an hour for accelerometer and gyroscope together.
            assertTrue(compact <= 50 * 12, "$sensor: $compact bytes")
            assertTrue(compact * 4 < asSamples, "$sensor: $compact vs $asSamples bytes")
        }
    }

    private fun roundTrip(batch: ImuBatch): ImuBatch = (
        JournalCodec.decode(
            JournalCodec.encode(JournalRecord.Imu(batch))
        ) as JournalRecord.Imu
        ).batch

    /** One second at 50 Hz of a wrist during running: a 1.4 Hz arm swing plus noise. */
    private fun runningBatch(sensor: ImuSensor, random: Random): ImuBatch {
        val amplitude = if (sensor == ImuSensor.ACCELEROMETER) 15.0 else 4.0
        val readings = (0 until 50).map { i ->
            val t = i * 0.02
            fun axis(phase: Double) =
                (amplitude * sin(2 * PI * 1.4 * t + phase) + random.nextDouble(-0.3, 0.3)).toFloat()
            // Platform timestamps jitter by a fraction of a millisecond.
            val jitter = if (i == 0) 0L else random.nextLong(-200_000, 200_000)
            ImuReading(i * 20_000_000L + jitter, axis(0.0), axis(1.0), axis(2.0))
        }
        return ImuBatch(at(5), SourceId.WATCH_IMU, sensor, readings)
    }
}
