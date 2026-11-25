package com.runpro.tools.replay

import com.runpro.core.model.SensorListener
import com.runpro.core.model.SensorSource
import com.runpro.core.model.SourceId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What a replay did: records handed to a listener, records of sources nobody had started. */
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
 * Start the sources you need, then call [run] once. [cancel] (from a listener or another thread) stops the
 * replay before the next record.
 */
class Replayer(
    private val trace: Trace,
    private val speed: ReplaySpeed = ReplaySpeed.UNLIMITED,
    private val clock: ReplayClock = SystemReplayClock
) {
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
        val origin = trace.records.firstOrNull()?.time?.elapsedRealtimeNanos ?: 0L
        val start = clock.nanoTime()
        var offset = 0L
        var delivered = 0
        var dropped = 0
        for (record in trace.records) {
            if (cancelled) break
            offset = maxOf(offset, record.time.elapsedRealtimeNanos - origin)
            waitUntil(start, offset)
            if (byId.getValue(record.source).deliver(record)) delivered++ else dropped++
        }
        return ReplayResult(
            delivered,
            dropped,
            cancelled = delivered + dropped < trace.records.size
        )
    }

    private fun waitUntil(start: Long, traceOffsetNanos: Long) {
        if (speed.isUnlimited) return
        val due = start + (traceOffsetNanos / speed.factor).toLong()
        val wait = due - clock.nanoTime()
        if (wait > 0) clock.sleepNanos(wait)
    }
}
