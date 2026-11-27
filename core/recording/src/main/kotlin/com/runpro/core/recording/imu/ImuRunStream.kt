package com.runpro.core.recording.imu

import com.runpro.core.journal.ImuStream
import com.runpro.core.journal.ImuStreamWriter
import com.runpro.core.journal.MonotonicClock
import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ImuSensor
import com.runpro.core.model.RunId
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** Where [ImuCapture] writes and how it checks space; shared by every run of one capture. */
internal class ImuStorage(
    val directory: Path,
    val clock: () -> Timestamp,
    val config: ImuCaptureConfig,
    val freeBytes: (Path) -> Long,
    val source: SourceId,
    val observer: SensorListener?
) {
    val journalClock = MonotonicClock { clock().elapsedRealtimeNanos }
}

/**
 * The open IMU stream segment of one run with its batchers. Confined to the capture executor. Problems
 * end it and go to [onProblem]; nothing throws.
 */
internal class ImuRunStream private constructor(
    private val storage: ImuStorage,
    private var writer: ImuStreamWriter?,
    private val onBatch: (ImuBatch, dropped: Long) -> Unit,
    private val onProblem: (ImuProblem, IOException?) -> Unit
) {
    val segment: String = requireNotNull(writer).file.name
    private var batchers = emptyMap<ImuSensor, ImuBatcher>()
    private var lastSpaceCheck = storage.clock().elapsedRealtimeNanos

    val isOpen: Boolean get() = writer != null

    /** Sends the readings of [sensors] to the stream (batchers are kept across pauses). */
    fun use(sensors: Set<ImuSensor>) {
        batchers = sensors.associateWith { sensor ->
            batchers[sensor] ?: ImuBatcher(
                storage.source,
                sensor,
                storage.config.batchSpan,
                storage.clock,
                ::write
            )
        }
    }

    fun add(sensor: ImuSensor, nanos: Long, x: Float, y: Float, z: Float) {
        if (isOpen) batchers[sensor]?.add(nanos, x, y, z)
    }

    /** Writes partial batches and hands everything to the OS (it survives process death). */
    fun flush() {
        batchers.values.forEach(ImuBatcher::flush)
        val out = writer ?: return
        try {
            out.flush()
        } catch (e: IOException) {
            end(ImuProblem.STORAGE_FAILED, e)
        }
    }

    /** Writes what is left and closes the segment. */
    fun close() {
        if (!isOpen) return
        flush()
        closeWriter()
    }

    /** Ends the segment after [problem], keeping what was written. */
    fun end(problem: ImuProblem, cause: IOException? = null) {
        if (!isOpen) return
        if (problem != ImuProblem.STORAGE_FAILED) batchers.values.forEach(ImuBatcher::flush)
        closeWriter()
        onProblem(problem, cause)
    }

    private fun write(batch: ImuBatch) {
        val out = writer ?: return
        try {
            out.append(batch)
        } catch (e: IOException) {
            end(ImuProblem.STORAGE_FAILED, e)
            return
        }
        storage.observer?.onImu(batch)
        onBatch(batch, batchers.values.sumOf { it.dropped })
        val now = batch.end.elapsedRealtimeNanos
        if (now - lastSpaceCheck >= storage.config.spaceCheckInterval.inWholeNanoseconds) {
            lastSpaceCheck = now
            if (storage.freeBytes(storage.directory) < storage.config.minFreeBytes) {
                end(ImuProblem.LOW_STORAGE)
            }
        }
    }

    private fun closeWriter() {
        val out = writer ?: return
        writer = null
        try {
            out.close()
        } catch (e: IOException) {
            // The batches written before stay readable; the run is not affected.
            onProblem(ImuProblem.STORAGE_FAILED, e)
        }
    }

    companion object {
        /**
         * Prunes old streams, checks free space and opens the next segment of [runId]; null (after
         * [onProblem]) when the run needs the space or storage fails.
         */
        fun open(
            storage: ImuStorage,
            runId: RunId,
            onBatch: (ImuBatch, dropped: Long) -> Unit,
            onProblem: (ImuProblem, IOException?) -> Unit
        ): ImuRunStream? = try {
            val config = storage.config
            ImuStream.prune(storage.directory, config.maxStorageBytes, keep = runId)
            Files.createDirectories(storage.directory)
            if (storage.freeBytes(storage.directory) >= config.minFreeBytes) {
                val writer = ImuStream.create(
                    storage.directory,
                    runId,
                    storage.clock(),
                    storage.journalClock,
                    config.policy
                )
                ImuRunStream(storage, writer, onBatch, onProblem)
            } else {
                onProblem(ImuProblem.LOW_STORAGE, null)
                null
            }
        } catch (e: IOException) {
            onProblem(ImuProblem.STORAGE_FAILED, e)
            null
        }
    }
}
