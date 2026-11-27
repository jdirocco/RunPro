package com.runpro.tools.replay

import com.runpro.core.model.AccelerationSample
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LocationSample
import com.runpro.core.model.PowerSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp

/** Copies every model variant so clock faults preserve the source and payload. */
internal fun TraceRecord.withTime(time: Timestamp): TraceRecord = when (this) {
    is TraceRecord.SampleRecord -> TraceRecord.SampleRecord(sample.withTime(time))
    is TraceRecord.EventRecord -> TraceRecord.EventRecord(event.withTime(time))
    is TraceRecord.ImuRecord -> TraceRecord.ImuRecord(batch.copy(time = time))
}

private fun Sample.withTime(time: Timestamp): Sample = when (this) {
    is AccelerationSample -> copy(time = time)
    is CadenceSample -> copy(time = time)
    is DistanceSample -> copy(time = time)
    is ElevationSample -> copy(time = time)
    is HeartRateSample -> copy(time = time)
    is LocationSample -> copy(time = time)
    is PowerSample -> copy(time = time)
    is SpeedSample -> copy(time = time)
}

private fun SensorEvent.withTime(time: Timestamp): SensorEvent = when (this) {
    is SensorEvent.Connected -> copy(time = time)
    is SensorEvent.Disconnected -> copy(time = time)
    is SensorEvent.GpsAcquired -> copy(time = time)
    is SensorEvent.GpsLost -> copy(time = time)
}
