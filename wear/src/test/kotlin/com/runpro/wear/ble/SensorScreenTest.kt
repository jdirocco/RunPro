package com.runpro.wear.ble

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.runpro.core.ble.BleSensorKind
import com.runpro.core.ble.PairedSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val strap = PairedSensor("AA:BB:CC:DD:EE:01", BleSensorKind.HEART_RATE, "Polar H10")

    @Test
    fun `unpaired kinds offer pairing once Bluetooth is allowed`() {
        val asked = mutableListOf<BleSensorKind>()
        compose.setContent {
            SensorScreen(
                SensorScreenState(bluetoothAllowed = true),
                SensorActions(pair = {
                    asked +=
                        it
                })
            )
        }
        compose.onNodeWithText("Pair HR strap").performScrollTo().performClick()
        compose.onNodeWithText("Pair footpod").performScrollTo().performClick()
        assertEquals(listOf(BleSensorKind.HEART_RATE, BleSensorKind.FOOTPOD), asked)
    }

    @Test
    fun `without the permission the screen asks for it before pairing`() {
        var asked = false
        compose.setContent {
            SensorScreen(SensorScreenState(), SensorActions(allowBluetooth = { asked = true }))
        }
        compose.onNodeWithText("Pair HR strap").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Allow").performScrollTo().performClick()
        assertTrue(asked)
    }

    @Test
    fun `a paired strap shows its name and can be forgotten`() {
        val forgotten = mutableListOf<BleSensorKind>()
        compose.setContent {
            SensorScreen(
                SensorScreenState(paired = listOf(strap), bluetoothAllowed = true),
                SensorActions(forget = { forgotten += it })
            )
        }
        compose.onNodeWithText("Strap: Polar H10").performScrollTo()
        compose.onNodeWithText("Forget").performScrollTo().performClick()
        assertEquals(listOf(BleSensorKind.HEART_RATE), forgotten)
    }

    @Test
    fun `scan results are offered while searching`() {
        val chosen = mutableListOf<PairedSensor>()
        val found = PairedSensor("AA:BB:CC:DD:EE:05", BleSensorKind.HEART_RATE)
        compose.setContent {
            SensorScreen(
                SensorScreenState(
                    bluetoothAllowed = true,
                    searching = BleSensorKind.HEART_RATE,
                    found = listOf(found)
                ),
                SensorActions(choose = { chosen += it })
            )
        }
        compose.onNodeWithText("Pair footpod").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(found.address).performScrollTo().performClick()
        assertEquals(listOf(found), chosen)
    }
}
