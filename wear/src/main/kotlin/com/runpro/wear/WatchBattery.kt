package com.runpro.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Sticky initial battery state plus changes, including while a run is paused. No disk work. */
internal class WatchBattery(private val context: Context, private val changed: (Int) -> Unit) :
    AutoCloseable {
    @Volatile var percent: Int? = null
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = receive(intent)
    }

    fun start() {
        // BATTERY_CHANGED is a protected system broadcast, so no export flag is required.
        receive(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    private fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (scale <= 0 || level !in 0..scale) return
        val reading = (level.toLong() * FULL_PERCENT / scale).toInt()
        percent = reading
        changed(reading)
    }

    override fun close() = context.unregisterReceiver(receiver)

    private companion object {
        const val FULL_PERCENT = 100
    }
}
