package com.runpro.core.journal

import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ImuReading
import com.runpro.core.model.ImuSensor
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp

/**
 * Compact encoding of [ImuBatch]es ([ImuBatchProto], ADR 0012): offsets to 1 µs, accelerometer values to
 * 0.001 m/s², gyroscope values to 0.0001 rad/s, all delta encoded. Both are finer than the watch sensors.
 */
internal object ImuCodec {
    private const val NANOS_PER_MICRO = 1_000L
    private const val HALF_MICRO = NANOS_PER_MICRO / 2
    private const val ACCELEROMETER_UNITS = 1_000
    private const val GYROSCOPE_UNITS = 10_000

    /** Permanent wire codes; 0 means missing. */
    private val sensorCodes = mapOf(ImuSensor.ACCELEROMETER to 1, ImuSensor.GYROSCOPE to 2)
    private val sensors = sensorCodes.entries.associate { (sensor, code) -> code to sensor }

    fun unitsPerValue(sensor: ImuSensor): Int = when (sensor) {
        ImuSensor.ACCELEROMETER -> ACCELEROMETER_UNITS
        ImuSensor.GYROSCOPE -> GYROSCOPE_UNITS
    }

    fun encode(batch: ImuBatch): ImuBatchProto {
        val units = unitsPerValue(batch.sensor)
        val offsets = DeltaEncoder(zigzag = false)
        val x = DeltaEncoder(zigzag = true)
        val y = DeltaEncoder(zigzag = true)
        val z = DeltaEncoder(zigzag = true)
        for (reading in batch.readings) {
            offsets.add((reading.offsetNanos + HALF_MICRO) / NANOS_PER_MICRO)
            x.add(quantise(reading.x, units))
            y.add(quantise(reading.y, units))
            z.add(quantise(reading.z, units))
        }
        return ImuBatchProto(
            sensor = requireNotNull(sensorCodes[batch.sensor]),
            time = TimeProto(batch.time.elapsedRealtimeNanos, batch.time.wallClockMillis),
            source = batch.source.value,
            unitsPerValue = units,
            offsetDeltas = offsets.deltas,
            x = x.deltas,
            y = y.deltas,
            z = z.deltas
        )
    }

    fun decode(proto: ImuBatchProto): ImuBatch {
        val sensor = requireNotNull(sensors[proto.sensor]) { "Unknown IMU sensor: ${proto.sensor}" }
        val count = proto.offsetDeltas.size
        require(
            count > 0 && proto.x.size == count && proto.y.size == count && proto.z.size == count
        ) {
            "IMU axes have different lengths"
        }
        require(proto.unitsPerValue > 0) { "Invalid IMU resolution: ${proto.unitsPerValue}" }
        require(proto.offsetDeltas.all { it >= 0 }) { "IMU offsets must not decrease" }
        val units = proto.unitsPerValue.toDouble()
        val offsets = proto.offsetDeltas.runningReduce(Long::plus)
        val x = proto.x.map(::unzigzag).runningReduce(Long::plus)
        val y = proto.y.map(::unzigzag).runningReduce(Long::plus)
        val z = proto.z.map(::unzigzag).runningReduce(Long::plus)
        val readings = List(count) { i ->
            ImuReading(
                offsets[i] * NANOS_PER_MICRO,
                (x[i] / units).toFloat(),
                (y[i] / units).toFloat(),
                (z[i] / units).toFloat()
            )
        }
        return ImuBatch(
            Timestamp(proto.time.elapsedNanos, proto.time.wallMillis),
            SourceId(proto.source),
            sensor,
            readings
        )
    }

    // Math.round saturates, so a value outside any real sensor range cannot wrap around.
    private fun quantise(value: Float, units: Int): Long = Math.round(value.toDouble() * units)

    private fun zigzag(value: Long): Long = (value shl 1) xor (value shr Long.SIZE_BITS - 1)

    private fun unzigzag(value: Long): Long = (value ushr 1) xor -(value and 1)

    private class DeltaEncoder(private val zigzag: Boolean) {
        private var previous = 0L
        val deltas = mutableListOf<Long>()

        fun add(value: Long) {
            val delta = value - previous
            deltas += if (zigzag) zigzag(delta) else delta
            previous = value
        }
    }
}
