package com.runpro.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState

/**
 * The ongoing notification of a run and its Wear Ongoing Activity (the icon on the watch face and in the
 * recent apps that brings the runner back to RunPro). The stopwatch runs on the system's elapsed realtime
 * clock, so it keeps counting even while RunPro does not update the notification.
 */
class RunNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.run_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.run_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Builds the notification for [state] and attaches the Ongoing Activity to it. */
    fun build(state: RunState): android.app.Notification {
        val touch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = context.getString(phaseText(state.phase))
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(touch)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(touch)
            .setStatus(status(state, text))
            .build()
            .apply(context)
        return builder.build()
    }

    /** Re-posts the notification; skipped without the notification permission (the run goes on). */
    fun update(state: RunState) {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            manager.notify(NOTIFICATION_ID, build(state))
        }
    }

    private fun status(state: RunState, text: String): Status {
        val timerMillis = state.totals.timer.inWholeMilliseconds
        val now = SystemClock.elapsedRealtime()
        val stopwatch = when (state.phase) {
            RunPhase.RUNNING -> Status.StopwatchPart(now - timerMillis)
            RunPhase.PAUSED -> Status.StopwatchPart(now - timerMillis, now)
            else -> return Status.forPart(Status.TextPart(text))
        }
        return Status.Builder()
            .addTemplate("#text# · #timer#")
            .addPart("text", Status.TextPart(text))
            .addPart("timer", stopwatch)
            .build()
    }

    private fun phaseText(phase: RunPhase): Int = when (phase) {
        RunPhase.PAUSED -> R.string.run_paused
        RunPhase.RUNNING -> R.string.run_recording
        RunPhase.STOPPED -> R.string.run_stopped
        RunPhase.IDLE, RunPhase.PREPARING -> R.string.run_starting
    }

    companion object {
        const val CHANNEL_ID = "run"
        const val NOTIFICATION_ID = 1
    }
}
