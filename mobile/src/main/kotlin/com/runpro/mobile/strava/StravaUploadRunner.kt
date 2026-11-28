package com.runpro.mobile.strava

import android.content.Context
import android.util.Log
import com.runpro.core.sync.strava.StravaApiClient
import com.runpro.core.sync.strava.StravaConfig
import com.runpro.core.sync.strava.StravaStores
import com.runpro.core.sync.strava.StravaUploader
import com.runpro.mobile.BuildConfig
import com.runpro.mobile.FitReceiverService
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Runs every Strava call (OAuth exchange, uploads, polling) on one background thread, so an automatic
 * upload after a sync and a manual retry from [StravaActivity] never race on the same run. It never
 * blocks the Data Layer receiver: [FitReceiverService] schedules work only after the run is stored and
 * acknowledged (R3 unchanged), and the FIT files in `files/runs` are only read.
 */
object StravaUploadRunner {
    private const val TAG = "StravaUpload"

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "runpro-strava").apply { isDaemon = true }
    }

    /** Where [StravaActivity] runs its Strava work; tests replace it to run inline. */
    internal var dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /** Builds the uploader; tests replace it with one backed by a fake transport. */
    internal var uploaderFactory: (Context) -> StravaUploader = ::defaultUploader

    /** The credentials of this build (ADR 0015); blank in builds without `strava.*` in local.properties. */
    fun buildConfig(): StravaConfig = StravaConfig(
        clientId = BuildConfig.STRAVA_CLIENT_ID,
        clientSecret = BuildConfig.STRAVA_CLIENT_SECRET,
        callbackHost = BuildConfig.STRAVA_CALLBACK_HOST.ifBlank {
            StravaConfig.DEFAULT_CALLBACK_HOST
        }
    )

    fun stateDir(context: Context): File = File(context.filesDir, "strava")

    fun uploader(context: Context): StravaUploader = uploaderFactory(context.applicationContext)

    /**
     * Uploads [fitFile] in the background when the runner opted in to automatic upload and is
     * connected. A failure stays in the ledger and is retried from [StravaActivity].
     */
    fun scheduleAutoUpload(context: Context, fitFile: Path) {
        val appContext = context.applicationContext
        executor.execute {
            @Suppress("TooGenericExceptionCaught")
            try {
                val outcome = uploader(appContext).autoUploadIfEnabled(fitFile)
                Log.i(TAG, "Auto upload ${fitFile.fileName}: ${describe(outcome)}")
            } catch (e: Exception) {
                Log.w(TAG, "Strava auto upload failed for ${fitFile.fileName}", e)
            }
        }
    }

    /** A log line without tokens or the client secret: only the outcome kind and file name. */
    internal fun describe(outcome: StravaUploader.FileOutcome): String =
        outcome::class.simpleName.orEmpty()

    private fun defaultUploader(context: Context): StravaUploader {
        val config = buildConfig()
        return StravaUploader(
            runsDir = FitReceiverService.runs(context).toPath(),
            config = config,
            client = StravaApiClient(config),
            stores = StravaStores.fileBacked(stateDir(context).toPath())
        )
    }
}
