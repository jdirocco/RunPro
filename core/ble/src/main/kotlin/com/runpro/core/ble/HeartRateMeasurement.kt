package com.runpro.core.ble

/** Skin contact as reported in the Heart Rate Measurement flags (bits 1-2). */
enum class SensorContact {
    /** The strap does not report contact (bit 2 clear); bit 1 carries no meaning then. */
    NOT_SUPPORTED,

    /** Supported but no skin contact: the heart rate value is not a measurement. */
    NOT_DETECTED,

    DETECTED
}

/**
 * Heart Rate Measurement (0x2A37) of the Heart Rate service (0x180D).
 *
 * @property heartRateBpm as sent (uint8 or uint16); not range checked, see [BleSamples].
 * @property energyExpendedKilojoules cumulative since the strap was reset, when present.
 * @property rrIntervals1024 beat-to-beat intervals in units of 1/1024 s, oldest first (exact raw values).
 */
data class HeartRateMeasurement(
    val heartRateBpm: Int,
    val sensorContact: SensorContact = SensorContact.NOT_SUPPORTED,
    val energyExpendedKilojoules: Int? = null,
    val rrIntervals1024: List<Int> = emptyList()
) : BleMeasurement {
    val rrIntervalsSeconds: List<Double> get() = rrIntervals1024.map { it / RR_UNITS_PER_SECOND }

    companion object {
        private const val FORMAT_UINT16 = 0
        private const val CONTACT_DETECTED = 1
        private const val CONTACT_SUPPORTED = 2
        private const val ENERGY_PRESENT = 3
        private const val RR_PRESENT = 4
        private const val RR_BYTES = 2
        private const val RR_UNITS_PER_SECOND = 1024.0

        /**
         * Decodes a notification. A trailing half RR interval is ignored rather than losing the heart rate;
         * reserved flag bits and bytes beyond the fields are ignored.
         */
        fun parse(value: ByteArray): ParseResult<HeartRateMeasurement> =
            decoding("Heart Rate Measurement", value) {
                val flags = uint8("flags")
                val wide = flags.hasBit(FORMAT_UINT16)
                val bpm = if (wide) uint16("heart rate") else uint8("heart rate")
                val contact = when {
                    !flags.hasBit(CONTACT_SUPPORTED) -> SensorContact.NOT_SUPPORTED
                    flags.hasBit(CONTACT_DETECTED) -> SensorContact.DETECTED
                    else -> SensorContact.NOT_DETECTED
                }
                val energy = if (flags.hasBit(ENERGY_PRESENT)) uint16("energy expended") else null
                val rr = buildList {
                    if (flags.hasBit(RR_PRESENT)) {
                        while (remaining >= RR_BYTES) add(uint16("RR interval"))
                    }
                }
                HeartRateMeasurement(bpm, contact, energy, rr)
            }
    }
}
