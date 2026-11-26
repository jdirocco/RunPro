package com.runpro.core.ble

/** Whether the pedal power balance refers to the left pedal (flag bit 1) or is of unknown reference. */
enum class BalanceReference { UNKNOWN, LEFT }

/** Whether the accumulated torque is measured at the wheel or the crank (flag bit 3). */
enum class TorqueSource { WHEEL, CRANK }

/** Pedal power balance in percent (raw unit 1/2 %). */
data class PedalPowerBalance(val percent: Double, val reference: BalanceReference)

/** Accumulated torque in 1/32 Nm, a rolling uint16 counter. */
data class AccumulatedTorque(val torque32: Int, val source: TorqueSource) {
    val newtonMeters: Double get() = torque32 / TORQUE_UNITS_PER_NM
}

/** Cumulative wheel revolutions (uint32) and the last wheel event time in 1/2048 s (rolling uint16). */
data class WheelRevolutions(val cumulative: Long, val lastEventTime2048: Int)

/** Cumulative crank revolutions (rolling uint16) and the last crank event time in 1/1024 s (rolling uint16). */
data class CrankRevolutions(val cumulative: Int, val lastEventTime1024: Int)

/** A maximum and minimum pair, in the unit named by the property holding it. */
data class ExtremeValues(val maximum: Int, val minimum: Int)

internal const val TORQUE_UNITS_PER_NM = 32.0

/**
 * Cycling Power Measurement (0x2A63) of the Cycling Power service (0x1818). Stryd sends running power
 * here, usually as instantaneous power only. Counters and event times are raw so consumers can handle
 * their roll-over.
 *
 * @property instantaneousPowerWatts signed as in the specification; see [BleSamples] for negative values.
 * @property extremeTorque32 maximum and minimum torque in 1/32 Nm.
 */
data class CyclingPowerMeasurement(
    val instantaneousPowerWatts: Int,
    val pedalPowerBalance: PedalPowerBalance? = null,
    val accumulatedTorque: AccumulatedTorque? = null,
    val wheelRevolutions: WheelRevolutions? = null,
    val crankRevolutions: CrankRevolutions? = null,
    val extremeForceNewtons: ExtremeValues? = null,
    val extremeTorque32: ExtremeValues? = null,
    val extremeAnglesDegrees: ExtremeValues? = null,
    val topDeadSpotAngleDegrees: Int? = null,
    val bottomDeadSpotAngleDegrees: Int? = null,
    val accumulatedEnergyKilojoules: Int? = null,
    val offsetCompensation: Boolean = false
) : BleMeasurement {
    companion object {
        private const val BALANCE_PRESENT = 0
        private const val BALANCE_LEFT = 1
        private const val TORQUE_PRESENT = 2
        private const val TORQUE_CRANK = 3
        private const val WHEEL_PRESENT = 4
        private const val CRANK_PRESENT = 5
        private const val FORCE_PRESENT = 6
        private const val EXTREME_TORQUE_PRESENT = 7
        private const val ANGLES_PRESENT = 8
        private const val TOP_DEAD_SPOT_PRESENT = 9
        private const val BOTTOM_DEAD_SPOT_PRESENT = 10
        private const val ENERGY_PRESENT = 11
        private const val OFFSET_COMPENSATION = 12
        private const val BALANCE_UNITS_PER_PERCENT = 2.0
        private const val ANGLE_BITS = 12
        private const val ANGLE_MASK = 0xFFF

        /**
         * Decodes a notification. Fields follow the flag order of the specification; reserved flag bits
         * and bytes beyond the fields are ignored.
         */
        fun parse(value: ByteArray): ParseResult<CyclingPowerMeasurement> =
            decoding("Cycling Power Measurement", value) {
                val flags = uint16("flags")
                val power = sint16("instantaneous power")
                val balance = optional(flags, BALANCE_PRESENT) { pedalPowerBalance(flags) }
                val torque = optional(flags, TORQUE_PRESENT) { accumulatedTorque(flags) }
                val wheel = optional(flags, WHEEL_PRESENT) {
                    WheelRevolutions(uint32("wheel revolutions"), uint16("last wheel event time"))
                }
                val crank = optional(flags, CRANK_PRESENT) {
                    CrankRevolutions(uint16("crank revolutions"), uint16("last crank event time"))
                }
                val force = optional(flags, FORCE_PRESENT) {
                    ExtremeValues(sint16("maximum force"), sint16("minimum force"))
                }
                val extremeTorque = optional(flags, EXTREME_TORQUE_PRESENT) {
                    ExtremeValues(sint16("maximum torque"), sint16("minimum torque"))
                }
                val angles = optional(flags, ANGLES_PRESENT) { extremeAngles() }
                val topDeadSpot =
                    optional(flags, TOP_DEAD_SPOT_PRESENT) { uint16("top dead spot angle") }
                val bottomDeadSpot =
                    optional(flags, BOTTOM_DEAD_SPOT_PRESENT) { uint16("bottom dead spot angle") }
                val energy = optional(flags, ENERGY_PRESENT) { uint16("accumulated energy") }
                CyclingPowerMeasurement(
                    power, balance, torque, wheel, crank, force, extremeTorque, angles,
                    topDeadSpot, bottomDeadSpot, energy, flags.hasBit(OFFSET_COMPENSATION)
                )
            }

        private inline fun <T> optional(flags: Int, bit: Int, read: () -> T): T? =
            if (flags.hasBit(bit)) read() else null

        private fun ByteReader.pedalPowerBalance(flags: Int): PedalPowerBalance {
            val left = flags.hasBit(BALANCE_LEFT)
            val reference = if (left) BalanceReference.LEFT else BalanceReference.UNKNOWN
            return PedalPowerBalance(
                uint8("pedal power balance") / BALANCE_UNITS_PER_PERCENT,
                reference
            )
        }

        private fun ByteReader.accumulatedTorque(flags: Int): AccumulatedTorque {
            val source = if (flags.hasBit(TORQUE_CRANK)) TorqueSource.CRANK else TorqueSource.WHEEL
            return AccumulatedTorque(uint16("accumulated torque"), source)
        }

        /** Two uint12 in three bytes: maximum in the low 12 bits, minimum in the high 12 bits. */
        private fun ByteReader.extremeAngles(): ExtremeValues {
            val packed = uint24("extreme angles")
            return ExtremeValues(packed and ANGLE_MASK, packed shr ANGLE_BITS)
        }
    }
}
