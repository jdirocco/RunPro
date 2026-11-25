package com.runpro.core.recording

import com.runpro.core.model.RunId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** R6: the recording path never runs on the caller's (main) thread and never waits for the UI. */
class RecordingThreadingTest {
    private val engineThreadName = "test-recording"
    private val executor = Executors.newSingleThreadExecutor { Thread(it, engineThreadName) }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val trace = SyntheticTraces.easyThirtyMinutes()

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
    }

    /** The current thread name without the `@coroutine#N` suffix added in coroutine debug mode. */
    private fun threadName() = Thread.currentThread().name.substringBefore(" @coroutine#")

    private fun engine() = RecordingEngine(
        dispatcher = dispatcher,
        runIds = { RunId("run-1") }
    )

    private fun awaitStopped(engine: RecordingEngine): RunState = runBlocking {
        withTimeout(10.seconds) { engine.state.first { it.phase == RunPhase.STOPPED } }
    }

    @Test
    fun `the engine computes the same totals as the state machine`() {
        val engine = engine()
        trace.forEach(engine::submit)
        val expected = machine().feed(trace)
        assertEquals(expected, awaitStopped(engine))
        engine.close()
    }

    @Test
    fun `inputs are processed on the engine dispatcher, not on the caller thread`() {
        val processedOn = mutableSetOf<String>()
        val engine = RecordingEngine(
            dispatcher = dispatcher,
            runIds = { RunId("run-1") },
            onProcessed = { synchronized(processedOn) { processedOn += threadName() } }
        )
        trace.forEach(engine::submit)
        awaitStopped(engine)
        assertEquals(setOf(engineThreadName), synchronized(processedOn) { processedOn.toSet() })
        engine.close()
    }

    @Test
    fun `the default engine runs on its own recording thread`() {
        val threads = mutableSetOf<String>()
        val engine = RecordingEngine(
            runIds = { RunId("run-1") },
            onProcessed = { synchronized(threads) { threads += threadName() } }
        )
        trace.forEach(engine::submit)
        awaitStopped(engine)
        assertEquals(setOf(RecordingEngine.THREAD_NAME), synchronized(threads) { threads.toSet() })
        engine.close()
    }

    @Test
    fun `submitting never waits for a busy engine`() {
        // Block the engine thread before the engine exists, so its loop cannot drain any input early.
        val busy = CountDownLatch(1)
        executor.execute { busy.await() }
        val engine = engine()

        val startedAt = System.nanoTime()
        trace.forEach { assertTrue(engine.submit(it)) }
        val submitMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue(
            submitMillis < 2_000,
            "Submitting took $submitMillis ms while the engine was busy"
        )
        assertEquals(RunPhase.IDLE, engine.state.value.phase)

        busy.countDown()
        assertEquals(machine().feed(trace).totals, awaitStopped(engine).totals)
        engine.close()
    }

    @Test
    fun `a stuck UI collector does not stall the recording`() = runBlocking {
        val engine = engine()
        val uiThread = Executors.newSingleThreadExecutor { Thread(it, "test-ui") }
        val stuck = CountDownLatch(1)
        val uiJob = launch(uiThread.asCoroutineDispatcher()) {
            engine.state.collect { stuck.await() }
        }
        try {
            trace.forEach(engine::submit)
            val stopped = withTimeout(10.seconds) {
                engine.state.first { it.phase == RunPhase.STOPPED }
            }
            assertEquals(5160.0, stopped.totals.distance.meters, 0.01)
        } finally {
            stuck.countDown()
            uiJob.cancelAndJoin()
            uiThread.shutdown()
            engine.close()
        }
    }

    @Test
    fun `a closed engine rejects inputs`() {
        val engine = engine()
        engine.close()
        assertTrue(!engine.submit(prepare(0)))
    }
}
