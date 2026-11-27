package com.runpro.tools.replay

import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ModelJson
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One JSON object per line. Records are the [Sample] and [SensorEvent] JSON of `:core:model` (ADR 0003),
 * told apart by their `type`; the header is a [TraceHeader] with `"type":"trace_header"` and an IMU batch
 * is an [ImuBatch] with `"type":"imu_batch"` (ADR 0012). Readers before it skip IMU lines as unknown.
 */
internal object TraceCodec {
    private const val TYPE = "type"
    private const val HEADER_TYPE = "trace_header"
    private const val IMU_TYPE = "imu_batch"

    private val json = ModelJson.json
    private val sampleTypes = subtypeNames(Sample.serializer().descriptor)
    private val eventTypes = subtypeNames(SensorEvent.serializer().descriptor)

    /** The serial names of the subclasses of a sealed type (element 1 of its descriptor). */
    private fun subtypeNames(descriptor: SerialDescriptor): Set<String> =
        descriptor.getElementDescriptor(1).elementNames.toSet()

    fun encodeHeader(header: TraceHeader): String =
        typed(HEADER_TYPE, json.encodeToJsonElement(TraceHeader.serializer(), header).jsonObject)

    fun encodeRecord(record: TraceRecord): String = when (record) {
        is TraceRecord.SampleRecord -> json.encodeToString(Sample.serializer(), record.sample)

        is TraceRecord.EventRecord -> json.encodeToString(SensorEvent.serializer(), record.event)

        is TraceRecord.ImuRecord ->
            typed(
                IMU_TYPE,
                json.encodeToJsonElement(ImuBatch.serializer(), record.batch).jsonObject
            )
    }

    private fun typed(type: String, fields: JsonObject): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(mapOf(TYPE to JsonPrimitive(type)) + fields)
    )

    /** @throws IllegalArgumentException when [line] is not a valid header. */
    fun decodeHeader(line: String): TraceHeader {
        val obj = parse(line)
        require(typeOf(obj) == HEADER_TYPE) { "The first line is not a trace header" }
        val header = json.decodeFromJsonElement(TraceHeader.serializer(), obj)
        require(header.version <= TraceHeader.CURRENT_VERSION) {
            "Trace version ${header.version} is newer than this reader " +
                "(version ${TraceHeader.CURRENT_VERSION})"
        }
        return header
    }

    /**
     * The record on [line], or null when its type is unknown (written by a newer version).
     * @throws IllegalArgumentException when [line] is not a valid record.
     */
    fun decodeRecord(line: String): TraceRecord? {
        val obj = parse(line)
        val type = typeOf(obj)
        return when (type) {
            in sampleTypes -> TraceRecord.SampleRecord(
                json.decodeFromJsonElement(Sample.serializer(), obj)
            )

            in eventTypes -> TraceRecord.EventRecord(
                json.decodeFromJsonElement(SensorEvent.serializer(), obj)
            )

            IMU_TYPE -> TraceRecord.ImuRecord(
                json.decodeFromJsonElement(ImuBatch.serializer(), obj)
            )

            else -> null
        }
    }

    private fun parse(line: String): JsonObject = json.parseToJsonElement(line).jsonObject

    private fun typeOf(obj: JsonObject): String =
        requireNotNull(obj[TYPE]?.jsonPrimitive?.contentOrNull) { "A trace line needs a type" }
}
