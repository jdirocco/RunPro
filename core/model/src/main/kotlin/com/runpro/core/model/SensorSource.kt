package com.runpro.core.model

/**
 * Receives what a [SensorSource] reports. Callbacks may arrive on any thread and must return quickly:
 * the recording path never waits (R6).
 */
interface SensorListener {
    fun onSample(sample: Sample)

    fun onEvent(event: SensorEvent)
}

/**
 * One producer of samples, identified by [id]: the Health Services adapter, a BLE sensor, the
 * accelerometer, or a replayed trace (`:tools:replay`). The contract is callback based because the
 * platform APIs behind real sources are; consumers can wrap it into whatever stream type they use.
 *
 * A source delivers to the listener given to [start] until [stop]. Starting a source that is already
 * started is an error; stopping a stopped source does nothing.
 */
interface SensorSource {
    val id: SourceId

    fun start(listener: SensorListener)

    fun stop()
}
