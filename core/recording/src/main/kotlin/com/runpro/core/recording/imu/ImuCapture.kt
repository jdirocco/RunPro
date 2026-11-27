package com.runpro.core.recording.imu

import com.runpro.core.journal.ImuStream
import com.runpro.core.journal.JournalPolicy
import com.runpro.core.model.ImuBatch
import com.runpro.core.model.RunId
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Limits of the IMU capture (ADR 0012).
 *
 * @property batchSpan readings are written in batches spanning less than this.
 * @property maxStorageBytes all IMU streams together; the oldest runs are deleted when a capture opens.
 * @property minFreeBytes the capture stops below this free space, twice the low-storage warning, so the
 *   IMU never takes the space the run journal needs (R7).
 * @property spaceCheckInterval how often free space is checked while capturing.
 */
data class ImuCaptureConfig(
    val rate: ImuRate = ImuRate(),
    val batchSpan: Duration = 1.seconds,
    val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
    val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    val spaceCheckInterval: Duration = 30.seconds,
    val policy: JournalPolicy = JournalPolicy()
) {
    companion object {
        /** About 80 hours of accelerometer and gyroscope (3.6 MB an hour). */
        const val DEFAULT_MAX_STORAGE_BYTES = 300_000_000L
        const val DEFAULT_MIN_FREE_BYTES = 100_000_000L
    }
}

/**
 * Records the watch IMU at 50 Hz while a run is running, into the run's IMU stream ([ImuStream]) in
 * [directory]. It follows the run ([follow]): sensors on while running, off while paused, stream closed
 * when the run stops. The run never depends on it (ADR 0012):
 *
 * - Nothing here touches the run journal or the engine; a failure (no sensor, storage error, low space,
 *   low battery) only ends the capture for the current run and is reported in [status]. Nothing throws.
 * - All work runs on [executor], which must be single threaded (on the watch, the sensor thread).
 *   [follow] only queues, so the recording thread that calls it never waits (R6).
 * - A capture restarted during a run (process death) writes a new segment of the same run.
 *
 * [observer] receives every batch on the executor (for a debug trace or live estimators); [onChange]
 * receives the status when its state, sensors, segment or problem change (for the log). Both run on the
 * executor and must return quickly.
 */
