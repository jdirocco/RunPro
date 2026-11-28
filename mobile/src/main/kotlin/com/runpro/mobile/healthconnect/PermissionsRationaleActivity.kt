package com.runpro.mobile.healthconnect

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.runpro.core.sync.healthconnect.HealthConnectExporter
import com.runpro.core.sync.healthconnect.HealthConnectPort
import com.runpro.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Required Health Connect permissions rationale activity (handles both
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` on API 30-33 and
 * `android.intent.action.VIEW_PERMISSION_USAGE` via `ViewPermissionUsageActivity` on API 34+).
 *
 * Explains RunPro's write-only Health Connect usage and lets the runner grant permissions and
 * export any pending runs received from the watch.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var statusText: TextView
    private lateinit var grantButton: Button
    private lateinit var exportButton: Button

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectPermissions.ALL_WRITE_PERMISSIONS)) {
            runExportPending()
        } else {
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_connect_rationale)
        statusText = findViewById(R.id.health_connect_status)
        grantButton = findViewById(R.id.health_connect_grant_button)
        exportButton = findViewById(R.id.health_connect_export_button)

        grantButton.setOnClickListener {
            requestPermissions.launch(HealthConnectPermissions.ALL_WRITE_PERMISSIONS)
        }
        exportButton.setOnClickListener {
            runExportPending()
        }
        refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val port = portFactory(this)
        when (port.availability()) {
            HealthConnectPort.Availability.AVAILABLE -> {
                grantButton.isEnabled = true
                exportButton.isEnabled = true
                statusText.setText(R.string.health_connect_status_ready)
            }

            HealthConnectPort.Availability.UPDATE_REQUIRED -> {
                grantButton.isEnabled = false
                exportButton.isEnabled = false
                statusText.setText(R.string.health_connect_status_update_required)
            }

            HealthConnectPort.Availability.UNAVAILABLE -> {
                grantButton.isEnabled = false
                exportButton.isEnabled = false
                statusText.setText(R.string.health_connect_status_unavailable)
            }
        }
    }

    private fun runExportPending() {
        exportButton.isEnabled = false
        statusText.setText(R.string.health_connect_status_exporting)
        val appContext = applicationContext
        val port = portFactory(appContext)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                HealthConnectExportRunner.exporter(appContext, port).exportPending()
            }
            exportButton.isEnabled =
                outcome.availability == HealthConnectPort.Availability.AVAILABLE
            statusText.text = formatBatchOutcome(outcome)
        }
    }

    private fun formatBatchOutcome(outcome: HealthConnectExporter.BatchOutcome): String = when {
        outcome.availability == HealthConnectPort.Availability.UPDATE_REQUIRED ->
            getString(R.string.health_connect_status_update_required)

        outcome.availability == HealthConnectPort.Availability.UNAVAILABLE ->
            getString(R.string.health_connect_status_unavailable)

        outcome.missingPermissions.isNotEmpty() ->
            getString(R.string.health_connect_status_missing_permissions)

        outcome.failed.isNotEmpty() -> resources.getQuantityString(
            R.plurals.health_connect_status_partial,
            outcome.exported.size,
            outcome.exported.size,
            outcome.skipped.size,
            outcome.failed.size
        )

        else -> resources.getQuantityString(
            R.plurals.health_connect_status_done,
            outcome.exported.size,
            outcome.exported.size,
            outcome.skipped.size
        )
    }

    companion object {
        internal var portFactory: (Context) -> HealthConnectPort = { context ->
            AndroidHealthConnectPort(
                sdkStatus = { HealthConnectClient.getSdkStatus(context) },
                clientProvider = { HealthConnectClient.getOrCreate(context) }
            )
        }
    }
}
