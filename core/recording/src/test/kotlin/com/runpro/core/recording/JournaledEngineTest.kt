package com.runpro.core.recording

import com.runpro.core.journal.JournalOutput
import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class JournaledEngineTest {
    @Test
    fun `journal sync and session creation run off the caller thread before start is published`() =
        runBlocking {
            val syncing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caller = Thread.currentThread().threadId()
            val storedBytes = ByteArrayOutputStream()
            val engine = RecordingEngine(sessionFactory = {
                assertTrue(Thread.currentThread().threadId() != caller)
                RecordingSession(
                    journal = JournalWriter(object : JournalOutput {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            storedBytes.write(bytes, offset, length)
                        }
                        override fun sync() {
                            assertTrue(Thread.currentThread().threadId() != caller)
                            syncing.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        override fun close() = Unit
                    })
                )
            })
            try {
                assertTrue(engine.submit(prepare(0)))
                assertTrue(engine.submit(start(0)))
                assertTrue(syncing.await(10, TimeUnit.SECONDS))
                assertTrue(engine.state.value.phase != RunPhase.RUNNING)
                assertTrue(engine.submit(stop(10))) // Does not wait for the blocked fsync.
                release.countDown()
                withTimeout(10.seconds) { engine.state.first { it.phase == RunPhase.STOPPED } }
                val journal = JournalReader.read(ByteArrayInputStream(storedBytes.toByteArray()))
                assertIs<JournalRecord.RunStarted>(journal.records.first())
                assertTrue(journal.isStopped)
            } finally {
                release.countDown()
                engine.close()
            }
        }

    @Test
    fun `persistence failures are observable without stopping later inputs`() = runBlocking {
        val engine = RecordingEngine(sessionFactory = {
            RecordingSession(
                journal = JournalWriter(object : JournalOutput {
                    override fun write(bytes: ByteArray, offset: Int, length: Int): Unit =
                        throw IOException("full")
                    override fun sync() = Unit
                    override fun close() = Unit
                })
            )
        })
        try {
            engine.submit(prepare(0))
            engine.submit(start(0))
            assertIs<IOException>(withTimeout(10.seconds) { engine.failure.first { it != null } })
            assertTrue(engine.submit(hr(10, 155)))
            assertTrue(engine.submit(tick(10)))
            withTimeout(10.seconds) { engine.state.first { it.current.heartRate?.bpm == 155 } }
            assertEquals(RunPhase.RUNNING, engine.state.value.phase)
            assertTrue(engine.state.value.journalFailed)
            engine.submit(stop(11))
            withTimeout(10.seconds) { engine.state.first { it.phase == RunPhase.STOPPED } }
            assertFalse(engine.state.value.journalSaved)
        } finally {
            engine.close()
        }
    }
}
