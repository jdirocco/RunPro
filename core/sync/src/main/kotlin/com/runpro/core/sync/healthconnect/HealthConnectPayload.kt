package com.runpro.core.sync.healthconnect

import java.time.Instant
import java.time.ZoneOffset

/** The FIT activity cannot be mapped to a valid Health Connect running session. */
class HealthConnectMappingException(message: String) : Exception(message)

/**
 * Pure JVM representation of a RunPro run ready to be written to Health Connect: one running
 * `ExerciseSessionRecord` (with optional laps, active/pause segments and GPS route), optional
 * chunked `HeartRateRecord` series, optional `DistanceRecord`, and optional `StepsRecord`.
 */
data class HealthConnectRun(
    val runKey: String,
    val clientRecordVersion: Long,
    val startTime: Instant,
    val endTime: Instant,
    val startZoneOffset: ZoneOffset?,
    val endZoneOffset: ZoneOffset?,
    val title: String,
    val laps: List<HealthConnectLap>,
    val segments: List<HealthConnectSegment>,
    val route: List<HealthConnectRoutePoint>,
    val heartRateChunks: List<HealthConnectHeartRateChunk>,
    val distanceMeters: Double?,
    val stepsCount: Long?
) {
    /** Stable client record id for `ExerciseSessionRecord`. */
    val sessionClientRecordId: String get() = "runpro:$runKey:session"

    /** Stable client record id for `DistanceRecord`. */
    val distanceClientRecordId: String get() = "runpro:$runKey:distance"

    /** Stable client record id for `StepsRecord`. */
    val stepsClientRecordId: String get() = "runpro:$runKey:steps"

    /** Stable client record id for each `HeartRateRecord` chunk. */
    fun heartRateClientRecordId(chunkIndex: Int): String =
        if (chunkIndex == 0) "runpro:$runKey:hr" else "runpro:$runKey:hr:$chunkIndex"
}

/** One lap inside the Health Connect running session (`ExerciseLap`). */
data class HealthConnectLap(val startTime: Instant, val endTime: Instant, val lengthMeters: Double?)

/** Segment kind within a running session (`ExerciseSegment`). */
enum class HealthConnectSegmentType {
    RUNNING,
    PAUSE
}

/** One active or paused interval inside the Health Connect running session (`ExerciseSegment`). */
data class HealthConnectSegment(
    val startTime: Instant,
    val endTime: Instant,
    val type: HealthConnectSegmentType
)

/** One GPS point inside `ExerciseRoute`. */
data class HealthConnectRoutePoint(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?
)

/** One sample inside `HeartRateRecord`. */
data class HealthConnectHeartRateSample(val time: Instant, val beatsPerMinute: Long)

/** One bounded `HeartRateRecord` chunk (all samples lie within `[startTime, endTime]`). */
data class HealthConnectHeartRateChunk(
    val index: Int,
    val startTime: Instant,
    val endTime: Instant,
    val samples: List<HealthConnectHeartRateSample>
)
