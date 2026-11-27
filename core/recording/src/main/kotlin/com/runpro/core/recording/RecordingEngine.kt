package com.runpro.core.recording

import com.runpro.core.model.RunId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs a [RecordingStateMachine] on its own single thread and publishes its [state] (R6).
 *
 * - [submit] never blocks and never suspends: inputs go into an unbounded queue, so a sensor callback or the
 *   UI thread never waits for the engine.
 * - The engine never waits for the UI: [state] is a [StateFlow], which keeps only the latest value, so a slow
 *   or stuck collector cannot hold back the recording.
 * - Inputs are processed in submission order; the state is published once per batch of queued inputs.
 *
 * @param dispatcher where the engine runs; it must be single-threaded or limited to one thread. When null
 *   the engine creates its own daemon thread [THREAD_NAME] and shuts it down on [close].
 * @param sessionFactory creates or recovers the session on the engine thread; the engine owns its writer.
 *   Supply ticks at least once a second when journaling. The factory replaces config/runIds defaults.
 * @param onProcessed called on the engine thread after each batch with the published state (diagnostics).
 *   It must not block.
 */
class RecordingEngine(
    config: EngineConfig = EngineConfig(),
    runIds: () -> RunId = RunId::random,
    dispatcher: CoroutineDispatcher? = null,
    private val sessionFactory: () -> RecordingSession = { RecordingSession(config, runIds) },
    private val onProcessed: (RunState) -> Unit = {}
) : AutoCloseable {
    private val ownExecutor: ExecutorService? = if (dispatcher == null) {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, THREAD_NAME).apply {
                isDaemon =
                    true
            }
        }
    } else {
        null
    }
    private val scope = CoroutineScope(
        SupervisorJob() + (dispatcher ?: requireNotNull(ownExecutor).asCoroutineDispatcher())
    )
    private val inputs = Channel<EngineInput>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(RunState())
    private val mutableFailure = MutableStateFlow<Throwable?>(null)

    val state: StateFlow<RunState> = mutableState.asStateFlow()

    /** The latest failure. Journal I/O errors leave submission open; factory/processing errors close it. */
    val failure: StateFlow<Throwable?> = mutableFailure.asStateFlow()

    init {
        scope.launch { processInputs() }
    }

    /** Queues [input] without waiting. Returns false after [close] or a processing failure. */
    fun submit(input: EngineInput): Boolean = inputs.trySend(input).isSuccess

    /** Stops processing; queued inputs that were not processed yet are dropped. */
    override fun close() {
        inputs.close()
        scope.cancel()
        ownExecutor?.shutdown()
    }

    // Factories and callbacks can throw arbitrary exceptions; report them instead of leaving a live queue.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun processInputs() {
        var session: RecordingSession? = null
        try {
            var recording = sessionFactory()
            session = recording
            mutableState.value = recording.state
            for (input in inputs) {
                recording = process(input, recording)
                session = recording
                var next = inputs.tryReceive().getOrNull()
                while (next != null) {
                    recording = process(next, recording)
                    session = recording
                    next = inputs.tryReceive().getOrNull()
                }
                mutableFailure.value = recording.failure
                val snapshot = recording.state
                mutableState.value = snapshot
                onProcessed(snapshot)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            inputs.close()
            mutableFailure.value = e
        } finally {
            inputs.close()
            session?.abort()
        }
    }

    private fun process(input: EngineInput, recording: RecordingSession): RecordingSession = when {
        input == EngineInput.Reset && recording.state.phase == RunPhase.STOPPED -> {
            recording.close()
            sessionFactory()
        }

        input == EngineInput.Reset &&
            recording.state.phase == RunPhase.PREPARING &&
            recording.failure != null -> {
            recording.abort()
            sessionFactory()
        }

        else -> {
            recording.handle(input)
            recording
        }
    }

    companion object {
        const val THREAD_NAME = "runpro-recording"
    }
}
