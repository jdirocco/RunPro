package com.runpro.core.recording.exercise

import com.runpro.core.model.SensorListener
import com.runpro.core.model.SensorSource
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.service.RunPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the Health Services exercise of a run through [port] and turns its updates into samples.
 *
 * The recording engine is the authority: [follow] is called with every phase of the run and the exercise
 * follows it (warm-up while preparing, start, pause, resume, end when stopped, also for stops the engine
 * decides itself, like low battery). The controller sends one request at a time and, when it completes,
 * the next one ([ExerciseSession.next]). Nothing blocks or throws: a refused request is reported in
 * [status] and the run goes on without Health Services. The controller never pauses or stops the run
 * (R4): an exercise ended by the platform only changes [status] and loses the GPS fix.
 *
 * Ownership: when the process was restarted during a run and RunPro's exercise is still recording, it is
 * adopted instead of started again. When another app owns the exercise, warm-up waits and reports it;
 * the start of the run takes over (the runner chose RunPro), which ends the other app's exercise. An
 * exercise lost during the run is not restarted: a new one begins only with a new (or restored) run.
 *
 * As a [SensorSource] it delivers the samples of [id] and the GPS fix changes as sensor events. Thread
 * safe: commands, port callbacks and listener calls run under one lock; the listener must return
 * quickly (R6).
 */
class ExerciseController(
    private val port: ExercisePort,
    private val grantedPermissions: () -> Set<RunPermission>,
    private val clock: () -> Timestamp,
    override val id: SourceId = SourceId.HEALTH_SERVICES
) : SensorSource {
    private val lock = Any()
    private val mutableStatus = MutableStateFlow(ExerciseStatus())
    val status: StateFlow<ExerciseStatus> = mutableStatus.asStateFlow()

    private var sink: SensorListener? = null
    private var session = ExerciseSession(id)
    private var busy = false
    private var runPhase = RunPhase.IDLE

    override fun start(listener: SensorListener) = synchronized(lock) {
        check(sink == null) { "Health Services source already started" }
        sink = listener
    }

    override fun stop() = synchronized(lock) {
        sink = null
    }

    /** Brings the exercise to the [phase] of the run. Never blocks. */
    fun follow(phase: RunPhase) = synchronized(lock) {
        val continuing = runPhase == RunPhase.RUNNING || runPhase == RunPhase.PAUSED
        val active = phase != RunPhase.STOPPED && phase != RunPhase.IDLE
        if (active && !continuing && session.isOver) newSession()
        runPhase = phase
        session.want(phase)
        advance()
        publish()
    }

    private fun newSession() {
        if (session.registered) port.clearListener()
        session = ExerciseSession(id)
        busy = false
    }

    /** Sends the next request. Touches no state after sending: a fake port completes inline. */
    private fun advance() {
        if (busy) return
        val current = session
        val request = current.next() ?: return
        if (request == ExerciseRequest.CLEAR_LISTENER) {
            port.clearListener()
            current.completed(request, Result.success(Unit))
            advance()
        } else {
            busy = true
            send(current, request)
        }
    }

    private fun send(current: ExerciseSession, request: ExerciseRequest) {
        val plan = current.plan
        val done = { result: Result<Unit> ->
            completed(current) { current.completed(request, result) }
        }
        when (request) {
            ExerciseRequest.INSPECT -> port.inspect { result ->
                completed(current) { current.inspected(result, grantedPermissions()) }
            }

            ExerciseRequest.PREPARE -> port.prepare(checkNotNull(plan), done)

            ExerciseRequest.START -> port.start(checkNotNull(plan), done)

            ExerciseRequest.PAUSE -> port.pause(done)

            ExerciseRequest.RESUME -> port.resume(done)

            ExerciseRequest.END -> port.end(done)

            ExerciseRequest.CLEAR_LISTENER -> error("not a port request")
        }
    }

    private fun completed(current: ExerciseSession, update: () -> Unit) = synchronized(lock) {
        if (current === session) {
            busy = false
            update()
            if (current.registerListener()) port.setListener(SessionListener(current))
            advance()
            publish()
        }
    }

    private fun publish() {
        mutableStatus.value = session.status(busy)
    }

    /** Port callbacks of one session; those of an earlier session are ignored. */
    private inner class SessionListener(private val owner: ExerciseSession) : ExercisePortListener {
        override fun onRegistrationFailed(error: Throwable) = inSession {
            owner.error = describe(error)
        }

        override fun onReadings(readings: List<ExerciseReading>) = inSession {
            val samples = owner.mapper.map(readings, clock())
            owner.signals.received(samples)
            samples.forEach { sink?.onSample(it) }
        }

        override fun onAvailability(data: ExerciseData, availability: DataAvailability) =
            inSession {
                owner.signals.changed(data, availability, clock())?.let { sink?.onEvent(it) }
            }

        override fun onState(state: PlatformExerciseState, endReason: ExerciseEndReason?) =
            inSession {
                if (owner.platformState(state, endReason)) {
                    owner.signals.lost(clock())?.let { sink?.onEvent(it) }
                }
                advance()
            }

        private inline fun inSession(handle: () -> Unit) = synchronized(lock) {
            if (owner === session) {
                handle()
                publish()
            }
        }
    }
}
