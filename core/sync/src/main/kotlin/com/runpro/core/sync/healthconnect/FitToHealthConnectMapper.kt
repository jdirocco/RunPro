package com.runpro.core.sync.healthconnect

import com.runpro.core.fit.FitActivity
import com.runpro.core.fit.FitEvent
import com.runpro.core.fit.FitTotals
import com.runpro.core.model.RunEvent
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong

private const val MAX_DISTANCE_METERS = 1_000_000.0
private const val MILLIS_PER_SECOND = 1_000.0
private const val TIMER_START_TYPE = "TIMER/START"
private const val TIMER_STOP_TYPE = "TIMER/STOP_ALL"

/**
 * Pure JVM mapper from a decoded [FitActivity] to a validated [HealthConnectRun].
 *
 * Every Health Connect record constraint (strict `startTime < endTime`, non-overlapping in-bounds
 * laps and segments, GPS coordinate bounds, heart rate 1..300 bpm, positive distance/steps bounds,
 * and bounded HR chunk size) is enforced here so the `:mobile` adapter stays a thin conversion.
 */
object FitToHealthConnectMapper {
    const val DEFAULT_TITLE = "RunPro Run"

    /** One hour of 1 Hz samples per `HeartRateRecord` keeps Binder IPC payloads well under limits. */
    const val MAX_HR_SAMPLES_PER_CHUNK = 3_600

    fun map(
        activity: FitActivity,
        fallbackRunKey: String,
        clientRecordVersion: Long = 1L,
        title: String = DEFAULT_TITLE,
        zoneOffsetAt: (Instant) -> ZoneOffset? = { null }
    ): HealthConnectRun {
        val session = requirePresent(activity.session) { "FIT activity has no session message" }
        val (startTime, endTime) = sessionBounds(session)
        val runKey = requirePresent(
            activity.runId?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallbackRunKey.trim().takeIf { it.isNotEmpty() }
        ) { "Run key is blank" }

        val segments = buildSegments(activity.events, startTime, endTime)
        val activeWindows = activeIntervals(segments, startTime, endTime)
        return HealthConnectRun(
            runKey = runKey,
            clientRecordVersion = clientRecordVersion,
            startTime = startTime,
            endTime = endTime,
            startZoneOffset = zoneOffsetAt(startTime),
            endZoneOffset = zoneOffsetAt(endTime),
            title = title,
            laps = buildLaps(activity.laps, startTime, endTime),
            segments = segments,
            route = buildRoute(activity.records, startTime, endTime),
            heartRateChunks = buildHeartRateChunks(
                activity.records,
                startTime,
                endTime,
                MAX_HR_SAMPLES_PER_CHUNK
            ),
            distanceMeters = resolveDistance(session, activity.records, startTime, endTime),
            stepsCount = resolveSteps(session, activity.records, activeWindows, startTime, endTime)
        )
    }
}

private inline fun <T> requirePresent(value: T?, message: () -> String): T =
    value ?: throw HealthConnectMappingException(message())

private fun sessionBounds(session: FitTotals): Pair<Instant, Instant> {
    val startMillis = requirePresent(session.startMillis) { "FIT session has no start time" }
    val startTime = Instant.ofEpochMilli(startMillis)
    val rawEndMillis = requirePresent(
        session.endMillis ?: session.elapsedSeconds
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { startMillis + (it * MILLIS_PER_SECOND).roundToLong() }
    ) { "FIT session has no end time" }
    val endTime = resolveEndTime(startMillis, startTime, rawEndMillis, session)
    return startTime to endTime
}

private fun resolveEndTime(
    startMillis: Long,
    startTime: Instant,
    rawEndMillis: Long,
    session: FitTotals
): Instant = when {
    rawEndMillis > startMillis -> Instant.ofEpochMilli(rawEndMillis)

    rawEndMillis == startMillis -> {
        val durationSeconds = session.elapsedSeconds ?: session.timerSeconds
        val bumpMillis = requirePresent(
            durationSeconds
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { (it * MILLIS_PER_SECOND).roundToLong().coerceAtLeast(1L) }
        ) { "FIT session has zero duration" }
        startTime.plusMillis(bumpMillis)
    }

    else -> throw HealthConnectMappingException("FIT session end precedes start")
}

