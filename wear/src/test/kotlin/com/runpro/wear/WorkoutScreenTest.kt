package com.runpro.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.MaterialTheme
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingStateMachine
import com.runpro.core.recording.imu.ImuMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkoutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `pre-run auto lap cycles from the default kilometer to two kilometers`() {
        var settings by mutableStateOf(WorkoutSettings())
        compose.setContent {
            MaterialTheme {
                ScalingLazyColumn { workoutSetup(settings) { settings = it } }
            }
        }
        compose.onNodeWithText("Auto lap: 1000 m").performClick()
        compose.onNodeWithText("Auto lap: 2000 m").assertIsDisplayed()
    }

    @Test
    fun `the IMU setting cycles accelerometer, accelerometer and gyroscope, off`() {
        var mode by mutableStateOf(ImuMode.ACCELEROMETER)
        compose.setContent {
            MaterialTheme {
                ScalingLazyColumn { item { ImuButton(mode) { mode = it } } }
            }
        }
        compose.onNodeWithText("IMU: accelerometer").performClick()
        compose.onNodeWithText("IMU: accel + gyro").performClick()
        compose.onNodeWithText("IMU: Off").performClick()
        compose.onNodeWithText("IMU: accelerometer").assertIsDisplayed()
    }

    @Test
    fun `lap popup displays totals and dismisses after five seconds of run time`() {
        val machine = RecordingStateMachine()
        machine.handle(EngineInput.Prepare(at(0)))
        machine.handle(EngineInput.Start(at(0)))
        machine.handle(distance(0, 0.0))
        var state by mutableStateOf(machine.state)
        compose.setContent { MaterialTheme { LapSummaryPopup(state) } }
        compose.onNodeWithText(SUMMARY).assertDoesNotExist()

        machine.handle(distance(10, 1000.0))
        state = machine.state
        compose.onNodeWithText(SUMMARY).assertIsDisplayed()

        machine.handle(EngineInput.Tick(at(14)))
        state = machine.state
        compose.onNodeWithText(SUMMARY).assertIsDisplayed()

        machine.handle(EngineInput.Tick(at(15)))
        state = machine.state
        compose.onNodeWithText(SUMMARY).assertDoesNotExist()
    }

    @Test
    fun `tapping the lap popup dismisses it`() {
        val machine = RecordingStateMachine()
        machine.handle(EngineInput.Prepare(at(0)))
        machine.handle(EngineInput.Start(at(0)))
        machine.handle(distance(0, 0.0))
        machine.handle(distance(10, 1000.0))
        compose.setContent { MaterialTheme { LapSummaryPopup(machine.state) } }
        compose.onNodeWithText(SUMMARY).performClick()
        compose.onNodeWithText(SUMMARY).assertDoesNotExist()
    }

    private fun distance(seconds: Int, meters: Double) = EngineInput.SampleReceived(
        DistanceSample(at(seconds), SourceId.HEALTH_SERVICES, Distance(meters))
    )

    private fun at(seconds: Int) = Timestamp(seconds * 1_000_000_000L, seconds * 1000L)

    private companion object {
        const val SUMMARY = "Lap 1\n1000 m · 0:10"
    }
}
