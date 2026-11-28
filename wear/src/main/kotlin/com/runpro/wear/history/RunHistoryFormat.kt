package com.runpro.wear.history

import com.runpro.core.model.Distance
import com.runpro.core.model.Pace
import com.runpro.wear.clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

private const val METERS_PER_KILOMETER = 1000.0
private const val SECONDS_PER_MINUTE = 60

internal fun formatDistance(distance: Distance, locale: Locale = Locale.getDefault()): String =
    if (distance.meters >= METERS_PER_KILOMETER) {
        String.format(locale, "%.2f km", distance.kilometers)
    } else {
        String.format(locale, "%d m", distance.meters.roundToInt())
    }

internal fun formatPace(pace: Pace): String {
    val totalSeconds = pace.secondsPerKilometer.roundToInt()
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d /km".format(Locale.US, minutes, seconds)
}

internal fun formatDuration(duration: Duration): String = clock(duration)

internal fun formatRunDate(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String = Instant.ofEpochMilli(epochMillis)
    .atZone(zone)
    .format(DateTimeFormatter.ofPattern("d MMM HH:mm", locale))
