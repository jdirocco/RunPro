package com.runpro.core.recording

import com.runpro.core.model.RunId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 * @param onProcessed called on the engine thread after each batch, with the published state (diagnostics,
 *   later the journal flush trigger). It must not block.
 */
class RecordingEngine(
    config: EngineConfig = EngineConfig(),
    runIds: () -> RunId = RunId::random,
    dispatcher: CoroutineDispatcher? = null,
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
    private val machine = RecordingStateMachine(config, runIds)
    private val mutableState = MutableStateFlow(machine.state)

    val state: StateFlow<RunState> = mutableState.asStateFlow()

    init {
        scope.launch { processInputs() }
    }

    /** Queues [input] without waiting. Returns false only after [close]. */
    fun submit(input: EngineInput): Boolean = inputs.trySend(input).isSuccess

    /** Stops processing; queued inputs that were not processed yet are dropped. */
    override fun close() {
        inputs.close()
        scope.cancel()
        ownExecutor?.shutdown()
    }

    private suspend fun processInputs() {
        for (input in inputs) {
            machine.handle(input)
            var next = inputs.tryReceive().getOrNull()
            while (next != null) {
                machine.handle(next)
                next = inputs.tryReceive().getOrNull()
            }
            val snapshot = machine.state
            mutableState.value = snapshot
            onProcessed(snapshot)
        }
    }

    companion object {
        const val THREAD_NAME = "runpro-recording"
    }
}
