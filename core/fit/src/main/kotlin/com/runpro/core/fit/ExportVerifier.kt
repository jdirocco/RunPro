package com.runpro.core.fit

import com.runpro.core.model.RunEvent
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.Totals
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** The result of comparing a FIT file with the journal it was made from: verified when [problems] is empty. */
data class ExportVerification(val problems: List<String>) {
    val isVerified: Boolean get() = problems.isEmpty()
}

/**
 * Compares a decoded FIT file with the run rebuilt from its journal (R3). It checks everything the file
 * claims about the run: the run id, every run and sensor event (exactly, through the RunPro developer
 * fields), every record at FIT resolution, every lap and the session totals, and that every source has a
 * device entry. Tolerances are the FIT resolution of each field plus the 32-bit float the SDK uses.
 */
object ExportVerifier {
    private const val SECONDS_TOLERANCE = 0.01
    private const val METERS_TOLERANCE = 0.02
    private const val SPEED_TOLERANCE = 0.002
    private const val DEGREES_TOLERANCE = 1e-6
    private const val ALTITUDE_TOLERANCE = 0.11
    private const val CADENCE_TOLERANCE = 0.02

    fun verify(run: RecordedRun, fit: FitActivity): ExportVerification {
        val problems = mutableListOf<String>()
        Check(run, fit, problems).all()
        return ExportVerification(problems)
    }

