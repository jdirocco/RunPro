package com.runpro.core.ble

import java.util.Locale
import java.util.UUID

/** The Bluetooth SIG assigned numbers RunPro uses, expanded on the Bluetooth base UUID. */
object GattUuids {
    val HEART_RATE_SERVICE: UUID = assigned(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = assigned(0x2A37)

    val RUNNING_SPEED_AND_CADENCE_SERVICE: UUID = assigned(0x1814)
    val RSC_MEASUREMENT: UUID = assigned(0x2A53)

    /** Stryd reports running power through the Cycling Power service. */
    val CYCLING_POWER_SERVICE: UUID = assigned(0x1818)
    val CYCLING_POWER_MEASUREMENT: UUID = assigned(0x2A63)

    val BATTERY_SERVICE: UUID = assigned(0x180F)
    val BATTERY_LEVEL: UUID = assigned(0x2A19)

    /** The descriptor written to enable notifications on a characteristic. */
    val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID = assigned(0x2902)

    /** The 128-bit UUID of a 16-bit assigned number. */
    fun assigned(number: Int): UUID {
        require(number in 0..MAX_ASSIGNED_16) { "Not a 16-bit assigned number: $number" }
        return UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(Locale.ROOT, number))
    }

    private const val MAX_ASSIGNED_16 = 0xFFFF
}

/** Picks the parser for a characteristic. */
object GattParsers {
    /** Decodes [value] read or notified from [characteristic], or null when RunPro has no parser for it. */
    fun parse(characteristic: UUID, value: ByteArray): ParseResult<BleMeasurement>? =
        when (characteristic) {
            GattUuids.HEART_RATE_MEASUREMENT -> HeartRateMeasurement.parse(value)
            GattUuids.RSC_MEASUREMENT -> RscMeasurement.parse(value)
            GattUuids.CYCLING_POWER_MEASUREMENT -> CyclingPowerMeasurement.parse(value)
            GattUuids.BATTERY_LEVEL -> BatteryLevel.parse(value)
            else -> null
        }
}
