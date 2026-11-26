package com.runpro.core.recording

import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import java.io.IOException

/**
 * A single recording and its journal, confined to the recording thread. Accepted samples and run events
 * are journaled before the engine publishes state. Callers must supply ticks at least once a second.
 * A journal belongs to one run: create a new session after saving it instead of resetting this one.
 */
class RecordingSession internal constructor(
    private val machine: RecordingStateMachine,
    private val journal: JournalWriter? = null
) : AutoCloseable {
    constructor(
        config: EngineConfig = EngineConfig(),
        runIds: () -> RunId = RunId::random,
        journal: JournalWriter? = null
    ) : this(RecordingStateMachine(config, runIds), journal)

    val state: RunState get() = machine.state
    private var failed = false

    fun handle(input: EngineInput): Boolean {
        check(!failed) { "The recording session failed" }
        if (journal != null && input == EngineInput.Reset) return false
        val previousEvents = state.run?.events?.size ?: 0
        val accepted = machine.handle(input)
        try {
            if (accepted) persist(input, previousEvents)
            journal?.tick()
        } catch (e: IOException) {
            failed = true
            throw e
        }
        return accepted
    }

    private fun persist(input: EngineInput, previousEvents: Int) {
        val run = state.run ?: return // Preparing samples are not part of the run.
        for (event in run.events.drop(previousEvents)) {
            journal?.append(
                if (event is RunEvent.Started) {
                    JournalRecord.RunStarted(run.id, event.time)
                } else {
                    JournalRecord.Event(event)
                }
            )
        }
        if (input is EngineInput.SampleReceived) {
            journal?.append(
                JournalRecord.Samples(listOf(input.sample))
            )
        }
        if (input is EngineInput.Tick) journal?.append(JournalRecord.Checkpoint(input.time))
    }

    /** Orderly shutdown: flushes buffered samples. A stopped run was already synced by handle(Stop). */
    override fun close() = journal?.close() ?: Unit

    /** Abrupt shutdown: discards unflushed samples. RecordingEngine.close uses this path. */
    fun abort() = journal?.abort() ?: Unit
}
