package com.runpro.core.ble

import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Power
import com.runpro.core.model.PowerSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SourceId
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp

/**
 * Turns parsed measurements into model samples. Readings the model cannot represent are dropped here, at
 * the edge, so they never reach the recording path:
 *
 * - heart rate above [HeartRate.MAX_BPM] is dropped; lost skin contact becomes 0 bpm, the model's "no
 *   current heart rate" whatever value the strap sends with it;
 * - negative power is dropped;
 * - RSC gives speed, cadence (taken as steps per minute) and, when present, the sensor's cumulative
 *   distance (the engine works on its increases and treats a decrease as a reset);
 * - battery level is device status, not a sample.
 */
object BleSamples {
    fun from(measurement: BleMeasurement, time: Timestamp, source: SourceId): List<Sample> =
        when (measurement) {
            is HeartRateMeasurement -> heartRate(measurement, time, source)

            is RscMeasurement -> buildList {
                add(SpeedSample(time, source, measurement.speed))
                add(CadenceSample(time, source, Cadence(measurement.cadencePerMinute.toDouble())))
                measurement.totalDistance?.let { add(DistanceSample(time, source, it)) }
            }

            is CyclingPowerMeasurement ->
                measurement.instantaneousPowerWatts
                    .takeIf { it >= 0 }
                    ?.let { listOf(PowerSample(time, source, Power(it))) }
                    .orEmpty()

            is BatteryLevel -> emptyList()
        }

    private fun heartRate(
        hr: HeartRateMeasurement,
        time: Timestamp,
        source: SourceId
    ): List<Sample> {
        val bpm = if (hr.sensorContact == SensorContact.NOT_DETECTED) 0 else hr.heartRateBpm
        if (bpm > HeartRate.MAX_BPM) return emptyList()
        return listOf(HeartRateSample(time, source, HeartRate(bpm)))
    }
}
