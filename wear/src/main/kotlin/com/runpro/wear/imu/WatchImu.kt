package com.runpro.wear.imu

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.imu.ImuCapture
import com.runpro.wear.sync.WatchStorage
import java.util.concurrent.Executor

/** The watch IMU capture (ADR 0012): `SensorManager` readings and stream writes on [thread]. */
internal object WatchImu {
    private const val TAG = "ImuCapture"
    private const val FULL_BATTERY = 100

    fun capture(context: Context, thread: HandlerThread, now: () -> Timestamp): ImuCapture {
        val handler = Handler(thread.looper)
        return ImuCapture(
            AndroidImuPort(context, handler),
            WatchStorage.imu(context).toPath(),
            Executor(handler::post),
            now,
            // Battery at every change of the capture: the battery cost of the IMU is a field result.
            onChange = { Log.i(TAG, "$it battery=${batteryPercent(context)}%") }
        )
    }

    private fun batteryPercent(context: Context): Int? =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..FULL_BATTERY }
}