    private class Check(
        private val run: RecordedRun,
        private val fit: FitActivity,
        private val problems: MutableList<String>
    ) {
        fun all() {
            file()
            devices()
            events()
            records()
            laps()
            session()
        }

        private fun file() {
            equal("file type", "ACTIVITY", fit.fileType)
            equal("run id", run.id.value, fit.runId)
            equal("session count", 1, fit.sessionCount)
            near(
                "activity timer",
                seconds(run.state.totals.timer),
                fit.activityTimerSeconds,
                SECONDS_TOLERANCE
            )
        }

        private fun devices() {
            val descriptors = fit.devices.associate { it.descriptor to it.index }
            deviceIndexes(run).forEach { (source, index) ->
                equal("device index of $source", index, descriptors[source.value])
            }
            equal("creator device", true, fit.devices.any { it.index == 0 })
        }

        private fun events() {
            val runEvents = fit.events.mapNotNull { it.runEvent }
            equal("run event count", run.run.events.size, runEvents.size)
            run.run.events.zip(runEvents).forEachIndexed { i, (expected, actual) ->
                equal("run event $i", expected, actual)
            }
            val sensorEvents = fit.events.mapNotNull { it.sensorEvent }
            equal("sensor event count", run.sensorEvents.size, sensorEvents.size)
            run.sensorEvents.zip(sensorEvents).forEachIndexed { i, (expected, actual) ->
                equal("sensor event $i", expected, actual)
            }
            fit.events.forEachIndexed { i, event ->
                val time = (event.runEvent?.time ?: event.sensorEvent?.time) ?: run {
                    problems += "event $i (${event.type}) carries no RunPro event"
                    return@forEachIndexed
                }
                equal("event $i time", wholeSecond(run.wallClockMillis(time)), event.timeMillis)
                event.runEvent?.let {
                    val (fitEvent, type) = RunProFit.fitEventOf(it)
                    equal("event $i type", "${fitEvent.name}/${type.name}", event.type)
                }
            }
            val timers = fit.events.count { it.type.startsWith("TIMER/") }
            val expectedTimers = run.run.events.count {
                it is RunEvent.Started || it is RunEvent.Paused || it is RunEvent.Resumed ||
                    it is RunEvent.Stopped
            }
            equal("timer event count", expectedTimers, timers)
        }

        private fun records() {
            val expected = recordContents(run)
            equal("record count", expected.size, fit.records.size)
            expected.zip(fit.records).forEachIndexed { i, (want, got) ->
                val name = "record $i"
                equal("$name time", want.second, got.timeMillis)
                equal("$name heart rate", want.heartRateBpm, got.heartRateBpm)
                near("$name latitude", want.latitude, got.latitude, DEGREES_TOLERANCE)
                near("$name longitude", want.longitude, got.longitude, DEGREES_TOLERANCE)
                near("$name altitude", want.altitudeMeters, got.altitudeMeters, ALTITUDE_TOLERANCE)
                near("$name distance", want.distanceMeters, got.distanceMeters, METERS_TOLERANCE)
                near(
                    "$name speed",
                    want.speedMetersPerSecond,
                    got.speedMetersPerSecond,
                    SPEED_TOLERANCE
                )
                near(
                    "$name cadence",
                    want.cadenceStepsPerMinute,
                    got.cadenceStepsPerMinute,
                    CADENCE_TOLERANCE
                )
                equal("$name power", want.powerWatts, got.powerWatts)
            }
        }

        private fun laps() {
            equal("lap count", run.state.laps.size, fit.laps.size)
            run.state.laps.zip(fit.laps).forEachIndexed { i, (lap, got) ->
                val name = "lap $i"
                equal(
                    "$name start",
                    wholeSecond(run.wallClockMillis(lap.lap.start)),
                    got.startMillis
                )
                equal(
                    "$name end",
                    lap.lap.end?.let {
                        wholeSecond(run.wallClockMillis(it))
                    },
                    got.endMillis
                )
                equal("$name trigger", lap.lap.trigger, got.lapTrigger)
                totals(name, lap.totals, got)
            }
        }

        private fun session() {
            val session = fit.session ?: run {
                problems += "no session"
                return
            }
            equal(
                "session start",
                wholeSecond(run.wallClockMillis(run.startedAt)),
                session.startMillis
            )
            equal("session sport", "RUNNING", session.sport)
            equal("session lap count", run.state.laps.size, session.lapCount)
            totals("session", run.state.totals, session)
        }

        private fun totals(name: String, totals: Totals, got: FitTotals) {
            near("$name elapsed", seconds(totals.elapsed), got.elapsedSeconds, SECONDS_TOLERANCE)
            near("$name timer", seconds(totals.timer), got.timerSeconds, SECONDS_TOLERANCE)
            near("$name moving", seconds(totals.moving), got.movingSeconds, SECONDS_TOLERANCE)
            near("$name distance", totals.distance.meters, got.distanceMeters, METERS_TOLERANCE)
            near(
                "$name average speed",
                totals.averageSpeed?.metersPerSecond,
                got.averageSpeedMetersPerSecond,
                SPEED_TOLERANCE
            )
            equal("$name average heart rate", totals.averageHeartRate?.bpm, got.averageHeartRateBpm)
            equal("$name max heart rate", totals.maxHeartRate?.bpm, got.maxHeartRateBpm)
            near(
                "$name average cadence",
                totals.averageCadence?.stepsPerMinute,
                got.averageCadenceStepsPerMinute,
                CADENCE_TOLERANCE
            )
            near(
                "$name max cadence",
                totals.maxCadence?.stepsPerMinute,
                got.maxCadenceStepsPerMinute,
                CADENCE_TOLERANCE
            )
            equal("$name ascent", totals.ascent.meters.roundToInt(), got.ascentMeters)
            equal("$name descent", totals.descent.meters.roundToInt(), got.descentMeters)
        }

        private fun equal(name: String, expected: Any?, actual: Any?) {
            if (expected != actual) problems += "$name: journal $expected, FIT $actual"
        }

        private fun near(name: String, expected: Double?, actual: Double?, tolerance: Double) {
            val matches = if (expected == null || actual == null) {
                expected == actual
            } else {
                abs(expected - actual) <= tolerance
            }
            if (!matches) problems += "$name: journal $expected, FIT $actual"
        }

        private fun seconds(duration: Duration): Double = duration.toDouble(DurationUnit.SECONDS)
    }
}
