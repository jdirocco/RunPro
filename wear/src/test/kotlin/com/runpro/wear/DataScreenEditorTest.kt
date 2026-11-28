package com.runpro.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class DataScreenEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `editor chooses fields changes layout and adds reorders and removes pages`() {
        var screens by mutableStateOf(DataScreens(listOf(listOf(DataField.TIME))))
        compose.setContent { DataScreenEditor(screens, { screens = it }, {}) }
        click("Page 1 · 1 field")
        compose.onNodeWithText("Remove page").assertIsNotEnabled()
        compose.onNodeWithText("Remove last field").assertIsNotEnabled()
        repeat(4) { click("Add field") }
        compose.onNodeWithText("Add field").assertIsNotEnabled()
        click("Field 2: Time")
        click("Power")
        assertEquals(DataField.POWER, screens.pages[0][1])
        click("Remove last field")
        assertEquals(4, screens.pages[0].size)
        click("Back")
        click("Add page")
        click("Page 2 · 1 field")
        click("Move page earlier")
        assertEquals(listOf(DataField.TIME), screens.pages[0])
        click("Remove page")
        assertEquals(1, screens.pages.size)
        assertEquals(DataField.POWER, screens.pages[0][1])
    }

    private fun click(text: String) = compose.onNodeWithText(text).performClick()
}
