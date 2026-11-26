package com.runpro.core.fit

import com.runpro.core.model.CadenceSample
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LocationSample
import com.runpro.core.model.PowerSample
import com.runpro.core.model.RunEvent
import com.runpro.core.model.SourceId
import com.runpro.core.model.SpeedSample
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.RecordedSample

private const val MILLIS_PER_SECOND = 1000L

/** FIT stores times in whole seconds: the start of the second that holds [millis]. */
internal fun wholeSecond(millis: Long): Long =
    Math.floorDiv(millis, MILLIS_PER_SECOND) * MILLIS_PER_SECOND

/**
 * The content of one FIT `record`: the latest value of each metric among the samples of one wall clock
 * second. FIT records have a resolution of one second, so several samples of one metric within a second
 * keep only the last one.
 *
 * @property sortMillis the exact wall time of the last sample, used to order the file.
 */
internal data class RecordContent(
    val second: Long,
    val sortMillis: Long,
    val heartRateBpm: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val distanceMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val cadenceStepsPerMinute: Double? = null,
    val powerWatts: Int? = null
)

/**
 * Groups the samples of [run] into FIT records. Both the encoder and the verifier use it, so the verifier
 * checks that what was written and read back is exactly what the journal holds at FIT resolution.
 *
 * Distance is the run distance counted by the engine (distance during a pause is dropped), not the raw
 * cumulative distance of the source. Altitude comes from elevation samples and, when a second has none,
 * from the location fix. Acceleration has no FIT record field and is not exported.
 */
internal fun recordContents(run: RecordedRun): List<RecordContent> {
    val records = linkedMapOf<Long, RecordBuilder>()
    for (recorded in run.samples) {
        val millis = run.wallClockMillis(recorded.sample.time)
        val second = wholeSecond(millis)
        records.getOrPut(second) { RecordBuilder(second) }.add(recorded, millis)
    }
    return records.values.mapNotNull(RecordBuilder::build).sortedBy { it.second }
}

private class RecordBuilder(val second: Long) {
    private var content = RecordContent(second, second)
    private var hasData = false
    private var altitudeFromElevation = false

    fun add(recorded: RecordedSample, millis: Long) {
        val sample = recorded.sample
        content = when (sample) {
            // FIT heart rate is a uint8 with 255 as "invalid": higher readings are not exported.
            is HeartRateSample -> content.copy(
                heartRateBpm = sample.heartRate.bpm.takeIf { it <= RunProFit.MAX_UINT8 }
            )

            is LocationSample -> content.copy(
                latitude = sample.latitude,
                longitude = sample.longitude,
                altitudeMeters = if (altitudeFromElevation) {
                    content.altitudeMeters
                } else {
                    sample.altitude?.meters ?: content.altitudeMeters
                }
            )

            is ElevationSample -> {
                altitudeFromElevation = true
                content.copy(altitudeMeters = sample.elevation.meters)
            }

            is DistanceSample -> content.copy(distanceMeters = recorded.distance.meters)

            is SpeedSample -> content.copy(speedMetersPerSecond = sample.speed.metersPerSecond)

            is CadenceSample -> content.copy(cadenceStepsPerMinute = sample.cadence.stepsPerMinute)

            is PowerSample -> content.copy(powerWatts = sample.power.watts)

            else -> return
        }.copy(sortMillis = maxOf(content.sortMillis, millis))
        hasData = true
    }

    fun build(): RecordContent? = content.takeIf { hasData }
}

/**
 * The sensors of [run] in order of first appearance, each with its FIT device index (the creator, RunPro
 * itself, is index 0).
 */
internal fun deviceIndexes(run: RecordedRun): Map<SourceId, Int> {
    val sources = linkedSetOf<SourceId>()
    run.samples.forEach { sources += it.sample.source }
    run.run.events.forEach { event ->
        when (event) {
            is RunEvent.SensorConnected -> sources += event.source

            is RunEvent.SensorLost -> sources += event.source

            is RunEvent.SourceSwitched -> {
                event.from?.let(sources::add)
                event.to?.let(sources::add)
            }

            else -> Unit
        }
    }
    run.sensorEvents.forEach { sources += it.source }
    return sources.withIndex().associate { (i, source) -> source to i + 1 }
}
