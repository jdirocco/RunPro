package com.runpro.core.sync.strava

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure JVM Strava OAuth authorization URL builder and callback validator.
 */
object StravaOAuth {
    private const val STATE_BYTES = 24
    private const val MOBILE_AUTHORIZE_PATH = "/oauth/mobile/authorize"

    sealed interface CallbackResult {
        data class Authorized(val code: String, val grantedScopes: Set<String>) : CallbackResult
        data class Denied(val error: String) : CallbackResult
        data class Invalid(val reason: String) : CallbackResult
    }

    fun generateState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(STATE_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun authorizationUrl(config: StravaConfig, state: String): String {
        require(config.isConfigured) { "Strava OAuth config is incomplete" }
        require(state.isNotBlank()) { "OAuth state must not be blank" }
        val base = config.webBaseUrl.trimEnd('/')
        val query = listOf(
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "response_type" to "code",
            "approval_prompt" to "auto",
            "scope" to StravaConfig.REQUIRED_SCOPE,
            "state" to state
        ).joinToString("&") { (key, value) ->
            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
        }
        return "$base$MOBILE_AUTHORIZE_PATH?$query"
    }

    fun parseCallback(
        uriString: String,
        expectedRedirectUri: String,
        expectedState: String?
    ): CallbackResult {
        val callbackUri = parseUri(uriString)
        val redirectUri = parseUri(expectedRedirectUri)
        return when {
            callbackUri == null || redirectUri == null ->
                CallbackResult.Invalid("Invalid OAuth callback URI")

            !matchesEndpoint(callbackUri, redirectUri) ->
                CallbackResult.Invalid("OAuth callback URI does not match configured redirect")

            else -> validateQuery(parseQuery(callbackUri.rawQuery), expectedState)
        }
    }

    private fun validateQuery(params: Map<String, String>, expectedState: String?): CallbackResult {
        val state = params["state"]
        val error = params["error"]
        val code = params["code"]
        val scopes = parseScopes(params["scope"])
        return when {
            expectedState.isNullOrBlank() || state.isNullOrBlank() ||
                !constantTimeEquals(state, expectedState) ->
                CallbackResult.Invalid("OAuth state mismatch")

            !error.isNullOrBlank() ->
                CallbackResult.Denied(error)

            code.isNullOrBlank() ->
                CallbackResult.Invalid("Missing OAuth authorization code")

            StravaConfig.REQUIRED_SCOPE !in scopes ->
                CallbackResult.Invalid(
                    "Missing required Strava scope: ${StravaConfig.REQUIRED_SCOPE}"
                )

            else ->
                CallbackResult.Authorized(code = code, grantedScopes = scopes)
        }
    }

    private fun parseUri(raw: String): URI? = try {
        URI(raw)
    } catch (@Suppress("SwallowedException") e: URISyntaxException) {
        null
    }

    private fun matchesEndpoint(actual: URI, expected: URI): Boolean =
        actual.scheme.equals(expected.scheme, ignoreCase = true) &&
            actual.host.equals(expected.host, ignoreCase = true) &&
            actual.path == expected.path

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            val key = decodeQueryComponent(if (idx >= 0) pair.substring(0, idx) else pair)
            val value = if (idx >= 0) decodeQueryComponent(pair.substring(idx + 1)) else ""
            if (key.isNotEmpty() && key !in result) {
                result[key] = value
            }
        }
        return result
    }

    private fun parseScopes(rawScope: String?): Set<String> = rawScope
        .orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8)

    private fun decodeQueryComponent(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8)
}
