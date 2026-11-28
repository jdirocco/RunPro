package com.runpro.core.sync.strava

/**
 * The runner's Strava connection: OAuth with a durable single-use state, token storage, disconnect
 * and the opt-in auto-upload preference. The client secret is only ever sent to Strava's token endpoint.
 */
class StravaAccount(
    private val config: StravaConfig,
    private val client: StravaApiClient,
    private val stores: StravaStores
) {
    sealed interface OAuthCompletionOutcome {
        data class Connected(val tokens: StravaTokens) : OAuthCompletionOutcome
        data object NotConfigured : OAuthCompletionOutcome
        data class Denied(val error: String) : OAuthCompletionOutcome
        data class Failed(val reason: String) : OAuthCompletionOutcome
    }

    val isConfigured: Boolean
        get() = config.isConfigured

    fun currentTokens(): StravaTokens? = stores.tokens.read()

    fun preferences(): StravaPreferences = stores.preferences.read()

    fun setAutoUpload(enabled: Boolean) {
        stores.preferences.save(StravaPreferences(autoUpload = enabled))
    }

    /** Forgets the tokens; the upload ledger and its activity links stay. */
    fun disconnect() {
        stores.tokens.clear()
        stores.pendingAuth.clear()
    }

    /**
     * Starts a connection: stores a fresh single-use state (replacing any earlier one) and returns the
     * Strava authorization URL to open, or null when this build has no Strava credentials.
     */
    fun beginOAuth(): String? {
        if (!config.isConfigured) return null
        val state = StravaOAuth.generateState()
        stores.pendingAuth.save(state)
        return StravaOAuth.authorizationUrl(config, state)
    }

    /**
     * Completes the connection started by [beginOAuth]. The stored state is consumed by every attempt,
     * so a replayed or forged callback can never be exchanged twice.
     */
    fun completeOAuth(callbackUri: String): OAuthCompletionOutcome {
        if (!config.isConfigured) return OAuthCompletionOutcome.NotConfigured
        val expected = stores.pendingAuth.read()
        stores.pendingAuth.clear()
        return completeOAuth(callbackUri, expected)
    }

    fun completeOAuth(callbackUri: String, expectedState: String?): OAuthCompletionOutcome {
        if (!config.isConfigured) return OAuthCompletionOutcome.NotConfigured
        return when (
            val parsed = StravaOAuth.parseCallback(callbackUri, config.redirectUri, expectedState)
        ) {
            is StravaOAuth.CallbackResult.Denied -> OAuthCompletionOutcome.Denied(parsed.error)

            is StravaOAuth.CallbackResult.Invalid -> OAuthCompletionOutcome.Failed(parsed.reason)

            is StravaOAuth.CallbackResult.Authorized -> exchangeAndStore(
                parsed.code,
                parsed.grantedScopes
            )
        }
    }

    private fun exchangeAndStore(code: String, scopes: Set<String>): OAuthCompletionOutcome =
        when (val result = client.exchangeAuthorizationCode(code)) {
            is StravaApiClient.TokenResult.Success -> {
                val enriched = result.tokens.copy(scope = scopes.sorted().joinToString(","))
                stores.tokens.save(enriched)
                OAuthCompletionOutcome.Connected(enriched)
            }

            is StravaApiClient.TokenResult.Unauthorized -> OAuthCompletionOutcome.Failed(
                result.reason
            )

            is StravaApiClient.TokenResult.Failed -> OAuthCompletionOutcome.Failed(result.reason)
        }
}
