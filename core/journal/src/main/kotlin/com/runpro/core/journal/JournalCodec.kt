@file:OptIn(ExperimentalSerializationApi::class)

package com.runpro.core.journal

import com.runpro.core.model.Acceleration
import com.runpro.core.model.AccelerationSample
import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Elevation
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Metric
import com.runpro.core.model.PauseReason
import com.runpro.core.model.Power
import com.runpro.core.model.PowerSample
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorLossReason
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.StopReason
import com.runpro.core.model.SwitchReason
import com.runpro.core.model.Timestamp
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Maps [JournalRecord]s to record payloads (protobuf [RecordProto]) and back.
 *
 * [decode] returns null for a record kind it does not know (written by a newer version) and throws
 * [IllegalArgumentException] for a payload that is not a valid record.
 */
internal object JournalCodec {
    private val protoBuf = ProtoBuf { encodeDefaults = false }

    fun encode(record: JournalRecord): ByteArray =
        protoBuf.encodeToByteArray(RecordProto.serializer(), RecordEncoder.encode(record))

    fun decode(payload: ByteArray): JournalRecord? =
        RecordDecoder.decode(protoBuf.decodeFromByteArray(RecordProto.serializer(), payload))
}

private object RecordEncoder {
    fun encode(record: JournalRecord): RecordProto = when (record) {
        is JournalRecord.RunStarted -> RecordProto(
            runStarted = RunStartedProto(record.runId.value, record.time.toProto())
        )

        is JournalRecord.Samples -> RecordProto(
            samples = SampleBatchProto(record.samples.map(::encode))
        )

        is JournalRecord.Event -> encode(record.event)

        is JournalRecord.Sensor -> RecordProto(sensorEvent = encode(record.event))
    }

    private fun encode(event: RunEvent): RecordProto = when (event) {
        is RunEvent.Started -> error("The run start is a RunStarted record")

        is RunEvent.Paused -> RecordProto(
            pause = PauseProto(event.time.toProto(), WireCodes.pauseReasons.encode(event.reason))
        )

        is RunEvent.Resumed -> RecordProto(resume = ResumeProto(event.time.toProto()))

        is RunEvent.LapMarked -> RecordProto(
            lap = LapProto(event.time.toProto(), WireCodes.lapTriggers.encode(event.trigger))
        )

        is RunEvent.SourceSwitched -> RecordProto(
            sourceSwitch = SourceSwitchProto(
                time = event.time.toProto(),
                metric = WireCodes.metrics.encode(event.metric),
                from = event.from?.value,
                to = event.to?.value,
                reason = WireCodes.switchReasons.encode(event.reason)
            )
        )

        is RunEvent.SensorConnected -> RecordProto(
            sensorStatus = SensorStatusProto(
                WireCodes.SENSOR_STATUS_CONNECTED,
                event.time.toProto(),
                event.source.value
            )
        )

        is RunEvent.SensorLost -> RecordProto(
            sensorStatus = SensorStatusProto(
                kind = WireCodes.SENSOR_STATUS_LOST,
                time = event.time.toProto(),
                source = event.source.value,
                lossReason = WireCodes.lossReasons.encode(event.reason)
            )
        )

        is RunEvent.Stopped -> RecordProto(
            runStopped = RunStoppedProto(
                event.time.toProto(),
                WireCodes.stopReasons.encode(event.reason)
            )
        )
    }

    private fun encode(sample: Sample): SampleProto {
        val time = sample.time.toProto()
        val source = sample.source.value
        val metric = WireCodes.metrics.encode(sample.metric)
        return when (sample) {
            is HeartRateSample -> SampleProto(metric, time, source, intValue = sample.heartRate.bpm)

            is LocationSample -> SampleProto(
                metric,
                time,
                source,
                latitude = sample.latitude,
                longitude = sample.longitude,
                accuracy = sample.horizontalAccuracy?.meters,
                altitude = sample.altitude?.meters
            )

            is DistanceSample -> SampleProto(metric, time, source, value = sample.total.meters)

            is SpeedSample -> SampleProto(metric, time, source, sample.speed.metersPerSecond)

            is CadenceSample -> SampleProto(metric, time, source, sample.cadence.stepsPerMinute)

            is PowerSample -> SampleProto(metric, time, source, intValue = sample.power.watts)

            is ElevationSample -> SampleProto(metric, time, source, sample.elevation.meters)

            is AccelerationSample -> SampleProto(
                metric,
                time,
                source,
                x = sample.acceleration.x,
                y = sample.acceleration.y,
                z = sample.acceleration.z
            )
        }
    }

