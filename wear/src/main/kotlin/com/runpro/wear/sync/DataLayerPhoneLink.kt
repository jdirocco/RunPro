package com.runpro.wear.sync

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.runpro.core.sync.PhoneLink
import com.runpro.core.sync.SyncChannel
import com.runpro.core.sync.SyncProtocol
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [PhoneLink] over the Wearable Data Layer: a `ChannelClient` channel to a reachable node that declares
 * [SyncProtocol.PHONE_CAPABILITY]. Blocking: call on the sync thread only. A channel is closed after
 * [TRANSFER_TIMEOUT_S] whatever happens, so a phone that stops answering cannot hang the sync.
 */
internal class DataLayerPhoneLink(
    context: Context,
    private val watchdog: ScheduledExecutorService
) : PhoneLink {
    private val capabilities = Wearable.getCapabilityClient(context)
    private val channels = Wearable.getChannelClient(context)

    override fun open(): SyncChannel? {
        val nodes = await(
            capabilities.getCapability(
                SyncProtocol.PHONE_CAPABILITY,
                CapabilityClient.FILTER_REACHABLE
            )
        ).nodes
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return null
        val channel = await(channels.openChannel(node.id, SyncProtocol.CHANNEL_PATH))
        return try {
            DataLayerChannel(
                channel,
                await(channels.getInputStream(channel)),
                await(channels.getOutputStream(channel))
            )
        } catch (e: IOException) {
            channels.close(channel)
            throw e
        }
    }

    private inner class DataLayerChannel(
        private val channel: ChannelClient.Channel,
        override val input: InputStream,
        override val output: OutputStream
    ) : SyncChannel {
        private val deadline = watchdog.schedule({ close() }, TRANSFER_TIMEOUT_S, TimeUnit.SECONDS)

        override fun close() {
            deadline.cancel(false)
            channels.close(channel)
        }
    }

    private fun <T> await(task: Task<T>): T = try {
        Tasks.await(task, CALL_TIMEOUT_S, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        throw IOException("Data Layer call failed", e.cause ?: e)
    } catch (e: TimeoutException) {
        throw IOException("Data Layer call timed out", e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("interrupted", e)
    }

    private companion object {
        const val CALL_TIMEOUT_S = 30L
        const val TRANSFER_TIMEOUT_S = 120L
    }
}
