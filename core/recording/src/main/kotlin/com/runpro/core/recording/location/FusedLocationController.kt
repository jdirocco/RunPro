package com.runpro.core.recording.location

import com.runpro.core.model.Distance
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SensorSource
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.exercise.Readiness
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state of the fused location source for the warm-up screen and the diagnostics log.
 *
 * @property accuracy horizontal accuracy of the last admitted fix.
 * @property rejected fixes kept out of the run in this request, by reason.
 */
data class LocationStatus(
    val readiness: Readiness = Readiness.NOT_REQUESTED,
    val accuracy: Distance? = null,
    val rejected: Map<FixRejection, Int> = emptyMap(),
    val error: String? = null
)

/**
 * `watch:fused-location` (ADR 0019): fused location updates from the warm-up to the stop, behind a
 * [LocationFixGate] that is new for every run. It is a [SensorSource] like Health Services; the engine's
 * source arbitration decides when its samples count (R5), and nothing here can pause or stop the run (R4).
 *
 * The fix is acquired with the first admitted fix and lost after [lostAfter] without one, when the
 * provider says it is unavailable, or when updates stop; both are [SensorEvent]s for the engine.
 *
 * Thread safe: commands, port callbacks and listener calls run under one lock; [follow] never blocks (R6).
 */
class FusedLocationController(
    private val port: LocationPort,
    private val locationAllowed: () -> Boolean,
    private val clock: () -> Timestamp,
    private val lostAfter: Duration = LOST_AFTER,
    private val newGate: () -> LocationFixGate = { LocationFixGate() }
) : SensorSource {
    override val id: SourceId = SourceId.FUSED_LOCATION

    private val lock = Any()
    private val mutableStatus = MutableStateFlow(LocationStatus())
    val status: StateFlow<LocationStatus> = mutableStatus.asStateFlow()

    private var sink: SensorListener? = null
    private var request: Request? = null

    override fun start(listener: SensorListener) = synchronized(lock) {
        check(sink == null) { "Fused location source already started" }
        sink = listener
    }

    override fun stop() = synchronized(lock) {
        sink = null
    }

    /** Updates run while [phase] is a warm-up or a run, and only with the location permission. */
    fun follow(phase: RunPhase) = synchronized(lock) {
        val active =
            phase == RunPhase.PREPARING || phase == RunPhase.RUNNING || phase == RunPhase.PAUSED
        val current = request
        when {
            active && current == null && locationAllowed() -> {
                val next = Request(newGate())
                request = next
                mutableStatus.value = LocationStatus(readiness = Readiness.WAITING)
                port.request(next)
            }

            !active && current != null -> {
                request = null
                port.remove()
                current.lose()
                mutableStatus.value = LocationStatus()
            }

            else -> Unit
        }
    }

    /** One request: its gate, fix state and counters. Callbacks of an earlier request are ignored. */
    private inner class Request(private val gate: LocationFixGate) : LocationReceiver {
        private var hasFix = false
        private var lastAdmitted: Timestamp? = null

        override fun fix(fix: LocationFix) = inRequest {
            val now = clock()
            when (val verdict = gate.admit(fix, now)) {
                is FixVerdict.Accepted -> {
                    lastAdmitted = now
                    if (!hasFix) {
                        hasFix = true
                        sink?.onEvent(SensorEvent.GpsAcquired(now, id))
                    }
                    verdict.samples.forEach { sink?.onSample(it) }
                    mutableStatus.value = status.value.copy(
                        readiness = Readiness.READY,
                        accuracy = Distance(fix.horizontalAccuracyMeters),
                        error = null
                    )
                }

                is FixVerdict.Rejected -> {
                    val rejected = status.value.rejected
                    mutableStatus.value = status.value.copy(
                        rejected =
                            rejected + (verdict.reason to (rejected[verdict.reason] ?: 0) + 1)
                    )
                    val since = lastAdmitted
                    if (since != null && now.elapsedSince(since) >= lostAfter) lose()
                }
            }
        }

        override fun availability(available: Boolean) = inRequest {
            if (!available) lose()
        }

        override fun failed(message: String) = inRequest {
            lose()
            mutableStatus.value =
                status.value.copy(readiness = Readiness.UNAVAILABLE, error = message)
        }

        /** The fix is gone: one [SensorEvent.GpsLost] if there was one. */
        fun lose() {
            if (hasFix) {
                hasFix = false
                sink?.onEvent(SensorEvent.GpsLost(clock(), id))
            }
            if (request === this && status.value.readiness == Readiness.READY) {
                mutableStatus.value = status.value.copy(readiness = Readiness.WAITING)
            }
        }

        private inline fun inRequest(handle: () -> Unit) = synchronized(lock) {
            if (request === this) handle()
        }
    }

    companion object {
        /** Without an admitted fix for this long the fix is lost, as Health Services reports it. */
        val LOST_AFTER: Duration = 10.seconds
    }
}
