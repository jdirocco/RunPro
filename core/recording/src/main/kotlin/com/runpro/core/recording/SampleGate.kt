package com.runpro.core.recording

import com.runpro.core.model.Metric
import com.runpro.core.model.Sample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp

/**
 * Decides which samples reach the totals: one source per metric, samples in time order.
 *
 * Until source arbitration with priorities and fallback exists (#19), the first source that reports a
 * metric is its only source, so two sources never mix in one total.
 */
internal class SampleGate {
    private val sources = mutableMapOf<Metric, SourceId>()
    private val lastTimes = mutableMapOf<Metric, Timestamp>()

    /** True when [sample] comes from the active source of its metric and is not older than the last one. */
    fun admit(sample: Sample): Boolean {
        val source = sources.getOrPut(sample.metric) { sample.source }
        val last = lastTimes[sample.metric]
        val admitted = source == sample.source && (last == null || sample.time >= last)
        if (admitted) lastTimes[sample.metric] = sample.time
        return admitted
    }
}
