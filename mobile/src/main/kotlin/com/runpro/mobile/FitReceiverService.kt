package com.runpro.mobile

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.runpro.core.sync.FitInbox
import com.runpro.core.sync.SyncChannel
import com.runpro.core.sync.SyncProtocol
import com.runpro.mobile.healthconnect.HealthConnectExportRunner
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Receives FIT files from the watch over `ChannelClient` into `files/runs` and acknowledges them through
 * [FitInbox] (stored durably first, each run once). Play services calls it on a background thread.
 */
class FitReceiverService : WearableListenerService() {
    @Suppress("TooGenericExceptionCaught")
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != SyncProtocol.CHANNEL_PATH) return
        val client = Wearable.getChannelClient(this)
        try {
            val input = await(client.getInputStream(channel))
            val output = await(client.getOutputStream(channel))
            val outcome = FitInbox(runs(this).toPath()).receive(
                object : SyncChannel {
                    override val input: InputStream = input
                    override val output: OutputStream = output
                    override fun close() = Unit
                }
            )
            Log.i(TAG, "FIT from ${channel.nodeId}: $outcome")
            output.close()
            if (outcome is FitInbox.Outcome.Stored) {
                HealthConnectExportRunner.scheduleExport(applicationContext, outcome.file)
            }
        } catch (e: Exception) {
            // Nothing was acknowledged; the watch keeps the journal and sends the run again.
            Log.w(TAG, "FIT transfer failed", e)
        } finally {
            client.close(channel)
        }
    }

    private fun <T> await(task: Task<T>): T = Tasks.await(task, CALL_TIMEOUT_S, TimeUnit.SECONDS)

    companion object {
        private const val TAG = "FitReceiver"
        private const val CALL_TIMEOUT_S = 30L

        /** The received runs, one `<run id>.fit` each; Strava and Health Connect (#31, #32) read them. */
        fun runs(context: android.content.Context): File = File(context.filesDir, "runs")
    }
}
