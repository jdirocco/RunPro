package com.runpro.core.recording

import com.runpro.core.journal.JournalContents
import com.runpro.core.journal.JournalRecord
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Run
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.Timestamp
import com.runpro.core.model.WorkoutSettings

/**
 * One journaled sample as the engine counted it.
 *
 * @property distance the run distance (pauses and source resets excluded) right after this sample.
 */
data class RecordedSample(val sample: Sample, val distance: Distance)

/**
 * A stopped run rebuilt from its journal by the recording engine itself, so an export shows the same totals
 * and laps the runner saw on the watch.
 *
 * @property samples the journaled samples the engine admitted, in journal order.
 * @property sensorEvents the adapter reports (e.g. a disconnect with its GATT status), in journal order.
 */
class RecordedRun internal constructor(
    val state: RunState,
    val samples: List<RecordedSample>,
    val sensorEvents: List<SensorEvent>
) {
    val run: Run get() = checkNotNull(state.run)
    val id: RunId get() = run.id
    val startedAt: Timestamp get() = run.startedAt

    /** The wall clock of [time] derived from the start, free of wall clock jumps during the run. */
    fun wallClockMillis(time: Timestamp): Long = time.wallClockFrom(startedAt)

    companion object {
        /**
         * Rebuilds the run in [contents]. Only a complete run can be rebuilt: it must be stopped and every
         * record must be known, otherwise an export would silently lose data.
         */
        fun from(contents: JournalContents, config: EngineConfig = EngineConfig()): RecordedRun {
            require(contents.skippedRecords == 0) {
                "The journal has ${contents.skippedRecords} unknown records"
            }
            require(contents.isStopped) { "The run is not stopped" }
            val start = contents.records.firstOrNull() as? JournalRecord.RunStarted
            requireNotNull(start) { "The journal must begin with a run start" }

            val machine = RecordingStateMachine(config, automaticLaps = false) { start.runId }
            machine.handle(EngineInput.ConfigureWorkout(start.workout ?: WorkoutSettings()))
            machine.handle(EngineInput.Prepare(start.time))
            machine.handle(EngineInput.Start(start.time))
            val samples = mutableListOf<RecordedSample>()
            val sensorEvents = mutableListOf<SensorEvent>()
            var distance = Distance.ZERO
            replayJournal(
                contents,
                machine,
                onSample = { sample, admitted ->
                    if (admitted) {
                        if (sample is DistanceSample) distance = machine.state.totals.distance
                        samples += RecordedSample(sample, distance)
                    }
                },
                onSensorEvent = { sensorEvents += it }
            )
            check(machine.state.phase == RunPhase.STOPPED) { "The rebuilt run did not stop" }
            return RecordedRun(machine.state, samples, sensorEvents)
        }
    }
}

/**
 * Feeds the records after the run start of [contents] to [machine] without journaling them again and
 * returns the latest retained timestamp. Shared by recovery and export so both rebuild a run the same way.
 */
internal fun replayJournal(
    contents: JournalContents,
    machine: RecordingStateMachine,
    onSample: (sample: Sample, admitted: Boolean) -> Unit = { _, _ -> },
    onSensorEvent: (SensorEvent) -> Unit = {}
): Timestamp {
    val start = contents.records.first() as JournalRecord.RunStarted
    var last = start.time
    for (record in contents.records.drop(1)) {
        when (record) {
            is JournalRecord.RunStarted -> error("Multiple run starts in one journal")

            is JournalRecord.Samples -> record.samples.forEach {
                onSample(it, machine.restoreSample(it))
                last = maxOf(last, it.time)
            }

            is JournalRecord.Event -> {
                machine.restoreEvent(record.event)
                last = maxOf(last, record.event.time)
            }

            is JournalRecord.Sensor -> {
                machine.restoreSensor(record.event)
                onSensorEvent(record.event)
                last = maxOf(last, record.event.time)
            }

            is JournalRecord.Checkpoint -> {
                machine.restoreClock(record.time)
                last = maxOf(last, record.time)
            }

            // IMU batches belong to the IMU stream (ADR 0012); they never change the run.
            is JournalRecord.Imu -> Unit
        }
    }
    machine.restoreClock(last)
    return last
}
