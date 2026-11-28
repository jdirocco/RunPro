package com.runpro.core.recording.imu

import com.runpro.core.model.ImuSensor
import com.runpro.core.model.RunId

/** What the IMU capture is doing. */
enum class ImuCaptureState {
    /** No run is recording (or the capture was closed). */
    IDLE,

    /** Sensors on, batches going to the IMU stream. */
    CAPTURING,

    /** The run is paused: sensors off, stream open. */
    PAUSED,

    /** The run records no IMU: the mode is off or a [ImuProblem] ended the capture for this run. */
    OFF
}

/** Why the capture ended early for the current run. It is not retried until the next run. */
enum class ImuProblem {
    /** The watch has none of the requested sensors. */
    NO_SENSORS,

    /** The platform refused to start the sensors, or a callback failed. */
    SENSOR_FAILED,

    /** Free space fell below [ImuCaptureConfig.minFreeBytes]: the run journal gets the space. */
    LOW_STORAGE,

    /** The battery warning is on: the IMU must not shorten the run. */
    LOW_BATTERY,

    /** Creating or writing the IMU stream failed. */
    STORAGE_FAILED
}

/**
 * Published by [ImuCapture] on every change of state and after every written batch (about 1 Hz).
 *
 * @property readings readings written for [runId] so far, per sensor.
 * @property dropped readings dropped (out of order or not finite) for [runId].
 * @property segment file name of the IMU stream segment being written, or the last one.
 * @property error the last exception message, for the log.
 */
data class ImuStatus(
    val state: ImuCaptureState = ImuCaptureState.IDLE,
    val runId: RunId? = null,
    val mode: ImuMode = ImuMode.OFF,
    val sensors: Set<ImuSensor> = emptySet(),
    val readings: Map<ImuSensor, Long> = emptyMap(),
    val dropped: Long = 0,
    val segment: String? = null,
    val problem: ImuProblem? = null,
    val error: String? = null
)
