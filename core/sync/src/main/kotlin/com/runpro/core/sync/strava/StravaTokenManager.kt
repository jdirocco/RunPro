package com.runpro.core.sync.strava

/**
 * Handles Strava access-token validity checks, proactive refresh before expiry, single reactive
 * refresh on HTTP 401, and atomic persistence of rotated refresh tokens.
 */
internal class StravaTokenManager(
    private val client: StravaApiClient,
    private val tokenStore: StravaTokenStore,
    private val refreshLeewaySeconds: Long,
    private val clockMillis: () -> Long
) {
    sealed interface AuthState {
        data class Ready(val tokens: StravaTokens) : AuthState
        data object NotConnected : AuthState
        data class AuthRequired(val reason: String) : AuthState
        data class Failed(val reason: String) : AuthState
    }

    sealed interface AuthorizedCall {
        data class Ok(val response: StravaUploadResponse, val tokens: StravaTokens) : AuthorizedCall
        data class AuthRequired(val reason: String) : AuthorizedCall
        data class RateLimited(val reason: String) : AuthorizedCall
        data class Failed(val reason: String) : AuthorizedCall
    }

    fun ensureValidTokens(): AuthState {
        val stored = tokenStore.read() ?: return AuthState.NotConnected
        val nowSeconds = clockMillis() / MILLIS_PER_SECOND
        return if (!stored.isExpiredAt(nowSeconds, refreshLeewaySeconds)) {
            AuthState.Ready(stored)
        } else {
            refreshAndStore(stored)
        }
    }

    fun executeWithAuthRetry(
        initialTokens: StravaTokens,
        call: (accessToken: String) -> StravaApiClient.UploadApiResult
    ): AuthorizedCall {
        val first = call(initialTokens.accessToken)
        return if (first is StravaApiClient.UploadApiResult.Unauthorized) {
            retryAfterRefresh(initialTokens, call)
        } else {
            mapApiResult(first, initialTokens)
        }
    }

    private fun refreshAndStore(previous: StravaTokens): AuthState =
        when (val result = client.refreshTokens(previous.refreshToken)) {
            is StravaApiClient.TokenResult.Success -> {
                val merged = result.tokens.copy(
                    scope = previous.scope,
                    athleteId = result.tokens.athleteId ?: previous.athleteId,
                    athleteName = result.tokens.athleteName ?: previous.athleteName
                )
                tokenStore.save(merged)
                AuthState.Ready(merged)
            }

            is StravaApiClient.TokenResult.Unauthorized ->
                AuthState.AuthRequired(result.reason)

            is StravaApiClient.TokenResult.Failed ->
                AuthState.Failed(result.reason)
        }

    private fun retryAfterRefresh(
        previous: StravaTokens,
        call: (accessToken: String) -> StravaApiClient.UploadApiResult
    ): AuthorizedCall = when (val refreshed = refreshAndStore(previous)) {
        is AuthState.Ready -> mapApiResult(call(refreshed.tokens.accessToken), refreshed.tokens)
        is AuthState.AuthRequired -> AuthorizedCall.AuthRequired(refreshed.reason)
        is AuthState.Failed -> AuthorizedCall.Failed(refreshed.reason)
        AuthState.NotConnected -> AuthorizedCall.AuthRequired("Not connected to Strava")
    }

    private fun mapApiResult(
        result: StravaApiClient.UploadApiResult,
        tokens: StravaTokens
    ): AuthorizedCall = when (result) {
        is StravaApiClient.UploadApiResult.Success -> AuthorizedCall.Ok(result.response, tokens)

        StravaApiClient.UploadApiResult.Unauthorized ->
            AuthorizedCall.AuthRequired("Strava authorization expired")

        is StravaApiClient.UploadApiResult.RateLimited ->
            AuthorizedCall.RateLimited(result.reason)

        is StravaApiClient.UploadApiResult.Failed -> AuthorizedCall.Failed(result.reason)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
