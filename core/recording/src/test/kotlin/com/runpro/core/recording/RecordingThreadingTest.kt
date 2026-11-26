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

    /**
     * Records the threads `onProcessed` runs on. The engine publishes `state` before it calls `onProcessed`,
     * so waiting on `state` can read the threads too early (#64): wait for `onProcessed` itself to see STOPPED.
     */
    private class ProcessedThreads(private val slowMillis: Long = 0) {
        private val names = mutableSetOf<String>()
        private val stopped = CountDownLatch(1)

        fun onProcessed(state: RunState) {
            if (slowMillis > 0) Thread.sleep(slowMillis)
            synchronized(names) { names += threadName() }
            if (state.phase == RunPhase.STOPPED) stopped.countDown()
        }

        fun awaitStopped(): Set<String> {
            assertTrue(stopped.await(10, TimeUnit.SECONDS), "onProcessed never saw STOPPED")
            return synchronized(names) { names.toSet() }
        }

        /** The current thread name without the `@coroutine#N` suffix added in coroutine debug mode. */
        private fun threadName() = Thread.currentThread().name.substringBefore(" @coroutine#")
    }

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
        val processed = ProcessedThreads()
        val engine = RecordingEngine(
            dispatcher = dispatcher,
            runIds = { RunId("run-1") },
            onProcessed = processed::onProcessed
        )
        trace.forEach(engine::submit)
        assertEquals(setOf(engineThreadName), processed.awaitStopped())
        engine.close()
    }

    @Test
    fun `the default engine runs on its own recording thread`() {
        val processed = ProcessedThreads()
        val engine = RecordingEngine(
            runIds = { RunId("run-1") },
            onProcessed = processed::onProcessed
        )
        trace.forEach(engine::submit)
        assertEquals(setOf(RecordingEngine.THREAD_NAME), processed.awaitStopped())
        engine.close()
    }

    @Test
    fun `a slow onProcessed callback still reports the engine thread`() {
        // Regression for #64: with a slow callback, STOPPED is published long before onProcessed records it.
        val processed = ProcessedThreads(slowMillis = 200)
        val engine = RecordingEngine(
            dispatcher = dispatcher,
            runIds = { RunId("run-1") },
            onProcessed = processed::onProcessed
        )
        trace.forEach(engine::submit)
        assertEquals(setOf(engineThreadName), processed.awaitStopped())
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
