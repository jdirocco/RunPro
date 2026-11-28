package com.runpro.core.recording

import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.AlertSettings
import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.HrZoneMethod
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Pace
import com.runpro.core.model.RunSettings
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import com.runpro.core.model.UserProfile
import com.runpro.core.model.WorkoutSettings
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AlertMonitorTest {
    @Test
    fun `heart rate high and low alerts fire with independent rate limits and ignore zero bpm`() {
        val alerts = AlertSettings(hrLowBpm = 120, hrHighBpm = 170, cooldownSeconds = 30)
        val machine = started(alerts)

        // In range: no alert.
        machine.handle(hr(1, 150))
        assertTrue(machine.state.alerts.isEmpty())

        // High HR fires once, then is rate-limited for 30 s.
        machine.handle(hr(2, 175))
        machine.handle(hr(10, 180))
        machine.handle(hr(31, 182))
        assertEquals(1, machine.state.alerts.size)
        assertEquals(AlertKind.HR_HIGH, machine.state.alerts[0].kind)
        assertEquals(HeartRate(175), machine.state.alerts[0].heartRate)
        assertEquals(at(2), machine.state.alerts[0].time)

        // At exact cooldown boundary (2 + 30 = 32 s), HR_HIGH fires again.
        machine.handle(hr(32, 178))
        assertEquals(2, machine.state.alerts.size)
        assertEquals(at(32), machine.state.alerts[1].time)

        // HR_LOW is independent of HR_HIGH's cooldown.
        machine.handle(hr(33, 115))
        assertEquals(3, machine.state.alerts.size)
        assertEquals(AlertKind.HR_LOW, machine.state.alerts[2].kind)
        assertEquals(HeartRate(115), machine.state.alerts[2].heartRate)

        // 0 bpm (lost skin contact) must never trigger HR_LOW.
        machine.handle(hr(70, 0))
        assertEquals(3, machine.state.alerts.size)
        assertEquals(RunPhase.RUNNING, machine.state.phase)
    }

    @Test
    fun `pace fast and slow alerts fire on fresh moving samples and ignore standing still`() {
        val alerts = AlertSettings(
            paceFast = Pace(270.0), // 4:30/km
            paceSlow = Pace(330.0), // 5:30/km
            cooldownSeconds = 20
        )
        val machine = started(alerts)

        // 5:00/km (3.333 m/s): in range.
        machine.handle(speed(1, 1000.0 / 300.0))
        assertTrue(machine.state.alerts.isEmpty())

        // Standing still (0 m/s) or slower than 30:00/km jitter: ignored.
        machine.handle(speed(2, 0.0))
        machine.handle(speed(3, 0.3))
        assertTrue(machine.state.alerts.isEmpty())

        // 4:00/km (240 s/km): too fast.
        machine.handle(speed(4, 1000.0 / 240.0))
        machine.handle(speed(10, 1000.0 / 230.0)) // rate-limited
        assertEquals(listOf(AlertKind.PACE_TOO_FAST), machine.state.alerts.map { it.kind })

        // 6:00/km (360 s/km): too slow (different kind, fires immediately).
        machine.handle(speed(11, 1000.0 / 360.0))
        machine.handle(speed(20, 1000.0 / 370.0)) // rate-limited
        machine.handle(speed(31, 1000.0 / 375.0)) // cooldown expired (11 + 20 = 31)
        assertEquals(
            listOf(AlertKind.PACE_TOO_FAST, AlertKind.PACE_TOO_SLOW, AlertKind.PACE_TOO_SLOW),
            machine.state.alerts.map { it.kind }
        )
    }

    @Test
    fun `stale batched samples and paused phases never trigger hr or pace alerts`() {
        val alerts = AlertSettings(
            hrLowBpm = 130,
            hrHighBpm = 160,
            paceFast = Pace(270.0),
            paceSlow = Pace(330.0),
            cooldownSeconds = 5
        )
        val machine = started(alerts)
        machine.handle(hr(0, 145))
        machine.handle(speed(0, 1000.0 / 300.0))

        // Advance clock 20 s without samples, then deliver old batched samples (> 3 s old).
        machine.handle(EngineInput.Tick(at(20)))
        machine.handle(hr(5, 190))
        machine.handle(speed(5, 1000.0 / 220.0))
        assertTrue(machine.state.alerts.isEmpty())

        // While paused, fresh out-of-range samples must not fire alerts.
        machine.handle(EngineInput.Pause(at(21)))
        machine.handle(hr(22, 195))
        machine.handle(speed(22, 1000.0 / 400.0))
        assertTrue(machine.state.alerts.isEmpty())
    }

    @Test
    fun `ble disconnect stall and lost contact emit sensor lost and back once each`() {
        val alerts = AlertSettings(sensorAlerts = true, cooldownSeconds = 10)
        val machine = started(alerts)

        // Initial BLE connection and samples: no SENSOR_BACK on first connect.
        machine.handle(EngineInput.SensorReceived(SensorEvent.Connected(at(0), STRAP)))
        machine.handle(hr(1, 145, STRAP))
        machine.handle(hr(1, 144, SourceId.HEALTH_SERVICES))
        assertTrue(machine.state.alerts.isEmpty())

        // Explicit GATT 133 disconnect: one SENSOR_LOST (even though arbitration also switches).
        machine.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(5), STRAP, 133)))
        assertEquals(listOf(AlertKind.SENSOR_LOST), machine.state.alerts.map { it.kind })
        assertEquals(STRAP, machine.state.alerts.single().source)

        // Explicit reconnect + sample: one SENSOR_BACK, not two.
        machine.handle(EngineInput.SensorReceived(SensorEvent.Connected(at(10), STRAP)))
        machine.handle(hr(11, 148, STRAP))
        assertEquals(
            listOf(AlertKind.SENSOR_LOST, AlertKind.SENSOR_BACK),
            machine.state.alerts.map { it.kind }
        )

        // Silent stall (no disconnect event): after 5 s stale timeout, fires SENSOR_LOST.
        machine.handle(hr(16, 146, SourceId.HEALTH_SERVICES))
        machine.handle(EngineInput.Tick(at(17)))
        assertEquals(
            listOf(AlertKind.SENSOR_LOST, AlertKind.SENSOR_BACK, AlertKind.SENSOR_LOST),
            machine.state.alerts.map { it.kind }
        )

        // Samples resume after cooldown: fires SENSOR_BACK.
        machine.handle(hr(22, 150, STRAP))
        assertEquals(
            listOf(
                AlertKind.SENSOR_LOST,
                AlertKind.SENSOR_BACK,
                AlertKind.SENSOR_LOST,
                AlertKind.SENSOR_BACK
            ),
            machine.state.alerts.map { it.kind }
        )

        // Lost skin contact (0 bpm) after cooldown: fires SENSOR_LOST; valid HR fires SENSOR_BACK.
        machine.handle(hr(35, 145, SourceId.HEALTH_SERVICES))
        machine.handle(hr(35, 0, STRAP))
        machine.handle(hr(40, 152, STRAP))
        assertEquals(
            listOf(
                AlertKind.SENSOR_LOST,
                AlertKind.SENSOR_BACK,
                AlertKind.SENSOR_LOST,
                AlertKind.SENSOR_BACK,
                AlertKind.SENSOR_LOST,
                AlertKind.SENSOR_BACK
            ),
            machine.state.alerts.map { it.kind }
        )
        assertEquals(RunPhase.RUNNING, machine.state.phase)
    }

    @Test
    fun `multi-metric ble sensor emits one lost alert and flapping sensors are rate-limited`() {
        val alerts = AlertSettings(sensorAlerts = true, cooldownSeconds = 30)
        val machine = started(alerts)

        machine.handle(speed(1, 3.0, FOOTPOD))
        machine.handle(
            EngineInput.SampleReceived(CadenceSample(at(1), FOOTPOD, Cadence(170.0)))
        )
        // Disconnecting a multi-metric footpod fires exactly one SENSOR_LOST.
        machine.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(5), FOOTPOD, 8)))
        machine.handle(EngineInput.SensorReceived(SensorEvent.Connected(at(8), FOOTPOD)))
        // Immediate second flap within 30 s cooldown is suppressed.
        machine.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(10), FOOTPOD, 8)))
        machine.handle(EngineInput.SensorReceived(SensorEvent.Connected(at(12), FOOTPOD)))

        assertEquals(
            listOf(AlertKind.SENSOR_LOST, AlertKind.SENSOR_BACK),
            machine.state.alerts.map { it.kind }
        )
        // Another BLE device is rate-limited independently.
        machine.handle(hr(13, 150, STRAP))
        machine.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(15), STRAP, 133)))
        assertEquals(
            listOf(AlertKind.SENSOR_LOST, AlertKind.SENSOR_BACK, AlertKind.SENSOR_LOST),
            machine.state.alerts.map { it.kind }
        )
        assertEquals(STRAP, machine.state.alerts.last().source)
    }

    @Test
    fun `gps lost and back respect backup location source and ignore never-acquired indoor runs`() {
        val alerts = AlertSettings(gpsAlerts = true, cooldownSeconds = 10)

        // Indoor run: GPS never acquired -> no GPS_LOST on ticks.
        val indoor = started(alerts)
        indoor.handle(EngineInput.Tick(at(20)))
        assertTrue(indoor.state.alerts.isEmpty())

        // Outdoor run with fused backup: losing Health Services while fused is live fires no alert.
        val backedUp = started(alerts)
        backedUp.handle(location(1, SourceId.HEALTH_SERVICES))
        backedUp.handle(location(1, SourceId.FUSED_LOCATION))
        backedUp.handle(
            EngineInput.SensorReceived(SensorEvent.GpsLost(at(2), SourceId.HEALTH_SERVICES))
        )
        assertTrue(backedUp.state.alerts.isEmpty())

        // Losing the backup too fires GPS_LOST once; regaining fires GPS_BACK once.
        backedUp.handle(
            EngineInput.SensorReceived(SensorEvent.GpsLost(at(3), SourceId.FUSED_LOCATION))
        )
        assertEquals(listOf(AlertKind.GPS_LOST), backedUp.state.alerts.map { it.kind })

        backedUp.handle(
            EngineInput.SensorReceived(SensorEvent.GpsAcquired(at(8), SourceId.HEALTH_SERVICES))
        )
        backedUp.handle(location(9, SourceId.HEALTH_SERVICES))
        assertEquals(
            listOf(AlertKind.GPS_LOST, AlertKind.GPS_BACK),
            backedUp.state.alerts.map { it.kind }
        )
        assertEquals(RunPhase.RUNNING, backedUp.state.phase)
    }

    @Test
    fun `disabled alerts stay quiet and wall clock jumps do not break rate limits`() {
        val quiet = started(AlertSettings(sensorAlerts = false, gpsAlerts = false))
        quiet.handle(hr(1, 145, STRAP))
        quiet.handle(location(1, SourceId.HEALTH_SERVICES))
        quiet.handle(EngineInput.SensorReceived(SensorEvent.Disconnected(at(5), STRAP, 133)))
        quiet.handle(
            EngineInput.SensorReceived(SensorEvent.GpsLost(at(5), SourceId.HEALTH_SERVICES))
        )
        assertTrue(quiet.state.alerts.isEmpty())

        // Wall clock jumps +1 hour while monotonic time only advances 5 s: cooldown still holds!
        val machine = started(AlertSettings(hrHighBpm = 160, cooldownSeconds = 30))
        machine.handle(
            EngineInput.SampleReceived(
                HeartRateSample(Timestamp(1_000_000_000L, 1_000L), STRAP, HeartRate(170))
            )
        )
        machine.handle(
            EngineInput.SampleReceived(
                HeartRateSample(
                    Timestamp(6_000_000_000L, 3_606_000L),
                    STRAP,
                    HeartRate(175)
                )
            )
        )
        assertEquals(1, machine.state.alerts.size)
    }

    @Test
    fun `journal recovery preserves zone and alert settings without replaying historical alerts`(
        @TempDir dir: Path
    ) {
        val runSettings = RunSettings(
            profile = UserProfile(
                maxHeartRate = 195,
                lactateThresholdHeartRate = 172,
                thresholdPace = Pace(280.0)
            ),
            hrZoneMethod = HrZoneMethod.LTHR,
            alerts = AlertSettings(hrHighBpm = 165, cooldownSeconds = 20)
        )
        val file = dir.resolve("alerts.journal")
        RecordingSession(journal = JournalWriter.create(file)).use { session ->
            session.handle(
                EngineInput.ConfigureWorkout(WorkoutSettings(runSettings = runSettings))
            )
            session.handle(EngineInput.Prepare(at(0)))
            session.handle(EngineInput.Start(at(0)))
            session.handle(hr(1, 180))
            assertEquals(1, session.state.alerts.size)
        }
        JournalRecovery.restore(file, RecoveryMode.RESUME_SAME_BOOT).use { restored ->
            assertEquals(runSettings, restored.state.workoutSettings.runSettings)
            // Historical HR 180 sample must not emit an alert during restore.
            assertTrue(restored.state.alerts.isEmpty())
            // Live samples after restore continue alerting.
            restored.handle(hr(25, 182))
            assertEquals(listOf(AlertKind.HR_HIGH), restored.state.alerts.map { it.kind })
        }
    }

    private fun started(alerts: AlertSettings) = RecordingStateMachine().also {
        it.handle(
            EngineInput.ConfigureWorkout(
                WorkoutSettings(runSettings = RunSettings(alerts = alerts))
            )
        )
        it.handle(EngineInput.Prepare(at(0)))
        it.handle(EngineInput.Start(at(0)))
    }

    private fun at(seconds: Int) = Timestamp(seconds.seconds.inWholeNanoseconds, seconds * 1000L)

    private fun hr(seconds: Int, bpm: Int, source: SourceId = SourceId.HEALTH_SERVICES) =
        EngineInput.SampleReceived(HeartRateSample(at(seconds), source, HeartRate(bpm)))

    private fun speed(seconds: Int, mps: Double, source: SourceId = SourceId.HEALTH_SERVICES) =
        EngineInput.SampleReceived(SpeedSample(at(seconds), source, Speed(mps)))

    private fun location(seconds: Int, source: SourceId) =
        EngineInput.SampleReceived(LocationSample(at(seconds), source, 42.0, 12.0))

    private companion object {
        val STRAP = SourceId("ble:AA:BB:CC:DD:EE:FF")
        val FOOTPOD = SourceId("ble:11:22:33:44:55:66")
    }
}
