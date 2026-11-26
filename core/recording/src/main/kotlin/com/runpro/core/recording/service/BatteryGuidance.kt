package com.runpro.core.recording.service

/** A battery setting the runner has to change so the watch does not suspend RunPro during a run. */
enum class BatteryAdvice {
    /**
     * Exempt RunPro from battery optimisation. Wear OS on the Galaxy Watch has no screen for it (#10), so the
     * runner does it with `adb shell dumpsys deviceidle whitelist +com.runpro.wear`.
     */
    ALLOW_UNRESTRICTED_BATTERY,

    /** Galaxy Wearable on the phone → Battery → Background usage limits: RunPro not in "Sleeping apps". */
    REMOVE_FROM_SLEEPING_APPS
}

/** The battery advice shown before a run (PLAN.md: One UI "Sleeping apps" and Doze kill background work). */
object BatteryGuidance {
    /**
     * @param ignoringOptimizations `PowerManager.isIgnoringBatteryOptimizations` for RunPro.
     * @param manufacturer `Build.MANUFACTURER`.
     * @param sleepingAppsChecked the runner confirmed RunPro is not in Samsung's "Sleeping apps". One UI has no
     *   API to read that list, so the app can only ask.
     */
    fun advice(
        ignoringOptimizations: Boolean,
        manufacturer: String,
        sleepingAppsChecked: Boolean
    ): List<BatteryAdvice> = buildList {
        if (!ignoringOptimizations) add(BatteryAdvice.ALLOW_UNRESTRICTED_BATTERY)
        if (manufacturer.equals("samsung", ignoreCase = true) && !sleepingAppsChecked) {
            add(BatteryAdvice.REMOVE_FROM_SLEEPING_APPS)
        }
    }
}
