package com.runpro.core.sync.strava

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class HttpUrlConnectionStravaTransportTest {
    @Test
    fun `exchanges token, uploads multipart FIT as run, and polls status over real HTTP`(
        @TempDir temp: Path
    ) {
        val runs = temp.resolve("runs").also(Files::createDirectories)
        val stateDir = temp.resolve("strava")
        val fitBytes = byteArrayOf(14, 16, 0x2E, 0x46, 0x49, 0x54, 1, 2, 3, 4)
        val fitFile = runs.resolve("sample-run.fit")
        Files.write(fitFile, fitBytes)

        val capture = ServerCapture()
        val server = startFakeStravaServer(capture)
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val config = StravaConfig(
                clientId = "205420",
                clientSecret = "local-test-secret",
                callbackHost = "localhost",
                webBaseUrl = baseUrl,
                apiBaseUrl = "$baseUrl/api/v3"
            )
            val uploader = StravaUploader(
                runsDir = runs,
                config = config,
                client = StravaApiClient(config, HttpUrlConnectionStravaTransport()),
                stores = StravaStores.fileBacked(stateDir),
                pollPolicy = StravaUploader.PollPolicy(
                    maxAttempts = 3,
                    initialDelayMillis = 1L,
                    maxDelayMillis = 5L,
                    sleepMillis = {}
                )
            )

            val auth = uploader.account.completeOAuth(
                callbackUri = "runpro://localhost/oauth/callback" +
                    "?state=s1&code=c1&scope=read,activity:write",
                expectedState = "s1"
            )
            assertIs<StravaAccount.OAuthCompletionOutcome.Connected>(auth)
            assertTrue(capture.tokenBody.contains("client_id=205420"))
            assertTrue(capture.tokenBody.contains("client_secret=local-test-secret"))
            assertTrue(capture.tokenBody.contains("code=c1"))
            assertTrue(capture.tokenBody.contains("grant_type=authorization_code"))

            val uploaded =
                assertIs<StravaUploader.FileOutcome.Uploaded>(uploader.uploadFile(fitFile))
            assertEquals(777_888L, uploaded.activityId)
            assertEquals("https://www.strava.com/activities/777888", uploaded.activityUrl)
            assertEquals("Bearer at-http-1", capture.uploadAuth)
            assertTrue(capture.uploadContentType.startsWith("multipart/form-data; boundary="))

            val bodyLatin1 = capture.uploadBody.toString(Charsets.ISO_8859_1)
            assertTrue(bodyLatin1.contains("name=\"data_type\"\r\n\r\nfit\r\n"))
            assertTrue(bodyLatin1.contains("name=\"activity_type\"\r\n\r\nrun\r\n"))
            assertTrue(bodyLatin1.contains("name=\"file\"; filename=\"sample-run.fit\""))
            assertTrue(bodyLatin1.contains(fitBytes.toString(Charsets.ISO_8859_1)))
            assertEquals(1, capture.pollCount)
        } finally {
            server.stop(0)
        }
    }

    private class ServerCapture {
        var tokenBody: String = ""
        var uploadAuth: String = ""
        var uploadContentType: String = ""
        var uploadBody: ByteArray = ByteArray(0)
        var pollCount: Int = 0
    }

    private fun startFakeStravaServer(capture: ServerCapture): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/oauth/token") { exchange ->
            capture.tokenBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = """
                {
                  "token_type": "Bearer",
                  "expires_at": 9999999999,
                  "refresh_token": "rt-http-1",
                  "access_token": "at-http-1"
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/api/v3/uploads") { exchange ->
            val path = exchange.requestURI.path
            when {
                exchange.requestMethod == "POST" && path == "/api/v3/uploads" -> {
                    capture.uploadAuth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                    capture.uploadContentType =
                        exchange.requestHeaders.getFirst("Content-Type").orEmpty()
                    capture.uploadBody = exchange.requestBody.readBytes()
                    val body =
                        """{"id":4242,"status":"Processing","error":null,"activity_id":null}"""
                            .toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(201, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }

                exchange.requestMethod == "GET" && path == "/api/v3/uploads/4242" -> {
                    capture.pollCount++
                    val body = (
                        """{"id":4242,"status":"Your activity is ready.",""" +
                            """"error":null,"activity_id":777888}"""
                        )
                        .toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }

                else -> {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                }
            }
        }
        server.start()
        return server
    }
}
