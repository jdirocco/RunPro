package com.runpro.core.sync.strava

import com.runpro.core.sync.sha256
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Coordinates Strava OAuth completion, durable token refresh/rotation, multipart FIT uploads,
 * bounded upload status polling, and idempotent status tracking across `.fit` files in `files/runs`.
 *
 * FIT files in [runsDir] are strictly read-only: they are never modified or deleted.
 */
class StravaUploader(
    private val runsDir: Path,
    private val config: StravaConfig,
    private val client: StravaApiClient,
    private val stores: StravaStores,
    private val pollPolicy: PollPolicy = PollPolicy(),
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    data class PollPolicy(
        val maxAttempts: Int = DEFAULT_MAX_POLL_ATTEMPTS,
        val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MS,
        val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MS,
        val refreshLeewaySeconds: Long = StravaTokens.DEFAULT_REFRESH_LEEWAY_SECONDS,
        val sleepMillis: (Long) -> Unit = Thread::sleep
    )

    sealed interface FileOutcome {
        data class Uploaded(
            val fileName: String,
            val uploadId: Long,
            val activityId: Long,
            val activityUrl: String
        ) : FileOutcome

        data class AlreadyUploaded(
            val fileName: String,
            val activityId: Long,
            val activityUrl: String
        ) : FileOutcome

        data class Processing(val fileName: String, val uploadId: Long) : FileOutcome
        data object AutoUploadSkipped : FileOutcome
        data object NotConfigured : FileOutcome
        data object NotConnected : FileOutcome
        data class AuthRequired(val reason: String) : FileOutcome
        data class RateLimited(val reason: String) : FileOutcome
        data class Failed(val fileName: String, val reason: String) : FileOutcome
    }

    data class BatchOutcome(
        val uploaded: List<FileOutcome.Uploaded> = emptyList(),
        val alreadyUploaded: List<String> = emptyList(),
        val processing: List<FileOutcome.Processing> = emptyList(),
        val failed: Map<String, String> = emptyMap(),
        val notConfigured: Boolean = false,
        val notConnected: Boolean = false,
        val authRequiredReason: String? = null,
        val rateLimitedReason: String? = null
    )

    private val tokenManager = StravaTokenManager(
        client = client,
        tokenStore = stores.tokens,
        refreshLeewaySeconds = pollPolicy.refreshLeewaySeconds,
        clockMillis = clockMillis
    )
    private val fileExecutor = StravaFileUploadExecutor(
        client = client,
        tokenManager = tokenManager,
        ledger = stores.ledger,
        pollPolicy = pollPolicy,
        clockMillis = clockMillis
    )

    /** Connection, OAuth and preferences sharing this uploader's stores. */
    val account: StravaAccount = StravaAccount(config, client, stores)

    fun listRuns(): List<StravaRunStatus> {
        val ledgerEntries = stores.ledger.readAll()
        return listFitFiles(runsDir).map { file -> runStatusFor(file, ledgerEntries[file.name]) }
    }

    fun autoUploadIfEnabled(fitFile: Path): FileOutcome {
        val allowed = StravaAutoUploadPolicy.shouldAutoUpload(
            config = config,
            tokens = stores.tokens.read(),
            preferences = stores.preferences.read()
        )
        return if (!allowed) FileOutcome.AutoUploadSkipped else uploadFile(fitFile)
    }

    fun uploadPending(): BatchOutcome = when {
        !config.isConfigured -> BatchOutcome(notConfigured = true)
        stores.tokens.read() == null -> BatchOutcome(notConnected = true)
        else -> uploadBatch(listFitFiles(runsDir))
    }

    fun uploadFile(fitFile: Path): FileOutcome {
        val fileName = fitFile.name
        val hash = readFileHash(fitFile).getOrElse { error ->
            return FileOutcome.Failed(fileName, "read failed: ${error.message}")
        }
        val existing = stores.ledger.get(fileName)
        return when {
            existing != null &&
                existing.sha256 == hash &&
                existing.status == StravaUploadStatus.UPLOADED &&
                existing.activityId != null -> FileOutcome.AlreadyUploaded(
                fileName = fileName,
                activityId = existing.activityId,
                activityUrl = StravaActivityUrls.activityUrl(existing.activityId)
            )

            !config.isConfigured -> FileOutcome.NotConfigured

            else -> fileExecutor.uploadOrResume(fitFile, fileName, hash, existing)
        }
    }

    private fun uploadBatch(files: List<Path>): BatchOutcome {
        val uploaded = mutableListOf<FileOutcome.Uploaded>()
        val alreadyUploaded = mutableListOf<String>()
        val processing = mutableListOf<FileOutcome.Processing>()
        val failed = linkedMapOf<String, String>()
        var notConfigured = false
        var notConnected = false
        var authRequiredReason: String? = null
        var rateLimitedReason: String? = null

        for (file in files) {
            var stop = false
            when (val outcome = uploadFile(file)) {
                is FileOutcome.Uploaded -> uploaded += outcome

                is FileOutcome.AlreadyUploaded -> alreadyUploaded += outcome.fileName

                is FileOutcome.Processing -> processing += outcome

                is FileOutcome.Failed -> failed[outcome.fileName] = outcome.reason

                FileOutcome.AutoUploadSkipped -> Unit

                FileOutcome.NotConfigured -> {
                    notConfigured = true
                    stop = true
                }

                FileOutcome.NotConnected -> {
                    notConnected = true
                    stop = true
                }

                is FileOutcome.AuthRequired -> {
                    authRequiredReason = outcome.reason
                    stop = true
                }

                is FileOutcome.RateLimited -> {
                    rateLimitedReason = outcome.reason
                    stop = true
                }
            }
            if (stop) break
        }
        return BatchOutcome(
            uploaded = uploaded,
            alreadyUploaded = alreadyUploaded,
            processing = processing,
            failed = failed,
            notConfigured = notConfigured,
            notConnected = notConnected,
            authRequiredReason = authRequiredReason,
            rateLimitedReason = rateLimitedReason
        )
    }

    companion object {
        private const val DEFAULT_MAX_POLL_ATTEMPTS = 8
        private const val DEFAULT_INITIAL_DELAY_MS = 1_500L
        private const val DEFAULT_MAX_DELAY_MS = 5_000L
    }
}

