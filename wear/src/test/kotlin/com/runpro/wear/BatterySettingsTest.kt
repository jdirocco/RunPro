package com.runpro.wear

import android.os.PowerManager
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Field bugs on the Galaxy Watch 5 Pro (#10). The watch (Wear OS, API 36) has no screen that exempts an app
 * from battery optimisation: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` resolves to a `FakeSettingsActivity`
 * that closes at once, `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` resolves to nothing, and the app details
 * page has no Battery entry. So the app shows the adb command instead of a button that leads nowhere, and
 * drops the advice once the exemption is in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatterySettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `battery advice shows the adb command and no settings button`() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(COMMAND, substring = true))

        compose.onNodeWithText(COMMAND, substring = true).assertExists()
        compose.onNodeWithText("Open settings").assertDoesNotExist()
    }

    @Test
    fun `battery advice disappears once RunPro is exempt`() {
        val power = compose.activity.getSystemService(PowerManager::class.java)
        shadowOf(power).setIgnoringBatteryOptimizations("com.runpro.wear", true)

        // The runner comes back to RunPro after running the command.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithText(COMMAND, substring = true).assertDoesNotExist()
    }

    private companion object {
        const val COMMAND = "adb shell dumpsys deviceidle whitelist +com.runpro.wear"
    }
}
