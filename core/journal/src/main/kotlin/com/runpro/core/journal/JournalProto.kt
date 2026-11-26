@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("MagicNumber") // Field numbers are the wire format.

package com.runpro.core.journal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// Protobuf messages of the journal payload (ADR 0006). They are private to the journal and mapped to the
// model types by JournalCodec, so the wire shape does not follow Kotlin renames. Field numbers and enum codes
// are permanent: never reuse or renumber them; add new fields with new numbers. Code 0 is never a valid enum
// value, so a missing field is detected.

/** One record: exactly one field is set. A record with none of the known fields comes from a newer writer. */
@Serializable
internal class RecordProto(
    @ProtoNumber(1) val runStarted: RunStartedProto? = null,
    @ProtoNumber(2) val samples: SampleBatchProto? = null,
    @ProtoNumber(3) val lap: LapProto? = null,
    @ProtoNumber(4) val pause: PauseProto? = null,
    @ProtoNumber(5) val resume: ResumeProto? = null,
    @ProtoNumber(6) val sourceSwitch: SourceSwitchProto? = null,
    @ProtoNumber(7) val sensorEvent: SensorEventProto? = null,
    @ProtoNumber(8) val runStopped: RunStoppedProto? = null,
    @ProtoNumber(9) val sensorStatus: SensorStatusProto? = null
)

@Serializable
internal class TimeProto(
    @ProtoNumber(1) val elapsedNanos: Long = 0,
    @ProtoNumber(2) val wallMillis: Long = 0
)

@Serializable
internal class RunStartedProto(
    @ProtoNumber(1) val runId: String = "",
    @ProtoNumber(2) val time: TimeProto = TimeProto()
)

@Serializable
internal class SampleBatchProto(@ProtoNumber(1) val samples: List<SampleProto> = emptyList())

/**
 * One sample; [metric] says which fields are used: [value] for distance (cumulative metres), speed (m/s),
 * cadence (steps/min) and elevation (m); [intValue] for heart rate (bpm) and power (W); [latitude],
 * [longitude], [accuracy] and [altitude] for location; [x], [y], [z] for acceleration (m/s²).
 */
@Serializable
internal class SampleProto(
    @ProtoNumber(1) val metric: Int = 0,
    @ProtoNumber(2) val time: TimeProto = TimeProto(),
    @ProtoNumber(3) val source: String = "",
    @ProtoNumber(4) val value: Double = 0.0,
    @ProtoNumber(5) val intValue: Int = 0,
    @ProtoNumber(6) val latitude: Double = 0.0,
    @ProtoNumber(7) val longitude: Double = 0.0,
    @ProtoNumber(8) val accuracy: Double? = null,
    @ProtoNumber(9) val altitude: Double? = null,
    @ProtoNumber(10) val x: Double = 0.0,
    @ProtoNumber(11) val y: Double = 0.0,
    @ProtoNumber(12) val z: Double = 0.0
)

@Serializable
internal class LapProto(
    @ProtoNumber(1) val time: TimeProto = TimeProto(),
    @ProtoNumber(2) val trigger: Int = 0
)

@Serializable
internal class PauseProto(
    @ProtoNumber(1) val time: TimeProto = TimeProto(),
    @ProtoNumber(2) val reason: Int = 0
)

@Serializable
internal class ResumeProto(@ProtoNumber(1) val time: TimeProto = TimeProto())

@Serializable
internal class SourceSwitchProto(
    @ProtoNumber(1) val time: TimeProto = TimeProto(),
    @ProtoNumber(2) val metric: Int = 0,
    @ProtoNumber(3) val from: String? = null,
    @ProtoNumber(4) val to: String? = null,
    @ProtoNumber(5) val reason: Int = 0
)

/** A sensor adapter's report (model `SensorEvent`). */
@Serializable
internal class SensorEventProto(
    @ProtoNumber(1) val kind: Int = 0,
    @ProtoNumber(2) val time: TimeProto = TimeProto(),
    @ProtoNumber(3) val source: String = "",
    @ProtoNumber(4) val gattStatus: Int? = null
)

@Serializable
internal class RunStoppedProto(
    @ProtoNumber(1) val time: TimeProto = TimeProto(),
    @ProtoNumber(2) val reason: Int = 0
)

/** The engine's sensor decisions (`RunEvent.SensorConnected` / `RunEvent.SensorLost`). */
@Serializable
internal class SensorStatusProto(
    @ProtoNumber(1) val kind: Int = 0,
    @ProtoNumber(2) val time: TimeProto = TimeProto(),
    @ProtoNumber(3) val source: String = "",
    @ProtoNumber(4) val lossReason: Int = 0
)
