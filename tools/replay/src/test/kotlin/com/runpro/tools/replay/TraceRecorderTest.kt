package com.runpro.tools.replay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TraceRecorderTest {
    @Test
    fun `a recorder attached to sources writes what they deliver`() {
        val bytes = ByteArrayOutputStream()
        TraceRecorder(bytes, Fixtures.header).use { recorder ->
            // Record a replay: the recorder is itself a SensorListener.
            val replayer = Replayer(Fixtures.trace)
            replayer.sources.forEach { it.start(recorder) }
            replayer.run()
            assertEquals(Fixtures.trace.records.size, recorder.recordCount)
        }
        assertEquals(Fixtures.trace, TraceReader.read(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test
    fun `concurrent sources never interleave lines`() {
        val bytes = ByteArrayOutputStream()
        val threads = 4
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        TraceRecorder(bytes, Fixtures.header).use { recorder ->
            repeat(threads) {
                pool.execute {
                    start.await()
                    repeat(perThread) { i -> recorder.record(Fixtures.trace.records[i % 8]) }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
        val trace = TraceReader.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(threads * perThread, trace.records.size)
    }

    @Test
    fun `recording after close is an error`() {
        val recorder = TraceRecorder(ByteArrayOutputStream(), Fixtures.header)
        recorder.close()
        recorder.close()
        assertFailsWith<IllegalStateException> { recorder.record(Fixtures.trace.records[0]) }
    }
}
