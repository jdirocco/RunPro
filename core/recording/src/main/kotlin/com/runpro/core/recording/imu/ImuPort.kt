package com.runpro.core.recording.imu

import com.runpro.core.model.ImuSensor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Which IMU sensors a run records (a runner setting; ADR 0012). */
enum class ImuMode(val sensors: Set<ImuSensor>) {
    OFF(emptySet()),
    ACCELEROMETER(setOf(ImuSensor.ACCELEROMETER)),
    ACCELEROMETER_AND_GYROSCOPE(setOf(ImuSensor.ACCELEROMETER, ImuSensor.GYROSCOPE))
}

/**
 * How the platform delivers readings: one every [samplingPeriod] (20 ms = 50 Hz), batched in the sensor
 * hub for up to [maxReportLatency], so the processor wakes about once a second instead of 50 times.
 */
data class ImuRate(
    val samplingPeriod: Duration = 20.milliseconds,
    val maxReportLatency: Duration = 1.seconds
) {
    init {
        require(samplingPeriod.isPositive() && !maxReportLatency.isNegative())
    }
}

/** Receives raw readings: monotonic time in nanoseconds (`SensorEvent.timestamp`) and the three axes. */
fun interface ImuSink {
    fun onReading(sensor: ImuSensor, elapsedNanos: Long, x: Float, y: Float, z: Float)
}

/**
 * The watch motion sensors (Android `SensorManager` in `:wear`), behind an interface so the capture is
 * tested on the JVM. [ImuCapture] calls it from its executor only. The sink may be called on any thread.
 */
interface ImuPort {
    /**
     * Starts the [sensors] the watch has at [rate], delivering to [sink], and returns the ones started
     * (empty when the watch has none of them). May throw if the platform refuses.
     */
    fun start(sensors: Set<ImuSensor>, rate: ImuRate, sink: ImuSink): Set<ImuSensor>

    /** Stops every sensor started by [start]; stopping a stopped port does nothing. */
    fun stop()
}
