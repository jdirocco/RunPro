package com.runpro.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.wear.compose.material3.MaterialTheme
import com.runpro.core.model.AlertSettings
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HrZoneMethod
import com.runpro.core.model.Pace
import com.runpro.core.model.Run
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.RunSettings
import com.runpro.core.model.Timestamp
import com.runpro.core.model.UserProfile
import com.runpro.core.recording.AlertKind
import com.runpro.core.recording.RunAlert
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.core.recording.Totals
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w227dp-h2500dp")
class ZonesAndAlertsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `settings edits lthr threshold pace zones and alert rules`() {
        var settings by mutableStateOf(RunSettings())
        compose.setContent { SettingsScreen(settings, { settings = it }, {}) }

        // Configure LTHR and Threshold pace in Settings.
        compose.onNodeWithText("LTHR: Not set").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(170, settings.profile.lactateThresholdHeartRate)

        compose.onNodeWithText("Threshold pace: Not set").performClick()
        compose.onNodeWithText("Decrease −").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(Pace(295.0), settings.profile.thresholdPace)

        // Open Zones and alerts.
        compose.onNodeWithText("Zones and alerts").performClick()
        compose.onNodeWithText("Set Max HR in Settings to compute HR zones.").assertIsDisplayed()

        // Switch HR zone method to LTHR -> shows computed LTHR zones.
        compose.onNodeWithText("HR zones: Max HR").performClick()
        assertEquals(HrZoneMethod.LTHR, settings.hrZoneMethod)
        compose.onNodeWithText("Z5 · 170+ bpm").assertIsDisplayed()

        // Pace zones computed from 295 s/km (4:55/km) threshold pace are shown.
        compose.onNodeWithText("Pace zones").assertIsDisplayed()

        // Set HR high alert, then clear HR low after cancel check.
        compose.onNodeWithText("HR high: Off").performClick()
        compose.onNodeWithText("Increase +").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(180, settings.alerts.hrHighBpm)

        compose.onNodeWithText("HR low: Off").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(settings.alerts.hrLowBpm)

        // Set fast pace alert.
        compose.onNodeWithText("Pace fast: Off").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(Pace(270.0), settings.alerts.paceFast)

        // Toggle sensor alerts, GPS alerts, and cycle cooldown.
        compose.onNodeWithText("Sensor alerts: On").performClick()
        compose.onNodeWithText("GPS alerts: On").performClick()
        compose.onNodeWithText("Alert cooldown: 30 s").performClick()
        assertFalse(settings.alerts.sensorAlerts)
        assertFalse(settings.alerts.gpsAlerts)
        assertEquals(60, settings.alerts.cooldownSeconds)

        // Clear HR high alert back to Off.
        compose.onNodeWithText("HR high: 180 bpm").performClick()
        compose.onNodeWithText("Clear value").performClick()
        assertNull(settings.alerts.hrHighBpm)
        compose.onNodeWithText("HR high: Off").assertIsDisplayed()
    }

    @Test
    fun `alert popup shows recent alert while running dismisses on tap and expires`() {
        val start = Timestamp(0, 0)
        val run = Run(RunId("alert-ui"), listOf(RunEvent.Started(start)))
        var state by mutableStateOf(
            RunState(
                phase = RunPhase.RUNNING,
                run = run,
                totals = Totals(elapsed = 10.seconds, timer = 10.seconds),
                alerts = listOf(
                    RunAlert(
                        id = 1L,
                        time = start + 8.seconds,
                        kind = AlertKind.HR_HIGH,
                        heartRate = HeartRate(182)
                    )
                )
            )
        )
        compose.setContent { MaterialTheme { AlertPopup(state) } }
        compose.onNodeWithText("HR high\n182 bpm").assertIsDisplayed()

        // Tapping dismisses the current alert.
        compose.onNodeWithText("HR high\n182 bpm").performClick()
        compose.onNodeWithText("HR high\n182 bpm").assertDoesNotExist()

        // A newer alert displays again, then expires once elapsed passes 4 s after alert time.
        state = state.copy(
            totals = Totals(elapsed = 20.seconds, timer = 20.seconds),
            alerts = state.alerts + RunAlert(
                id = 2L,
                time = start + 20.seconds,
                kind = AlertKind.GPS_LOST
            )
        )
        compose.onNodeWithText("GPS lost").assertIsDisplayed()
        state = state.copy(totals = Totals(elapsed = 24.seconds, timer = 24.seconds))
        compose.onNodeWithText("GPS lost").assertDoesNotExist()
    }
}
