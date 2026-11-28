package com.runpro.wear

import android.annotation.SuppressLint
import android.util.Log
import com.runpro.core.model.ModelJson
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Optional replay-compatible raw sensor trace. All serialization and I/O runs off the engine thread. */
internal class DebugTrace(private val directory: File) : AutoCloseable {
    private val worker = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { task -> Thread(task, "runpro-debug-trace").apply { isDaemon = true } }
    )

    @Volatile private var disabled = false

    @Volatile private var active: RunId? = null
    private var writer: BufferedWriter? = null
    private var bytes = 0L

    /** Engine thread: only queue when the run changes. Recovery creates a new trace segment. */
    fun follow(state: RunState) {
        val id = state.run?.id?.takeIf {
            state.workoutSettings.runSettings.debugTrace &&
                (state.phase == RunPhase.RUNNING || state.phase == RunPhase.PAUSED)
        }
        if (id == active) return
        active = id
        enqueue {
            closeWriter()
            if (id != null && !disabled) open(id)
        }
    }

    fun onSample(sample: Sample) {
        if (active !=
            null
        ) {
            enqueue { write(ModelJson.json.encodeToString(Sample.serializer(), sample)) }
        }
    }

    fun onEvent(event: SensorEvent) {
        if (active !=
            null
        ) {
            enqueue { write(ModelJson.json.encodeToString(SensorEvent.serializer(), event)) }
        }
    }

    private fun enqueue(task: () -> Unit) {
        try {
            worker.execute {
                try {
                    if (disabled) closeWriter() else task()
                } catch (e: IOException) {
                    fail(e)
                } catch (e: SecurityException) {
                    fail(e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // A slow disk must never back-pressure a sensor or the recording thread.
            disabled = true
        }
    }

    // Debug capture uses only ordinary free bytes, leaving reclaimable cache for the run journal.
    @SuppressLint("UsableSpace")
    private fun open(id: RunId) {
        if (!directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IOException("Cannot create trace directory")
        }
        if (directory.usableSpace < MIN_FREE_BYTES) return
        val files = directory.listFiles { file -> file.name.endsWith(".jsonl") }
            .orEmpty().sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        while (total > RETAINED_BYTES && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            val size = oldest.length()
            if (oldest.delete()) total -= size
        }
        val file = File.createTempFile("${id.value}-", ".jsonl", directory)
        writer = file.bufferedWriter()
        bytes = 0
        write("""{"type":"trace_header","version":1,"name":"${id.value}","device":"Wear OS"}""")
    }

    @SuppressLint("UsableSpace")
    private fun write(line: String) {
        val output = writer ?: return
        bytes += line.toByteArray(Charsets.UTF_8).size + 1
        if (bytes > MAX_TRACE_BYTES || directory.usableSpace < MIN_FREE_BYTES) {
            closeWriter()
            return
        }
        output.appendLine(line)
        output.flush()
    }

    private fun closeWriter() {
        val output = writer
        writer = null
        output?.close()
    }

    private fun fail(error: Exception) {
        disabled = true
        Log.w("DebugTrace", "Debug trace stopped; recording continues", error)
        try {
            closeWriter()
        } catch (_: IOException) {
            // The optional trace is already disabled.
        }
    }

    override fun close() {
        active = null
        enqueue(::closeWriter)
        worker.shutdown() // queued work drains; the caller never waits for disk
    }

    private companion object {
        const val QUEUE_CAPACITY = 256
        const val MIN_FREE_BYTES = 100_000_000L
        const val MAX_TRACE_BYTES = 10_000_000L
        const val RETAINED_BYTES = 20_000_000L
    }
}
