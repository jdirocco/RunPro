package com.runpro.wear

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DataScreenPreferencesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `ordered pages and duplicate fields survive reopening`() {
        val screens = DataScreens(
            (1..5).map { size -> List(size) { DataField.entries[it] } } +
                listOf(listOf(DataField.POWER, DataField.POWER))
        )
        DataScreenPreferences.save(app, screens)
        assertEquals(
            screens,
            DataScreenPreferences.load(app.createConfigurationContext(app.resources.configuration))
        )
    }

    @Test
    fun `missing corrupted unknown and unbounded configurations fall back safely`() {
        val prefs = app.getSharedPreferences("data_screens", 0)
        listOf(null, "", "UNKNOWN", "TIME;", "TIME,TIME,TIME,TIME,TIME,TIME", "TIME;".repeat(100))
            .forEach { stored ->
                prefs.edit().putString("pages", stored).commit()
                assertEquals(DataScreens.DEFAULT, DataScreenPreferences.load(app))
            }
    }
}
