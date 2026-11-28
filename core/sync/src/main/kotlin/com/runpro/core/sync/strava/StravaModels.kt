package com.runpro.core.sync.strava

/**
 * Strava OAuth and API configuration.
 *
 * [clientSecret] is intentionally redacted from [toString] so logs and diagnostics never leak it.
 */
data class StravaConfig(
    val clientId: String,
    val clientSecret: String,
    val callbackHost: String = DEFAULT_CALLBACK_HOST,
    val webBaseUrl: String = DEFAULT_WEB_BASE_URL,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank() && callbackHost.isNotBlank()

    val redirectUri: String
        get() = "$CALLBACK_SCHEME://${callbackHost.trim()}$CALLBACK_PATH"

    override fun toString(): String =
        "StravaConfig(clientId=$clientId, callbackHost=$callbackHost, configured=$isConfigured)"

    companion object {
        const val DEFAULT_CALLBACK_HOST = "localhost"
        const val CALLBACK_SCHEME = "runpro"
        const val CALLBACK_PATH = "/oauth/callback"
        const val REQUIRED_SCOPE = "activity:write"
        const val DEFAULT_WEB_BASE_URL = "https://www.strava.com"
        const val DEFAULT_API_BASE_URL = "https://www.strava.com/api/v3"
    }
}

/**
 * Strava OAuth tokens with redacted [toString] to prevent accidental credential logging.
 */
data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val scope: String = StravaConfig.REQUIRED_SCOPE,
    val athleteId: Long? = null,
    val athleteName: String? = null
) {
    fun isExpiredAt(
        nowEpochSeconds: Long,
        leewaySeconds: Long = DEFAULT_REFRESH_LEEWAY_SECONDS
    ): Boolean = nowEpochSeconds + leewaySeconds >= expiresAtEpochSeconds

    override fun toString(): String =
        "StravaTokens(expiresAt=$expiresAtEpochSeconds, scope=$scope, athleteId=$athleteId)"

    companion object {
        const val DEFAULT_REFRESH_LEEWAY_SECONDS = 300L
    }
}

enum class StravaUploadStatus {
    PENDING,
    PROCESSING,
    UPLOADED,
    FAILED
}

data class StravaLedgerEntry(
    val fileName: String,
    val sha256: String,
    val status: StravaUploadStatus,
    val uploadId: Long? = null,
    val activityId: Long? = null,
    val error: String? = null,
    val updatedAtEpochMillis: Long = 0L
) {
    val activityUrl: String?
        get() = activityId?.let(StravaActivityUrls::activityUrl)
}

data class StravaRunStatus(
    val fileName: String,
    val status: StravaUploadStatus,
    val uploadId: Long? = null,
    val activityId: Long? = null,
    val activityUrl: String? = null,
    val error: String? = null
)

data class StravaPreferences(val autoUpload: Boolean = false)

object StravaActivityUrls {
    private const val ACTIVITY_URL_PREFIX = "https://www.strava.com/activities/"

    fun activityUrl(activityId: Long): String = "$ACTIVITY_URL_PREFIX$activityId"
}

/**
 * Auto-upload is opt-in and runs only when the build is configured, the athlete connected Strava,
 * and the runner enabled automatic upload.
 */
object StravaAutoUploadPolicy {
    fun shouldAutoUpload(
        config: StravaConfig,
        tokens: StravaTokens?,
        preferences: StravaPreferences
    ): Boolean = config.isConfigured && tokens != null && preferences.autoUpload
}
