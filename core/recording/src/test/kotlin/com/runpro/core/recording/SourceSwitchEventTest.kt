package com.runpro.core.recording

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Metric
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.SensorEvent
import com.runpro.core.recording.TestTime.at
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SourceSwitchEventTest {
    @Test
    fun `journal retains each switch before its admitted sample and rebuilds exact totals`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("switches.journal")
        val session = RecordingSession(journal = JournalWriter.create(file))
        session.use {
            listOf(
                prepare(0), start(0), hr(0, 100), hr(1, 150, STRAP),
                dist(1, 1000.0, FOOTPOD), dist(2, 1003.0, FOOTPOD),
                hr(5, 101), dist(6, 9000.0), tick(7),
                hr(8, 102), dist(8, 9006.0), hr(9, 151, STRAP),
                dist(9, 2000.0, FOOTPOD), dist(10, 2003.0, FOOTPOD), stop(11)
            ).forEach(it::handle)
        }
        val contents = JournalReader.read(file)
        val switches = session.state.run!!.events.filterIsInstance<RunEvent.SourceSwitched>()
        assertTrue(switches.any { it.from == STRAP && it.to == WATCH })
        assertTrue(switches.any { it.metric == Metric.DISTANCE && it.to == WATCH })
        assertEquals(session.state.run!!.events, contents.runEvents)
        val rebuilt = RecordedRun.from(contents)
        assertEquals(session.state.totals, rebuilt.state.totals)
        assertEquals(session.state.laps, rebuilt.state.laps)
        assertEquals(session.state.current, rebuilt.state.current)
        assertEquals(session.state.activeSources, rebuilt.state.activeSources)
        val firstSwitch = contents.records.indexOfFirst {
            it is JournalRecord.Event && it.event is RunEvent.SourceSwitched
        }
        val firstSample = contents.records.indexOfFirst { it is JournalRecord.Samples }
        assertTrue(firstSwitch < firstSample)
    }

    @Test
    fun `recovery cannot revive disconnected source from its retained pre-loss measurement`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("disconnected.journal")
        val session = RecordingSession(journal = JournalWriter.create(file))
        listOf(prepare(0), start(0), hr(0, 100), hr(0, 150, STRAP)).forEach(session::handle)
        session.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(1), STRAP)))
        session.close()
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use { restored ->
            restored.handle(tick(2))
            assertEquals(WATCH, restored.state.activeSources[Metric.HEART_RATE])
            assertEquals(session.state.run, restored.state.run)
            restored.handle(hr(3, 151, STRAP))
            assertEquals(STRAP, restored.state.activeSources[Metric.HEART_RATE])
        }
    }

    @Test
    fun `recovery retains GPS loss of an inactive standby source`(@TempDir dir: Path) {
        val file = dir.resolve("gps-lost.journal")
        val session = RecordingSession(journal = JournalWriter.create(file))
        listOf(prepare(0), start(0), dist(0, 100.0), dist(1, 1000.0, FOOTPOD))
            .forEach(session::handle)
        session.handle(EngineInput.SensorReceived(SensorEvent.GpsLost(at(2), WATCH)))
        session.close()
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use { restored ->
            restored.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(3), FOOTPOD)))
            assertEquals(null, restored.state.activeSources[Metric.DISTANCE])
        }
    }

    @Test
    fun `same boot recovery continues source timeout and records return once`(@TempDir dir: Path) {
        val file = dir.resolve("resume.journal")
        val session = RecordingSession(
            journal = JournalWriter.create(file),
            runIds = { RunId("resumed") }
        )
        listOf(prepare(0), start(0), hr(0, 100), hr(1, 150, STRAP), tick(2))
            .forEach(session::handle)
        session.close()
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use { restored ->
            assertEquals(session.state.activeSources, restored.state.activeSources)
            listOf(hr(5, 101), tick(6), hr(7, 151, STRAP), stop(8)).forEach(restored::handle)
            val switches = restored.state.run!!.events.filterIsInstance<RunEvent.SourceSwitched>()
            assertEquals(listOf(WATCH, STRAP, WATCH, STRAP), switches.map { it.to })
            assertEquals(restored.state.run, RecordedRun.from(JournalReader.read(file)).run)
        }
    }
}
