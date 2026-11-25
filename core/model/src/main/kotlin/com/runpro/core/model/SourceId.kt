package com.runpro.core.model

import kotlinx.serialization.Serializable

/**
 * Identifies where a sample came from, e.g. `ble:AA:BB:CC:DD:EE:FF` for a strap or `watch:health-services`.
 * Source arbitration and source-switch events (R5) refer to sources by this id, so it must be stable across
 * runs for the same physical sensor.
 */
@Serializable
@JvmInline
value class SourceId(val value: String) {
    init {
        val valid = value.isNotEmpty() && value.none(Char::isWhitespace)
        require(valid) { "Invalid source id: '$value'" }
    }

    val isBle: Boolean get() = value.startsWith(BLE_PREFIX)

    /** The Bluetooth device address for BLE sources, otherwise null. */
    val bleAddress: String? get() = if (isBle) value.removePrefix(BLE_PREFIX) else null

    override fun toString(): String = value

    companion object {
        private const val BLE_PREFIX = "ble:"
        private val BLE_ADDRESS = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

        /** Everything Health Services reports: wrist HR, GPS location, distance, speed, steps cadence, elevation. */
        val HEALTH_SERVICES = SourceId("watch:health-services")

        /** The raw watch accelerometer. */
        val WATCH_IMU = SourceId("watch:imu")

        /** Metrics computed by RunPro itself from other samples. */
        val DERIVED = SourceId("derived")

        /** A BLE sensor, identified by its device address (any case, normalised to upper case). */
        fun ble(address: String): SourceId {
            val normalised = address.uppercase()
            require(BLE_ADDRESS.matches(normalised)) { "Invalid BLE address: '$address'" }
            return SourceId(BLE_PREFIX + normalised)
        }
    }
}
