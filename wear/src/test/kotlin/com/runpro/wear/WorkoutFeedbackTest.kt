package com.runpro.wear

import android.app.Application
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.model.IntervalTarget
import com.runpro.core.model.IntervalWorkout
import com.runpro.core.model.Timestamp
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkoutFeedbackTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val vibrator = app.getSystemService(Vibrator::class.java)
    private val feedback = WorkoutFeedback(app)

    @Test
    fun `countdown vibrates once per second and stage change has a longer pattern`() {
        val machine = running()
        feedback.update(machine.state)
        machine.handle(EngineInput.Tick(at(7)))
        feedback.update(machine.state)
        assertTrue(shadowOf(vibrator).isVibrating)
        vibrator.cancel()
        feedback.update(machine.state)
        assertFalse(shadowOf(vibrator).isVibrating)
        machine.handle(EngineInput.Tick(at(10)))
        feedback.update(machine.state)
        assertTrue(shadowOf(vibrator).isVibrating)
        assertTrue(shadowOf(vibrator).pattern.contentEquals(longArrayOf(0, 200, 100, 200)))
    }

    @Test
    fun `paused countdown and binding to an existing lap produce no vibration`() {
        val machine = running()
        machine.handle(EngineInput.Tick(at(10)))
        feedback.update(machine.state)
        assertFalse(shadowOf(vibrator).isVibrating)
        machine.handle(EngineInput.Pause(at(17)))
        feedback.update(machine.state)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `pre-run preferences retain mixed targets and reject invalid saved values`() {
        val settings = WorkoutSettings(
            workout = IntervalWorkout(
                null,
                3,
                IntervalTarget(meters = 400.0),
                IntervalTarget(seconds = 60),
                null
            )
        )
        WorkoutPreferences.save(app, settings)
        assertEquals(settings, WorkoutPreferences.load(app))
        app.getSharedPreferences("workout", 0).edit().putString("settings", "{}").commit()
        assertEquals(WorkoutSettings(), WorkoutPreferences.load(app))
        app.getSharedPreferences("workout", 0).edit()
            .putString("settings", "{\"autoLapMeters\":-1}").commit()
        assertEquals(WorkoutSettings(), WorkoutPreferences.load(app))
    }

    private fun running() = RecordingStateMachine().also {
        it.handle(
            EngineInput.ConfigureWorkout(
                WorkoutSettings(
                    workout = IntervalWorkout(
                        null,
                        2,
                        IntervalTarget(seconds = 10),
                        IntervalTarget(seconds = 10),
                        null
                    )
                )
            )
        )
        it.handle(EngineInput.Prepare(at(0)))
        it.handle(EngineInput.Start(at(0)))
    }

    private fun at(seconds: Int) = Timestamp(seconds * 1_000_000_000L, seconds * 1000L)
}
