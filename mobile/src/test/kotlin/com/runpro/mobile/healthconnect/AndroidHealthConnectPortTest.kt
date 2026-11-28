package com.runpro.mobile.healthconnect

import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import com.runpro.core.sync.healthconnect.HealthConnectHeartRateChunk
import com.runpro.core.sync.healthconnect.HealthConnectHeartRateSample
import com.runpro.core.sync.healthconnect.HealthConnectPort
import com.runpro.core.sync.healthconnect.HealthConnectRoutePoint
import com.runpro.core.sync.healthconnect.HealthConnectRun
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHealthConnectPortTest {
    @Test
    fun `maps Health Connect SDK availability statuses`() {
        assertEquals(
            HealthConnectPort.Availability.AVAILABLE,
            AndroidHealthConnectPort(
                sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
                clientProvider = { error("unused") }
            ).availability()
        )
        assertEquals(
            HealthConnectPort.Availability.UPDATE_REQUIRED,
            AndroidHealthConnectPort(
                sdkStatus = { HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED },
                clientProvider = { error("unused") }
            ).availability()
        )
        assertEquals(
            HealthConnectPort.Availability.UNAVAILABLE,
            AndroidHealthConnectPort(
                sdkStatus = { HealthConnectClient.SDK_UNAVAILABLE },
                clientProvider = { error("unused") }
            ).availability()
        )
    }

    @Test
    fun `inserts all mapped records when required permissions are granted`() = runBlocking {
        val fakeClient = FakeHealthConnectClient(
            grantedPermissions = HealthConnectPermissions.ALL_WRITE_PERMISSIONS
        )
        val port = AndroidHealthConnectPort(
            sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
            clientProvider = { fakeClient }
        )

        val outcome = port.write(sampleOutdoorRun())

        assertEquals(HealthConnectPort.WriteOutcome.Written, outcome)
        assertEquals(1, fakeClient.insertedBatches.size)
        val inserted = fakeClient.insertedBatches.single()
        assertEquals(4, inserted.size)
        assertTrue(inserted.any { it is ExerciseSessionRecord })
        assertTrue(inserted.any { it is HeartRateRecord })
        assertTrue(inserted.any { it is DistanceRecord })
        assertTrue(inserted.any { it is StepsRecord })
    }

    @Test
    fun `reports missing route permission without inserting partial outdoor run`() = runBlocking {
        val fakeClient = FakeHealthConnectClient(
            grantedPermissions = HealthConnectPermissions.ALL_WRITE_PERMISSIONS -
                HealthConnectPermissions.WRITE_EXERCISE_ROUTE
        )
        val port = AndroidHealthConnectPort(
            sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
            clientProvider = { fakeClient }
        )

        val outcome = port.write(sampleOutdoorRun())

        assertEquals(
            HealthConnectPort.WriteOutcome.MissingPermissions(
                setOf(HealthConnectPermissions.WRITE_EXERCISE_ROUTE)
            ),
            outcome
        )
        assertTrue(fakeClient.insertedBatches.isEmpty())
    }

    @Test
    fun `allows indoor run without route permission`() = runBlocking {
        val fakeClient = FakeHealthConnectClient(
            grantedPermissions = setOf(HealthConnectPermissions.WRITE_EXERCISE)
        )
        val port = AndroidHealthConnectPort(
            sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
            clientProvider = { fakeClient }
        )
        val indoor = sampleOutdoorRun().copy(
            route = emptyList(),
            heartRateChunks = emptyList(),
            distanceMeters = null,
            stepsCount = null
        )

        val outcome = port.write(indoor)

        assertEquals(HealthConnectPort.WriteOutcome.Written, outcome)
        assertEquals(1, fakeClient.insertedBatches.single().size)
    }

    @Test
    fun `maps SecurityException, RemoteException, IOException and unavailable status`() =
        runBlocking {
            val run = sampleOutdoorRun()

            val unavailable = AndroidHealthConnectPort(
                sdkStatus = { HealthConnectClient.SDK_UNAVAILABLE },
                clientProvider = { error("should not be called") }
            ).write(run)
            assertTrue(unavailable is HealthConnectPort.WriteOutcome.Failed)

            val revoked = AndroidHealthConnectPort(
                sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
                clientProvider = {
                    FakeHealthConnectClient(
                        grantedPermissions = HealthConnectPermissions.ALL_WRITE_PERMISSIONS,
                        onInsert = { throw SecurityException("revoked") }
                    )
                }
            ).write(run)
            assertTrue(revoked is HealthConnectPort.WriteOutcome.MissingPermissions)

            val ipcFailed = AndroidHealthConnectPort(
                sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
                clientProvider = {
                    FakeHealthConnectClient(
                        grantedPermissions = HealthConnectPermissions.ALL_WRITE_PERMISSIONS,
                        onInsert = { throw RemoteException("dead binder") }
                    )
                }
            ).write(run)
            assertTrue(ipcFailed is HealthConnectPort.WriteOutcome.Failed)

            val ioFailed = AndroidHealthConnectPort(
                sdkStatus = { HealthConnectClient.SDK_AVAILABLE },
                clientProvider = {
                    FakeHealthConnectClient(
                        grantedPermissions = HealthConnectPermissions.ALL_WRITE_PERMISSIONS,
                        onInsert = { throw IOException("disk full") }
                    )
                }
            ).write(run)
            assertTrue(ioFailed is HealthConnectPort.WriteOutcome.Failed)
        }

    private fun sampleOutdoorRun(): HealthConnectRun {
        val start = Instant.ofEpochMilli(1_764_064_800_000L)
        val end = start.plusSeconds(60)
        return HealthConnectRun(
            runKey = "run-1",
            clientRecordVersion = 1L,
            startTime = start,
            endTime = end,
            startZoneOffset = ZoneOffset.UTC,
            endZoneOffset = ZoneOffset.UTC,
            title = "RunPro Run",
            laps = emptyList(),
            segments = emptyList(),
            route = listOf(HealthConnectRoutePoint(start, 45.4642, 9.19, 120.0)),
            heartRateChunks = listOf(
                HealthConnectHeartRateChunk(
                    index = 0,
                    startTime = start,
                    endTime = end,
                    samples = listOf(HealthConnectHeartRateSample(start, 145L))
                )
            ),
            distanceMeters = 200.0,
            stepsCount = 175L
        )
    }

    private class FakeHealthConnectClient(
        private val grantedPermissions: Set<String>,
        private val onInsert: (List<Record>) -> Unit = {}
    ) : HealthConnectClient {
        val insertedBatches = mutableListOf<List<Record>>()

        override val permissionController: PermissionController = object : PermissionController {
            override suspend fun getGrantedPermissions(): Set<String> = grantedPermissions
            override suspend fun revokeAllPermissions() = Unit
        }

        override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
            onInsert(records)
            insertedBatches += records
            return InsertRecordsResponse(records.indices.map { "id-$it" })
        }

        override suspend fun updateRecords(records: List<Record>) = error("unused")

        override suspend fun deleteRecords(
            recordType: KClass<out Record>,
            recordIdsList: List<String>,
            clientRecordIdsList: List<String>
        ) = error("unused")

        override suspend fun deleteRecords(
            recordType: KClass<out Record>,
            timeRangeFilter: TimeRangeFilter
        ) = error("unused")

        override suspend fun <T : Record> readRecord(
            recordType: KClass<T>,
            recordId: String
        ): ReadRecordResponse<T> = error("unused")

        override suspend fun <T : Record> readRecords(
            request: ReadRecordsRequest<T>
        ): ReadRecordsResponse<T> = error("unused")

        override suspend fun aggregate(request: AggregateRequest): AggregationResult =
            error("unused")

        override suspend fun aggregateGroupByDuration(
            request: AggregateGroupByDurationRequest
        ): List<AggregationResultGroupedByDuration> = error("unused")

        override suspend fun aggregateGroupByPeriod(
            request: AggregateGroupByPeriodRequest
        ): List<AggregationResultGroupedByPeriod> = error("unused")

        override suspend fun getChangesToken(request: ChangesTokenRequest): String = error("unused")

        override suspend fun getChanges(changesToken: String): ChangesResponse = error("unused")
    }
}
