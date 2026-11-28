package com.runpro.core.fit

import com.runpro.core.journal.JournalReader
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Metric
import com.runpro.core.model.RunEvent
import com.runpro.core.model.Sample
import com.runpro.core.model.SwitchReason
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SourceSwitchEventTest {
    @Test
    fun `engine strap wrist and footpod GPS switches survive journal and FIT exactly`(
        @TempDir dir: Path
    ) {
        val journal = dir.resolve("switch.journal")
        RecordingSession(journal = JournalWriter.create(journal)).use { session ->
            session.handle(EngineInput.Prepare(at(0.0)))
            session.handle(EngineInput.Start(at(0.0)))
            val samples: List<Sample> = listOf(
                HeartRateSample(at(0.0), WATCH, HeartRate(100)),
                HeartRateSample(at(1.0), STRAP, HeartRate(150)),
                DistanceSample(at(1.0), FOOTPOD, Distance(1000.0)),
                DistanceSample(at(2.0), FOOTPOD, Distance(1003.0)),
                HeartRateSample(at(5.0), WATCH, HeartRate(101)),
                DistanceSample(at(7.0), WATCH, Distance(9000.0)),
                HeartRateSample(at(8.0), WATCH, HeartRate(102)),
                DistanceSample(at(8.0), WATCH, Distance(9003.0)),
                HeartRateSample(at(9.0), STRAP, HeartRate(151)),
                DistanceSample(at(9.0), FOOTPOD, Distance(2000.0)),
                DistanceSample(at(10.0), FOOTPOD, Distance(2003.0))
            )
            for (sample in samples) {
                session.handle(EngineInput.SampleReceived(sample))
                session.handle(EngineInput.Tick(sample.time))
            }
            session.handle(EngineInput.Stop(at(11.0)))
            val exported = FitExporter.export(journal, dir.resolve("switch.fit"))
            val fit = FitDecoder.decode(Files.readAllBytes(exported.fit))
            val switches = session.state.run!!.events.filterIsInstance<RunEvent.SourceSwitched>()
            assertTrue(
                switches.any {
                    it.from == STRAP && it.to == WATCH &&
                        it.reason == SwitchReason.STALE
                }
            )
            assertTrue(
                switches.any {
                    it.metric == Metric.DISTANCE && it.from == FOOTPOD &&
                        it.to == WATCH
                }
            )
            assertTrue(
                switches.any {
                    it.metric == Metric.DISTANCE && it.from == WATCH &&
                        it.to == FOOTPOD
                }
            )
            assertEquals(
                switches,
                fit.events.mapNotNull {
                    it.runEvent as? RunEvent.SourceSwitched
                }
            )
            assertEquals(session.state.run!!.events, JournalReader.read(journal).runEvents)
            assertEquals(session.state.totals.distance.meters, fit.session!!.distanceMeters!!, 0.01)
        }
    }
}