    private fun encode(event: SensorEvent): SensorEventProto {
        val time = event.time.toProto()
        val source = event.source.value
        return when (event) {
            is SensorEvent.Connected -> SensorEventProto(WireCodes.SENSOR_CONNECTED, time, source)

            is SensorEvent.Disconnected ->
                SensorEventProto(WireCodes.SENSOR_DISCONNECTED, time, source, event.gattStatus)

            is SensorEvent.GpsLost -> SensorEventProto(WireCodes.GPS_LOST, time, source)

            is SensorEvent.GpsAcquired -> SensorEventProto(WireCodes.GPS_ACQUIRED, time, source)
        }
    }

    private fun Timestamp.toProto() = TimeProto(elapsedRealtimeNanos, wallClockMillis)
}

private object RecordDecoder {
    fun decode(proto: RecordProto): JournalRecord? {
        val kinds = listOfNotNull(
            proto.runStarted?.let(::decode),
            proto.samples?.let(::decode),
            proto.lap?.let(::decode),
            proto.pause?.let(::decode),
            proto.resume?.let { JournalRecord.Event(RunEvent.Resumed(it.time.toModel())) },
            proto.sourceSwitch?.let(::decode),
            proto.sensorEvent?.let(::decode),
            proto.runStopped?.let(::decode),
            proto.sensorStatus?.let(::decode)
        )
        require(kinds.size <= 1) { "A record holds ${kinds.size} record kinds" }
        return kinds.firstOrNull()
    }

    private fun decode(proto: RunStartedProto) =
        JournalRecord.RunStarted(RunId(proto.runId), proto.time.toModel())

    private fun decode(proto: SampleBatchProto) = JournalRecord.Samples(proto.samples.map(::decode))

    private fun decode(proto: LapProto) = JournalRecord.Event(
        RunEvent.LapMarked(proto.time.toModel(), WireCodes.lapTriggers.decode(proto.trigger))
    )

    private fun decode(proto: PauseProto) = JournalRecord.Event(
        RunEvent.Paused(proto.time.toModel(), WireCodes.pauseReasons.decode(proto.reason))
    )

    private fun decode(proto: RunStoppedProto) = JournalRecord.Event(
        RunEvent.Stopped(proto.time.toModel(), WireCodes.stopReasons.decode(proto.reason))
    )

    private fun decode(proto: SampleProto): Sample {
        val time = proto.time.toModel()
        val source = SourceId(proto.source)
        return when (WireCodes.metrics.decode(proto.metric)) {
            Metric.HEART_RATE -> HeartRateSample(time, source, HeartRate(proto.intValue))

            Metric.LOCATION -> LocationSample(
                time,
                source,
                latitude = proto.latitude,
                longitude = proto.longitude,
                horizontalAccuracy = proto.accuracy?.let(::Distance),
                altitude = proto.altitude?.let(::Elevation)
            )

            Metric.DISTANCE -> DistanceSample(time, source, Distance(proto.value))

            Metric.SPEED -> SpeedSample(time, source, Speed(proto.value))

            Metric.CADENCE -> CadenceSample(time, source, Cadence(proto.value))

            Metric.POWER -> PowerSample(time, source, Power(proto.intValue))

            Metric.ELEVATION -> ElevationSample(time, source, Elevation(proto.value))

            Metric.ACCELERATION ->
                AccelerationSample(time, source, Acceleration(proto.x, proto.y, proto.z))
        }
    }

