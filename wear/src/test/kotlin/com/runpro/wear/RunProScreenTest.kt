package com.runpro.wear

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.runpro.core.model.Distance
import com.runpro.core.model.HeartRate
import com.runpro.core.model.Lap
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.Run
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.Speed
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.CurrentValues
import com.runpro.core.recording.LapTotals
import com.runpro.core.recording.ResourceState
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.core.recording.Totals
import com.runpro.core.recording.exercise.ExerciseStatus
import com.runpro.core.recording.exercise.Readiness
import com.runpro.core.recording.service.RunPermission
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

/** A tall screen, so every item of the scrolling column is composed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class RunProScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val commands = mutableListOf<ServiceCommand>()
    private var historyOpened = 0
    private val runState = MutableStateFlow(RunState())
    private val exercise = MutableStateFlow(ExerciseStatus())

    private fun show() {
        val setup = SetupState(
            ServicePermissions.readiness(RunPermission.entries.toSet()),
            advice = emptyList(),
            backgroundLocationSkipped = false
        )
        val actions = SetupActions({}, {}, {}, {}, commands::add, openHistory = { historyOpened++ })
        compose.setContent { RunProScreen(setup, runState, exercise, actions) }
    }

    private fun click(text: String) = compose.onNodeWithText(text).performClick()

    private fun shows(text: String) = compose.onNodeWithText(text).assertIsDisplayed()

    @Test
    fun `get ready starts the warm-up, not the run`() {
        show()
        click("Get ready")
        assertEquals(listOf(ServiceCommand.PREPARE), commands)
    }

    @Test
    fun `the warm-up shows GPS and heart-rate readiness and can start or go back`() {
        runState.value = RunState(phase = RunPhase.PREPARING)
        exercise.value = ExerciseStatus(gps = Readiness.WAITING, heartRate = Readiness.READY)
        show()

        shows("GPS: searching")
        shows("Heart rate: ready")
        exercise.value = exercise.value.copy(gps = Readiness.READY)
        shows("GPS: ready")

        click("Start")
        click("Back")
        assertEquals(listOf(ServiceCommand.START, ServiceCommand.CANCEL), commands)
    }

    @Test
    fun `the run screen shows time, distance, pace and heart rate`() {
        runState.value = running()
        show()

        shows("25:00")
        shows("5.00")
        shows("5:00") // 3.333 m/s
        shows("150")
        shows("Lap 2 · 3:20")
    }

    @Test
    fun `lap and pause go to the service`() {
        runState.value = running()
        show()
        click("Lap")
        click("Pause")
        assertEquals(listOf(ServiceCommand.LAP, ServiceCommand.PAUSE), commands)
    }

    @Test
    fun `a paused run resumes and has no lap button`() {
        runState.value = running().copy(phase = RunPhase.PAUSED)
        show()
        shows("Paused")
        compose.onNodeWithText("Lap").assertDoesNotExist()
        click("Resume")
        assertEquals(listOf(ServiceCommand.RESUME), commands)
    }

    @Test
    fun `stop needs a confirmation and keep running sends nothing`() {
        runState.value = running()
        show()

        click("Stop")
        shows("Stop and save the run?")
        assertEquals(emptyList<ServiceCommand>(), commands)

        click("Keep running")
        shows("25:00")
        click("Stop")
        click("Stop and save")
        assertEquals(listOf(ServiceCommand.STOP), commands)
    }

    @Test
    fun `the summary shows the totals and whether the run was saved`() {
        runState.value = running().copy(phase = RunPhase.STOPPED, journalSaved = true)
        show()

        shows("Run done")
        shows("25:00")
        shows("5.00")
        shows("5:00") // 5 km in 25 min
        shows("148")
        shows("2 laps")
        shows("Saved")
        click("Done")
        shows("Get ready")
        click("History")
        assertEquals(1, historyOpened)
    }

    @Test
    fun `a failed journal is never shown as saved`() {
        runState.value = running().copy(phase = RunPhase.STOPPED, journalFailed = true)
        show()
        shows("Not saved: the run could not be written to the watch storage.")
        compose.onNodeWithText("Saved").assertDoesNotExist()
    }

    @Test
    fun `a failed journal during the warm-up explains why start could not save`() {
        runState.value = RunState(phase = RunPhase.PREPARING, journalFailed = true)
        show()
        shows("Not saved: the run could not be written to the watch storage.")
    }

    @Test
    fun `resource warnings remain visible during a paused run and clear on recovery`() {
        runState.value = running().copy(resources = ResourceState(15, 49_999_999))
        show()
        shows("Low battery: 15% or less.")
        shows("Low storage: less than 50 MB free.")
        runState.value = runState.value.copy(phase = RunPhase.PAUSED)
        shows("Low battery: 15% or less.")
        shows("Low storage: less than 50 MB free.")
        runState.value = runState.value.copy(resources = ResourceState(16, 50_000_000))
        compose.onNodeWithText("Low battery: 15% or less.").assertDoesNotExist()
        compose.onNodeWithText("Low storage: less than 50 MB free.").assertDoesNotExist()
    }

    @Test
    fun `warm-up explains critical resources that reject start`() {
        runState.value = RunState(
            phase = RunPhase.PREPARING,
            resources = ResourceState(3, 0)
        )
        show()
        shows("Cannot start: charge the watch above 3%.")
        shows("Cannot start: free some watch storage.")
    }

    @Test
    fun `an automatic stop explains its reason without claiming a failed save`() {
        val time = Timestamp(0, 0)
        runState.value = RunState(
            phase = RunPhase.STOPPED,
            run = Run(
                RunId("resource-stop"),
                listOf(RunEvent.Started(time), RunEvent.Stopped(time, StopReason.LOW_BATTERY))
            ),
            journalFailed = true
        )
        show()
        shows("Run stopped automatically: battery at 3% or less.")
        shows("Not saved: the run could not be written to the watch storage.")
        compose.onNodeWithText("Saved").assertDoesNotExist()
    }

    private fun running(): RunState {
        val start = Timestamp(0, 0)
        val totals = Totals(
            elapsed = 1500.seconds,
            timer = 1500.seconds,
            distance = Distance(5_000.0),
            averageHeartRate = HeartRate(148)
        )
        return RunState(
            phase = RunPhase.RUNNING,
            totals = totals,
            laps = listOf(
                LapTotals(Lap(0, start, start, LapTrigger.MANUAL), totals),
                LapTotals(Lap(1, start), Totals(timer = 200.seconds))
            ),
            current = CurrentValues(heartRate = HeartRate(150), speed = Speed(1000.0 / 300.0))
        )
    }
}
