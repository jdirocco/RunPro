package com.runpro.wear.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log
import com.runpro.core.model.ImuSensor
import com.runpro.core.recording.imu.ImuPort
import com.runpro.core.recording.imu.ImuRate
import com.runpro.core.recording.imu.ImuSink

/**
 * [ImuPort] on `SensorManager`. Listeners run on [handler] (the IMU thread, never the main or recording
 * thread). The wake-up variant of a sensor is preferred: the sensor hub then wakes the processor when its
 * batch is due, so readings are not lost while the watch sleeps during a run (ADR 0012).
 * `SensorEvent.timestamp` is on the `elapsedRealtimeNanos` clock on Wear OS.
 */
class AndroidImuPort(context: Context, private val handler: Handler) : ImuPort {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener? = null

    override fun start(sensors: Set<ImuSensor>, rate: ImuRate, sink: ImuSink): Set<ImuSensor> {
        stop()
        val manager = manager ?: return emptySet()
        val found = sensors.mapNotNull { imu -> find(manager, imu)?.let { it to imu } }.toMap()
        val callback = Listener(found, sink)
        listener = callback
        val periodUs = rate.samplingPeriod.inWholeMicroseconds.toInt()
        val latencyUs = rate.maxReportLatency.inWholeMicroseconds.toInt()
        val started = found.filter { (sensor, imu) ->
            manager.registerListener(callback, sensor, periodUs, latencyUs, handler).also { ok ->
                Log.i(
                    TAG,
                    "$imu ${sensor.name} wakeUp=${sensor.isWakeUpSensor} " +
                        "fifo=${sensor.fifoMaxEventCount} registered=$ok"
                )
            }
        }.values.toSet()
        if (started.isEmpty()) stop()
        return started
    }

    override fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
    }

    private class Listener(private val sensors: Map<Sensor, ImuSensor>, private val sink: ImuSink) :
        SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val imu = sensors[event.sensor] ?: return
            val v = event.values
            if (v.size >= AXES) sink.onReading(imu, event.timestamp, v[0], v[1], v[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun find(manager: SensorManager, sensor: ImuSensor): Sensor? {
        val type = when (sensor) {
            ImuSensor.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
            ImuSensor.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
        }
        return manager.getDefaultSensor(type, true) ?: manager.getDefaultSensor(type)
    }

    private companion object {
        const val TAG = "AndroidImuPort"
        const val AXES = 3
    }
}
