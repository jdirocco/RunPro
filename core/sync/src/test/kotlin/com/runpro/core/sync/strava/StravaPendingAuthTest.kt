package com.runpro.core.sync.strava

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class StravaPendingAuthTest {
    private val config = StravaConfig(clientId = "205420", clientSecret = "test-secret")

    @Test
    fun `begin OAuth persists a single-use state that survives a restart`(@TempDir temp: Path) {
        val stateDir = temp.resolve("strava")
        val transport = TokenOnlyTransport()
        val url = assertNotNull(uploader(temp, stateDir, transport).account.beginOAuth())
        val state = url.substringAfter("state=")
        assertTrue(state.isNotBlank())

        // The browser round trip may outlive the process: a new uploader still knows the state.
        val restarted = uploader(temp, stateDir, transport)
        val callback = "runpro://localhost/oauth/callback?state=$state&code=c1&scope=activity:write"
        assertIs<StravaAccount.OAuthCompletionOutcome.Connected>(
            restarted.account.completeOAuth(callback)
        )
        assertEquals(1, transport.tokenCalls)

        // Replaying the same callback is refused without another token exchange.
        val replay = restarted.account.completeOAuth(callback)
        assertIs<StravaAccount.OAuthCompletionOutcome.Failed>(replay)
        assertEquals(1, transport.tokenCalls)
    }

    @Test
    fun `a newer begin OAuth invalidates the previous state`(@TempDir temp: Path) {
        val stateDir = temp.resolve("strava")
        val transport = TokenOnlyTransport()
        val uploader = uploader(temp, stateDir, transport)
        val first = assertNotNull(uploader.account.beginOAuth()).substringAfter("state=")
        uploader.account.beginOAuth()

        val stale = "runpro://localhost/oauth/callback?state=$first&code=c1&scope=activity:write"
        assertIs<StravaAccount.OAuthCompletionOutcome.Failed>(uploader.account.completeOAuth(stale))
        assertEquals(0, transport.tokenCalls)
        assertNull(uploader.account.currentTokens())
    }

    @Test
    fun `a denied callback clears the pending state and an unconfigured build has none`(
        @TempDir temp: Path
    ) {
        val stateDir = temp.resolve("strava")
        val uploader = uploader(temp, stateDir, TokenOnlyTransport())
        val state = assertNotNull(uploader.account.beginOAuth()).substringAfter("state=")
        val denied = "runpro://localhost/oauth/callback?state=$state&error=access_denied"
        assertIs<StravaAccount.OAuthCompletionOutcome.Denied>(
            uploader.account.completeOAuth(denied)
        )
        assertNull(StravaStores.fileBacked(stateDir).pendingAuth.read())

        val unconfigured = StravaConfig(clientId = "", clientSecret = "")
        val disabled = StravaUploader(
            runsDir = temp.resolve("runs"),
            config = unconfigured,
            client = StravaApiClient(unconfigured, TokenOnlyTransport()),
            stores = StravaStores.fileBacked(stateDir)
        )
        assertNull(disabled.account.beginOAuth())
        assertEquals(
            StravaAccount.OAuthCompletionOutcome.NotConfigured,
            disabled.account.completeOAuth("runpro://localhost/oauth/callback?state=x&code=y")
        )
    }

    private fun uploader(temp: Path, stateDir: Path, transport: StravaHttpTransport) =
        StravaUploader(
            runsDir = temp.resolve("runs").also(Files::createDirectories),
            config = config,
            client = StravaApiClient(config, transport),
            stores = StravaStores.fileBacked(stateDir)
        )

    private class TokenOnlyTransport : StravaHttpTransport {
        var tokenCalls = 0

        override fun postForm(url: String, formFields: Map<String, String>): StravaHttpResponse {
            tokenCalls++
            return StravaHttpResponse(
                200,
                """{"expires_at":99999,"refresh_token":"r","access_token":"a"}"""
            )
        }

        override fun postMultipart(
            url: String,
            headers: Map<String, String>,
            fields: Map<String, String>,
            filePart: MultipartFilePart
        ): StravaHttpResponse = error("no uploads expected")

        override fun get(url: String, headers: Map<String, String>): StravaHttpResponse =
            error("no polls expected")
    }
}
