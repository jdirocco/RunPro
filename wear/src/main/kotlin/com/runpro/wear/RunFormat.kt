package com.runpro.wear

import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceUnit
import com.runpro.core.model.HeartRate
import com.runpro.core.model.Pace
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Shown for a metric without a value (no GPS yet, no heart rate). */
internal const val NO_VALUE = "--"

private const val SECONDS_PER_MINUTE = 60
private const val HUNDREDTHS = 100.0
private const val MILLIS_PER_SECOND = 1000.0
private val SLOWEST_PACE = 30.minutes

/** `h:mm:ss` or `m:ss`. */
internal fun clock(duration: Duration): String {
    val total = duration.inWholeSeconds
    val hours = total / (SECONDS_PER_MINUTE * SECONDS_PER_MINUTE)
    val minutes = total / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Kilometres with two decimals, truncated: 4.999 km is not shown as 5.00 before it is reached. */
internal fun kilometers(distance: Distance, locale: Locale = Locale.getDefault()): String =
    "%.2f".format(locale, floor(distance.kilometers * HUNDREDTHS) / HUNDREDTHS)

/** `m:ss` per kilometre; [NO_VALUE] when there is no pace or it is slower than a walk. */
internal fun pace(pace: Pace?, unit: DistanceUnit = DistanceUnit.KILOMETER): String {
    pace?.per(DistanceUnit.KILOMETER)?.takeIf { it <= SLOWEST_PACE }
        ?: return NO_VALUE
    val perUnit = requireNotNull(pace).per(unit)
    val total = (perUnit.inWholeMilliseconds / MILLIS_PER_SECOND).roundToLong()
    return "%d:%02d".format(total / SECONDS_PER_MINUTE, total % SECONDS_PER_MINUTE)
}

internal fun heartRate(heartRate: HeartRate?): String = heartRate?.bpm?.toString() ?: NO_VALUE

internal fun displayDistance(distance: Distance, unit: DistanceUnit): String =
    "%.2f".format(Locale.getDefault(), floor(distance.inUnit(unit) * HUNDREDTHS) / HUNDREDTHS)
