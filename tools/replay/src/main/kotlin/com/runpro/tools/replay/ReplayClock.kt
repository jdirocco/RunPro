package com.runpro.tools.replay

import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** The time source a [Replayer] paces itself with. */
interface ReplayClock {
    /** A monotonic time in nanoseconds; only differences matter. */
    fun nanoTime(): Long

    fun sleepNanos(nanos: Long)
}

/** Real time: the replay actually waits. */
object SystemReplayClock : ReplayClock {
    override fun nanoTime(): Long = System.nanoTime()

    override fun sleepNanos(nanos: Long) = TimeUnit.NANOSECONDS.sleep(nanos)
}

/**
 * Virtual time for tests: sleeping advances the clock at once. [slept] is the total time the replay
 * would have waited. Not thread safe; use it from the replaying thread.
 */
class VirtualReplayClock(startNanos: Long = 0) : ReplayClock {
    private var now = startNanos

    var slept: Duration = Duration.ZERO
        private set

    override fun nanoTime(): Long = now

    override fun sleepNanos(nanos: Long) {
        require(nanos >= 0) { "Cannot sleep a negative time: $nanos ns" }
        now += nanos
        slept += nanos.nanoseconds
    }
}

/** How fast a trace is replayed relative to the time it was recorded in. */
@JvmInline
value class ReplaySpeed private constructor(val factor: Double) {
    /** True when records are delivered without waiting. */
    val isUnlimited: Boolean get() = factor == Double.POSITIVE_INFINITY

    override fun toString(): String = if (isUnlimited) "unlimited" else "${factor}x"

    companion object {
        /** As recorded: a 30 min trace takes 30 min. */
        val REAL_TIME = ReplaySpeed(1.0)

        /** No waiting at all: the default for unit tests. */
        val UNLIMITED = ReplaySpeed(Double.POSITIVE_INFINITY)

        /** [factor] times faster than recorded (e.g. 60 replays a minute per second). */
        fun times(factor: Double): ReplaySpeed {
            require(factor > 0.0) { "Replay speed must be positive: $factor" }
            return ReplaySpeed(factor)
        }
    }
}
