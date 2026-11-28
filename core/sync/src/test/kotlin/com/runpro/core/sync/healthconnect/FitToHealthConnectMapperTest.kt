package com.runpro.core.sync.healthconnect

import com.runpro.core.fit.FitActivity
import com.runpro.core.fit.FitDecoder
import com.runpro.core.fit.FitEvent
import com.runpro.core.fit.FitExporter
import com.runpro.core.fit.FitRecord
import com.runpro.core.fit.FitTotals
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
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
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingSession
import com.runpro.tools.replay.Trace
import com.runpro.tools.replay.TraceRecord
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class FitToHealthConnectMapperTest {
    @Test
    fun `maps exported easy-run FIT file with route, HR, distance, steps and laps`(
        @TempDir temp: Path
    ) {
        val trace = Trace.read(tracesDir().resolve("easy-run-30min.jsonl.gz"))
        val journal = temp.resolve("easy.journal")
        val fit = temp.resolve("easy.fit")
        recordEasyRun(trace, journal)
        FitExporter.export(journal, fit)
        val activity = FitDecoder.decode(Files.readAllBytes(fit))

        val offset = ZoneOffset.ofHours(1)
        val mapped = FitToHealthConnectMapper.map(
            activity = activity,
            fallbackRunKey = "fallback",
            zoneOffsetAt = { offset }
        )

        assertEquals("easy-30min", mapped.runKey)
        assertEquals("runpro:easy-30min:session", mapped.sessionClientRecordId)
        assertEquals("runpro:easy-30min:distance", mapped.distanceClientRecordId)
        assertEquals("runpro:easy-30min:steps", mapped.stepsClientRecordId)
        assertEquals("runpro:easy-30min:hr", mapped.heartRateClientRecordId(0))
        assertEquals(offset, mapped.startZoneOffset)
        assertEquals(offset, mapped.endZoneOffset)
        assertTrue(mapped.startTime.isBefore(mapped.endTime))

        assertEquals(3, mapped.laps.size)
        for (lap in mapped.laps) {
            assertTrue(lap.startTime.isBefore(lap.endTime))
            assertTrue(!lap.startTime.isBefore(mapped.startTime))
            assertTrue(!lap.endTime.isAfter(mapped.endTime))
            assertTrue((lap.lengthMeters ?: 0.0) > 1000.0)
        }
        assertTrue(mapped.segments.isEmpty())

        assertTrue(mapped.route.size > 1000)
        assertTrue(mapped.route.zipWithNext().all { (a, b) -> a.time.isBefore(b.time) })
        assertTrue(
            mapped.route.all {
                !it.time.isBefore(mapped.startTime) && it.time.isBefore(mapped.endTime)
            }
        )

        assertEquals(1, mapped.heartRateChunks.size)
        val hrChunk = mapped.heartRateChunks.single()
        assertTrue(hrChunk.samples.size > 1000)
        assertTrue(hrChunk.startTime.isBefore(hrChunk.endTime))
        assertTrue(hrChunk.samples.all { it.beatsPerMinute in 1L..300L })

        val distance = requireNotNull(mapped.distanceMeters)
        val sessionDistance = requireNotNull(activity.session?.distanceMeters)
        assertEquals(sessionDistance, distance)
        assertTrue(distance > 4_000.0, "Expected > 4 km for 30-min easy run, got $distance")

        val steps = requireNotNull(mapped.stepsCount)
        assertTrue(steps in 4_000L..6_000L, "Expected realistic 30-min running steps, got $steps")
    }

    @Test
    fun `maps pauses into running and pause segments and excludes paused time from steps`(
        @TempDir temp: Path
    ) {
        val journal = temp.resolve("paused.journal")
        val fit = temp.resolve("paused.fit")
        val originNanos = 100_000_000_000L
        val originWall = 1_764_064_800_000L
        fun ts(sec: Int) = Timestamp(
            elapsedRealtimeNanos = originNanos + sec * 1_000_000_000L,
            wallClockMillis = originWall + sec * 1_000L
        )
        JournalWriter.create(journal).use { writer ->
            writer.append(JournalRecord.RunStarted(RunId("paused-run"), ts(0)))
            for (s in 0..120) {
                if (s == 30) {
                    writer.append(JournalRecord.Event(RunEvent.Paused(ts(30), PauseReason.MANUAL)))
                }
                if (s == 90) {
                    writer.append(JournalRecord.Event(RunEvent.Resumed(ts(90))))
                }
                val active = s < 30 || s >= 90
                val dist = when {
                    s < 30 -> s * 3.0
                    s < 90 -> 30 * 3.0
                    else -> (30 + (s - 90)) * 3.0
                }
                writer.append(
                    JournalRecord.Samples(
                        listOf(
                            HeartRateSample(ts(s), SourceId.HEALTH_SERVICES, HeartRate(145)),
                            DistanceSample(ts(s), SourceId.HEALTH_SERVICES, Distance(dist)),
                            CadenceSample(
                                ts(s),
                                SourceId.HEALTH_SERVICES,
                                Cadence(if (active) 180.0 else 0.0)
                            ),
                            LocationSample(
                                ts(s),
                                SourceId.HEALTH_SERVICES,
                                latitude = 45.4642 + s * 1e-5,
                                longitude = 9.19 + s * 1e-5,
                                horizontalAccuracy = Distance(4.0)
                            )
                        )
                    )
                )
            }
            writer.append(JournalRecord.Event(RunEvent.Stopped(ts(120), StopReason.USER)))
        }
        FitExporter.export(journal, fit)
        val activity = FitDecoder.decode(Files.readAllBytes(fit))

        val mapped = FitToHealthConnectMapper.map(activity, fallbackRunKey = "fallback")

        assertEquals(3, mapped.segments.size)
        assertEquals(HealthConnectSegmentType.RUNNING, mapped.segments[0].type)
        assertEquals(Instant.ofEpochMilli(originWall), mapped.segments[0].startTime)
        assertEquals(Instant.ofEpochMilli(originWall + 30_000L), mapped.segments[0].endTime)
        assertEquals(HealthConnectSegmentType.PAUSE, mapped.segments[1].type)
        assertEquals(Instant.ofEpochMilli(originWall + 30_000L), mapped.segments[1].startTime)
        assertEquals(Instant.ofEpochMilli(originWall + 90_000L), mapped.segments[1].endTime)
        assertEquals(HealthConnectSegmentType.RUNNING, mapped.segments[2].type)
        assertEquals(Instant.ofEpochMilli(originWall + 90_000L), mapped.segments[2].startTime)
        assertEquals(Instant.ofEpochMilli(originWall + 120_000L), mapped.segments[2].endTime)

        // 60 active seconds at 180 spm = 180 steps (the 60 paused seconds must add 0).
        val steps = requireNotNull(mapped.stepsCount)
        assertTrue(abs(steps - 180L) <= 6L, "Expected ~180 active steps, got $steps")
    }

    @Test
    fun `omits route, HR, distance and steps for minimal indoor run without sensor data`() {
        val activity = fitActivity(
            runId = null,
            startMillis = 1_764_064_800_000L,
            endMillis = 1_764_065_400_000L,
            distanceMeters = 0.0
        )

        val mapped = FitToHealthConnectMapper.map(activity, fallbackRunKey = "indoor-1")

        assertEquals("indoor-1", mapped.runKey)
        assertTrue(mapped.route.isEmpty())
        assertTrue(mapped.heartRateChunks.isEmpty())
        assertNull(mapped.distanceMeters)
        assertNull(mapped.stepsCount)
        assertTrue(mapped.laps.isEmpty())
        assertTrue(mapped.segments.isEmpty())
    }

    @Test
    fun `drops invalid coordinates, out-of-range heart rates, and out-of-window records`() {
        val start = 1_764_064_800_000L
        val end = start + 60_000L
        val activity = fitActivity(
            runId = "sanitize",
            startMillis = start,
            endMillis = end,
            records = listOf(
                // Before start: must be dropped
                FitRecord(start - 5_000L, 140, 45.0, 9.0, 100.0, 0.0, 3.0, 170.0, null),
                // Invalid HR (0 and 301) and invalid lat/lon
                FitRecord(start + 1_000L, 0, 95.0, 9.0, Double.NaN, null, null, null, null),
                FitRecord(start + 2_000L, 301, 45.0, -190.0, null, null, null, null, null),
                // Valid point, followed by duplicate timestamp with updated values
                FitRecord(start + 10_000L, 140, 45.1, 9.1, 120.0, 30.0, 3.0, null, null),
                FitRecord(start + 10_000L, 142, 45.2, 9.2, 125.0, 32.0, 3.1, null, null),
                // After end: must be dropped
                FitRecord(end + 5_000L, 150, 45.3, 9.3, 130.0, 200.0, 3.0, 170.0, null)
            )
        )

        val mapped = FitToHealthConnectMapper.map(activity, fallbackRunKey = "fallback")

        assertEquals(1, mapped.route.size)
        assertEquals(45.2, mapped.route.single().latitude)
        assertEquals(9.2, mapped.route.single().longitude)
        assertEquals(125.0, mapped.route.single().altitudeMeters)

        assertEquals(1, mapped.heartRateChunks.size)
        val chunk = mapped.heartRateChunks.single()
        assertEquals(1, chunk.samples.size)
        assertEquals(142L, chunk.samples.single().beatsPerMinute)
        assertTrue(chunk.startTime.isBefore(chunk.endTime))
        assertTrue(!chunk.samples.single().time.isBefore(chunk.startTime))
        assertTrue(!chunk.samples.single().time.isAfter(chunk.endTime))

        // Distance falls back to max valid record distance when session distance is absent.
        assertEquals(32.0, mapped.distanceMeters)
    }

    @Test
    fun `caps cadence hold during sensor dropout and falls back to session average cadence`() {
        val start = 1_764_064_800_000L
        val end = start + 600_000L // 10 min

        // Two cadence samples 5 minutes apart: each can hold at most 5 s -> 10 s * 3 steps/s = 30 steps.
        val dropoutActivity = fitActivity(
            runId = "dropout",
            startMillis = start,
            endMillis = end,
            records = listOf(
                FitRecord(start, null, null, null, null, null, null, 180.0, null),
                FitRecord(start + 300_000L, null, null, null, null, null, null, 180.0, null)
            )
        )
        val dropoutMapped = FitToHealthConnectMapper.map(dropoutActivity, fallbackRunKey = "x")
        assertEquals(30L, dropoutMapped.stepsCount)

        // No cadence records, only session average cadence -> 170 spm * 10 min = 1700 steps.
        val summaryOnly = fitActivity(
            runId = "summary",
            startMillis = start,
            endMillis = end,
            timerSeconds = 600.0,
            averageCadenceStepsPerMinute = 170.0
        )
        val summaryMapped = FitToHealthConnectMapper.map(summaryOnly, fallbackRunKey = "x")
        assertEquals(1_700L, summaryMapped.stepsCount)
    }

    @Test
    fun `chunks long heart rate series cleanly with distinct client record ids`() {
        val start = 1_764_064_800_000L
        val totalSeconds = FitToHealthConnectMapper.MAX_HR_SAMPLES_PER_CHUNK + 500
        val records = (0 until totalSeconds).map { s ->
            FitRecord(
                timeMillis = start + s * 1_000L,
                heartRateBpm = 140 + (s % 20),
                latitude = null,
                longitude = null,
                altitudeMeters = null,
                distanceMeters = null,
                speedMetersPerSecond = null,
                cadenceStepsPerMinute = null,
                powerWatts = null
            )
        }
        val activity = fitActivity(
            runId = "marathon",
            startMillis = start,
            endMillis = start + totalSeconds * 1_000L,
            records = records
        )

        val mapped = FitToHealthConnectMapper.map(activity, fallbackRunKey = "fallback")

        assertEquals(2, mapped.heartRateChunks.size)
        assertEquals(
            FitToHealthConnectMapper.MAX_HR_SAMPLES_PER_CHUNK,
            mapped.heartRateChunks[0].samples.size
        )
        assertEquals(500, mapped.heartRateChunks[1].samples.size)
        assertEquals("runpro:marathon:hr", mapped.heartRateClientRecordId(0))
        assertEquals("runpro:marathon:hr:1", mapped.heartRateClientRecordId(1))
        for (chunk in mapped.heartRateChunks) {
            assertTrue(chunk.startTime.isBefore(chunk.endTime))
            assertTrue(
                chunk.samples.all {
                    !it.time.isBefore(chunk.startTime) && !it.time.isAfter(chunk.endTime)
                }
            )
        }
    }

    @Test
    fun `normalizes overlapping and out-of-bounds laps and handles sub-second session`() {
        val start = 1_764_064_800_000L
        val end = start + 100_000L
        val activity = fitActivity(
            runId = "laps",
            startMillis = start,
            endMillis = end,
            laps = listOf(
                lapTotals(start - 2_000L, start + 40_000L, 120.0),
                // Overlaps previous lap by 5 s
                lapTotals(start + 35_000L, start + 80_000L, 150.0),
                // Zero-length lap: dropped
                lapTotals(start + 80_000L, start + 80_000L, 0.0),
                // Extends past session end: clamped
                lapTotals(start + 80_000L, end + 5_000L, 60.0)
            )
        )

        val mapped = FitToHealthConnectMapper.map(activity, fallbackRunKey = "fallback")

        assertEquals(3, mapped.laps.size)
        assertEquals(Instant.ofEpochMilli(start), mapped.laps[0].startTime)
        assertEquals(Instant.ofEpochMilli(start + 40_000L), mapped.laps[0].endTime)
        assertEquals(Instant.ofEpochMilli(start + 40_000L), mapped.laps[1].startTime)
        assertEquals(Instant.ofEpochMilli(start + 80_000L), mapped.laps[1].endTime)
        assertEquals(Instant.ofEpochMilli(start + 80_000L), mapped.laps[2].startTime)
        assertEquals(Instant.ofEpochMilli(end), mapped.laps[2].endTime)

        val subSecond = fitActivity(
            runId = "short",
            startMillis = start,
            endMillis = start,
            elapsedSeconds = 0.4
        )
        val mappedShort = FitToHealthConnectMapper.map(subSecond, fallbackRunKey = "fallback")
        assertEquals(Instant.ofEpochMilli(start), mappedShort.startTime)
        assertEquals(Instant.ofEpochMilli(start + 400L), mappedShort.endTime)
    }

    @Test
    fun `fails cleanly when FIT activity has no session`() {
        val noSession = FitActivity(
            fileType = "ACTIVITY",
            timeCreatedMillis = null,
            devices = emptyList(),
            events = emptyList(),
            records = emptyList(),
            laps = emptyList(),
            session = null,
            sessionCount = 0,
            runId = "empty",
            activityTimerSeconds = null
        )

        assertFailsWith<HealthConnectMappingException> {
            FitToHealthConnectMapper.map(noSession, fallbackRunKey = "empty")
        }
    }

    private fun tracesDir(): Path {
        val fromProp = System.getProperty("runpro.traces")
        if (fromProp != null) return Path.of(fromProp)
        return Path.of("..", "..", "testdata", "traces").normalize()
    }

    private fun recordEasyRun(trace: Trace, journal: Path) {
        val start = trace.records.first().time
        val lapTimes = listOf(600.seconds, 1_200.seconds)
        val pendingLaps = ArrayDeque(lapTimes)
        RecordingSession(
            runIds = { RunId("easy-30min") },
            journal = JournalWriter.create(journal)
        ).use { session ->
            session.handle(EngineInput.ConfigureWorkout(WorkoutSettings(autoLapMeters = null)))
            check(session.handle(EngineInput.Prepare(start)))
            check(session.handle(EngineInput.Start(start)))
            for (record in trace.records) {
                while (pendingLaps.isNotEmpty() && start + pendingLaps.first() < record.time) {
                    val t = start + pendingLaps.removeFirst()
                    check(session.handle(EngineInput.MarkLap(t)))
                }
                if (record is TraceRecord.SampleRecord) {
                    session.handle(EngineInput.SampleReceived(record.sample))
                }
                session.handle(EngineInput.Tick(record.time))
            }
            check(session.handle(EngineInput.Stop(start + 1_800.seconds)))
        }
    }

    private fun fitActivity(
        runId: String?,
        startMillis: Long,
        endMillis: Long,
        elapsedSeconds: Double? = (endMillis - startMillis) / 1000.0,
        timerSeconds: Double? = elapsedSeconds,
        distanceMeters: Double? = null,
        averageCadenceStepsPerMinute: Double? = null,
        records: List<FitRecord> = emptyList(),
        laps: List<FitTotals> = emptyList(),
        events: List<FitEvent> = emptyList()
    ) = FitActivity(
        fileType = "ACTIVITY",
        timeCreatedMillis = endMillis,
        devices = emptyList(),
        events = events,
        records = records,
        laps = laps,
        session = FitTotals(
            startMillis = startMillis,
            endMillis = endMillis,
            elapsedSeconds = elapsedSeconds,
            timerSeconds = timerSeconds,
            movingSeconds = timerSeconds,
            distanceMeters = distanceMeters,
            averageHeartRateBpm = null,
            maxHeartRateBpm = null,
            averageCadenceStepsPerMinute = averageCadenceStepsPerMinute,
            maxCadenceStepsPerMinute = null,
            ascentMeters = null,
            descentMeters = null,
            averageSpeedMetersPerSecond = null,
            sport = "RUNNING",
            lapCount = laps.size
        ),
        sessionCount = 1,
        runId = runId,
        activityTimerSeconds = timerSeconds
    )

    private fun lapTotals(startMillis: Long, endMillis: Long, distanceMeters: Double?) = FitTotals(
        startMillis = startMillis,
        endMillis = endMillis,
        elapsedSeconds = (endMillis - startMillis) / 1000.0,
        timerSeconds = (endMillis - startMillis) / 1000.0,
        movingSeconds = (endMillis - startMillis) / 1000.0,
        distanceMeters = distanceMeters,
        averageHeartRateBpm = null,
        maxHeartRateBpm = null,
        averageCadenceStepsPerMinute = null,
        maxCadenceStepsPerMinute = null,
        ascentMeters = null,
        descentMeters = null,
        averageSpeedMetersPerSecond = null,
        sport = "RUNNING",
        lapTrigger = LapTrigger.MANUAL
    )
}