    private fun decode(proto: SourceSwitchProto) = JournalRecord.Event(
        RunEvent.SourceSwitched(
            time = proto.time.toModel(),
            metric = WireCodes.metrics.decode(proto.metric),
            from = proto.from?.let(::SourceId),
            to = proto.to?.let(::SourceId),
            reason = WireCodes.switchReasons.decode(proto.reason)
        )
    )

    private fun decode(proto: SensorEventProto): JournalRecord.Sensor {
        val time = proto.time.toModel()
        val source = SourceId(proto.source)
        val event = when (proto.kind) {
            WireCodes.SENSOR_CONNECTED -> SensorEvent.Connected(time, source)

            WireCodes.SENSOR_DISCONNECTED -> SensorEvent.Disconnected(
                time,
                source,
                proto.gattStatus
            )

            WireCodes.GPS_LOST -> SensorEvent.GpsLost(time, source)

            WireCodes.GPS_ACQUIRED -> SensorEvent.GpsAcquired(time, source)

            else -> throw IllegalArgumentException("Unknown sensor event kind: ${proto.kind}")
        }
        return JournalRecord.Sensor(event)
    }

    private fun decode(proto: SensorStatusProto): JournalRecord.Event {
        val time = proto.time.toModel()
        val source = SourceId(proto.source)
        val event = when (proto.kind) {
            WireCodes.SENSOR_STATUS_CONNECTED -> RunEvent.SensorConnected(time, source)

            WireCodes.SENSOR_STATUS_LOST ->
                RunEvent.SensorLost(time, source, WireCodes.lossReasons.decode(proto.lossReason))

            else -> throw IllegalArgumentException("Unknown sensor status kind: ${proto.kind}")
        }
        return JournalRecord.Event(event)
    }

    private fun TimeProto.toModel() = Timestamp(elapsedNanos, wallMillis)
}

/** Permanent wire codes of the journal enums (ADR 0006). Never renumber; 0 means "missing". */
@Suppress("MagicNumber") // The numbers are the wire format.
private object WireCodes {
    const val SENSOR_CONNECTED = 1
    const val SENSOR_DISCONNECTED = 2
    const val GPS_LOST = 3
    const val GPS_ACQUIRED = 4
    const val SENSOR_STATUS_CONNECTED = 1
    const val SENSOR_STATUS_LOST = 2

    val metrics = Codes(
        Metric.HEART_RATE to 1,
        Metric.LOCATION to 2,
        Metric.DISTANCE to 3,
        Metric.SPEED to 4,
        Metric.CADENCE to 5,
        Metric.POWER to 6,
        Metric.ELEVATION to 7,
        Metric.ACCELERATION to 8
    )
    val pauseReasons = Codes(PauseReason.MANUAL to 1, PauseReason.AUTO to 2)
    val lapTriggers = Codes(
        LapTrigger.MANUAL to 1,
        LapTrigger.DISTANCE to 2,
        LapTrigger.TIME to 3,
        LapTrigger.STOP to 4
    )
    val switchReasons = Codes(
        SwitchReason.STALE to 1,
        SwitchReason.HIGHER_PRIORITY to 2,
        SwitchReason.USER to 3
    )
    val lossReasons = Codes(
        SensorLossReason.DISCONNECTED to 1,
        SensorLossReason.NO_DATA to 2,
        SensorLossReason.ERROR to 3
    )
    val stopReasons = Codes(
        StopReason.USER to 1,
        StopReason.LOW_BATTERY to 2,
        StopReason.STORAGE_FULL to 3,
        StopReason.RECOVERY to 4
    )
}

/** A fixed two-way mapping between enum values and their wire codes. */
private class Codes<E : Enum<E>>(vararg pairs: Pair<E, Int>) {
    private val codes: Map<E, Int> = mapOf(*pairs)
    private val values: Map<Int, E> = pairs.associate { (value, code) -> code to value }

    init {
        require(values.size == codes.size && 0 !in values) {
            "Wire codes must be unique and non-zero"
        }
    }

    fun encode(value: E): Int = requireNotNull(codes[value]) { "No wire code for $value" }

    fun decode(code: Int): E = requireNotNull(values[code]) { "Unknown wire code: $code" }
}
