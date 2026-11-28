package com.runpro.mobile.healthconnect

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.runpro.core.fit.FitDecoder
import com.runpro.core.fit.FitExporter
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Elevation
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.LocationSample
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.SourceId
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp
import com.runpro.core.sync.healthconnect.FitToHealthConnectMapper
import com.runpro.core.sync.healthconnect.HealthConnectRun
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectRecordMapperTest {
    @Test
    fun `maps exported FIT file into valid Health Connect SDK records`() {
        val tempDir = Files.createTempDirectory("hc-mapper-test")
        try {
            val journal = tempDir.resolve("run.journal")
            val fit = tempDir.resolve("run.fit")
            writeFullRunJournal(journal)
            FitExporter.export(journal, fit)
            val activity = FitDecoder.decode(Files.readAllBytes(fit))
            val offset = ZoneOffset.ofHours(2)

            val mapped = FitToHealthConnectMapper.map(
                activity = activity,
                fallbackRunKey = "fallback",
                clientRecordVersion = 3L,
                zoneOffsetAt = { offset }
            )
            val records = HealthConnectRecordMapper.toRecords(mapped)

            assertEquals(4, records.size)
            val session = records.filterIsInstance<ExerciseSessionRecord>().single()
            assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, session.exerciseType)
            assertEquals(offset, session.startZoneOffset)
            assertEquals(offset, session.endZoneOffset)
            assertEquals("runpro:hc-run-1:session", session.metadata.clientRecordId)
            assertEquals(3L, session.metadata.clientRecordVersion)
            assertEquals(
                Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                session.metadata.recordingMethod
            )
            assertEquals(Device.TYPE_WATCH, session.metadata.device?.type)
            assertEquals(2, session.laps.size)
            assertEquals(3, session.segments.size)
            assertEquals(
                listOf(
                    ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING,
                    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                    ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING
                ),
                session.segments.map { it.segmentType }
            )
            val routeResult = session.exerciseRouteResult
            assertTrue(routeResult is ExerciseRouteResult.Data)
            val route = (routeResult as ExerciseRouteResult.Data).exerciseRoute.route
            assertEquals(61, route.size)
            assertEquals(120.0, route.first().altitude!!.inMeters, 0.5)

            val hr = records.filterIsInstance<HeartRateRecord>().single()
            assertEquals("runpro:hc-run-1:hr", hr.metadata.clientRecordId)
            assertEquals(61, hr.samples.size)

            val distance = records.filterIsInstance<DistanceRecord>().single()
            assertEquals("runpro:hc-run-1:distance", distance.metadata.clientRecordId)
            assertEquals(150.0, distance.distance.inMeters, 1.0)

            val steps = records.filterIsInstance<StepsRecord>().single()
            assertEquals("runpro:hc-run-1:steps", steps.metadata.clientRecordId)
            assertTrue(steps.count in 140L..160L)

            assertEquals(
                HealthConnectPermissions.ALL_WRITE_PERMISSIONS,
                HealthConnectPermissions.requiredFor(mapped)
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `maps minimal indoor run to ExerciseSessionRecord only`() {
        val start = Instant.ofEpochMilli(1_764_064_800_000L)
        val end = start.plusSeconds(600)
        val indoor = HealthConnectRun(
            runKey = "indoor-1",
            clientRecordVersion = 1L,
            startTime = start,
            endTime = end,
            startZoneOffset = ZoneOffset.UTC,
            endZoneOffset = ZoneOffset.UTC,
            title = "RunPro Run",
            laps = emptyList(),
            segments = emptyList(),
            route = emptyList(),
            heartRateChunks = emptyList(),
            distanceMeters = null,
            stepsCount = null
        )

        val records = HealthConnectRecordMapper.toRecords(indoor)

        assertEquals(1, records.size)
        val session = records.single() as ExerciseSessionRecord
        assertTrue(session.exerciseRouteResult is ExerciseRouteResult.NoData)
        assertEquals(
            setOf(HealthConnectPermissions.WRITE_EXERCISE),
            HealthConnectPermissions.requiredFor(indoor)
        )
    }

    private fun writeFullRunJournal(journal: java.nio.file.Path) {
        val originNanos = 100_000_000_000L
        val originWall = 1_764_064_800_000L
        fun ts(sec: Int) = Timestamp(
            elapsedRealtimeNanos = originNanos + sec * 1_000_000_000L,
            wallClockMillis = originWall + sec * 1_000L
        )
        JournalWriter.create(journal).use { writer ->
            writer.append(JournalRecord.RunStarted(RunId("hc-run-1"), ts(0)))
            for (s in 0..60) {
                if (s == 20) {
                    writer.append(JournalRecord.Event(RunEvent.Paused(ts(20), PauseReason.MANUAL)))
                }
                if (s == 30) {
                    writer.append(JournalRecord.Event(RunEvent.Resumed(ts(30))))
                    writer.append(
                        JournalRecord.Event(RunEvent.LapMarked(ts(30), LapTrigger.MANUAL))
                    )
                }
                val activeSec = when {
                    s < 20 -> s
                    s < 30 -> 20
                    else -> s - 10
                }
                val cadence = if (s in 20 until 30) 0.0 else 180.0
                writer.append(
                    JournalRecord.Samples(
                        listOf(
                            HeartRateSample(
                                ts(s),
                                SourceId.HEALTH_SERVICES,
                                HeartRate(140 + s / 2)
                            ),
                            DistanceSample(
                                ts(s),
                                SourceId.HEALTH_SERVICES,
                                Distance(activeSec * 3.0)
                            ),
                            CadenceSample(ts(s), SourceId.HEALTH_SERVICES, Cadence(cadence)),
                            LocationSample(
                                ts(s),
                                SourceId.HEALTH_SERVICES,
                                latitude = 45.4642 + s * 1e-5,
                                longitude = 9.1900 + s * 1e-5,
                                horizontalAccuracy = Distance(4.0),
                                altitude = Elevation(120.0 + s * 0.1)
                            )
                        )
                    )
                )
            }
            writer.append(JournalRecord.Event(RunEvent.Stopped(ts(60), StopReason.USER)))
        }
    }
}
