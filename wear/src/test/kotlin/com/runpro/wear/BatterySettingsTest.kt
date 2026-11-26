package com.runpro.wear

import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Field bug on the Galaxy Watch 5 Pro (#10): "Open settings" did nothing. On Wear OS
 * `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` resolves to a `FakeSettingsActivity` that closes at once,
 * so no `ActivityNotFoundException` triggered the fallback. The app's own settings page is the one that
 * exists on the watch and has the Battery entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatterySettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `open settings opens the app details page of RunPro`() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Open settings"))
        compose.onNodeWithText("Open settings").performClick()

        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals(Uri.fromParts("package", "com.runpro.wear", null), started.data)
    }
}
