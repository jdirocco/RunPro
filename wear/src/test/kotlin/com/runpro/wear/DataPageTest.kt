package com.runpro.wear

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.core.recording.exercise.ExerciseStatus
import com.runpro.core.recording.service.ServiceCommand
import com.runpro.core.recording.service.ServicePermissions
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp")
class DataPageTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `rotary touch and buttons navigate complete pages including wraparound while paused`() {
        val screens =
            DataScreens(
                listOf(listOf(DataField.TIME), listOf(DataField.BATTERY), listOf(DataField.POWER))
            )
        compose.setContent {
            MaterialTheme { DataPage(RunState(phase = RunPhase.PAUSED), screens) }
        }
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithTag("data-page").performRotaryScrollInput {
            rotateToScrollVertically(20f)
        }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onNodeWithTag("data-page").performRotaryScrollInput {
            advanceEventTime(300)
            rotateToScrollVertically(-20f)
        }
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithTag("previous-page").performClick()
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.onNodeWithTag("data-page").performTouchInput { swipeLeft() }
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithTag("next-page").performClick()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
    }

    @Test
    fun `embedded pages receive rotary focus and vertical swipes still reach run controls`() {
        val commands = mutableListOf<ServiceCommand>()
        val setup = SetupState(ServicePermissions.readiness(emptySet()), emptyList(), false)
        compose.setContent {
            RunProScreen(
                setup,
                MutableStateFlow(RunState(phase = RunPhase.RUNNING)),
                MutableStateFlow(ExerciseStatus()),
                SetupActions({}, {}, {}, {}, commands::add)
            )
        }
        compose.onNodeWithTag("data-page").performRotaryScrollInput {
            rotateToScrollVertically(20f)
        }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onNodeWithText("Pause").assertIsDisplayed().performClick()
        assertEquals(listOf(ServiceCommand.PAUSE), commands)
    }

    @Test
    fun `all five fields fit together on a small watch`() {
        val screens = DataScreens(
            listOf(
                listOf(
                    DataField.TIME,
                    DataField.DISTANCE,
                    DataField.CURRENT_PACE,
                    DataField.HEART_RATE,
                    DataField.CADENCE
                )
            )
        )
        val state = RunState(totals = com.runpro.core.recording.Totals(timer = 5000.seconds))
        compose.setContent { MaterialTheme { DataPage(state, screens) } }
        compose.onNodeWithText("Time").assertHeightIsAtLeast(10.dp)
        listOf("1:23:20", "Distance · km", "Pace · min/km", "HR · bpm", "Cadence · spm", "1 / 1")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }
}