private fun buildLaps(
    laps: List<FitTotals>,
    sessionStart: Instant,
    sessionEnd: Instant
): List<HealthConnectLap> {
    val candidates = laps.mapNotNull { lap ->
        toClampedLap(lap, sessionStart, sessionEnd)
    }.sortedWith(compareBy<HealthConnectLap> { it.startTime }.thenBy { it.endTime })

    val normalized = mutableListOf<HealthConnectLap>()
    var cursor = sessionStart
    for (lap in candidates) {
        val start = maxOf(lap.startTime, cursor)
        if (start.isBefore(lap.endTime)) {
            normalized += lap.copy(startTime = start)
            cursor = lap.endTime
        }
    }
    return normalized
}

private fun toClampedLap(
    lap: FitTotals,
    sessionStart: Instant,
    sessionEnd: Instant
): HealthConnectLap? {
    val rawStart = lap.startMillis?.let(Instant::ofEpochMilli)
    val rawEnd = lap.endMillis?.let(Instant::ofEpochMilli)
    val clampedStart = rawStart?.let { maxOf(it, sessionStart) }
    val clampedEnd = rawEnd?.let { minOf(it, sessionEnd) }
    return if (clampedStart != null && clampedEnd != null && clampedStart.isBefore(clampedEnd)) {
        val length = lap.distanceMeters
            ?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_DISTANCE_METERS }
        HealthConnectLap(clampedStart, clampedEnd, length)
    } else {
        null
    }
}

private fun buildSegments(
    events: List<FitEvent>,
    sessionStart: Instant,
    sessionEnd: Instant
): List<HealthConnectSegment> {
    val transitions = events.mapNotNull { pauseTransition(it, sessionStart, sessionEnd) }
        .sortedBy { it.first }
    if (transitions.none { it.second }) return emptyList()

    val result = mutableListOf<HealthConnectSegment>()
    var cursor = sessionStart
    var paused = false
    for ((time, nextPaused) in transitions) {
        if (nextPaused != paused) {
            if (cursor.isBefore(time)) {
                result += HealthConnectSegment(cursor, time, segmentType(paused))
                cursor = time
            }
            paused = nextPaused
        }
    }
    if (cursor.isBefore(sessionEnd)) {
        result += HealthConnectSegment(cursor, sessionEnd, segmentType(paused))
    }
    return result.takeIf { segs -> segs.any { it.type == HealthConnectSegmentType.PAUSE } }
        .orEmpty()
}

private fun pauseTransition(
    event: FitEvent,
    sessionStart: Instant,
    sessionEnd: Instant
): Pair<Instant, Boolean>? {
    val time = event.timeMillis?.let(Instant::ofEpochMilli)
        ?.takeIf { it.isAfter(sessionStart) && it.isBefore(sessionEnd) }
        ?: return null
    val paused = when (event.runEvent) {
        is RunEvent.Paused -> true

        is RunEvent.Resumed -> false

        null -> when (event.type) {
            TIMER_STOP_TYPE -> true
            TIMER_START_TYPE -> false
            else -> null
        }

        else -> null
    }
    return paused?.let { time to it }
}

private fun segmentType(paused: Boolean): HealthConnectSegmentType = if (paused) {
    HealthConnectSegmentType.PAUSE
} else {
    HealthConnectSegmentType.RUNNING
}

private fun activeIntervals(
    segments: List<HealthConnectSegment>,
    sessionStart: Instant,
    sessionEnd: Instant
): List<Pair<Instant, Instant>> = if (segments.isEmpty()) {
    listOf(sessionStart to sessionEnd)
} else {
    segments.filter { it.type == HealthConnectSegmentType.RUNNING }
        .map { it.startTime to it.endTime }
}
