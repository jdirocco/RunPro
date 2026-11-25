package com.runpro.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A change in the state of a sensor, reported by its adapter next to its readings ([Sample]s).
 * These are inputs to the recording engine; the engine's decisions about them are [RunEvent]s.
 *
 * Type names must stay distinct from the [Sample] type names: replay trace lines are told apart by them.
 */
@Serializable
sealed interface SensorEvent {
    val time: Timestamp
    val source: SourceId

    /** A BLE sensor connected and is (again) subscribed to notifications. */
    @Serializable
    @SerialName("sensor_connected")
    data class Connected(override val time: Timestamp, override val source: SourceId) : SensorEvent

    /**
     * A BLE sensor disconnected. [gattStatus] is the Android GATT status code when known
     * (e.g. 8 = connection timeout, 133 = the generic `GATT_ERROR`).
     */
    @Serializable
    @SerialName("sensor_disconnected")
    data class Disconnected(
        override val time: Timestamp,
        override val source: SourceId,
        val gattStatus: Int? = null
    ) : SensorEvent

    /** The location provider lost its fix; location samples stop until [GpsAcquired]. */
    @Serializable
    @SerialName("gps_lost")
    data class GpsLost(override val time: Timestamp, override val source: SourceId) : SensorEvent

    /** The location provider has a usable fix. */
    @Serializable
    @SerialName("gps_acquired")
    data class GpsAcquired(override val time: Timestamp, override val source: SourceId) :
        SensorEvent
}
