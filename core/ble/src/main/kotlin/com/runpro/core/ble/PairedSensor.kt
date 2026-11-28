package com.runpro.core.ble

import com.runpro.core.model.SourceId
import java.util.UUID

/**
 * Where a sensor is worn. One sensor per slot: a footpod and a Stryd would both report speed, cadence
 * and distance from the foot, and two BLE sources of one metric have no defined order (ADR 0013).
 */
enum class SensorSlot { CHEST, FOOT }

/**
 * What a paired sensor is used for: the service it is found by, the measurement notifications its
 * link requires ([BleConnectionStateMachine] watches each one) and its [SensorSlot].
 *
 * [STRYD] is found by its Cycling Power service, which a plain footpod does not have, and streams RSC
 * (speed, cadence, distance) and running power; both must keep arriving or the link resubscribes.
 * Stryd's proprietary characteristics (form power, ground time, vertical oscillation, leg spring
 * stiffness) are not read: their format is not public (#125).
 */
enum class BleSensorKind(val service: UUID, val measurements: List<UUID>, val slot: SensorSlot) {
    HEART_RATE(
        GattUuids.HEART_RATE_SERVICE,
        listOf(GattUuids.HEART_RATE_MEASUREMENT),
        SensorSlot.CHEST
    ),
    FOOTPOD(
        GattUuids.RUNNING_SPEED_AND_CADENCE_SERVICE,
        listOf(GattUuids.RSC_MEASUREMENT),
        SensorSlot.FOOT
    ),
    STRYD(
        GattUuids.CYCLING_POWER_SERVICE,
        listOf(GattUuids.RSC_MEASUREMENT, GattUuids.CYCLING_POWER_MEASUREMENT),
        SensorSlot.FOOT
    )
}

/**
 * A sensor the runner associated with the watch. [address] is the upper case device address, so
 * [source] is the same stable id across runs (R5). [associationId] is the platform companion
 * association, when there is one.
 */
data class PairedSensor(
    val address: String,
    val kind: BleSensorKind,
    val name: String? = null,
    val associationId: Int? = null
) {
    val source: SourceId = SourceId.ble(address)

    init {
        require(address == source.bleAddress) { "Address must be upper case: '$address'" }
    }

    companion object {
        /** Normalises [address] to upper case. */
        fun of(
            address: String,
            kind: BleSensorKind,
            name: String? = null,
            associationId: Int? = null
        ) = PairedSensor(address.uppercase(), kind, name, associationId)
    }
}

/** The paired sensors as one preference string, one per slot: `KIND|ADDRESS|ASSOCIATION|NAME` lines. */
object PairedSensors {
    private const val FIELDS = 4

    fun encode(sensors: List<PairedSensor>): String = sensors.joinToString("\n") {
        val name = it.name?.replace(Regex("[\\r\\n]+"), " ").orEmpty()
        "${it.kind.name}|${it.address}|${it.associationId ?: ""}|$name"
    }

    /** Skips damaged lines; a later line of the same slot replaces an earlier one. */
    fun decode(text: String?): List<PairedSensor> =
        text.orEmpty().lines().mapNotNull(::line).fold(emptyList(), ::pair)

    /**
     * Replaces the sensor of the same slot (or address): one strap and one foot sensor (footpod or
     * Stryd) at most.
     */
    fun pair(sensors: List<PairedSensor>, sensor: PairedSensor): List<PairedSensor> =
        sensors.filter { it.kind.slot != sensor.kind.slot && it.address != sensor.address } + sensor

    fun forget(sensors: List<PairedSensor>, kind: BleSensorKind): List<PairedSensor> =
        sensors.filter { it.kind != kind }

    private fun line(text: String): PairedSensor? = runCatching {
        val fields = text.split("|", limit = FIELDS)
        require(fields.size == FIELDS)
        val (kind, address, association) = fields
        require(association.isEmpty() || association.toIntOrNull() != null)
        PairedSensor.of(
            address,
            BleSensorKind.valueOf(kind),
            fields.last().takeIf { it.isNotEmpty() },
            association.toIntOrNull()
        )
    }.getOrNull()
}
