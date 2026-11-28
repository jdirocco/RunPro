package com.runpro.core.ble

import com.runpro.core.model.SourceId
import java.util.UUID

/**
 * What a paired sensor is used for: the service it is found by and the measurement notifications
 * its link requires ([BleConnectionStateMachine] watches each one). Stryd power follows in #21.
 */
enum class BleSensorKind(val service: UUID, val measurements: List<UUID>) {
    HEART_RATE(GattUuids.HEART_RATE_SERVICE, listOf(GattUuids.HEART_RATE_MEASUREMENT)),
    FOOTPOD(GattUuids.RUNNING_SPEED_AND_CADENCE_SERVICE, listOf(GattUuids.RSC_MEASUREMENT))
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

/** The paired sensors as one preference string, one per kind: `KIND|ADDRESS|ASSOCIATION|NAME` lines. */
object PairedSensors {
    private const val FIELDS = 4

    fun encode(sensors: List<PairedSensor>): String = sensors.joinToString("\n") {
        val name = it.name?.replace(Regex("[\\r\\n]+"), " ").orEmpty()
        "${it.kind.name}|${it.address}|${it.associationId ?: ""}|$name"
    }

    /** Skips damaged lines; a later line of the same kind replaces an earlier one. */
    fun decode(text: String?): List<PairedSensor> =
        text.orEmpty().lines().mapNotNull(::line).fold(emptyList(), ::pair)

    /** Replaces the sensor of the same kind (or address): one strap and one footpod at most. */
    fun pair(sensors: List<PairedSensor>, sensor: PairedSensor): List<PairedSensor> =
        sensors.filter { it.kind != sensor.kind && it.address != sensor.address } + sensor

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
