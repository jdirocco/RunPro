package com.runpro.wear.ble

import android.content.Context
import androidx.core.content.edit
import com.runpro.core.ble.PairedSensor
import com.runpro.core.ble.PairedSensors

/** The paired sensors, read when a run prepares (and when a killed run is restored). */
internal object BlePreferences {
    private const val PREFS = "ble_sensors"
    private const val KEY_SENSORS = "paired"

    fun load(context: Context): List<PairedSensor> =
        PairedSensors.decode(prefs(context).getString(KEY_SENSORS, null))

    fun save(context: Context, sensors: List<PairedSensor>) {
        prefs(context).edit { putString(KEY_SENSORS, PairedSensors.encode(sensors)) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
