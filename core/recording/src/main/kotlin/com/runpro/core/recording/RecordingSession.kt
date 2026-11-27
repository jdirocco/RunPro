package com.runpro.core.recording

import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.journal.MonotonicClock
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A single recording and its journal, confined to the recording thread. Accepted samples and run events
 * are journaled before publication. Supply ticks at least once a second. A journal belongs to one run.
 * I/O failure disables persistence, exposes [failure], and leaves the in-memory engine operational.
 */
class RecordingSession internal constructor(
    private val machine: RecordingStateMachine,
    private var journal: JournalWriter? = null,
    private val journalFactory: (() -> JournalWriter)? = null
) : AutoCloseable {
    constructor(
        config: EngineConfig = EngineConfig(),
        runIds: () -> RunId = RunId::random,
        journal: JournalWriter? = null
    ) : this(
        RecordingStateMachine(config, requireStorageCheck = journal != null, runIds = runIds),
        journal
    )

    private var closed = false
    private val journaled: Boolean get() = journal != null || journalFactory != null
    var failure: IOException? = null
        private set

    val state: RunState
        get() = machine.state.let {
            it.copy(
                journalSaved =
                    journaled && closed && failure == null && it.phase == RunPhase.STOPPED,
                journalFailed = failure != null
            )
        }

    fun handle(input: EngineInput): Boolean = when {
        journaled && input == EngineInput.Reset -> false

        // After a terminal write error keep accepting inputs, but never reuse the failed writer.
        failure != null -> machine.handle(input)

        else -> handlePersisted(input)
    }

    private fun handlePersisted(input: EngineInput): Boolean {
        var accepted = false
        try {
            checkStartStorage(input)
            val previousEvents = state.run?.events?.size ?: 0
            accepted = machine.handle(input)
            if (accepted) persist(input, previousEvents)
            finishPersistence()
        } catch (e: IOException) {
            failure = e
            try {
                abort()
            } catch (closing: IOException) {
                e.addSuppressed(closing)
            }
        }
        return accepted
    }

    private fun checkStartStorage(input: EngineInput) {
        if (input is EngineInput.Start && journaled && state.phase == RunPhase.PREPARING) {
            val writer = journal ?: requireNotNull(journalFactory).invoke().also { journal = it }
            machine.handle(
                EngineInput.Resources(input.time, availableBytes = writer.availableBytes)
            )
        }
    }

    private fun finishPersistence() {
        if (closed) return
        journal?.tick()
        if (journal?.storageFull == true) {
            val beforeStop = state.run?.events?.size ?: 0
            if (machine.stopForStorageFull()) persistEvents(beforeStop)
        }
        if (state.phase == RunPhase.STOPPED) close()
    }

    private fun persist(input: EngineInput, previousEvents: Int) {
        if (state.run == null) return // Preparing samples are not part of the run.
        persistEvents(previousEvents)
        when (input) {
            is EngineInput.SampleReceived -> journal?.append(
                JournalRecord.Samples(listOf(input.sample))
            )

            is EngineInput.SensorReceived -> journal?.append(JournalRecord.Sensor(input.event))

            is EngineInput.Tick -> journal?.append(JournalRecord.Checkpoint(input.time))

            else -> Unit
        }
    }

    private fun persistEvents(previousEvents: Int) {
        val run = state.run ?: return
        for (event in run.events.drop(previousEvents)) {
            journal?.append(
                if (event is RunEvent.Started) {
                    JournalRecord.RunStarted(run.id, event.time)
                } else {
                    JournalRecord.Event(event)
                }
            )
        }
    }

    /** Orderly shutdown; successful stops close before publication, including resource-triggered stops. */
    override fun close() {
        if (!closed) {
            journal?.close()
            closed = true
        }
    }

    /** Abrupt shutdown: discards unflushed samples. RecordingEngine.close uses this path. */
    fun abort() {
        journal?.abort()
    }

    companion object {
        /** Creates files only on Start, on the engine thread. Never overwrites a previous run (R3). */
        fun create(
            directory: Path,
            clock: MonotonicClock = MonotonicClock.SYSTEM,
            config: EngineConfig = EngineConfig()
        ): RecordingSession {
            val runId = RunId.random()
            return RecordingSession(
                RecordingStateMachine(config, requireStorageCheck = true, runIds = { runId }),
                journalFactory = {
                    Files.createDirectories(directory)
                    JournalWriter.create(directory.resolve("${runId.value}.journal"), clock)
                }
            )
        }
    }
}
