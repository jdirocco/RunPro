package com.runpro.wear

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.model.DisplayUnits
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunSettings
import com.runpro.core.model.Timestamp
import com.runpro.core.model.UserProfile
import com.runpro.core.model.WorkoutSettings
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingStateMachine
import com.runpro.core.recording.RunPhase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SettingsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `all settings persist through reopening and invalid profiles fall back safely`() {
        val settings =
            WorkoutSettings(
                runSettings = RunSettings(
                    DisplayUnits.IMPERIAL,
                    UserProfile(190, 50, 72.5),
                    true,
                    5,
                    true
                )
            )
        WorkoutPreferences.save(app, settings)
        assertEquals(
            settings,
            WorkoutPreferences.load(app.createConfigurationContext(app.resources.configuration))
        )
        app.getSharedPreferences(
            "workout",
            0
        ).edit().putString("settings", """{"runSettings":{"profile":{"weightKg":-1}}}""").commit()
        assertEquals(WorkoutSettings(), WorkoutPreferences.load(app))
    }

    @Test
    fun `auto pause keeps the exercise collecting speed while manual pause pauses it`() {
        val machine = RecordingStateMachine()
        val time = Timestamp(0, 0)
        machine.handle(EngineInput.Prepare(time))
        machine.handle(EngineInput.Start(time))
        machine.handle(EngineInput.Pause(time, PauseReason.AUTO))
        assertEquals(RunPhase.RUNNING, exercisePhase(machine.state))
        machine.handle(EngineInput.Resume(time))
        machine.handle(EngineInput.Pause(time))
        assertEquals(RunPhase.PAUSED, exercisePhase(machine.state))
    }
}
