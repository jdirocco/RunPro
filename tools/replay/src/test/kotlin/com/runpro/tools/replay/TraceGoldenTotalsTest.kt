package com.runpro.tools.replay

import com.runpro.core.model.DistanceSample
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingStateMachine
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.core.recording.Totals
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Replays the committed traces of `testdata/traces/` through [RecordingStateMachine] with the runner
 * commands of their golden file (`testdata/traces/golden/`) and checks the run and lap totals against it.
 *
 * The golden values were produced by the engine and checked against figures derived from the raw samples
 * without the engine (the tests below that compare with the samples). When an engine change moves them on
 * purpose (for example source arbitration, #19, picks the strap instead of the wrist), the failure
 * message prints the new totals in golden-file form: check them, then paste them into the golden file.
 */
class TraceGoldenTotalsTest {
    @Test
    fun `the 30 min easy run produces the golden totals`() = assertGolden(EASY_RUN_GOLDEN)

    @Test
    fun `the intervals run produces the golden totals`() = assertGolden(INTERVALS_GOLDEN)

    @Test
    fun `the easy run distance and heart rate match figures derived from the raw samples`() {
        val golden = golden(EASY_RUN_GOLDEN)
        val trace = trace(golden)
        val state = replay(trace, golden)
        val watch = WatchSeconds(trace)

        // Pause at 15:00, resume at 15:30: the intervals between those samples do not count.
        val distance = watch.distance(1799) - watch.distance(0) -
            (watch.distance(EASY_RESUME_S) - watch.distance(EASY_PAUSE_S))
        assertEquals(distance, state.totals.distance.meters, DISTANCE_TOLERANCE)

        // The sample at the resume arrives while still paused, so it does not count.
        val active = (0..EASY_PAUSE_S) + (EASY_RESUME_S + 1 until 1800)
        val heartRates = active.map(watch::heartRate)
        assertEquals(heartRates.max(), state.totals.maxHeartRate!!.bpm)
        // Samples every second, held for one second each: the time-weighted mean is the plain mean.
        assertEquals(heartRates.average(), state.totals.averageHeartRate!!.bpm.toDouble(), 1.0)

        assertEquals(1800.seconds, state.totals.elapsed)
        assertEquals((1800 - (EASY_RESUME_S - EASY_PAUSE_S)).seconds, state.totals.timer)
        // Always faster than the moving threshold, except the 30 intervals inside the pause.
        assertEquals((1799 - (EASY_RESUME_S - EASY_PAUSE_S)).seconds, state.totals.moving)
    }

    @Test
    fun `the intervals laps show the fast reps and the jogs`() {
        val golden = golden(INTERVALS_GOLDEN)
        val trace = trace(golden)
        val state = replay(trace, golden)
        val watch = WatchSeconds(trace)

        assertEquals(
            watch.distance(2699) - watch.distance(0),
            state.totals.distance.meters,
            DISTANCE_TOLERANCE
        )
        // Warm-up, 6 x (rep, jog), cool-down.
        assertEquals(14, state.laps.size)
        val paces = state.laps.map { it.totals.averagePace!!.secondsPerKilometer }
        val reps = (1..11 step 2).map(paces::get)
        val jogs = (2..12 step 2).map(paces::get)
        // 4:00/km target, minus the few seconds of acceleration at the start of each rep.
        reps.forEach { assertTrue(it in 235.0..250.0, "rep pace $it s/km") }
        // 6:30/km target, minus the few seconds of deceleration at the start of each jog.
        jogs.forEach { assertTrue(it in 370.0..395.0, "jog pace $it s/km") }
        listOf(paces.first(), paces.last()).forEach {
            assertTrue(it in 355.0..365.0, "easy pace $it s/km")
        }
    }

    @Test
    fun `the laps add up to the run`() {
        for (name in listOf(EASY_RUN_GOLDEN, INTERVALS_GOLDEN)) {
            val golden = golden(name)
            val state = replay(trace(golden), golden)
            val laps = state.laps.map { it.totals }
            assertEquals(state.totals.elapsed, laps.sumOfDurations { it.elapsed }, name)
            assertEquals(state.totals.timer, laps.sumOfDurations { it.timer }, name)
            assertEquals(state.totals.moving, laps.sumOfDurations { it.moving }, name)
            assertEquals(
                state.totals.distance.meters,
                laps.sumOf { it.distance.meters },
                DISTANCE_TOLERANCE,
                name
            )
            assertEquals(state.totals.maxHeartRate, laps.maxOf { it.maxHeartRate!! }, name)
            assertEquals(LapTrigger.STOP, state.laps.last().lap.trigger, name)
            assertEquals(state.run?.laps, state.laps.map { it.lap }, name)
        }
    }

    private fun List<Totals>.sumOfDurations(of: (Totals) -> Duration): Duration =
        fold(Duration.ZERO) { sum, lap -> sum + of(lap) }

    private fun assertGolden(name: String) {
        val golden = golden(name)
        val state = replay(trace(golden), golden)
        assertEquals(RunPhase.STOPPED, state.phase, name)

        val actual = golden.copy(
            run = GoldenTotals.of(state.totals),
            laps = state.laps.map { GoldenTotals.of(it.totals) }
        )
        val problems = buildList {
            golden.run.mismatches(actual.run, golden.tolerance).forEach { add("run $it") }
            if (golden.laps.size != actual.laps.size) {
                add("laps: golden ${golden.laps.size}, actual ${actual.laps.size}")
            }
            golden.laps.zip(actual.laps).forEachIndexed { i, (expected, lap) ->
                expected.mismatches(lap, golden.tolerance).forEach { add("lap $i $it") }
            }
        }
        if (problems.isNotEmpty()) {
            fail(
                "$name does not match the replay of ${golden.trace}:\n" +
                    problems.joinToString("\n") +
                    "\nActual, in golden-file form:\n" +
                    GoldenFile.json.encodeToString(GoldenFile.serializer(), actual)
            )
        }
    }

    /** Replays [trace] at full speed while the runner gives the [golden] commands; returns the state. */
    private fun replay(trace: Trace, golden: GoldenFile): RunState {
        val runner = ScriptedRunner(trace.records.first().time, golden.commands)
        val replayer = Replayer(trace)
        replayer.sources.forEach { it.start(runner) }
        val result = replayer.run()
        assertEquals(trace.records.size, result.delivered)
        runner.finish()
        return runner.machine.state
    }

    /**
     * Feeds every replayed sample to the state machine and gives each command right before the first
     * record that is later than it. Sensor events are not engine inputs yet (source arbitration, #19).
     */
    private class ScriptedRunner(private val start: Timestamp, commands: List<GoldenCommand>) :
        SensorListener {
        val machine = RecordingStateMachine(runIds = { RunId("golden") })
        private val pending = ArrayDeque(commands)

        init {
            require(commands.zipWithNext().all { (a, b) -> a.atSeconds <= b.atSeconds }) {
                "Golden commands must be in time order"
            }
            require(commands.lastOrNull()?.input == GoldenInput.STOP) {
                "The last command must be stop"
            }
            // The golden files describe the runner's manual laps; auto-lap is covered by its own replay.
            val manualLaps = WorkoutSettings(autoLapMeters = null)
            assertTrue(machine.handle(EngineInput.ConfigureWorkout(manualLaps)))
            assertTrue(machine.handle(EngineInput.Prepare(start)))
            assertTrue(machine.handle(EngineInput.Start(start)))
        }

        override fun onSample(sample: Sample) {
            giveCommandsBefore(sample.time)
            machine.handle(EngineInput.SampleReceived(sample))
        }

        override fun onEvent(event: SensorEvent) = giveCommandsBefore(event.time)

        fun finish() {
            while (pending.isNotEmpty()) give(pending.removeFirst())
        }

        private fun giveCommandsBefore(time: Timestamp) {
            while (pending.isNotEmpty() && timeOf(pending.first()) < time) {
                give(pending.removeFirst())
            }
        }

        private fun timeOf(command: GoldenCommand): Timestamp =
            start + (command.atSeconds * NANOS_PER_SECOND).toLong().nanoseconds

        private fun give(command: GoldenCommand) {
            val time = timeOf(command)
            val input = when (command.input) {
                GoldenInput.LAP -> EngineInput.MarkLap(time)
                GoldenInput.PAUSE -> EngineInput.Pause(time)
                GoldenInput.RESUME -> EngineInput.Resume(time)
                GoldenInput.STOP -> EngineInput.Stop(time)
            }
            assertTrue(machine.handle(input), "$command was rejected")
        }
    }

    /** The watch samples of a synthetic trace by whole second from its start. */
    private class WatchSeconds(trace: Trace) {
        private val start = trace.records.first().time
        private val samples = trace.samples.filter { it.source == SourceId.HEALTH_SERVICES }

        fun distance(second: Int): Double = at<DistanceSample>(second).total.meters

        fun heartRate(second: Int): Int = at<HeartRateSample>(second).heartRate.bpm

        private inline fun <reified T : Sample> at(second: Int): T =
            samples.filterIsInstance<T>().single { abs(secondsOf(it) - second) < HALF_SECOND }

        private fun secondsOf(sample: Sample): Double =
            sample.time.elapsedSince(start).toDouble(DurationUnit.SECONDS)
    }

    private fun golden(name: String): GoldenFile {
        val file = traces().resolve(GOLDEN_DIR).resolve(name)
        assertTrue(file.exists(), "$file is missing")
        return GoldenFile.read(file)
    }

    private fun trace(golden: GoldenFile): Trace = Trace.read(traces().resolve(golden.trace))

    private fun traces(): Path =
        Paths.get(checkNotNull(System.getProperty("runpro.traces")) { "runpro.traces not set" })

    private companion object {
        const val GOLDEN_DIR = "golden"
        const val EASY_RUN_GOLDEN = "easy-run-30min.json"
        const val INTERVALS_GOLDEN = "intervals-6x3min.json"
        const val EASY_PAUSE_S = 900
        const val EASY_RESUME_S = 930
        const val DISTANCE_TOLERANCE = 0.01
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val HALF_SECOND = 0.5
    }
}
