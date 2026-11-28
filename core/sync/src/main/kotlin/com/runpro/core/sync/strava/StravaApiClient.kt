package com.runpro.core.sync.strava

import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class StravaUploadResponse(
    val uploadId: Long,
    val status: String,
    val activityId: Long?,
    val error: String?
)

/**
 * Pure JVM client for Strava OAuth token exchange/refresh and FIT upload/polling endpoints.
 */
class StravaApiClient(
    private val config: StravaConfig,
    private val transport: StravaHttpTransport = HttpUrlConnectionStravaTransport()
) {
    sealed interface TokenResult {
        data class Success(val tokens: StravaTokens) : TokenResult
        data class Unauthorized(val reason: String) : TokenResult
        data class Failed(val reason: String, val statusCode: Int? = null) : TokenResult
    }

    sealed interface UploadApiResult {
        data class Success(val response: StravaUploadResponse) : UploadApiResult
        data object Unauthorized : UploadApiResult
        data class RateLimited(val reason: String) : UploadApiResult
        data class Failed(val reason: String, val statusCode: Int? = null) : UploadApiResult
    }

    fun exchangeAuthorizationCode(code: String): TokenResult = postTokenForm(
        mapOf(
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "code" to code,
            "grant_type" to "authorization_code"
        )
    )

    fun refreshTokens(refreshToken: String): TokenResult = postTokenForm(
        mapOf(
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token"
        )
    )

    fun uploadFit(
        accessToken: String,
        fileName: String,
        fitBytes: ByteArray,
        externalId: String
    ): UploadApiResult {
        val url = "${config.apiBaseUrl.trimEnd('/')}$UPLOADS_PATH"
        return executeUploadRequest {
            transport.postMultipart(
                url = url,
                headers = mapOf("Authorization" to "Bearer $accessToken"),
                fields = mapOf(
                    "data_type" to DATA_TYPE_FIT,
                    "activity_type" to ACTIVITY_TYPE_RUN,
                    "external_id" to externalId
                ),
                filePart = MultipartFilePart(
                    fieldName = "file",
                    fileName = fileName,
                    contentType = FIT_CONTENT_TYPE,
                    bytes = fitBytes
                )
            )
        }
    }

    fun getUploadStatus(accessToken: String, uploadId: Long): UploadApiResult {
        val url = "${config.apiBaseUrl.trimEnd('/')}$UPLOADS_PATH/$uploadId"
        return executeUploadRequest {
            transport.get(
                url = url,
                headers = mapOf("Authorization" to "Bearer $accessToken")
            )
        }
    }

    private fun postTokenForm(fields: Map<String, String>): TokenResult {
        val url = "${config.webBaseUrl.trimEnd('/')}$TOKEN_PATH"
        val response = try {
            transport.postForm(url, fields)
        } catch (e: IOException) {
            return TokenResult.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
        return parseTokenResponse(response)
    }

    private fun parseTokenResponse(response: StravaHttpResponse): TokenResult =
        when (response.statusCode) {
            in HTTP_OK_START..HTTP_OK_END -> decodeTokens(response.body)

            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED ->
                TokenResult.Unauthorized(
                    extractApiMessage(response.body) ?: "Strava authorization rejected"
                )

            else ->
                TokenResult.Failed(
                    reason = extractApiMessage(response.body) ?: "HTTP ${response.statusCode}",
                    statusCode = response.statusCode
                )
        }

    private fun decodeTokens(body: String): TokenResult {
        val obj =
            parseJsonObject(body) ?: return TokenResult.Failed("Invalid Strava token response")
        val access = obj.strField("access_token")
        val refresh = obj.strField("refresh_token")
        val expiresAt = obj.longField("expires_at")
        return if (access.isNullOrBlank() || refresh.isNullOrBlank() || expiresAt == null) {
            TokenResult.Failed("Incomplete Strava token payload")
        } else {
            val athlete = obj["athlete"] as? JsonObject
            TokenResult.Success(
                StravaTokens(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtEpochSeconds = expiresAt,
                    athleteId = athlete?.longField("id"),
                    athleteName = formatAthleteName(athlete)
                )
            )
        }
    }

    private fun executeUploadRequest(call: () -> StravaHttpResponse): UploadApiResult {
        val response = try {
            call()
        } catch (e: IOException) {
            return UploadApiResult.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
        return when (response.statusCode) {
            in HTTP_OK_START..HTTP_OK_END -> decodeUploadResponse(response.body)

            HTTP_UNAUTHORIZED -> UploadApiResult.Unauthorized

            HTTP_TOO_MANY_REQUESTS ->
                UploadApiResult.RateLimited(
                    extractApiMessage(response.body) ?: "Strava rate limit exceeded"
                )

            else -> UploadApiResult.Failed(
                reason = extractApiMessage(response.body) ?: "HTTP ${response.statusCode}",
                statusCode = response.statusCode
            )
        }
    }

    private fun decodeUploadResponse(body: String): UploadApiResult {
        val obj =
            parseJsonObject(body) ?: return UploadApiResult.Failed("Invalid Strava upload response")
        val uploadId = obj.longField("id") ?: obj.strField("id_str")?.toLongOrNull()
        return if (uploadId == null) {
            UploadApiResult.Failed("Strava upload response missing id")
        } else {
            UploadApiResult.Success(
                StravaUploadResponse(
                    uploadId = uploadId,
                    status = obj.strField("status").orEmpty(),
                    activityId = obj.longField("activity_id")
                        ?: obj.strField("activity_id_str")?.toLongOrNull(),
                    error = obj.strField("error")?.takeIf(String::isNotBlank)
                )
            )
        }
    }

    companion object {
        private const val TOKEN_PATH = "/oauth/token"
        private const val UPLOADS_PATH = "/uploads"
        private const val DATA_TYPE_FIT = "fit"
        private const val ACTIVITY_TYPE_RUN = "run"
        private const val FIT_CONTENT_TYPE = "application/octet-stream"
        private const val HTTP_OK_START = 200
        private const val HTTP_OK_END = 299
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val DUPLICATE_ACTIVITY_REGEX =
            Regex("""duplicate of activity (\d+)""", RegexOption.IGNORE_CASE)

        fun extractDuplicateActivityId(errorMessage: String?): Long? = errorMessage?.let {
            DUPLICATE_ACTIVITY_REGEX.find(it)?.groupValues?.get(1)?.toLongOrNull()
        }
    }
}

private fun formatAthleteName(athlete: JsonObject?): String? {
    if (athlete == null) return null
    val full = listOfNotNull(
        athlete.strField("firstname")?.takeIf(String::isNotBlank),
        athlete.strField("lastname")?.takeIf(String::isNotBlank)
    ).joinToString(" ")
    return full.ifBlank { athlete.strField("username")?.takeIf(String::isNotBlank) }
}

private fun extractApiMessage(body: String): String? {
    val obj = parseJsonObject(body) ?: return null
    return obj.strField("error")?.takeIf(String::isNotBlank)
        ?: obj.strField("message")?.takeIf(String::isNotBlank)
}

private fun parseJsonObject(body: String): JsonObject? = try {
    Json.parseToJsonElement(body).jsonObject
} catch (@Suppress("SwallowedException") e: SerializationException) {
    null
} catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
    null
}

private fun JsonObject.strField(key: String): String? {
    val element = this[key]
    return if (element is JsonPrimitive && element !is JsonNull) element.contentOrNull else null
}

private fun JsonObject.longField(key: String): Long? {
    val element = this[key]
    return if (element is JsonPrimitive && element !is JsonNull) {
        element.longOrNull ?: element.contentOrNull?.toLongOrNull()
    } else {
        null
    }
}
