package com.runpro.core.sync.healthconnect

import com.runpro.core.fit.FitRecord
import com.runpro.core.fit.FitTotals
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

private const val MIN_BPM = 1
private const val MAX_BPM = 300
private const val MIN_LAT = -90.0
private const val MAX_LAT = 90.0
private const val MIN_LON = -180.0
private const val MAX_LON = 180.0
private const val MAX_DISTANCE_METERS = 1_000_000.0
private const val MAX_STEPS_COUNT = 1_000_000L
private const val MILLIS_PER_SECOND = 1_000.0
private const val SECONDS_PER_MINUTE = 60.0
private const val MAX_CADENCE_HOLD_SECONDS = 5.0

internal fun buildRoute(
    records: List<FitRecord>,
    sessionStart: Instant,
    sessionEnd: Instant
): List<HealthConnectRoutePoint> {
    val byTime = sortedMapOf<Instant, HealthConnectRoutePoint>()
    records.mapNotNull { record ->
        val rawTime = recordTimeInBounds(record, sessionStart, sessionEnd)
        // Health Connect ExerciseSessionRecord requires route timestamps strictly before endTime.
        val time = rawTime?.let { routeTimeInHalfOpenRange(it, sessionStart, sessionEnd) }
        val lat = record.latitude?.takeIf { it in MIN_LAT..MAX_LAT }
        val lon = record.longitude?.takeIf { it in MIN_LON..MAX_LON }
        if (time != null && lat != null && lon != null) {
            HealthConnectRoutePoint(time, lat, lon, record.altitudeMeters?.takeIf(Double::isFinite))
        } else {
            null
        }
    }.forEach { byTime[it.time] = it }
    return byTime.values.toList()
}

private fun routeTimeInHalfOpenRange(
    time: Instant,
    sessionStart: Instant,
    sessionEnd: Instant
): Instant? = when {
    time.isBefore(sessionEnd) -> time
    sessionEnd.minusMillis(1).let { !it.isBefore(sessionStart) } -> sessionEnd.minusMillis(1)
    else -> null
}

internal fun buildHeartRateChunks(
    records: List<FitRecord>,
    sessionStart: Instant,
    sessionEnd: Instant,
    maxSamplesPerChunk: Int
): List<HealthConnectHeartRateChunk> {
    val byTime = sortedMapOf<Instant, HealthConnectHeartRateSample>()
    records.mapNotNull { record ->
        val time = recordTimeInBounds(record, sessionStart, sessionEnd)
        val bpm = record.heartRateBpm?.takeIf { it in MIN_BPM..MAX_BPM }
        if (time != null && bpm != null) HealthConnectHeartRateSample(time, bpm.toLong()) else null
    }.forEach { byTime[it.time] = it }

    return byTime.values.chunked(maxSamplesPerChunk).mapIndexed { index, samples ->
        val (chunkStart, chunkEnd) = heartRateChunkBounds(samples, sessionStart, sessionEnd)
        HealthConnectHeartRateChunk(index, chunkStart, chunkEnd, samples)
    }
}

internal fun resolveDistance(
    session: FitTotals,
    records: List<FitRecord>,
    sessionStart: Instant,
    sessionEnd: Instant
): Double? {
    val fromSession = session.distanceMeters
        ?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_DISTANCE_METERS }
    return fromSession ?: records.mapNotNull { record ->
        val time = recordTimeInBounds(record, sessionStart, sessionEnd)
        if (time == null) {
            null
        } else {
            record.distanceMeters?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_DISTANCE_METERS }
        }
    }.maxOrNull()
}

internal fun resolveSteps(
    session: FitTotals,
    records: List<FitRecord>,
    activeWindows: List<Pair<Instant, Instant>>,
    sessionStart: Instant,
    sessionEnd: Instant
): Long? {
    val cadenceSamples = sortedMapOf<Instant, Double>()
    records.mapNotNull { record ->
        val time = recordTimeInBounds(record, sessionStart, sessionEnd)
        val cadence = record.cadenceStepsPerMinute?.takeIf { it.isFinite() && it >= 0.0 }
        if (time != null && cadence != null) time to cadence else null
    }.forEach { (time, spm) -> cadenceSamples[time] = spm }

    val estimated = if (cadenceSamples.isNotEmpty()) {
        integrateCadence(cadenceSamples.entries.toList(), activeWindows, sessionEnd)
    } else {
        stepsFromSessionSummary(session, activeWindows)
    }
    return estimated?.takeIf { it in 1L..MAX_STEPS_COUNT }
}

private fun recordTimeInBounds(
    record: FitRecord,
    sessionStart: Instant,
    sessionEnd: Instant
): Instant? = record.timeMillis?.let(Instant::ofEpochMilli)
    ?.takeIf { !it.isBefore(sessionStart) && !it.isAfter(sessionEnd) }

private fun heartRateChunkBounds(
    samples: List<HealthConnectHeartRateSample>,
    sessionStart: Instant,
    sessionEnd: Instant
): Pair<Instant, Instant> {
    val firstTime = samples.first().time
    val lastTime = samples.last().time
    return when {
        firstTime.isBefore(lastTime) -> firstTime to lastTime

        firstTime.isBefore(sessionEnd) ->
            firstTime to minOf(sessionEnd, firstTime.plusSeconds(1))

        else -> sessionStart to sessionEnd
    }
}

private fun integrateCadence(
    samples: List<Map.Entry<Instant, Double>>,
    activeWindows: List<Pair<Instant, Instant>>,
    sessionEnd: Instant
): Long? {
    var totalSteps = 0.0
    val holdMillis = (MAX_CADENCE_HOLD_SECONDS * MILLIS_PER_SECOND).toLong()
    for (i in samples.indices) {
        val (time, spm) = samples[i]
        val nextTime = if (i + 1 < samples.size) samples[i + 1].key else sessionEnd
        val intervalEnd = minOf(nextTime, time.plusMillis(holdMillis), sessionEnd)
        if (spm > 0.0 && time.isBefore(intervalEnd)) {
            val activeSeconds = overlapSeconds(time, intervalEnd, activeWindows)
            totalSteps += spm * (activeSeconds / SECONDS_PER_MINUTE)
        }
    }
    return totalSteps.roundToLong().takeIf { it > 0L }
}

private fun stepsFromSessionSummary(
    session: FitTotals,
    activeWindows: List<Pair<Instant, Instant>>
): Long? {
    val avgSpm = session.averageCadenceStepsPerMinute?.takeIf { it.isFinite() && it > 0.0 }
        ?: return null
    val activeSeconds = session.movingSeconds?.takeIf { it.isFinite() && it > 0.0 }
        ?: session.timerSeconds?.takeIf { it.isFinite() && it > 0.0 }
        ?: activeWindows.sumOf { (s, e) ->
            Duration.between(s, e).toMillis() / MILLIS_PER_SECOND
        }
    return (avgSpm * (activeSeconds / SECONDS_PER_MINUTE)).roundToLong().takeIf { it > 0L }
}

private fun overlapSeconds(
    start: Instant,
    end: Instant,
    windows: List<Pair<Instant, Instant>>
): Double {
    var millis = 0L
    for ((windowStart, windowEnd) in windows) {
        val overlapStart = maxOf(start, windowStart)
        val overlapEnd = minOf(end, windowEnd)
        if (overlapStart.isBefore(overlapEnd)) {
            millis += Duration.between(overlapStart, overlapEnd).toMillis()
        }
    }
    return millis / MILLIS_PER_SECOND
}
