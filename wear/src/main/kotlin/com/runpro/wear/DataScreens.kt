package com.runpro.wear

import android.content.Context
import androidx.core.content.edit

/** Stable names are persisted rather than catalogue positions. Repeated fields are allowed. */
enum class DataField(val label: Int) {
    TIME(R.string.field_time),
    LAP_TIME(R.string.field_lap_time),
    DISTANCE(R.string.field_distance),
    CURRENT_PACE(R.string.field_current_pace),
    AVERAGE_PACE(R.string.field_average_pace),
    LAP_PACE(R.string.field_lap_pace),
    HEART_RATE(R.string.field_heart_rate),
    HR_ZONE(R.string.field_hr_zone),
    CADENCE(R.string.field_cadence),
    POWER(R.string.field_power),
    ELEVATION(R.string.field_elevation),
    CLOCK(R.string.field_clock),
    BATTERY(R.string.field_battery)
}

data class DataScreens(val pages: List<List<DataField>>) {
    init {
        require(pages.size in 1..MAX_PAGES)
        require(pages.all { it.size in 1..MAX_FIELDS })
    }

    companion object {
        const val MAX_PAGES = 10
        const val MAX_FIELDS = 5
        val DEFAULT = DataScreens(
            listOf(
                listOf(
                    DataField.TIME,
                    DataField.DISTANCE,
                    DataField.CURRENT_PACE,
                    DataField.HEART_RATE
                ),
                listOf(DataField.LAP_TIME, DataField.LAP_PACE, DataField.AVERAGE_PACE),
                listOf(DataField.CADENCE, DataField.POWER, DataField.ELEVATION, DataField.BATTERY)
            )
        )
    }
}

/** Display preferences are separate from the durable workout snapshot and never reach the service. */
internal object DataScreenPreferences {
    private fun prefs(context: Context) =
        context.getSharedPreferences("data_screens", Context.MODE_PRIVATE)

    fun load(context: Context): DataScreens = try {
        val stored = prefs(context).getString("pages", null)
        if (stored == null || stored.length > MAX_STORED_LENGTH) {
            DataScreens.DEFAULT
        } else {
            DataScreens(stored.split(';').map { page -> page.split(',').map(DataField::valueOf) })
        }
    } catch (_: IllegalArgumentException) {
        DataScreens.DEFAULT
    }

    fun save(context: Context, screens: DataScreens) {
        val stored = screens.pages.joinToString(";") {
            it.joinToString(",") { field -> field.name }
        }
        prefs(context).edit { putString("pages", stored) }
    }

    private const val MAX_STORED_LENGTH = 4096
}
