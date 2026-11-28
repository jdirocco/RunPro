package com.runpro.wear.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.runpro.core.ble.BleSensorKind
import com.runpro.core.ble.PairedSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `a companion chooser that fails falls back to the scan`() {
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        shadowOf(app.packageManager)
            .setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, true)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Pair HR strap").performScrollTo().performClick()
        compose.waitForIdle()
        val companion = shadowOf(app.getSystemService(CompanionDeviceManager::class.java))
        val callback = companion.lastAssociationCallback
        assertNotNull(callback)
        assertTrue(shadowOf(adapter.bluetoothLeScanner).scanCallbacks.isEmpty())

        // On the Galaxy Watch 5 Pro the chooser closes at once (#115).
        compose.activityRule.scenario.onActivity { callback.onFailure("closed") }
        compose.waitForIdle()

        assertEquals(1, shadowOf(adapter.bluetoothLeScanner).scanCallbacks.size)
        compose.onNodeWithText("Stop searching").performScrollTo()
    }
}
