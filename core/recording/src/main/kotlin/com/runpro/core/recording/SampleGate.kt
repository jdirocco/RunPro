package com.runpro.core.recording

import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Metric
import com.runpro.core.model.RunEvent
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SourceId
import com.runpro.core.model.SwitchReason
import com.runpro.core.model.Timestamp

/** Per-metric arbitration. Only measurement timestamps establish liveness, never connection reports. */
internal class SampleGate(private val policy: SourcePolicy) {
    private val sources = mutableMapOf<Metric, SourceId?>()
    private val readings = mutableMapOf<Pair<Metric, SourceId>, Sample>()
    private val lastTimes = mutableMapOf<Pair<Metric, SourceId>, Timestamp>()
    private val admittedTimes = mutableMapOf<Metric, Timestamp>()
    private val switchedAt = mutableMapOf<Metric, Timestamp>()

    val activeSources: Map<Metric, SourceId> get() = sources.mapNotNull { (metric, source) ->
        source?.let { metric to it }
    }.toMap()

    fun observe(sample: Sample): Boolean {
        val key = sample.metric to sample.source
        val previous = lastTimes[key]
        val oldForSource = previous != null && sample.time <= previous
        val oldForMetric = admittedTimes[sample.metric]?.let { sample.time < it } == true
        val beforeSwitch = switchedAt[sample.metric]?.let { sample.time < it } == true
        if (oldForSource || oldForMetric || beforeSwitch) return false
        lastTimes[key] = sample.time
        if (sample is HeartRateSample && sample.heartRate.bpm == 0) {
            readings.remove(key)
        } else {
            readings[key] = sample
        }
        return true
    }

    fun sensor(event: SensorEvent) {
        val affected = when (event) {
            is SensorEvent.Disconnected -> Metric.entries.toSet()

            is SensorEvent.GpsLost -> setOf(
                Metric.LOCATION,
                Metric.DISTANCE,
                Metric.SPEED,
                Metric.ELEVATION
            )

            else -> emptySet()
        }
        readings.entries.removeAll { (key, reading) ->
            key.second == event.source && key.first in affected && reading.time <= event.time
        }
        // A delayed measurement from before a loss cannot resurrect the source.
        affected.forEach { metric ->
            val key = metric to event.source
            lastTimes[key] = maxOf(lastTimes[key] ?: event.time, event.time)
        }
    }

    fun switches(
        time: Timestamp,
        metrics: Iterable<Metric> = Metric.entries
    ): List<RunEvent.SourceSwitched> = metrics.mapNotNull { metric ->
        val rule = policy.forMetric(metric)
        val from = sources[metric]
        val candidates = readings.values.filter {
            it.metric == metric && it.time <= time &&
                time.elapsedSince(it.time) < rule.staleAfter
        }
        // Keep the current source on a priority tie, avoiding oscillation between equal devices.
        val to = candidates.minWithOrNull(
            compareBy<Sample> { rule.rank(it.source) }.thenBy { it.source != from }
                .thenBy { it.source.value }
        )?.source
        if (from == to) return@mapNotNull null
        val reason = if (from != null && candidates.none { it.source == from }) {
            SwitchReason.STALE
        } else {
            SwitchReason.HIGHER_PRIORITY
        }
        RunEvent.SourceSwitched(time, metric, from, to, reason).also(::restore)
    }

    fun admit(sample: Sample): Boolean {
        val last = admittedTimes[sample.metric]
        val boundary = switchedAt[sample.metric]
        val admitted = sources[sample.metric] == sample.source &&
            (last == null || sample.time >= last) &&
            (boundary == null || sample.time >= boundary)
        if (admitted) admittedTimes[sample.metric] = sample.time
        return admitted
    }

    fun restore(event: RunEvent.SourceSwitched) {
        if (event.reason == SwitchReason.STALE && event.from != null) {
            readings.remove(event.metric to event.from)
        }
        sources[event.metric] = event.to
        switchedAt[event.metric] = event.time
    }

    /** Old journals without switch events retain their original first-source semantics. */
    fun restoreSample(sample: Sample): Boolean {
        if (!sources.containsKey(sample.metric)) sources[sample.metric] = sample.source
        observe(sample)
        return admit(sample)
    }
}
