package com.runpro.mobile.healthconnect

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import com.runpro.core.sync.healthconnect.HealthConnectExporter
import com.runpro.core.sync.healthconnect.HealthConnectPort
import com.runpro.core.sync.healthconnect.HealthConnectRun
import com.runpro.mobile.FitReceiverService
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * Thin Android adapter implementing [HealthConnectPort] on top of [HealthConnectClient].
 *
 * Accepts injectable status and client providers so JVM/Robolectric tests exercise availability,
 * permission checks, record insertion and error mapping without a live Health Connect provider.
 */
class AndroidHealthConnectPort(
    private val sdkStatus: () -> Int,
    private val clientProvider: () -> HealthConnectClient
) : HealthConnectPort {
    override fun availability(): HealthConnectPort.Availability = when (sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectPort.Availability.AVAILABLE

        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            HealthConnectPort.Availability.UPDATE_REQUIRED

        else -> HealthConnectPort.Availability.UNAVAILABLE
    }

    override suspend fun write(run: HealthConnectRun): HealthConnectPort.WriteOutcome {
        val status = availability()
        return if (status != HealthConnectPort.Availability.AVAILABLE) {
            HealthConnectPort.WriteOutcome.Failed("Health Connect unavailable: $status")
        } else {
            writeWithClient(run)
        }
    }

    private suspend fun writeWithClient(run: HealthConnectRun): HealthConnectPort.WriteOutcome {
        val required = HealthConnectPermissions.requiredFor(run)
        return try {
            val client = clientProvider()
            val granted = client.permissionController.getGrantedPermissions()
            val missing = required - granted
            if (missing.isNotEmpty()) {
                HealthConnectPort.WriteOutcome.MissingPermissions(missing)
            } else {
                client.insertRecords(HealthConnectRecordMapper.toRecords(run))
                HealthConnectPort.WriteOutcome.Written
            }
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            HealthConnectPort.WriteOutcome.MissingPermissions(required)
        } catch (e: RemoteException) {
            HealthConnectPort.WriteOutcome.Failed("Health Connect IPC error: ${e.message}")
        } catch (e: IOException) {
            HealthConnectPort.WriteOutcome.Failed("Health Connect I/O error: ${e.message}")
        } catch (e: IllegalStateException) {
            HealthConnectPort.WriteOutcome.Failed("Health Connect state error: ${e.message}")
        } catch (e: IllegalArgumentException) {
            HealthConnectPort.WriteOutcome.Failed("Invalid Health Connect record: ${e.message}")
        }
    }

    companion object {
        fun create(context: Context): AndroidHealthConnectPort {
            val appContext = context.applicationContext
            return AndroidHealthConnectPort(
                sdkStatus = { HealthConnectClient.getSdkStatus(appContext) },
                clientProvider = { HealthConnectClient.getOrCreate(appContext) }
            )
        }
    }
}

/**
 * Serialized background runner for exporting received FIT files to Health Connect without ever
 * blocking the Wearable Data Layer receiver or main thread.
 */
object HealthConnectExportRunner {
    private const val TAG = "HealthConnectExport"

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "runpro-health-connect").apply { isDaemon = true }
    }

    fun stateDir(context: Context): File = File(context.filesDir, "health-connect")

    fun exporter(
        context: Context,
        port: HealthConnectPort = AndroidHealthConnectPort.create(context)
    ): HealthConnectExporter {
        val zone = ZoneId.systemDefault()
        return HealthConnectExporter(
            runsDir = FitReceiverService.runs(context).toPath(),
            stateDir = stateDir(context).toPath(),
            port = port,
            zoneOffsetAt = { instant -> zone.rules.getOffset(instant) }
        )
    }

    /**
     * Best-effort background export after [FitReceiverService] has durably stored and acknowledged
     * [fitFile]. Failures are logged and retried on the next sync or permission grant.
     */
    fun scheduleExport(context: Context, fitFile: Path) {
        val appContext = context.applicationContext
        executor.execute {
            @Suppress("TooGenericExceptionCaught")
            try {
                val outcome = runBlocking { exporter(appContext).exportFile(fitFile) }
                Log.i(TAG, "Export ${fitFile.fileName}: $outcome")
            } catch (e: Exception) {
                Log.w(TAG, "Health Connect export failed for ${fitFile.fileName}", e)
            }
        }
    }
}