class ImuCapture(
    private val port: ImuPort,
    directory: Path,
    private val executor: Executor,
    clock: () -> Timestamp,
    private val config: ImuCaptureConfig = ImuCaptureConfig(),
    observer: SensorListener? = null,
    freeBytes: (Path) -> Long = { Files.getFileStore(it).usableSpace },
    source: SourceId = SourceId.WATCH_IMU,
    private val onChange: (ImuStatus) -> Unit = {}
) : AutoCloseable {
    private val mutableStatus = MutableStateFlow(ImuStatus())

    /** Updated on every change and after every written batch. */
    val status: StateFlow<ImuStatus> = mutableStatus.asStateFlow()

    private val storage = ImuStorage(directory, clock, config, freeBytes, source, observer)
    private val requested = AtomicReference<Target?>(null)
    private val sink = ImuSink { sensor, nanos, x, y, z ->
        executor.execute { guarded { stream?.add(sensor, nanos, x, y, z) } }
    }

    // Executor confined.
    private var run: RunId? = null
    private var mode = ImuMode.OFF
    private var stream: ImuRunStream? = null
    private var sensorsOn = false
    private var closed = false

    /**
     * Brings the capture to the run [state] with [mode] (the setting of this run). Call it with every
     * published state: repeated states are ignored without queuing. Never blocks.
     */
    fun follow(state: RunState, mode: ImuMode) {
        val target = Target(
            state.run?.id,
            state.phase,
            if (state.run == null) ImuMode.OFF else mode,
            state.resources.lowBattery
        )
        if (requested.getAndSet(target) != target) executor.execute { guarded { apply(target) } }
    }

    /** Stops the sensors and closes the stream. Queued like [follow]. */
    override fun close() {
        executor.execute {
            guarded { finishRun() }
            closed = true
        }
    }

    private fun apply(target: Target) {
        if (closed) return
        if (target.runId != run) {
            finishRun()
            run = target.runId
            mode = target.mode
            publish(ImuStatus(runId = run, mode = mode))
        }
        val runId = run
        val current = mutableStatus.value
        when {
            runId == null || target.phase !in ACTIVE_PHASES -> {
                finishRun()
                publish(mutableStatus.value.copy(state = ImuCaptureState.IDLE))
            }

            mode == ImuMode.OFF || current.problem != null ->
                publish(current.copy(state = ImuCaptureState.OFF))

            target.lowBattery -> fail(ImuProblem.LOW_BATTERY)

            target.phase == RunPhase.PAUSED -> {
                stopSensors()
                stream?.flush()
                if (mutableStatus.value.problem == null) {
                    publish(mutableStatus.value.copy(state = ImuCaptureState.PAUSED))
                }
            }

            !sensorsOn -> capture(runId)
        }
    }

    private fun capture(runId: RunId) {
        // The stream first: without space or storage the sensors are never turned on.
        val open = stream ?: ImuRunStream.open(storage, runId, ::onBatch, ::onProblem) ?: return
        stream = open
        val started = port.start(mode.sensors, config.rate, sink)
        sensorsOn = started.isNotEmpty()
        if (!sensorsOn) {
            fail(ImuProblem.NO_SENSORS)
            return
        }
        open.use(started)
        publish(
            mutableStatus.value.copy(
                state = ImuCaptureState.CAPTURING,
                sensors = started,
                segment = open.segment
            )
        )
    }

    private fun onBatch(batch: ImuBatch, dropped: Long) {
        val status = mutableStatus.value
        val count = (status.readings[batch.sensor] ?: 0) + batch.readings.size
        publish(
            status.copy(readings = status.readings + (batch.sensor to count), dropped = dropped)
        )
    }

    /** The stream ended for [problem]: the capture stays off for the rest of the run. */
    private fun onProblem(problem: ImuProblem, cause: IOException?) {
        stopSensors()
        stream = null
        val status = mutableStatus.value
        publish(
            status.copy(
                state = ImuCaptureState.OFF,
                problem = status.problem ?: problem,
                error = cause?.let(::describe) ?: status.error
            )
        )
    }

    private fun stopSensors() {
        if (sensorsOn) {
            sensorsOn = false
            port.stop()
        }
    }

    /** Ends the capture of the current run, keeping what was written. */
    private fun finishRun() {
        stopSensors()
        stream?.close()
        stream = null
    }

    private fun fail(problem: ImuProblem, cause: RuntimeException? = null) {
        stopSensors()
        val open = stream
        if (open != null && open.isOpen) {
            open.end(problem)
        } else {
            onProblem(problem, null)
        }
        cause?.let { publish(mutableStatus.value.copy(error = describe(it))) }
    }

    private fun publish(status: ImuStatus) {
        val previous = mutableStatus.value
        mutableStatus.value = status
        if (status.copy(readings = previous.readings, dropped = previous.dropped) != previous) {
            onChange(status)
        }
    }

    // A platform or file error on the sensor thread must never reach the process: it would crash the run.
    @Suppress("TooGenericExceptionCaught")
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            if (closed) return
            try {
                fail(ImuProblem.SENSOR_FAILED, e)
            } catch (_: RuntimeException) {
                stream = null
            }
        }
    }

    private data class Target(
        val runId: RunId?,
        val phase: RunPhase,
        val mode: ImuMode,
        val lowBattery: Boolean
    )

    private companion object {
        val ACTIVE_PHASES = setOf(RunPhase.RUNNING, RunPhase.PAUSED)

        fun describe(e: Throwable) = "${e::class.simpleName}: ${e.message}"
    }
}
