package com.runpro.wear

import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.ModelJson
import com.runpro.core.model.RunSettings
import com.runpro.core.model.Sample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingStateMachine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DebugTraceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `disabled tracing creates no files and enabled tracing writes raw samples`() {
        val directory = File(temporary.root, "traces")
        val machine = started(false)
        DebugTrace(directory).use {
            it.follow(machine.state)
            it.onSample(sample())
        }
        assertFalse(directory.exists())
        val enabled = started(true)
        DebugTrace(directory).use {
            it.follow(enabled.state)
            it.onSample(sample())
            enabled.handle(EngineInput.Stop(Timestamp(2_000_000_000, 2000)))
            it.follow(enabled.state)
        }
        val deadline = System.nanoTime() + 5_000_000_000
        var lines = emptyList<String>()
        while (System.nanoTime() < deadline) {
            lines = directory.listFiles()?.singleOrNull()?.readLines().orEmpty()
            if (lines.size == 2) break
            Thread.sleep(10)
        }
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("trace_header"))
        assertEquals(sample(), ModelJson.json.decodeFromString(Sample.serializer(), lines[1]))
    }

    @Test
    fun `unwritable trace destination never prevents a run from saving`() {
        val file = temporary.newFile("not-a-directory")
        val machine = started(true)
        DebugTrace(file).use {
            it.follow(machine.state)
            it.onSample(sample())
            machine.handle(EngineInput.Stop(Timestamp(2_000_000_000, 2000)))
            it.follow(machine.state)
        }
        assertTrue(machine.state.run!!.isStopped)
    }

    private fun started(trace: Boolean) = RecordingStateMachine().also {
        it.handle(
            EngineInput.ConfigureWorkout(
                WorkoutSettings(runSettings = RunSettings(debugTrace = trace))
            )
        )
        it.handle(EngineInput.Prepare(Timestamp(0, 0)))
        it.handle(EngineInput.Start(Timestamp(0, 0)))
    }

    private fun sample(): Sample =
        HeartRateSample(Timestamp(1_000_000_000, 1000), SourceId.HEALTH_SERVICES, HeartRate(150))
}
