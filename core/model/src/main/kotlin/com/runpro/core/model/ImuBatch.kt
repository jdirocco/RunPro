package com.runpro.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A motion sensor of the watch; values are along the device axes, as Android reports them. */
@Serializable
enum class ImuSensor {
    /** Acceleration in m/s², gravity included (`Sensor.TYPE_ACCELEROMETER`). */
    @SerialName("accelerometer")
    ACCELEROMETER,

    /** Rotation rate in rad/s (`Sensor.TYPE_GYROSCOPE`). */
    @SerialName("gyroscope")
    GYROSCOPE
}

/**
 * One reading of an [ImuSensor], [offsetNanos] after the start of its [ImuBatch] on the monotonic clock.
 * Floats, as the platform delivers them: IMU streams run at 50 Hz, so they are kept compact.
 */
@Serializable
data class ImuReading(
    @SerialName("o") val offsetNanos: Long,
    val x: Float,
    val y: Float,
    val z: Float
) {
    init {
        require(offsetNanos >= 0) { "Negative IMU offset: $offsetNanos" }
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "IMU values must be finite: ($x, $y, $z)"
        }
    }
}

/**
 * Consecutive readings of one IMU [sensor], typically one second at 50 Hz. IMU data does not go through
 * [Sample]s: one object per reading would cost too much memory and journal space (ADR 0012).
 *
 * [time] is the time of the first reading on both clocks; the readings are in time order and the first
 * one has offset 0.
 */
@Serializable
@SerialName("imu_batch")
data class ImuBatch(
    val time: Timestamp,
    val source: SourceId,
    val sensor: ImuSensor,
    val readings: List<ImuReading>
) {
    init {
        require(readings.isNotEmpty()) { "An IMU batch cannot be empty" }
        require(readings.first().offsetNanos == 0L) { "The first reading is at the batch time" }
        require(readings.zipWithNext().all { (a, b) -> a.offsetNanos <= b.offsetNanos }) {
            "IMU readings must be in time order"
        }
    }

    /** Time of the reading at [index] on both clocks. */
    fun timeOf(index: Int): Timestamp = time + readings[index].offsetNanos.nanoseconds

    /** Time of the last reading. */
    val end: Timestamp get() = timeOf(readings.lastIndex)

    /** Monotonic time from the first to the last reading. */
    val span: Duration get() = readings.last().offsetNanos.nanoseconds
}
