package com.runpro.mobile.healthconnect

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import com.runpro.core.sync.healthconnect.HealthConnectHeartRateChunk
import com.runpro.core.sync.healthconnect.HealthConnectLap
import com.runpro.core.sync.healthconnect.HealthConnectRoutePoint
import com.runpro.core.sync.healthconnect.HealthConnectRun
import com.runpro.core.sync.healthconnect.HealthConnectSegment
import com.runpro.core.sync.healthconnect.HealthConnectSegmentType

/**
 * Thin conversion from pure JVM [HealthConnectRun] to Health Connect SDK [Record] instances.
 *
 * Every record carries a stable `clientRecordId` and `clientRecordVersion` so retries and
 * replaced FIT files upsert cleanly without duplicating workouts in Health Connect.
 */
object HealthConnectRecordMapper {
    private val WATCH_DEVICE = Device(type = Device.TYPE_WATCH)

    fun toRecords(run: HealthConnectRun): List<Record> = buildList {
        add(toSessionRecord(run))
        run.heartRateChunks.forEach { chunk ->
            add(toHeartRateRecord(run, chunk))
        }
        run.distanceMeters?.let { meters ->
            add(
                DistanceRecord(
                    startTime = run.startTime,
                    startZoneOffset = run.startZoneOffset,
                    endTime = run.endTime,
                    endZoneOffset = run.endZoneOffset,
                    distance = Length.meters(meters),
                    metadata = recordMetadata(run.distanceClientRecordId, run.clientRecordVersion)
                )
            )
        }
        run.stepsCount?.let { count ->
            add(
                StepsRecord(
                    startTime = run.startTime,
                    startZoneOffset = run.startZoneOffset,
                    endTime = run.endTime,
                    endZoneOffset = run.endZoneOffset,
                    count = count,
                    metadata = recordMetadata(run.stepsClientRecordId, run.clientRecordVersion)
                )
            )
        }
    }

    private fun toSessionRecord(run: HealthConnectRun): ExerciseSessionRecord =
        ExerciseSessionRecord(
            startTime = run.startTime,
            startZoneOffset = run.startZoneOffset,
            endTime = run.endTime,
            endZoneOffset = run.endZoneOffset,
            metadata = recordMetadata(run.sessionClientRecordId, run.clientRecordVersion),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            title = run.title,
            segments = run.segments.map(::toSegment),
            laps = run.laps.map(::toLap),
            exerciseRoute = run.route.takeIf { it.isNotEmpty() }
                ?.let { ExerciseRoute(it.map(::toRouteLocation)) }
        )

    private fun toHeartRateRecord(
        run: HealthConnectRun,
        chunk: HealthConnectHeartRateChunk
    ): HeartRateRecord = HeartRateRecord(
        startTime = chunk.startTime,
        startZoneOffset = run.startZoneOffset,
        endTime = chunk.endTime,
        endZoneOffset = run.endZoneOffset,
        samples = chunk.samples.map { HeartRateRecord.Sample(it.time, it.beatsPerMinute) },
        metadata = recordMetadata(
            run.heartRateClientRecordId(chunk.index),
            run.clientRecordVersion
        )
    )

    private fun toLap(lap: HealthConnectLap): ExerciseLap = ExerciseLap(
        startTime = lap.startTime,
        endTime = lap.endTime,
        length = lap.lengthMeters?.let(Length::meters)
    )

    private fun toSegment(segment: HealthConnectSegment): ExerciseSegment = ExerciseSegment(
        startTime = segment.startTime,
        endTime = segment.endTime,
        segmentType = when (segment.type) {
            HealthConnectSegmentType.RUNNING -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING
            HealthConnectSegmentType.PAUSE -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE
        }
    )

    private fun toRouteLocation(point: HealthConnectRoutePoint): ExerciseRoute.Location =
        ExerciseRoute.Location(
            time = point.time,
            latitude = point.latitude,
            longitude = point.longitude,
            altitude = point.altitudeMeters?.let(Length::meters)
        )

    private fun recordMetadata(clientRecordId: String, version: Long): Metadata =
        Metadata.activelyRecorded(
            device = WATCH_DEVICE,
            clientRecordId = clientRecordId,
            clientRecordVersion = version
        )
}
