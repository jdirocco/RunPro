package com.runpro.wear

import android.content.Context
import androidx.core.content.edit
import com.runpro.core.model.WorkoutJson
import com.runpro.core.model.WorkoutSettings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** One pre-run selection, shared by the activity and the service; active runs keep their own copy. */
internal object WorkoutPreferences {
    private fun prefs(context: Context) =
        context.getSharedPreferences("workout", Context.MODE_PRIVATE)

    fun load(context: Context): WorkoutSettings = try {
        prefs(context).getString("settings", null)?.let {
            WorkoutJson.json.decodeFromString<WorkoutSettings>(it)
        } ?: WorkoutSettings()
    } catch (_: IllegalArgumentException) {
        WorkoutSettings()
    }

    fun save(context: Context, settings: WorkoutSettings) {
        prefs(context).edit { putString("settings", WorkoutJson.json.encodeToString(settings)) }
    }
}
