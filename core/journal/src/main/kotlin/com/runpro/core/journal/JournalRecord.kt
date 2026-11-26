package com.runpro.core.journal

import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.Timestamp

/**
 * One entry of a run journal. A journal starts with [RunStarted]; a run whose journal has no
 * [RunEvent.Stopped] event was interrupted and is recovered from it (#9).
 *
 * [JournalWriter] syncs run starts and run events to storage before `append` returns; samples and sensor
 * events follow the timed flush and sync policy ([JournalPolicy]).
 */
sealed interface JournalRecord {
    /** The start tap: the first record of every journal (R1). */
    data class RunStarted(val runId: RunId, val time: Timestamp) : JournalRecord {
        val event: RunEvent.Started get() = RunEvent.Started(time)
    }

    /** A batch of samples in the order the engine received them. */
    data class Samples(val samples: List<Sample>) : JournalRecord {
        init {
            require(samples.isNotEmpty()) { "A sample batch cannot be empty" }
        }
    }

    /** A run event other than the start: pause, resume, lap, source switch, sensor status, stop. */
    data class Event(val event: RunEvent) : JournalRecord {
        init {
            require(event !is RunEvent.Started) { "The run start is a RunStarted record" }
        }
    }

    /** A sensor state change reported by an adapter (e.g. a disconnect with its GATT status). */
    data class Sensor(val event: SensorEvent) : JournalRecord
}
