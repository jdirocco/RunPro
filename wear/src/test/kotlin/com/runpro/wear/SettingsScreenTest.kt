package com.runpro.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.wear.compose.material3.MaterialTheme
import com.runpro.core.model.DisplayUnits
import com.runpro.core.model.RunSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w227dp-h2000dp")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `preferences toggle and profile editing saves or cancels explicitly`() {
        var settings by mutableStateOf(RunSettings())
        compose.setContent { SettingsScreen(settings, { settings = it }, {}) }
        compose.onNodeWithText("Units: Metric").performClick()
        assertEquals(DisplayUnits.IMPERIAL, settings.units)
        compose.onNodeWithText("Auto-pause: Off").performClick()
        compose.onNodeWithText("Debug trace: Off").performClick()
        compose.onNodeWithText("Start countdown: Off").performClick()
        assertEquals(
            RunSettings(
                DisplayUnits.IMPERIAL,
                autoPause = true,
                countdownSeconds = 3,
                debugTrace = true
            ),
            settings
        )
        compose.onNodeWithText("Max HR: Not set").performClick()
        compose.onNodeWithText("Increase +").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(191, settings.profile.maxHeartRate)
        compose.onNodeWithText("Weight: Not set").performClick()
        compose.onNodeWithText("Increase +").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(settings.profile.weightKg)
        compose.onNodeWithText("Max HR: 191").performClick()
        compose.onNodeWithText("Clear value").performClick()
        compose.onNodeWithText("Max HR: Not set").assertIsDisplayed()
    }

    @Test
    fun `countdown starts exactly once after expiry and can be cancelled`() {
        var starts = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { StartCountdown(3) { starts++ } } }
        compose.onNodeWithText("Start").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
        assertEquals(0, starts)
        compose.onNodeWithText("Starting in 2 · Cancel").performClick()
        compose.mainClock.advanceTimeBy(5000)
        assertEquals(0, starts)
        compose.onNodeWithText("Start").performClick()
        repeat(4) { compose.mainClock.advanceTimeBy(1100) }
        assertEquals(1, starts)
        compose.mainClock.advanceTimeBy(5000)
        assertEquals(1, starts)
    }
}
