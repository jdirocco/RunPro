package com.runpro.tools.replay

import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import kotlin.time.Duration.Companion.seconds

internal object Fixtures {
    val t0 = Timestamp(elapsedRealtimeNanos = 10_000_000_000, wallClockMillis = 1_764_064_800_000)
    val strap = SourceId.ble("AA:BB:CC:DD:EE:FF")
    val watch = SourceId.HEALTH_SERVICES

    val header = TraceHeader(name = "fixture", description = "Three seconds of a run")

    /** Strap connects, then 3 s of strap HR and watch speed, with one strap disconnect. */
    val trace = Trace(
        header,
        listOf(
            TraceRecord.EventRecord(SensorEvent.Connected(t0, strap)),
            TraceRecord.SampleRecord(HeartRateSample(t0, strap, HeartRate(120))),
            TraceRecord.SampleRecord(SpeedSample(t0, watch, Speed(2.5))),
            TraceRecord.SampleRecord(HeartRateSample(t0 + 1.seconds, strap, HeartRate(121))),
            TraceRecord.SampleRecord(SpeedSample(t0 + 1.seconds, watch, Speed(2.6))),
            TraceRecord.EventRecord(
                SensorEvent.Disconnected(t0 + 2.seconds, strap, gattStatus = 133)
            ),
            TraceRecord.SampleRecord(SpeedSample(t0 + 2.seconds, watch, Speed(2.7))),
            TraceRecord.SampleRecord(SpeedSample(t0 + 3.seconds, watch, Speed(2.8)))
        )
    )
}

/** A fake consumer that keeps everything it receives, in order. */
internal class CollectingListener : SensorListener {
    val samples = mutableListOf<Sample>()
    val events = mutableListOf<SensorEvent>()

    override fun onSample(sample: Sample) {
        samples += sample
    }

    override fun onEvent(event: SensorEvent) {
        events += event
    }
}
