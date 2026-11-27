package com.runpro.core.ble

/** Battery Level (0x2A19) of the Battery service (0x180F), in percent. */
data class BatteryLevel(val percent: Int) : BleMeasurement {
    init {
        require(percent in 0..MAX_PERCENT) { "Invalid battery level: $percent %" }
    }

    companion object {
        private const val MAX_PERCENT = 100

        /** Decodes a read or notification; 101-255 are reserved by the specification and malformed here. */
        fun parse(value: ByteArray): ParseResult<BatteryLevel> = decoding("Battery Level", value) {
            val level = uint8("level")
            if (level > MAX_PERCENT) throw InvalidValue("reserved value $level")
            BatteryLevel(level)
        }
    }
}
