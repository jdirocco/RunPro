package com.runpro.wear

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchBatteryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `sticky level is available immediately and runtime changes stop after close`() {
        app.sendStickyBroadcast(battery(30, 200))
        val readings = mutableListOf<Int>()
        val battery = WatchBattery(app, readings::add)
        battery.start()
        assertEquals(15, battery.percent)
        ShadowLooper.idleMainLooper()
        assertEquals(15, readings.last())

        app.sendBroadcast(battery(3))
        ShadowLooper.idleMainLooper()
        assertEquals(3, battery.percent)
        assertEquals(3, readings.last())

        battery.close()
        app.sendBroadcast(battery(80))
        ShadowLooper.idleMainLooper()
        assertEquals(3, readings.last())
    }

    @Test
    fun `missing and malformed values do not invent critical battery readings`() {
        val readings = mutableListOf<Int>()
        val battery = WatchBattery(app, readings::add)
        battery.start()
        assertNull(battery.percent)
        listOf(battery(-1), battery(101), battery(10, 0), Intent(Intent.ACTION_BATTERY_CHANGED))
            .forEach { app.sendBroadcast(it) }
        ShadowLooper.idleMainLooper()
        assertNull(battery.percent)
        assertEquals(emptyList<Int>(), readings)
        battery.close()
    }
}

internal fun battery(level: Int, scale: Int = 100): Intent = Intent(Intent.ACTION_BATTERY_CHANGED)
    .putExtra(BatteryManager.EXTRA_LEVEL, level)
    .putExtra(BatteryManager.EXTRA_SCALE, scale)
