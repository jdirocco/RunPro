package com.runpro.wear.sync

import android.content.Context
import android.util.Log
import com.runpro.core.sync.FitOutbox
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Where the watch keeps journals and FIT exports. The journal writer (#60) must use [journals]. */
object WatchStorage {
    fun journals(context: Context): File = File(context.filesDir, "journals")

    fun exports(context: Context): File = File(context.filesDir, "exports")
}

/**
 * Sends stopped runs to the phone on its own thread, never on the main or recording thread (R6). A pass runs
 * when the app starts, when a run stops and when the phone becomes reachable ([PhoneSyncListenerService]);
 * while runs wait for an ack it is retried with backoff (30 s up to 15 min) as long as the process lives.
 */
object PhoneSync {
    private const val TAG = "PhoneSync"
    private const val FIRST_RETRY_S = 30L
    private const val MAX_RETRY_S = 15 * 60L

    private val executor: ScheduledExecutorService = daemonThread("runpro-sync")
    private val watchdog: ScheduledExecutorService = daemonThread("runpro-sync-watchdog")
    private val queued = AtomicBoolean(false)

    // Only touched on the sync thread.
    private var outbox: FitOutbox? = null
    private var retry: ScheduledFuture<*>? = null
    private var retryDelayS = FIRST_RETRY_S

    fun request(context: Context) {
        val app = context.applicationContext
        if (queued.compareAndSet(false, true)) {
            executor.execute {
                queued.set(false)
                pass(app)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun pass(context: Context) {
        retry?.cancel(false)
        val needsRetry = try {
            val outbox = outbox ?: FitOutbox(
                WatchStorage.journals(context).toPath(),
                WatchStorage.exports(context).toPath(),
                utcOffsetSeconds = {
                    TimeZone.getDefault().getOffset(System.currentTimeMillis()) /
                        1000
                }
            ).also { outbox = it }
            val report = outbox.sync(DataLayerPhoneLink(context, watchdog))
            Log.i(
                TAG,
                "delivered ${report.delivered}, waiting ${report.waiting}, phone reachable ${report.phoneReachable}"
            )
            report.problems.forEach { Log.w(TAG, it) }
            report.needsRetry
        } catch (e: Exception) {
            // Play services missing or a storage error: the journals stay, try again later.
            Log.w(TAG, "sync pass failed", e)
            true
        }
        if (needsRetry) {
            retry = executor.schedule({ pass(context) }, retryDelayS, TimeUnit.SECONDS)
            retryDelayS = minOf(retryDelayS * 2, MAX_RETRY_S)
        } else {
            retryDelayS = FIRST_RETRY_S
        }
    }
}

private fun daemonThread(name: String): ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { Thread(it, name).apply { isDaemon = true } }
