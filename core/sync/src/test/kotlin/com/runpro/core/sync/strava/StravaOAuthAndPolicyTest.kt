package com.runpro.core.sync.strava

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StravaOAuthAndPolicyTest {
    @Test
    fun `config checks required fields and never exposes client secret in toString`() {
        val unconfigured = StravaConfig(clientId = "", clientSecret = "")
        assertFalse(unconfigured.isConfigured)
        assertEquals("runpro://localhost/oauth/callback", unconfigured.redirectUri)

        val missingSecret = StravaConfig(clientId = "12345", clientSecret = "")
        assertFalse(missingSecret.isConfigured)

        val configured = StravaConfig(
            clientId = "12345",
            clientSecret = "fake-secret-value-not-real",
            callbackHost = "localhost"
        )
        assertTrue(configured.isConfigured)
        assertFalse(configured.toString().contains("fake-secret-value-not-real"))
    }

    @Test
    fun `tokens redact credentials in toString and compute expiry with leeway`() {
        val tokens = StravaTokens(
            accessToken = "fake-access-token",
            refreshToken = "fake-refresh-token",
            expiresAtEpochSeconds = 1_000L,
            scope = "read,activity:write",
            athleteId = 42L,
            athleteName = "Test Runner"
        )

        val rendered = tokens.toString()
        assertFalse(rendered.contains("fake-access-token"))
        assertFalse(rendered.contains("fake-refresh-token"))
        assertFalse(tokens.isExpiredAt(nowEpochSeconds = 600L, leewaySeconds = 300L))
        assertTrue(tokens.isExpiredAt(nowEpochSeconds = 700L, leewaySeconds = 300L))
        assertTrue(tokens.isExpiredAt(nowEpochSeconds = 1_100L, leewaySeconds = 300L))
    }

    @Test
    fun `builds Strava mobile authorization URL with state and activity write scope`() {
        val config = StravaConfig(
            clientId = "205420",
            clientSecret = "test-secret",
            callbackHost = "localhost"
        )
        val state1 = StravaOAuth.generateState()
        val state2 = StravaOAuth.generateState()
        assertTrue(state1.length >= 24)
        assertNotEquals(state1, state2)

        val url = StravaOAuth.authorizationUrl(config, state1)
        assertTrue(url.startsWith("https://www.strava.com/oauth/mobile/authorize?"))
        assertTrue(url.contains("client_id=205420"))
        assertTrue(url.contains("redirect_uri=runpro%3A%2F%2Flocalhost%2Foauth%2Fcallback"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("approval_prompt=auto"))
        assertTrue(url.contains("scope=activity%3Awrite"))
        assertTrue(url.contains("state=$state1"))
    }

    @Test
    fun `validates OAuth callback URI, state, scope, denial and missing code`() {
        val redirectUri = "runpro://localhost/oauth/callback"

        val ok = StravaOAuth.parseCallback(
            uriString = "$redirectUri?state=abc123&code=auth-code-1&scope=read,activity:write",
            expectedRedirectUri = redirectUri,
            expectedState = "abc123"
        )
        val authorized = assertIs<StravaOAuth.CallbackResult.Authorized>(ok)
        assertEquals("auth-code-1", authorized.code)
        assertEquals(setOf("read", "activity:write"), authorized.grantedScopes)

        val badState = StravaOAuth.parseCallback(
            uriString = "$redirectUri?state=wrong&code=auth-code-1&scope=read,activity:write",
            expectedRedirectUri = redirectUri,
            expectedState = "abc123"
        )
        assertIs<StravaOAuth.CallbackResult.Invalid>(badState)

        val missingState = StravaOAuth.parseCallback(
            uriString = "$redirectUri?state=abc123&code=auth-code-1&scope=read,activity:write",
            expectedRedirectUri = redirectUri,
            expectedState = null
        )
        assertIs<StravaOAuth.CallbackResult.Invalid>(missingState)

        val denied = StravaOAuth.parseCallback(
            uriString = "$redirectUri?state=abc123&error=access_denied",
            expectedRedirectUri = redirectUri,
            expectedState = "abc123"
        )
        val deniedResult = assertIs<StravaOAuth.CallbackResult.Denied>(denied)
        assertEquals("access_denied", deniedResult.error)

        val missingScope = StravaOAuth.parseCallback(
            uriString = "$redirectUri?state=abc123&code=auth-code-1&scope=read",
            expectedRedirectUri = redirectUri,
            expectedState = "abc123"
        )
        assertIs<StravaOAuth.CallbackResult.Invalid>(missingScope)

        val wrongHost = StravaOAuth.parseCallback(
            uriString = "runpro://evil.example/oauth/callback" +
                "?state=abc123&code=auth-code-1&scope=activity:write",
            expectedRedirectUri = redirectUri,
            expectedState = "abc123"
        )
        assertIs<StravaOAuth.CallbackResult.Invalid>(wrongHost)

        val missingCode = StravaOAuth.parseCallback(
            uriString = "$redirectUri?state=abc123&scope=read,activity:write",
            expectedRedirectUri = redirectUri,
            expectedState = "abc123"
        )
        assertIs<StravaOAuth.CallbackResult.Invalid>(missingCode)
    }

    @Test
    fun `auto-upload policy requires configured build, connected tokens, and opt-in preference`() {
        val configured = StravaConfig(clientId = "205420", clientSecret = "test-secret")
        val unconfigured = StravaConfig(clientId = "", clientSecret = "")
        val tokens = StravaTokens(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtEpochSeconds = 2_000L
        )

        assertFalse(StravaPreferences().autoUpload)
        assertFalse(
            StravaAutoUploadPolicy.shouldAutoUpload(
                config = unconfigured,
                tokens = tokens,
                preferences = StravaPreferences(autoUpload = true)
            )
        )
        assertFalse(
            StravaAutoUploadPolicy.shouldAutoUpload(
                config = configured,
                tokens = null,
                preferences = StravaPreferences(autoUpload = true)
            )
        )
        assertFalse(
            StravaAutoUploadPolicy.shouldAutoUpload(
                config = configured,
                tokens = tokens,
                preferences = StravaPreferences(autoUpload = false)
            )
        )
        assertTrue(
            StravaAutoUploadPolicy.shouldAutoUpload(
                config = configured,
                tokens = tokens,
                preferences = StravaPreferences(autoUpload = true)
            )
        )
    }

    @Test
    fun `activity URL resolves canonical Strava activity link`() {
        assertEquals(
            "https://www.strava.com/activities/9876543210",
            StravaActivityUrls.activityUrl(9_876_543_210L)
        )
    }
}