private class StravaFileUploadExecutor(
    private val client: StravaApiClient,
    private val tokenManager: StravaTokenManager,
    private val ledger: StravaUploadLedger,
    private val pollPolicy: StravaUploader.PollPolicy,
    private val clockMillis: () -> Long
) {
    fun uploadOrResume(
        fitFile: Path,
        fileName: String,
        hash: String,
        existing: StravaLedgerEntry?
    ): StravaUploader.FileOutcome {
        val auth = tokenManager.ensureValidTokens()
        if (auth !is StravaTokenManager.AuthState.Ready) {
            return mapAuthFailure(auth, fileName)
        }
        val resumeUploadId = existing?.takeIf {
            it.sha256 == hash && it.status == StravaUploadStatus.PROCESSING && it.uploadId != null
        }?.uploadId

        return if (resumeUploadId != null) {
            pollUpload(fileName, hash, resumeUploadId, auth.tokens)
        } else {
            submitNewUpload(fitFile, fileName, hash, auth.tokens)
        }
    }

    private fun submitNewUpload(
        fitFile: Path,
        fileName: String,
        hash: String,
        initialTokens: StravaTokens
    ): StravaUploader.FileOutcome {
        val bytes = try {
            Files.readAllBytes(fitFile)
        } catch (e: IOException) {
            return StravaUploader.FileOutcome.Failed(fileName, "read failed: ${e.message}")
        }

        val callResult = tokenManager.executeWithAuthRetry(initialTokens) { accessToken ->
            client.uploadFit(
                accessToken = accessToken,
                fileName = fileName,
                fitBytes = bytes,
                externalId = fileName
            )
        }
        return when (callResult) {
            is StravaTokenManager.AuthorizedCall.Ok ->
                handleInitialUploadResponse(fileName, hash, callResult.response, callResult.tokens)

            is StravaTokenManager.AuthorizedCall.AuthRequired ->
                StravaUploader.FileOutcome.AuthRequired(callResult.reason)

            is StravaTokenManager.AuthorizedCall.RateLimited ->
                StravaUploader.FileOutcome.RateLimited(callResult.reason)

            is StravaTokenManager.AuthorizedCall.Failed -> {
                recordFailure(fileName, hash, uploadId = null, reason = callResult.reason)
                StravaUploader.FileOutcome.Failed(fileName, callResult.reason)
            }
        }
    }

    private fun handleInitialUploadResponse(
        fileName: String,
        hash: String,
        response: StravaUploadResponse,
        tokens: StravaTokens
    ): StravaUploader.FileOutcome {
        val terminal = resolveTerminalResponse(fileName, hash, response)
        return if (terminal != null) {
            terminal
        } else {
            ledger.record(
                StravaLedgerEntry(
                    fileName = fileName,
                    sha256 = hash,
                    status = StravaUploadStatus.PROCESSING,
                    uploadId = response.uploadId,
                    updatedAtEpochMillis = clockMillis()
                )
            )
            pollUpload(fileName, hash, response.uploadId, tokens)
        }
    }

    private fun pollUpload(
        fileName: String,
        hash: String,
        uploadId: Long,
        initialTokens: StravaTokens
    ): StravaUploader.FileOutcome {
        var tokens = initialTokens
        var delay = pollPolicy.initialDelayMillis.coerceAtLeast(0L)
        var outcome: StravaUploader.FileOutcome? = null
        var attempt = 0
        while (attempt < pollPolicy.maxAttempts && outcome == null) {
            if (delay > 0L) pollPolicy.sleepMillis(delay)
            val call = tokenManager.executeWithAuthRetry(tokens) {
                client.getUploadStatus(it, uploadId)
            }
            when (call) {
                is StravaTokenManager.AuthorizedCall.Ok -> {
                    tokens = call.tokens
                    outcome = resolveTerminalResponse(fileName, hash, call.response)
                }

                is StravaTokenManager.AuthorizedCall.AuthRequired ->
                    outcome = StravaUploader.FileOutcome.AuthRequired(call.reason)

                is StravaTokenManager.AuthorizedCall.RateLimited ->
                    outcome = StravaUploader.FileOutcome.RateLimited(call.reason)

                is StravaTokenManager.AuthorizedCall.Failed -> {
                    recordFailure(fileName, hash, uploadId = uploadId, reason = call.reason)
                    outcome = StravaUploader.FileOutcome.Failed(fileName, call.reason)
                }
            }
            delay = nextDelay(delay)
            attempt++
        }
        return outcome ?: StravaUploader.FileOutcome.Processing(fileName, uploadId)
    }

    private fun resolveTerminalResponse(
        fileName: String,
        hash: String,
        response: StravaUploadResponse
    ): StravaUploader.FileOutcome? {
        val resolvedActivityId = response.activityId
            ?: StravaApiClient.extractDuplicateActivityId(response.error)
        return when {
            resolvedActivityId != null -> {
                val url = StravaActivityUrls.activityUrl(resolvedActivityId)
                ledger.record(
                    StravaLedgerEntry(
                        fileName = fileName,
                        sha256 = hash,
                        status = StravaUploadStatus.UPLOADED,
                        uploadId = response.uploadId,
                        activityId = resolvedActivityId,
                        updatedAtEpochMillis = clockMillis()
                    )
                )
                StravaUploader.FileOutcome.Uploaded(
                    fileName = fileName,
                    uploadId = response.uploadId,
                    activityId = resolvedActivityId,
                    activityUrl = url
                )
            }

            !response.error.isNullOrBlank() -> {
                recordFailure(fileName, hash, uploadId = response.uploadId, reason = response.error)
                StravaUploader.FileOutcome.Failed(fileName, response.error)
            }

            else -> null
        }
    }

    private fun recordFailure(fileName: String, hash: String, uploadId: Long?, reason: String) {
        ledger.record(
            StravaLedgerEntry(
                fileName = fileName,
                sha256 = hash,
                status = StravaUploadStatus.FAILED,
                uploadId = uploadId,
                error = reason,
                updatedAtEpochMillis = clockMillis()
            )
        )
    }

    private fun nextDelay(current: Long): Long =
        minOf((current * POLL_BACKOFF_MULTIPLIER).coerceAtLeast(current), pollPolicy.maxDelayMillis)

    private fun mapAuthFailure(
        auth: StravaTokenManager.AuthState,
        fileName: String
    ): StravaUploader.FileOutcome = when (auth) {
        StravaTokenManager.AuthState.NotConnected -> StravaUploader.FileOutcome.NotConnected

        is StravaTokenManager.AuthState.AuthRequired ->
            StravaUploader.FileOutcome.AuthRequired(auth.reason)

        is StravaTokenManager.AuthState.Failed ->
            StravaUploader.FileOutcome.Failed(fileName, auth.reason)

        is StravaTokenManager.AuthState.Ready -> error("Ready state is not an error")
    }

    private companion object {
        const val POLL_BACKOFF_MULTIPLIER = 2L
    }
}

private fun runStatusFor(file: Path, entry: StravaLedgerEntry?): StravaRunStatus {
    val hash = readFileHash(file).getOrNull()
    val matching = if (hash != null && entry != null && entry.sha256 == hash) entry else null
    return if (matching == null) {
        StravaRunStatus(fileName = file.name, status = StravaUploadStatus.PENDING)
    } else {
        StravaRunStatus(
            fileName = file.name,
            status = matching.status,
            uploadId = matching.uploadId,
            activityId = matching.activityId,
            activityUrl = matching.activityUrl,
            error = matching.error
        )
    }
}

private fun readFileHash(fitFile: Path): Result<String> = try {
    Result.success(sha256(fitFile))
} catch (e: IOException) {
    Result.failure(e)
}

private fun listFitFiles(runsDir: Path): List<Path> = if (!Files.isDirectory(runsDir)) {
    emptyList()
} else {
    try {
        runsDir.listDirectoryEntries("*.fit")
            .filter { it.isRegularFile() }
            .sortedBy { it.name }
    } catch (@Suppress("SwallowedException") e: IOException) {
        emptyList()
    }
}
