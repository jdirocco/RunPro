package com.runpro.wear.imu

import android.content.Context
import androidx.core.content.edit
import com.runpro.core.recording.imu.ImuMode

/** The IMU capture setting (ADR 0012), shared by the setup screen and the service. */
internal object ImuPreferences {
    /** Accelerometer only: the issue #33 baseline; the gyroscope is chosen for battery comparisons. */
    val DEFAULT = ImuMode.ACCELEROMETER

    private fun prefs(context: Context) = context.getSharedPreferences("imu", Context.MODE_PRIVATE)

    fun load(context: Context): ImuMode = prefs(context).getString(KEY, null)
        ?.let { name -> ImuMode.entries.firstOrNull { it.name == name } }
        ?: DEFAULT

    fun save(context: Context, mode: ImuMode) {
        prefs(context).edit { putString(KEY, mode.name) }
    }

    private const val KEY = "mode"
}
