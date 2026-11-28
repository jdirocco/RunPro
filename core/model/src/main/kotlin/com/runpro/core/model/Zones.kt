package com.runpro.core.model

import kotlinx.serialization.Serializable

/** Reference physiology used to compute five heart-rate zones. */
@Serializable
enum class HrZoneMethod {
    MAX_HR,
    LTHR
}

/**
 * Five heart-rate training zones defined by their inclusive lower bounds in bpm.
 *
 * Readings below [boundaries][0] are zone 0 (below active training); readings at or above
 * [boundaries][4] are zone 5. Zero bpm (lost skin contact) has no zone.
 */
@Serializable
data class HeartRateZones(val boundaries: List<Int>) {
    init {
        require(boundaries.size == ZONE_COUNT) { "Expected $ZONE_COUNT HR zone boundaries" }
        require(boundaries.all { it in 1..HeartRate.MAX_BPM }) {
            "HR zone boundaries must be in 1..${HeartRate.MAX_BPM}"
        }
        require(boundaries.zipWithNext().all { (a, b) -> a < b }) {
            "HR zone boundaries must be strictly increasing"
        }
    }

    /** Zone 0..5 for a valid reading, or null when [heartRate] is 0 bpm (no skin contact). */
    fun zoneFor(heartRate: HeartRate): Int? {
        if (heartRate.bpm == 0) return null
        return boundaries.count { heartRate.bpm >= it }
    }

    /** Inclusive bpm bounds of [zone] (1..5). Zone 5 extends to [HeartRate.MAX_BPM]. */
    fun rangeFor(zone: Int): IntRange {
        require(zone in 1..ZONE_COUNT) { "Zone must be in 1..$ZONE_COUNT" }
        val low = boundaries[zone - 1]
        val high = if (zone < ZONE_COUNT) boundaries[zone] - 1 else HeartRate.MAX_BPM
        return low..high
    }

    companion object {
        const val ZONE_COUNT = 5
        private const val PERCENT_SCALE = 100
        private val MAX_HR_PERCENTS = listOf(50, 60, 70, 80, 90)
        private val LTHR_PERCENTS = listOf(65, 85, 90, 95, 100)

        /** Standard 50/60/70/80/90% of maximum heart rate, rounded up so integer % matches. */
        fun fromMaxHeartRate(maxHeartRate: Int): HeartRateZones {
            require(maxHeartRate in UserProfile.MIN_MAX_HR..UserProfile.MAX_HR) {
                "Invalid max HR: $maxHeartRate"
            }
            return HeartRateZones(MAX_HR_PERCENTS.map { ceilPercent(maxHeartRate, it) })
        }

        /** Five running zones from lactate threshold HR (65/85/90/95/100% of LTHR). */
        fun fromLactateThreshold(lthr: Int): HeartRateZones {
            require(lthr in UserProfile.MIN_LTHR..UserProfile.MAX_HR) { "Invalid LTHR: $lthr" }
            return HeartRateZones(LTHR_PERCENTS.map { ceilPercent(lthr, it) })
        }

        private fun ceilPercent(reference: Int, percent: Int): Int =
            (reference * percent + PERCENT_SCALE - 1) / PERCENT_SCALE
    }
}

/**
 * Pace bounds of a pace zone. [fastest] is the smaller seconds/km bound (null for zone 5, which has
 * no upper speed limit); [slowest] is the larger seconds/km bound (null for zone 1, which has no
 * lower speed limit).
 */
data class PaceRange(val fastest: Pace?, val slowest: Pace?)

/**
 * Five running pace zones defined by four strictly decreasing boundaries in seconds per kilometre
 * (faster pace has fewer seconds per kilometre):
 * - Zone 1: slower than `boundaries[0]`
 * - Zone 2: `(boundaries[1], boundaries[0]]`
 * - Zone 3: `(boundaries[2], boundaries[1]]`
 * - Zone 4: `(boundaries[3], boundaries[2]]`
 * - Zone 5: faster than or equal to `boundaries[3]`
 */
@Serializable
data class PaceZones(val boundariesSecondsPerKm: List<Double>) {
    init {
        require(boundariesSecondsPerKm.size == BOUNDARY_COUNT) {
            "Expected $BOUNDARY_COUNT pace zone boundaries"
        }
        require(boundariesSecondsPerKm.all { it.isFinite() && it > 0.0 }) {
            "Pace zone boundaries must be finite and positive"
        }
        require(boundariesSecondsPerKm.zipWithNext().all { (slower, faster) -> slower > faster }) {
            "Pace zone boundaries must strictly decrease from slow to fast"
        }
    }

    /** Zone 1..5 for [pace]. */
    fun zoneFor(pace: Pace): Int =
        1 + boundariesSecondsPerKm.count { pace.secondsPerKilometer <= it }

    /** Bounds of [zone] (1..5) in SI [Pace]. */
    fun rangeFor(zone: Int): PaceRange {
        require(zone in 1..ZONE_COUNT) { "Zone must be in 1..$ZONE_COUNT" }
        val fastest = if (zone < ZONE_COUNT) Pace(boundariesSecondsPerKm[zone - 1]) else null
        val slowest = if (zone > 1) Pace(boundariesSecondsPerKm[zone - 2]) else null
        return PaceRange(fastest = fastest, slowest = slowest)
    }

    companion object {
        const val ZONE_COUNT = 5
        private const val BOUNDARY_COUNT = 4
        private const val PERCENT_SCALE = 100.0
        private val THRESHOLD_PERCENTS = listOf(129, 114, 106, 99)

        /** Standard five running pace zones derived from threshold pace (129/114/106/99%). */
        fun fromThreshold(thresholdPace: Pace): PaceZones = PaceZones(
            THRESHOLD_PERCENTS.map { thresholdPace.secondsPerKilometer * it / PERCENT_SCALE }
        )
    }
}
