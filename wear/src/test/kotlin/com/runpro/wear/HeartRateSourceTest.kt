package com.runpro.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.wear.compose.material3.MaterialTheme
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingStateMachine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en")
class HeartRateSourceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `icon follows engine selection fallback return and silence`() {
        val machine = RecordingStateMachine()
        machine.handle(EngineInput.Prepare(at(0)))
        machine.handle(EngineInput.Start(at(0)))
        machine.handle(hr(0, SourceId.HEALTH_SERVICES))
        var state by mutableStateOf(machine.state)
        compose.setContent { MaterialTheme { HeartRateSource(state) } }
        compose.onNodeWithContentDescription("Wrist HR").assertIsDisplayed()

        machine.handle(hr(1, STRAP))
        state = machine.state
        compose.onNodeWithContentDescription("HR strap").assertIsDisplayed()

        machine.handle(hr(5, SourceId.HEALTH_SERVICES))
        machine.handle(EngineInput.Tick(at(6)))
        state = machine.state
        compose.onNodeWithContentDescription("Wrist HR").assertIsDisplayed()
        compose.onNodeWithContentDescription("HR strap").assertDoesNotExist()

        machine.handle(hr(7, STRAP))
        state = machine.state
        compose.onNodeWithContentDescription("HR strap").assertIsDisplayed()

        machine.handle(EngineInput.Tick(at(12)))
        state = machine.state
        compose.onNodeWithText("No HR source").assertIsDisplayed()
        compose.onNodeWithContentDescription("HR strap").assertDoesNotExist()
    }

    private fun hr(seconds: Int, source: SourceId) =
        EngineInput.SampleReceived(HeartRateSample(at(seconds), source, HeartRate(140)))

    private fun at(seconds: Int) = Timestamp(seconds * 1_000_000_000L, seconds * 1000L)

    private companion object {
        val STRAP = SourceId.ble("AA:BB:CC:DD:EE:FF")
    }
}
