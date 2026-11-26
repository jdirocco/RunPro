package com.runpro.core.recording.exercise

import com.runpro.core.recording.service.RunPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExercisePlanTest {
    private val allSupported = ExerciseCapabilities(
        runningSupported = true,
        supported = ExerciseData.entries.toSet()
    )
    private val allGranted = RunPermission.entries.toSet()

    @Test
    fun `everything supported and granted requests every data type with GPS`() {
        val plan = ExercisePlan.of(allSupported, allGranted)

        assertEquals(ExerciseData.entries.toSet(), plan.dataTypes)
        assertTrue(plan.missing.isEmpty())
        assertTrue(plan.gpsEnabled)
        assertTrue(plan.usable)
        assertEquals(setOf(ExerciseData.HEART_RATE, ExerciseData.LOCATION), plan.warmUpTypes)
    }

    @Test
    fun `unsupported data types are left out and reported as missing`() {
        val capabilities = allSupported.copy(
            supported =
                ExerciseData.entries.toSet() - ExerciseData.ELEVATION
        )

        val plan = ExercisePlan.of(capabilities, allGranted)

        assertFalse(ExerciseData.ELEVATION in plan.dataTypes)
        assertEquals(setOf(ExerciseData.ELEVATION), plan.missing)
        assertTrue(plan.usable)
    }

    @Test
    fun `a data type without its permission is never requested`() {
        // Health Services throws a SecurityException for a data type without its permission.
        val plan = ExercisePlan.of(allSupported, allGranted - RunPermission.LOCATION)

        assertEquals(
            setOf(
                ExerciseData.HEART_RATE,
                ExerciseData.DISTANCE,
                ExerciseData.SPEED,
                ExerciseData.STEPS_PER_MINUTE
            ),
            plan.dataTypes
        )
        assertEquals(setOf(ExerciseData.LOCATION, ExerciseData.ELEVATION), plan.missing)
        assertFalse(plan.gpsEnabled)
        assertEquals(setOf(ExerciseData.HEART_RATE), plan.warmUpTypes)
    }

    @Test
    fun `without heart rate permission only the wrist heart rate is missing`() {
        val plan = ExercisePlan.of(allSupported, allGranted - RunPermission.HEART_RATE)

        assertEquals(setOf(ExerciseData.HEART_RATE), plan.missing)
        assertTrue(plan.usable)
    }

    @Test
    fun `no running support or no data type makes the plan unusable`() {
        assertFalse(ExercisePlan.of(allSupported.copy(runningSupported = false), allGranted).usable)
        assertFalse(ExercisePlan.of(allSupported, emptySet()).usable)
        assertEquals(
            ExerciseData.entries.toSet(),
            ExercisePlan.of(allSupported, emptySet()).missing
        )
    }
}
