package com.runpro.core.recording

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.RunId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class RecordedRunTest {
    private val inputs = listOf(
        prepare(-5),
        hr(-1, 100),
        start(0),
        hr(1, 140),
        hr(1, 150, STRAP),
        dist(1, 0.0),
        dist(2, 3.0),
        pause(3),
        dist(4, 6.0),
        resume(5),
        dist(6, 9.0),
        lap(7),
        cad(8, 170.0),
        tick(9),
        stop(10)
    )

    @Test
    fun `a stopped journal rebuilds the state the runner saw`(@TempDir dir: Path) {
        val file = dir.resolve("run.journal")
        val live =
            RecordingSession(runIds = { RunId("run-1") }, journal = JournalWriter.create(file))
        live.use { inputs.forEach(it::handle) }

        val recorded = RecordedRun.from(JournalReader.read(file))

        assertEquals(live.state.run, recorded.state.run)
        assertEquals(live.state.totals, recorded.state.totals)
        assertEquals(live.state.laps, recorded.state.laps)
        // Warm-up samples are not journaled; the strap sample is not admitted (the wrist reported first).
        assertEquals(6, recorded.samples.size)
        // Run distance after each distance sample: the paused interval is dropped.
        assertEquals(
            listOf(0.0, 3.0, 3.0, 3.0),
            recorded.samples.filter {
                it.sample is DistanceSample
            }.map { it.distance.meters }
        )
    }

    @Test
    fun `an unfinished journal cannot be rebuilt for export`(@TempDir dir: Path) {
        val file = dir.resolve("open.journal")
        RecordingSession(runIds = {
            RunId("run-1")
        }, journal = JournalWriter.create(file)).use { session ->
            inputs.dropLast(1).forEach(session::handle)
        }
        assertFailsWith<IllegalArgumentException> { RecordedRun.from(JournalReader.read(file)) }
    }
}
