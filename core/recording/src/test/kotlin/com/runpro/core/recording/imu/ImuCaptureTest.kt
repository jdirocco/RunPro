package com.runpro.core.recording.imu

import com.runpro.core.journal.ImuStream
import com.runpro.core.journal.JournalEnd
import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ImuSensor
import com.runpro.core.model.Run
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.ResourceState
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.tools.replay.noFileStorePath
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class ImuCaptureTest {
    @TempDir
    lateinit var root: Path

    private val dir: Path get() = root.resolve("imu")
    private val executor = QueueExecutor()
    private val port = FakeImuPort()
    private var nanos = 1_000_000_000_000L
    private val clock = { Timestamp(nanos, 1_764_064_800_000 + nanos / 1_000_000) }
    private var free = Long.MAX_VALUE
    private val observed = mutableListOf<ImuBatch>()
    private val observer = object : SensorListener {
        override fun onSample(sample: Sample) = Unit

        override fun onEvent(event: SensorEvent) = Unit

        override fun onImu(batch: ImuBatch) {
            observed += batch
        }
    }

    private fun capture(config: ImuCaptureConfig = ImuCaptureConfig()) =
        ImuCapture(port, dir, executor, clock, config, observer, freeBytes = { free })

    private val runId = RunId("run-1")
    private val start = Timestamp(nanos, 1_764_064_800_000)

    private fun state(phase: RunPhase, id: RunId = runId, battery: Int? = null): RunState {
        val events = buildList {
            add(RunEvent.Started(start))
            if (phase == RunPhase.STOPPED) add(RunEvent.Stopped(start, StopReason.USER))
        }
        val run = Run(id, events).takeIf { phase != RunPhase.IDLE && phase != RunPhase.PREPARING }
        return RunState(phase, run, resources = ResourceState(batteryPercent = battery))
    }

    /** [seconds] of 50 Hz readings on every started sensor, delivered as the platform would. */
    private fun deliver(seconds: Int) {
        repeat(seconds * 50) {
            nanos += 20_000_000
            port.emit(nanos, it % 8 / 8f, -9.75f, 0.5f)
        }
        executor.runAll()
    }

    private fun follow(
        capture: ImuCapture,
        state: RunState,
        mode: ImuMode = ImuMode.ACCELEROMETER
    ) {
        capture.follow(state, mode)
        executor.runAll()
    }

    @Test
    fun `a running run records the accelerometer at 50 Hz into its IMU stream`() {
        val capture = capture()
        follow(capture, state(RunPhase.PREPARING))
        assertEquals(emptyList(), port.starts)
        follow(capture, state(RunPhase.RUNNING))
        assertEquals(listOf(setOf(ImuSensor.ACCELEROMETER)), port.starts)
        assertEquals(ImuRate(20.milliseconds, 1.seconds), port.rate)
        assertEquals(ImuCaptureState.CAPTURING, capture.status.value.state)
        deliver(10)
        follow(capture, state(RunPhase.STOPPED))
        assertTrue(port.stopped)

        val stream = ImuStream.readRun(dir, runId).single()
        assertEquals(JournalEnd.Clean, stream.end)
        assertEquals(500, stream.readingCount)
        assertEquals(setOf(ImuSensor.ACCELEROMETER), stream.batches.map { it.sensor }.toSet())
        assertTrue(stream.batches.all { it.span < 1.seconds })
        assertEquals(observed, stream.batches)
        val status = capture.status.value
        assertEquals(ImuCaptureState.IDLE, status.state)
        assertEquals(mapOf(ImuSensor.ACCELEROMETER to 500L), status.readings)
        assertEquals("run-1.imu", status.segment)
        assertNull(status.problem)
    }

    @Test
    fun `pausing turns the sensors off and keeps writing to the same stream`() {
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING))
        deliver(2)
        port.emit(nanos + 1, 1f, 1f, 1f) // Queued before the pause: still part of the run.
        follow(capture, state(RunPhase.PAUSED))
        assertTrue(port.stopped)
        assertEquals(ImuCaptureState.PAUSED, capture.status.value.state)
        // Everything so far is handed to the OS while paused (survives process death).
        assertEquals(101, ImuStream.readRun(dir, runId).single().readingCount)
        follow(capture, state(RunPhase.RUNNING))
        assertEquals(2, port.starts.size)
        deliver(1)
        follow(capture, state(RunPhase.STOPPED))
        assertEquals(listOf("run-1.imu"), dir.listDirectoryEntries().map { it.name })
        assertEquals(151, ImuStream.readRun(dir, runId).single().readingCount)
    }

    @Test
    fun `the gyroscope is recorded when chosen and the watch has it`() {
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING), ImuMode.ACCELEROMETER_AND_GYROSCOPE)
        deliver(2)
        follow(capture, state(RunPhase.STOPPED), ImuMode.ACCELEROMETER_AND_GYROSCOPE)
        val stream = ImuStream.readRun(dir, runId).single()
        assertEquals(
            mapOf(ImuSensor.ACCELEROMETER to 100, ImuSensor.GYROSCOPE to 100),
            stream.batches.groupBy {
                it.sensor
            }.mapValues { (_, b) -> b.sumOf { it.readings.size } }
        )
    }

    @Test
    fun `a watch without a gyroscope records the accelerometer only`() {
        port.available = setOf(ImuSensor.ACCELEROMETER)
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING), ImuMode.ACCELEROMETER_AND_GYROSCOPE)
        assertEquals(setOf(ImuSensor.ACCELEROMETER), capture.status.value.sensors)
    }

    @Test
    fun `mode off records nothing`() {
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING), ImuMode.OFF)
        follow(capture, state(RunPhase.STOPPED), ImuMode.OFF)
        assertEquals(emptyList(), port.starts)
        assertTrue(Files.notExists(dir) || dir.listDirectoryEntries().isEmpty())
        assertEquals(ImuMode.OFF, capture.status.value.mode)
    }

    @Test
    fun `the mode of a run is the one it started with`() {
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING), ImuMode.ACCELEROMETER)
        follow(capture, state(RunPhase.PAUSED), ImuMode.ACCELEROMETER_AND_GYROSCOPE)
        follow(capture, state(RunPhase.RUNNING), ImuMode.ACCELEROMETER_AND_GYROSCOPE)
        assertEquals(List(2) { setOf(ImuSensor.ACCELEROMETER) }, port.starts)
    }

    @Test
    fun `no sensor ends the capture for the run without a file`() {
        port.available = emptySet()
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING))
        assertEquals(ImuProblem.NO_SENSORS, capture.status.value.problem)
        assertEquals(ImuCaptureState.OFF, capture.status.value.state)
    }

    @Test
    fun `a platform refusal never escapes and is not retried in the run`() {
        port.failure = SecurityException("no access")
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING))
        assertEquals(ImuProblem.SENSOR_FAILED, capture.status.value.problem)
        assertEquals("SecurityException: no access", capture.status.value.error)
        follow(capture, state(RunPhase.PAUSED))
        follow(capture, state(RunPhase.RUNNING))
        assertEquals(1, port.attempts)
        // The next run tries again.
        port.failure = null
        follow(capture, state(RunPhase.RUNNING, RunId("run-2")))
        assertEquals(ImuCaptureState.CAPTURING, capture.status.value.state)
    }

    @Test
    fun `the default free space check works where getFileStore is denied as on the watch`() {
        // Field report #105: Files.getFileStore threw SecurityException on the Galaxy Watch 5 Pro.
        val watchDir = noFileStorePath(dir) { SecurityException("getFileStore") }
        val capture = ImuCapture(port, watchDir, executor, clock)
        follow(capture, state(RunPhase.RUNNING))
        assertEquals(ImuCaptureState.CAPTURING, capture.status.value.state)
        assertNull(capture.status.value.problem)
        deliver(2)
        follow(capture, state(RunPhase.STOPPED))
        assertEquals(100, ImuStream.readRun(dir, runId).single().readingCount)
    }

    @Test
    fun `low free space keeps the IMU out of the run's way`() {
        free = 99_000_000
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING))
        assertEquals(ImuProblem.LOW_STORAGE, capture.status.value.problem)
        assertEquals(emptyList(), port.starts)
    }

    @Test
    fun `free space falling during the run stops the capture and keeps the stream`() {
        val capture = capture(ImuCaptureConfig(spaceCheckInterval = 5.seconds))
        follow(capture, state(RunPhase.RUNNING))
        deliver(3)
        free = 50_000_000
        deliver(10)
        val status = capture.status.value
        assertEquals(ImuProblem.LOW_STORAGE, status.problem)
        assertTrue(port.stopped)
        val stream = ImuStream.readRun(dir, runId).single()
        assertEquals(JournalEnd.Clean, stream.end)
        assertTrue(stream.readingCount in 250..350, "${stream.readingCount}")
    }

    @Test
    fun `a storage failure ends the capture without touching the run`() {
        root.resolve("imu").writeText("not a directory")
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING))
        assertEquals(ImuProblem.STORAGE_FAILED, capture.status.value.problem)
        assertEquals(emptyList(), port.starts)
    }

    @Test
    fun `the battery warning stops the capture`() {
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING, battery = 40))
        deliver(2)
        follow(capture, state(RunPhase.RUNNING, battery = 15))
        assertEquals(ImuProblem.LOW_BATTERY, capture.status.value.problem)
        assertTrue(port.stopped)
        assertEquals(100, ImuStream.readRun(dir, runId).single().readingCount)
    }

    @Test
    fun `a capture restarted during the run writes a new segment`() {
        val first = capture()
        follow(first, state(RunPhase.RUNNING))
        deliver(2)
        // Process death: nothing is closed. The restored run continues in a new capture.
        val second = capture()
        follow(second, state(RunPhase.RUNNING))
        deliver(1)
        follow(second, state(RunPhase.STOPPED))
        assertEquals(
            listOf("run-1.imu", "run-1.2.imu"),
            ImuStream.segments(dir, runId).map { it.name }
        )
        assertEquals(50, ImuStream.readRun(dir, runId).last().readingCount)
    }

    @Test
    fun `opening a capture prunes the oldest runs`() {
        val capture = capture(ImuCaptureConfig(maxStorageBytes = 1))
        follow(capture, state(RunPhase.RUNNING, RunId("old")))
        deliver(2)
        follow(capture, state(RunPhase.STOPPED, RunId("old")))
        follow(capture, state(RunPhase.RUNNING))
        assertEquals(listOf("run-1.imu"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `follow only queues work, so the recording thread never waits (R6)`() {
        val capture = capture()
        capture.follow(state(RunPhase.RUNNING), ImuMode.ACCELEROMETER)
        // The same state again is not queued.
        capture.follow(state(RunPhase.RUNNING), ImuMode.ACCELEROMETER)
        assertEquals(emptyList(), port.starts)
        assertEquals(1, executor.pending)
        executor.runAll()
        assertEquals(1, port.starts.size)
    }

    @Test
    fun `close stops the sensors and closes the stream`() {
        val capture = capture()
        follow(capture, state(RunPhase.RUNNING))
        deliver(1)
        capture.close()
        executor.runAll()
        assertTrue(port.stopped)
        assertEquals(JournalEnd.Clean, ImuStream.readRun(dir, runId).single().end)
        follow(capture, state(RunPhase.RUNNING, RunId("run-2")))
        assertEquals(1, port.starts.size)
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val pending: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class FakeImuPort : ImuPort {
        var available = ImuSensor.entries.toSet()
        var failure: RuntimeException? = null
        val starts = mutableListOf<Set<ImuSensor>>()
        var attempts = 0
        var rate: ImuRate? = null
        var stopped = false
        private var sink: ImuSink? = null
        private var running = emptySet<ImuSensor>()

        override fun start(sensors: Set<ImuSensor>, rate: ImuRate, sink: ImuSink): Set<ImuSensor> {
            attempts++
            failure?.let { throw it }
            running = sensors intersect available
            if (running.isNotEmpty()) starts += running
            this.rate = rate
            this.sink = sink
            stopped = false
            return running
        }

        override fun stop() {
            stopped = true
            running = emptySet()
        }

        fun emit(nanos: Long, x: Float, y: Float, z: Float) {
            running.forEach { sink?.onReading(it, nanos, x, y, z) }
        }
    }
}
