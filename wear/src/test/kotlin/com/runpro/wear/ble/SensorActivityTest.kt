package com.runpro.wear.ble

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.ble.BleSensorKind
import com.runpro.core.ble.PairedSensor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The activity saves what the runner pairs or forgets; the next warm-up reads it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensorActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<SensorActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `forgetting the strap removes it from the saved pairing`() {
        shadowOf(
            app
        ).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        BlePreferences.save(
            app,
            listOf(PairedSensor("AA:BB:CC:DD:EE:01", BleSensorKind.HEART_RATE, "Polar H10"))
        )
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Strap: Polar H10").performScrollTo()
        compose.onNodeWithText("Forget").performScrollTo().performClick()
        compose.onNodeWithText("Pair HR strap").performScrollTo()
        assertTrue(BlePreferences.load(app).isEmpty())
    }
}
