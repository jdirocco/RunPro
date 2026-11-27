package com.runpro.core.ble

import com.runpro.core.model.Distance
import com.runpro.core.model.Speed

/** The walking or running status bit of the RSC Measurement flags. */
enum class MotionStatus { WALKING, RUNNING }

/**
 * RSC Measurement (0x2A53) of the Running Speed and Cadence service (0x1814), sent by footpods and Stryd.
 *
 * @property cadencePerMinute the raw "instantaneous cadence" (foot falls per minute in the specification).
 *   Some footpods are known to send strides instead of steps; device quirks belong to the sensor adapter.
 * @property totalDistance cumulative since the sensor was reset (not since the run started), when present.
 */
data class RscMeasurement(
    val speed: Speed,
    val cadencePerMinute: Int,
    val strideLength: Distance?,
    val totalDistance: Distance?,
    val motion: MotionStatus
) : BleMeasurement {
    companion object {
        private const val STRIDE_PRESENT = 0
        private const val DISTANCE_PRESENT = 1
        private const val RUNNING = 2
        private const val SPEED_UNITS_PER_MPS = 256.0
        private const val STRIDE_UNITS_PER_METER = 100.0
        private const val DISTANCE_UNITS_PER_METER = 10.0

        /** Decodes a notification; reserved flag bits and bytes beyond the fields are ignored. */
        fun parse(value: ByteArray): ParseResult<RscMeasurement> =
            decoding("RSC Measurement", value) {
                val flags = uint8("flags")
                val speed = Speed(uint16("speed") / SPEED_UNITS_PER_MPS)
                val cadence = uint8("cadence")
                val stride = if (flags.hasBit(STRIDE_PRESENT)) {
                    Distance(uint16("stride length") / STRIDE_UNITS_PER_METER)
                } else {
                    null
                }
                val distance = if (flags.hasBit(DISTANCE_PRESENT)) {
                    Distance(uint32("total distance") / DISTANCE_UNITS_PER_METER)
                } else {
                    null
                }
                val running = flags.hasBit(RUNNING)
                val motion = if (running) MotionStatus.RUNNING else MotionStatus.WALKING
                RscMeasurement(speed, cadence, stride, distance, motion)
            }
    }
}
