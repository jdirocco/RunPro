package com.runpro.core.recording

import com.runpro.core.journal.JournalOutput
import com.runpro.core.journal.JournalWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.io.TempDir

class JournalFailureTest {
    @Test
    fun `write sync and close failures leave the engine operational and never claim a saved run`() {
        for (operation in listOf("write", "sync", "close")) {
            var failing = ""
            var writes = 0
            var now = 0L
            val output = object : JournalOutput {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writes++
                    if (failing == "write") throw IOException("write failed")
                }
                override fun sync() {
                    if (failing == "sync") throw IOException("sync failed")
                }
                override fun close() {
                    if (failing == "close") throw IOException("close failed")
                }
            }
            RecordingEngine(
                dispatcher = Dispatchers.Unconfined,
                sessionFactory = { RecordingSession(journal = JournalWriter(output, { now })) }
            ).use { engine ->
                engine.submit(prepare(0))
                engine.submit(start(0))
                failing = operation
                when (operation) {
                    "write" -> {
                        engine.submit(hr(0, 140))
                        now = 1.seconds.inWholeNanoseconds
                        engine.submit(tick(1))
                    }

                    "sync" -> engine.submit(pause(1))

                    "close" -> engine.submit(stop(1))
                }
                assertIs<IOException>(engine.failure.value)
                assertTrue(engine.state.value.journalFailed)
                assertFalse(engine.state.value.journalSaved)
                val writesAfterFailure = writes
                assertTrue(engine.submit(resume(2)))
                assertTrue(engine.submit(hr(3, 155)))
                assertTrue(engine.submit(tick(4)))
                if (operation != "close") {
                    assertEquals(155, engine.state.value.current.heartRate!!.bpm)
                    assertEquals(RunPhase.RUNNING, engine.state.value.phase)
                }
                assertTrue(engine.submit(stop(5)))
                assertEquals(RunPhase.STOPPED, engine.state.value.phase)
                assertFalse(engine.state.value.journalSaved)
                assertEquals(writesAfterFailure, writes, "failed writers must not be reused")
            }
        }
    }

    @Test
    fun `file creation failure does not crash or close engine submission`(@TempDir dir: Path) {
        val directory = dir.resolve("not-a-directory")
        Files.writeString(directory, "keep")
        RecordingEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = { RecordingSession.create(directory) }
        ).use { engine ->
            engine.submit(prepare(0))
            engine.submit(start(0))
            assertIs<IOException>(engine.failure.value)
            assertTrue(engine.state.value.journalFailed)
            assertFalse(engine.state.value.journalSaved)
            assertEquals(RunPhase.PREPARING, engine.state.value.phase)
            assertTrue(engine.submit(hr(1, 150)))
            assertEquals(150, engine.state.value.current.heartRate!!.bpm)
            assertEquals("keep", Files.readString(directory))
        }
    }
}
