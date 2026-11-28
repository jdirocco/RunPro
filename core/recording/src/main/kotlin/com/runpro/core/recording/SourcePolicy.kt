package com.runpro.core.recording

import com.runpro.core.model.Metric
import com.runpro.core.model.SourceId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Explicit ids are preferred in list order; unlisted sources follow: BLE, Health Services, fused location
 * (ADR 0019), the IMU, then derived and others.
 */
data class MetricSourcePolicy(
    val priorities: List<SourceId> = emptyList(),
    val staleAfter: Duration = 5.seconds
) {
    init {
        require(priorities.distinct().size == priorities.size) { "Duplicate source priority" }
        require(staleAfter.isPositive() && staleAfter.isFinite()) { "Invalid source timeout" }
    }

    internal fun rank(source: SourceId): Int {
        val explicit = priorities.indexOf(source)
        if (explicit >= 0) return explicit
        val fallback = when {
            source.isBle -> 0
            source == SourceId.HEALTH_SERVICES -> 1
            source == SourceId.FUSED_LOCATION -> 2
            source == SourceId.WATCH_IMU -> 3
            else -> 4
        }
        return priorities.size + fallback
    }
}

/** Per-metric overrides. Defaults tolerate slower location/elevation updates. */
data class SourcePolicy(val metrics: Map<Metric, MetricSourcePolicy> = emptyMap()) {
    internal fun forMetric(metric: Metric): MetricSourcePolicy = metrics[metric] ?: when (metric) {
        Metric.LOCATION -> MetricSourcePolicy(staleAfter = 15.seconds)
        Metric.ELEVATION -> MetricSourcePolicy(staleAfter = 30.seconds)
        else -> MetricSourcePolicy()
    }
}
