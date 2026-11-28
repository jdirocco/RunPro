package com.runpro.core.sync.strava

import com.runpro.core.fit.FitExporter
import com.runpro.core.sync.writeStoppedRun
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class StravaUploaderTest {
    @Test
    fun `connects, keeps tokens across restarts, uploads FIT as run, polls, keeps FIT intact`(
        @TempDir temp: Path
    ) {
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("strava")
        val fit = exportRun(temp.resolve("journals").also(Files::createDirectories), runs, run = 1)
        val originalBytes = Files.readAllBytes(fit)
        val transport = fullFlowTransport(originalBytes)
        val sleeps = mutableListOf<Long>()
        val uploader = createUploader(runs, stateDir, transport, sleepMillis = { sleeps += it })

        val auth = uploader.account.completeOAuth(
            callbackUri = "runpro://localhost/oauth/callback" +
                "?state=st-1&code=code-1&scope=read,activity:write",
            expectedState = "st-1"
        )
        val connected = assertIs<StravaAccount.OAuthCompletionOutcome.Connected>(auth)
        assertEquals(77L, connected.tokens.athleteId)
        assertEquals("Juri Runner", connected.tokens.athleteName)

        // Restart uploader to prove tokens survived durably on disk.
        val restarted = createUploader(runs, stateDir, transport, sleepMillis = { sleeps += it })
        val batch = restarted.uploadPending()
        assertEquals(1, batch.uploaded.size)
        assertEquals(888_001L, batch.uploaded.single().activityId)
        assertEquals(
            "https://www.strava.com/activities/888001",
            batch.uploaded.single().activityUrl
        )
        assertEquals(2, transport.pollCalls)
        assertEquals(2, sleeps.size)

        // FIT file in files/runs is never modified or deleted.
        assertContentEquals(originalBytes, Files.readAllBytes(fit))

        // Second pass is idempotent and makes zero additional upload or poll calls.
        val second = restarted.uploadPending()
        assertTrue(second.uploaded.isEmpty())
        assertEquals(listOf("run-01.fit"), second.alreadyUploaded)
        assertEquals(1, transport.uploadCalls)
        assertEquals(2, transport.pollCalls)

        val listed = restarted.listRuns().single()
        assertEquals(StravaUploadStatus.UPLOADED, listed.status)
        assertEquals("https://www.strava.com/activities/888001", listed.activityUrl)
    }

    private fun fullFlowTransport(expectedBytes: ByteArray): FakeStravaTransport =
        FakeStravaTransport(
            tokenResponse = {
                StravaHttpResponse(
                    200,
                    """
                {
                  "token_type": "Bearer",
                  "expires_at": 10000,
                  "refresh_token": "refresh-1",
                  "access_token": "access-1",
                  "athlete": { "id": 77, "firstname": "Juri", "lastname": "Runner" }
                }
                    """.trimIndent()
                )
            },
            uploadResponse = { req ->
                assertEquals("Bearer access-1", req.headers["Authorization"])
                assertEquals("fit", req.fields["data_type"])
                assertEquals("run", req.fields["activity_type"])
                assertEquals("run-01.fit", req.filePart.fileName)
                assertContentEquals(expectedBytes, req.filePart.bytes)
                StravaHttpResponse(
                    201,
                    """{"id":5001,"status":"Processing","error":null,"activity_id":null}"""
                )
            },
            pollResponses = ArrayDeque(
                listOf(
                    StravaHttpResponse(
                        200,
                        """{"id":5001,"status":"Processing","error":null,"activity_id":null}"""
                    ),
                    StravaHttpResponse(
                        200,
                        """{"id":5001,"status":"Ready","error":null,"activity_id":888001}"""
                    )
                )
            )
        )

    @Test
    fun `refreshes expiring token proactively and rotates refresh token atomically`(
        @TempDir temp: Path
    ) {
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("strava")
        exportRun(temp.resolve("journals").also(Files::createDirectories), runs, run = 1)

        val stores = StravaStores.fileBacked(stateDir)
        stores.tokens.save(
            StravaTokens(
                accessToken = "old-access",
                refreshToken = "old-refresh",
                expiresAtEpochSeconds = 1_100L,
                athleteId = 7L,
                athleteName = "Runner"
            )
        )

        val transport = FakeStravaTransport(
            tokenResponse = { fields ->
                assertEquals("refresh_token", fields["grant_type"])
                assertEquals("old-refresh", fields["refresh_token"])
                StravaHttpResponse(
                    200,
                    """
                    {
                      "expires_at": 20000,
                      "refresh_token": "rotated-refresh",
                      "access_token": "new-access"
                    }
                    """.trimIndent()
                )
            },
            uploadResponse = { req ->
                assertEquals("Bearer new-access", req.headers["Authorization"])
                StravaHttpResponse(
                    201,
                    """{"id":5002,"status":"Your activity is ready.","error":null,"activity_id":99002}"""
                )
            }
        )

        val uploader = createUploader(
            runsDir = runs,
            stateDir = stateDir,
            transport = transport,
            clockMillis = { 1_000_000L } // 1000s, within 300s leeway of 1100s expiry
        )

        val outcome = uploader.uploadPending()
        assertEquals(1, outcome.uploaded.size)
        assertEquals(1, transport.tokenCalls)

        // Rotated refresh token was persisted while retaining athlete metadata.
        val saved = assertNotNull(FileStravaTokenStore(stateDir).read())
        assertEquals("new-access", saved.accessToken)
        assertEquals("rotated-refresh", saved.refreshToken)
        assertEquals(7L, saved.athleteId)
        assertEquals("Runner", saved.athleteName)
    }

    @Test
    fun `retries once after 401 unauthorized by refreshing token`(@TempDir temp: Path) {
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("strava")
        val fit = exportRun(temp.resolve("journals").also(Files::createDirectories), runs, run = 1)

        FileStravaTokenStore(stateDir).save(
            StravaTokens(
                accessToken = "stale-access",
                refreshToken = "valid-refresh",
                expiresAtEpochSeconds = 50_000L
            )
        )

        val transport = FakeStravaTransport(
            tokenResponse = {
                StravaHttpResponse(
                    200,
                    """{"expires_at":90000,"refresh_token":"fresh-refresh","access_token":"fresh-access"}"""
                )
            },
            uploadResponse = { req ->
                if (req.headers["Authorization"] == "Bearer stale-access") {
                    StravaHttpResponse(401, """{"message":"Authorization Error"}""")
                } else {
                    assertEquals("Bearer fresh-access", req.headers["Authorization"])
                    StravaHttpResponse(
                        201,
                        """{"id":5003,"status":"Ready","error":null,"activity_id":77003}"""
                    )
                }
            }
        )

        val uploader = createUploader(
            runsDir = runs,
            stateDir = stateDir,
            transport = transport,
            clockMillis = { 1_000_000L }
        )

        val result = uploader.uploadFile(fit)
        val uploaded = assertIs<StravaUploader.FileOutcome.Uploaded>(result)
        assertEquals(77_003L, uploaded.activityId)
        assertEquals(1, transport.tokenCalls)
        assertEquals(2, transport.uploadCalls)
    }

    @Test
    fun `bounded polling persists uploadId and subsequent retry polls instead of re-uploading`(
        @TempDir temp: Path
    ) {
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("strava")
        val fit = exportRun(temp.resolve("journals").also(Files::createDirectories), runs, run = 1)

        FileStravaTokenStore(stateDir).save(
            StravaTokens("access", "refresh", expiresAtEpochSeconds = 99_999L)
        )

        val transport = FakeStravaTransport(
            uploadResponse = {
                StravaHttpResponse(
                    201,
                    """{"id":6001,"status":"Processing","error":null,"activity_id":null}"""
                )
            },
            pollResponses = ArrayDeque(
                listOf(
                    processing6001,
                    processing6001,
                    StravaHttpResponse(
                        200,
                        """{"id":6001,"status":"Ready","error":null,"activity_id":66001}"""
                    )
                )
            )
        )

        val uploader = createUploader(
            runsDir = runs,
            stateDir = stateDir,
            transport = transport,
            pollPolicy = twoPolls,
            clockMillis = { 1_000_000L }
        )

        val first = uploader.uploadFile(fit)
        val processing = assertIs<StravaUploader.FileOutcome.Processing>(first)
        assertEquals(6001L, processing.uploadId)
        assertEquals(1, transport.uploadCalls)
        assertEquals(2, transport.pollCalls)

        // Retry across a fresh uploader resumes polling uploadId 6001 without calling POST /uploads again.
        val restarted = createUploader(
            runsDir = runs,
            stateDir = stateDir,
            transport = transport,
            pollPolicy = twoPolls,
            clockMillis = { 2_000_000L }
        )
        val second = restarted.uploadFile(fit)
        val uploaded = assertIs<StravaUploader.FileOutcome.Uploaded>(second)
        assertEquals(66_001L, uploaded.activityId)
        assertEquals(1, transport.uploadCalls)
        assertEquals(3, transport.pollCalls)
    }

    @Test
    fun `resolves duplicate activity ID from Strava error message and marks run uploaded`(
        @TempDir temp: Path
    ) {
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("strava")
        val fit = exportRun(temp.resolve("journals").also(Files::createDirectories), runs, run = 1)
        FileStravaTokenStore(stateDir).save(
            StravaTokens("access", "refresh", expiresAtEpochSeconds = 99_999L)
        )

        val transport = FakeStravaTransport(
            uploadResponse = {
                StravaHttpResponse(
                    201,
                    """
                    {
                      "id": 7001,
                      "status": "There was an error processing your activity.",
                      "error": "run-01.fit duplicate of activity 123456789",
                      "activity_id": null
                    }
                    """.trimIndent()
                )
            }
        )

        val uploader = createUploader(runsDir = runs, stateDir = stateDir, transport = transport)
        val outcome = uploader.uploadFile(fit)
        val uploaded = assertIs<StravaUploader.FileOutcome.Uploaded>(outcome)
        assertEquals(123_456_789L, uploaded.activityId)
        assertEquals("https://www.strava.com/activities/123456789", uploaded.activityUrl)
    }

    @Test
    fun `autoUploadIfEnabled respects opt-in preference and disconnect preserves ledger`(
        @TempDir temp: Path
    ) {
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("strava")
        val fit = exportRun(temp.resolve("journals").also(Files::createDirectories), runs, run = 1)
        val stores = StravaStores.fileBacked(stateDir)
        stores.tokens.save(StravaTokens("access", "refresh", expiresAtEpochSeconds = 99_999L))

        val transport = FakeStravaTransport(
            uploadResponse = {
                StravaHttpResponse(
                    201,
                    """{"id":8001,"status":"Ready","error":null,"activity_id":88001}"""
                )
            }
        )
        val uploader = createUploader(runsDir = runs, stateDir = stateDir, transport = transport)

        // Default preference is opt-in (false): no network calls.
        val skipped = uploader.autoUploadIfEnabled(fit)
        assertIs<StravaUploader.FileOutcome.AutoUploadSkipped>(skipped)
        assertEquals(0, transport.uploadCalls)

        // Enable auto-upload -> uploads cleanly.
        stores.preferences.save(StravaPreferences(autoUpload = true))
        val uploaded = uploader.autoUploadIfEnabled(fit)
        assertIs<StravaUploader.FileOutcome.Uploaded>(uploaded)
        assertEquals(1, transport.uploadCalls)

        // Disconnect clears tokens but retains the activity link in the ledger and the FIT file.
        uploader.account.disconnect()
        assertNull(stores.tokens.read())
        assertTrue(Files.isRegularFile(fit))
        assertEquals(StravaUploadStatus.UPLOADED, uploader.listRuns().single().status)
    }

    @Test
    fun `isolates failed run in batch, allows manual retry, and re-uploads replaced FIT file`(
        @TempDir temp: Path
    ) {
        val journals = temp.resolve("journals").also(Files::createDirectories)
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("strava")
        val fit1 = exportRun(journals, runs, run = 1, seconds = 60)
        exportRun(journals, runs, run = 2, seconds = 60)

        FileStravaTokenStore(stateDir).save(
            StravaTokens("access", "refresh", expiresAtEpochSeconds = 99_999L)
        )

        var failRun1 = true
        val transport = FakeStravaTransport(
            uploadResponse = { req ->
                if (req.filePart.fileName == "run-01.fit" && failRun1) {
                    failRun1 = false
                    StravaHttpResponse(
                        201,
                        """{"id":9001,"status":"Error","error":"Temporary parse error","activity_id":null}"""
                    )
                } else {
                    val activityId = if (req.filePart.fileName == "run-01.fit") 9901L else 9902L
                    StravaHttpResponse(
                        201,
                        """{"id":9002,"status":"Ready","error":null,"activity_id":$activityId}"""
                    )
                }
            }
        )

        val uploader = createUploader(runsDir = runs, stateDir = stateDir, transport = transport)

        val first = uploader.uploadPending()
        assertEquals(listOf("run-02.fit"), first.uploaded.map { it.fileName })
        assertEquals(setOf("run-01.fit"), first.failed.keys)

        // Manual retry succeeds for run-01 while skipping run-02.
        val retry = uploader.uploadPending()
        assertEquals(listOf("run-01.fit"), retry.uploaded.map { it.fileName })
        assertEquals(listOf("run-02.fit"), retry.alreadyUploaded)

        // Replace run-01.fit with updated bytes -> SHA-256 changed, so it becomes pending and re-uploads.
        val updatedJournal = writeStoppedRun(
            temp.resolve("journals2").also(Files::createDirectories),
            run = 1,
            seconds = 90
        )
        FitExporter.export(updatedJournal, fit1)
        assertEquals(
            StravaUploadStatus.PENDING,
            uploader.listRuns().first { it.fileName == "run-01.fit" }.status
        )
        val reupload = uploader.uploadFile(fit1)
        assertIs<StravaUploader.FileOutcome.Uploaded>(reupload)
    }

    @Test
    fun `handles revoked refresh token, rate limit, and unconfigured build cleanly`(
        @TempDir temp: Path
    ) {
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("strava")
        val fit = exportRun(temp.resolve("journals").also(Files::createDirectories), runs, run = 1)
        val original = Files.readAllBytes(fit)

        val unconfigured = StravaConfig(clientId = "", clientSecret = "")
        val unconfiguredUploader = StravaUploader(
            runsDir = runs,
            config = unconfigured,
            client = StravaApiClient(unconfigured, FakeStravaTransport()),
            stores = StravaStores.fileBacked(stateDir)
        )
        assertEquals(StravaUploader.FileOutcome.NotConfigured, unconfiguredUploader.uploadFile(fit))
        assertTrue(unconfiguredUploader.uploadPending().notConfigured)

        FileStravaTokenStore(stateDir).save(
            StravaTokens("expired", "revoked-refresh", expiresAtEpochSeconds = 100L)
        )
        val revokedTransport = FakeStravaTransport(
            tokenResponse = {
                StravaHttpResponse(400, """{"message":"Bad Request","error":"invalid_grant"}""")
            }
        )
        val revokedUploader = createUploader(
            runsDir = runs,
            stateDir = stateDir,
            transport = revokedTransport,
            clockMillis = { 1_000_000L }
        )
        assertIs<StravaUploader.FileOutcome.AuthRequired>(revokedUploader.uploadFile(fit))

        FileStravaTokenStore(stateDir).save(
            StravaTokens("valid", "refresh", expiresAtEpochSeconds = 99_999L)
        )
        val rateLimitedTransport = FakeStravaTransport(
            uploadResponse = {
                StravaHttpResponse(
                    201,
                    """{"id":999,"status":"Processing","error":null,"activity_id":null}"""
                )
            },
            pollResponses = ArrayDeque(
                listOf(StravaHttpResponse(429, """{"message":"Rate Limit Exceeded"}"""))
            )
        )
        val rateLimitedUploader = createUploader(
            runsDir = runs,
            stateDir = stateDir,
            transport = rateLimitedTransport,
            clockMillis = { 1_000_000L }
        )
        assertIs<StravaUploader.FileOutcome.RateLimited>(rateLimitedUploader.uploadFile(fit))
        assertEquals(StravaUploadStatus.PROCESSING, rateLimitedUploader.listRuns().single().status)
        assertEquals(999L, rateLimitedUploader.listRuns().single().uploadId)
        assertContentEquals(original, Files.readAllBytes(fit))
    }

    private val twoPolls = StravaUploader.PollPolicy(
        maxAttempts = 2,
        initialDelayMillis = 10L,
        maxDelayMillis = 20L
    )

    private val processing6001 = StravaHttpResponse(
        200,
        """{"id":6001,"status":"Processing","error":null,"activity_id":null}"""
    )

    private fun createUploader(
        runsDir: Path,
        stateDir: Path,
        transport: StravaHttpTransport,
        pollPolicy: StravaUploader.PollPolicy = StravaUploader.PollPolicy(
            maxAttempts = 4,
            initialDelayMillis = 10L,
            maxDelayMillis = 20L,
            sleepMillis = {}
        ),
        clockMillis: () -> Long = { 1_000_000L },
        sleepMillis: (Long) -> Unit = {}
    ): StravaUploader {
        val config = StravaConfig(clientId = "205420", clientSecret = "test-secret")
        return StravaUploader(
            runsDir = runsDir,
            config = config,
            client = StravaApiClient(config, transport),
            stores = StravaStores.fileBacked(stateDir),
            pollPolicy = pollPolicy.copy(sleepMillis = sleepMillis),
            clockMillis = clockMillis
        )
    }

    private fun exportRun(journals: Path, runs: Path, run: Int, seconds: Int = 60): Path {
        val journal = writeStoppedRun(journals, run = run, seconds = seconds)
        val fit = runs.resolve("run-%02d.fit".format(run))
        FitExporter.export(journal, fit)
        return fit
    }

    private data class RecordedMultipart(
        val url: String,
        val headers: Map<String, String>,
        val fields: Map<String, String>,
        val filePart: MultipartFilePart
    )

    private class FakeStravaTransport(
        private val tokenResponse: (Map<String, String>) -> StravaHttpResponse = {
            StravaHttpResponse(500, """{"message":"unexpected token call"}""")
        },
        private val uploadResponse: (RecordedMultipart) -> StravaHttpResponse = {
            StravaHttpResponse(500, """{"message":"unexpected upload call"}""")
        },
        private val pollResponses: ArrayDeque<StravaHttpResponse> = ArrayDeque()
    ) : StravaHttpTransport {
        var tokenCalls = 0
        var uploadCalls = 0
        var pollCalls = 0

        override fun postForm(url: String, formFields: Map<String, String>): StravaHttpResponse {
            tokenCalls++
            return tokenResponse(formFields)
        }

        override fun postMultipart(
            url: String,
            headers: Map<String, String>,
            fields: Map<String, String>,
            filePart: MultipartFilePart
        ): StravaHttpResponse {
            uploadCalls++
            return uploadResponse(RecordedMultipart(url, headers, fields, filePart))
        }

        override fun get(url: String, headers: Map<String, String>): StravaHttpResponse {
            pollCalls++
            return pollResponses.removeFirstOrNull()
                ?: StravaHttpResponse(500, """{"message":"no poll response configured"}""")
        }
    }
}
