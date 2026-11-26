package com.runpro.wear

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.runpro.core.recording.RunState
import com.runpro.core.recording.service.BatteryAdvice
import com.runpro.core.recording.service.BatteryGuidance
import com.runpro.core.recording.service.PermissionReadiness
import com.runpro.core.recording.service.RunPermission
import com.runpro.core.recording.service.ServiceCommand
import com.runpro.core.recording.service.ServicePermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Setup checks before a run (permissions, battery settings) and minimal run controls. The real run screens
 * are issue #12.
 */
class MainActivity : ComponentActivity() {
    private var readiness by mutableStateOf(ServicePermissions.readiness(emptySet()))
    private var advice by mutableStateOf(emptyList<BatteryAdvice>())
    private var runState by mutableStateOf<StateFlow<RunState>>(MutableStateFlow(RunState()))
    private var backgroundLocationSkipped by mutableStateOf(false)

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            (binder as? RecordingService.LocalBinder)?.let { runState = it.state }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runState = MutableStateFlow(RunState())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RunProScreen(
                SetupState(readiness, advice, backgroundLocationSkipped),
                runState,
                SetupActions(
                    askForeground = { ask(readiness.missingForeground) },
                    askBackgroundLocation = { ask(listOf(RunPermission.BACKGROUND_LOCATION)) },
                    skipBackgroundLocation = { backgroundLocationSkipped = true },
                    openBatterySettings = ::openBatterySettings,
                    confirmSleepingApps = ::confirmSleepingApps,
                    command = ::send
                )
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, RecordingService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        refresh() // the runner may come back from the settings
    }

    override fun onStop() {
        unbindService(connection)
        super.onStop()
    }

    private fun refresh() {
        readiness = ServicePermissions.readiness(AndroidPermissions.granted(this))
        val power = getSystemService(PowerManager::class.java)
        advice = BatteryGuidance.advice(
            ignoringOptimizations = power.isIgnoringBatteryOptimizations(packageName),
            manufacturer = Build.MANUFACTURER,
            sleepingAppsChecked = prefs().getBoolean(KEY_SLEEPING_APPS_CHECKED, false)
        )
    }

    private fun ask(permissions: List<RunPermission>) {
        val platform = permissions.flatMap { AndroidPermissions.platform(it) }
        if (platform.isEmpty()) refresh() else askPermissions.launch(platform.toTypedArray())
    }

    private fun send(command: ServiceCommand) {
        val intent = RecordingService.intent(this, command)
        if (command == ServiceCommand.START) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    /**
     * RunPro's own settings page, which has the Battery entry. Not `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`:
     * on Wear OS it resolves to a placeholder activity that closes at once (field bug on the watch, #10).
     */
    private fun openBatterySettings() {
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        try {
            startActivity(details)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun confirmSleepingApps() {
        prefs().edit { putBoolean(KEY_SLEEPING_APPS_CHECKED, true) }
        refresh()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "setup"
        const val KEY_SLEEPING_APPS_CHECKED = "sleeping_apps_checked"
    }
}

/** What the setup part of the screen shows. */
data class SetupState(
    val readiness: PermissionReadiness,
    val advice: List<BatteryAdvice>,
    val backgroundLocationSkipped: Boolean
)

class SetupActions(
    val askForeground: () -> Unit,
    val askBackgroundLocation: () -> Unit,
    val skipBackgroundLocation: () -> Unit,
    val openBatterySettings: () -> Unit,
    val confirmSleepingApps: () -> Unit,
    val command: (ServiceCommand) -> Unit
)
