package com.runpro.mobile.strava

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import com.runpro.core.sync.strava.StravaAccount
import com.runpro.core.sync.strava.StravaConfig
import com.runpro.core.sync.strava.StravaRunStatus
import com.runpro.core.sync.strava.StravaTokens
import com.runpro.core.sync.strava.StravaUploadStatus
import com.runpro.core.sync.strava.StravaUploader
import com.runpro.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone companion's Strava screen (#31): connect through Strava OAuth (the callback
 * `runpro://<callbackHost>/oauth/callback` comes back to this single-task activity), choose automatic
 * upload, upload or retry pending runs, and open each uploaded run on Strava.
 */
@Suppress("TooManyFunctions")
class StravaActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var statusText: TextView
    private lateinit var messageText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var autoUploadSwitch: Switch
    private lateinit var uploadButton: Button
    private lateinit var runsList: LinearLayout

    private val autoUploadListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        runInBackground({ uploader().account.setAutoUpload(checked) }) { refresh() }
    }

    private data class ScreenState(
        val configured: Boolean,
        val tokens: StravaTokens?,
        val autoUpload: Boolean,
        val runs: List<StravaRunStatus>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strava)
        statusText = findViewById(R.id.strava_status)
        messageText = findViewById(R.id.strava_message)
        connectButton = findViewById(R.id.strava_connect_button)
        disconnectButton = findViewById(R.id.strava_disconnect_button)
        autoUploadSwitch = findViewById(R.id.strava_auto_upload_switch)
        uploadButton = findViewById(R.id.strava_upload_button)
        runsList = findViewById(R.id.strava_runs)

        connectButton.setOnClickListener { connect() }
        disconnectButton.setOnClickListener {
            runInBackground({ uploader().account.disconnect() }) { refresh() }
        }
        uploadButton.setOnClickListener { uploadPending() }

        // A recreated activity keeps its intent: only a fresh launch may consume the OAuth callback.
        if (savedInstanceState == null && isOAuthCallback(intent)) {
            completeOAuth(intent)
        } else {
            refresh()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isOAuthCallback(intent)) completeOAuth(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun uploader(): StravaUploader = StravaUploadRunner.uploader(this)

    private fun isOAuthCallback(intent: Intent?): Boolean = intent?.action == Intent.ACTION_VIEW &&
        intent.data?.scheme.equals(StravaConfig.CALLBACK_SCHEME, ignoreCase = true)

    private fun connect() {
        runInBackground({ uploader().account.beginOAuth() }) { url ->
            if (url == null) {
                showMessage(getString(R.string.strava_status_not_configured))
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (@Suppress("SwallowedException") e: ActivityNotFoundException) {
                    showMessage(getString(R.string.strava_status_no_browser))
                }
            }
        }
    }

    private fun completeOAuth(callback: Intent) {
        val uri = callback.data?.toString().orEmpty()
        showMessage(getString(R.string.strava_status_connecting))
        runInBackground({ uploader().account.completeOAuth(uri) }) { outcome ->
            showMessage(formatOAuth(outcome))
            refresh()
        }
    }

    private fun uploadPending() {
        uploadButton.isEnabled = false
        showMessage(getString(R.string.strava_status_uploading))
        runInBackground({ uploader().uploadPending() }) { outcome ->
            showMessage(formatBatch(outcome))
            refresh()
        }
    }

    private fun refresh() {
        runInBackground({
            val uploader = uploader()
            ScreenState(
                configured = uploader.account.isConfigured,
                tokens = uploader.account.currentTokens(),
                autoUpload = uploader.account.preferences().autoUpload,
                runs = uploader.listRuns()
            )
        }) { render(it) }
    }

    private fun render(state: ScreenState) {
        val connected = state.configured && state.tokens != null
        statusText.text = when {
            !state.configured -> getString(R.string.strava_status_not_configured)
            state.tokens == null -> getString(R.string.strava_status_disconnected)
            state.tokens.athleteName.isNullOrBlank() -> getString(R.string.strava_status_connected)
            else -> getString(R.string.strava_status_connected_as, state.tokens.athleteName)
        }
        connectButton.isEnabled = state.configured
        connectButton.setText(
            if (connected) R.string.strava_reconnect else R.string.strava_connect
        )
        disconnectButton.isEnabled = connected
        uploadButton.isEnabled = connected
        autoUploadSwitch.setOnCheckedChangeListener(null)
        autoUploadSwitch.isChecked = state.autoUpload
        autoUploadSwitch.isEnabled = connected
        autoUploadSwitch.setOnCheckedChangeListener(autoUploadListener)
        renderRuns(state.runs)
    }

    private fun renderRuns(runs: List<StravaRunStatus>) {
        runsList.removeAllViews()
        if (runs.isEmpty()) {
            runsList.addView(TextView(this).apply { setText(R.string.strava_runs_empty) })
            return
        }
        for (run in runs.sortedByDescending { it.fileName }) {
            runsList.addView(TextView(this).apply { text = formatRun(run) })
            val url = run.activityUrl ?: continue
            runsList.addView(
                Button(this).apply {
                    setText(R.string.strava_open_activity)
                    contentDescription = getString(R.string.strava_open_activity_for, run.fileName)
                    setOnClickListener { openUrl(url) }
                }
            )
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (@Suppress("SwallowedException") e: ActivityNotFoundException) {
            showMessage(getString(R.string.strava_status_no_browser))
        }
    }

    private fun showMessage(message: String) {
        messageText.text = message
    }

    private fun formatRun(run: StravaRunStatus): String = when (run.status) {
        StravaUploadStatus.PENDING -> getString(R.string.strava_run_pending, run.fileName)

        StravaUploadStatus.PROCESSING -> getString(R.string.strava_run_processing, run.fileName)

        StravaUploadStatus.UPLOADED -> getString(R.string.strava_run_uploaded, run.fileName)

        StravaUploadStatus.FAILED ->
            getString(R.string.strava_run_failed, run.fileName, run.error.orEmpty())
    }

    private fun formatOAuth(outcome: StravaAccount.OAuthCompletionOutcome): String =
        when (outcome) {
            is StravaAccount.OAuthCompletionOutcome.Connected -> getString(
                R.string.strava_result_connected
            )

            StravaAccount.OAuthCompletionOutcome.NotConfigured ->
                getString(R.string.strava_status_not_configured)

            is StravaAccount.OAuthCompletionOutcome.Denied -> getString(
                R.string.strava_result_denied
            )

            is StravaAccount.OAuthCompletionOutcome.Failed ->
                getString(R.string.strava_result_connect_failed, outcome.reason)
        }

    private fun formatBatch(outcome: StravaUploader.BatchOutcome): String {
        val summary = resources.getQuantityString(
            R.plurals.strava_result_batch,
            outcome.uploaded.size,
            outcome.uploaded.size,
            outcome.alreadyUploaded.size,
            outcome.processing.size,
            outcome.failed.size
        )
        return when {
            outcome.notConfigured -> getString(R.string.strava_status_not_configured)

            outcome.notConnected -> getString(R.string.strava_result_not_connected)

            outcome.authRequiredReason != null ->
                getString(R.string.strava_result_auth_required, summary)

            outcome.rateLimitedReason != null ->
                getString(R.string.strava_result_rate_limited, summary)

            else -> summary
        }
    }

    private fun <T> runInBackground(work: () -> T, onDone: (T) -> Unit) {
        scope.launch {
            @Suppress("TooGenericExceptionCaught")
            val result = try {
                Result.success(withContext(StravaUploadRunner.dispatcher) { work() })
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(onDone) { error ->
                showMessage(getString(R.string.strava_result_error, error.javaClass.simpleName))
            }
        }
    }
}
