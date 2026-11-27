package com.runpro.tools.replay

import com.runpro.core.model.SensorListener
import com.runpro.core.model.SensorSource
import com.runpro.core.model.SourceId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Counts the effective records after chaos filtering, including injected sensor events. Fault callbacks
 * and suppressed records are not counted. [cancelled] means scheduled work was left unprocessed.
 */
data class ReplayResult(val delivered: Int, val dropped: Int, val cancelled: Boolean)

/**
 * A fake [SensorSource] fed by a [Replayer]: one per source in the trace. While it is not started, its
 * records are dropped, like the readings of a sensor nobody is listening to.
 */
class ReplaySensorSource internal constructor(override val id: SourceId) : SensorSource {
    private val listener = AtomicReference<SensorListener?>(null)

    val isStarted: Boolean get() = listener.get() != null

    override fun start(listener: SensorListener) {
        check(this.listener.compareAndSet(null, listener)) { "Source $id is already started" }
    }

    override fun stop() {
        listener.set(null)
    }

    internal fun deliver(record: TraceRecord): Boolean {
        val target = listener.get() ?: return false
        when (record) {
            is TraceRecord.SampleRecord -> target.onSample(record.sample)
            is TraceRecord.EventRecord -> target.onEvent(record.event)
            is TraceRecord.ImuRecord -> target.onImu(record.batch)
        }
        return true
    }
}

/**
 * Plays a [trace] into fake [sources] on the calling thread.
 *
 * Records are delivered in file (delivery) order. Each one is due at its monotonic offset from the first
 * record divided by [speed]; the replay clock never goes back, so a record delivered late by the platform
 * (older timestamp than the record before it) is delivered right after its predecessor, as it was then.
 *
 * [chaos] schedules reusable faults; process death and storage full require [faultHandler].
 * Start the sources you need, then call [run] once. [cancel] (from a listener or another thread) stops the
 * replay before the next record.
 */
class Replayer(
    private val trace: Trace,
    private val speed: ReplaySpeed = ReplaySpeed.UNLIMITED,
    private val clock: ReplayClock = SystemReplayClock,
    chaos: ChaosPlan = ChaosPlan(),
    private val faultHandler: ReplayFaultHandler? = null
) {
    private val steps = chaos.steps(trace, faultHandler)
    val sources: List<ReplaySensorSource> = trace.sources.map(::ReplaySensorSource)
    private val byId = sources.associateBy { it.id }
    private val ran = AtomicBoolean(false)

    @Volatile
    private var cancelled = false

    fun source(id: SourceId): ReplaySensorSource =
        requireNotNull(byId[id]) { "Trace '${trace.header.name}' has no source $id" }

    fun cancel() {
        cancelled = true
    }

    /** Delivers the whole trace and returns when done or cancelled. */
    fun run(): ReplayResult {
        check(ran.compareAndSet(false, true)) { "A replayer runs once" }
        val start = clock.nanoTime()
        var processed = 0
        var delivered = 0
        var dropped = 0
        for (step in steps) {
            if (!cancelled) waitUntil(start, step.offsetNanos)
            if (cancelled) break
            step.fault?.let { requireNotNull(faultHandler).onFault(it, requireNotNull(step.time)) }
            step.record?.let { record ->
                if (byId.getValue(record.source).deliver(record)) delivered++ else dropped++
            }
            processed++
        }
        return ReplayResult(
            delivered,
            dropped,
            cancelled = processed < steps.size
        )
    }

    private fun waitUntil(start: Long, traceOffsetNanos: Long) {
        if (speed.isUnlimited) return
        val due = start + (traceOffsetNanos / speed.factor).toLong()
        val wait = due - clock.nanoTime()
        if (wait > 0) clock.sleepNanos(wait)
    }
}
