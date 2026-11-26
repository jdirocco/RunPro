package com.runpro.core.recording.service

import com.runpro.core.recording.service.ForegroundType.CONNECTED_DEVICE
import com.runpro.core.recording.service.ForegroundType.HEALTH
import com.runpro.core.recording.service.ForegroundType.LOCATION
import com.runpro.core.recording.service.RunPermission.ACTIVITY_RECOGNITION
import com.runpro.core.recording.service.RunPermission.BACKGROUND_LOCATION
import com.runpro.core.recording.service.RunPermission.BLUETOOTH
import com.runpro.core.recording.service.RunPermission.HEART_RATE
import com.runpro.core.recording.service.RunPermission.LOCATION as LOCATION_PERMISSION
import com.runpro.core.recording.service.RunPermission.NOTIFICATIONS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServicePermissionsTest {
    private val all = RunPermission.entries.toSet()

    @Test
    fun `with every permission the service runs with all three types`() {
        assertEquals(
            setOf(HEALTH, LOCATION, CONNECTED_DEVICE),
            ServicePermissions.foregroundTypes(all, false)
        )
        assertEquals(
            setOf(HEALTH, LOCATION, CONNECTED_DEVICE),
            ServicePermissions.foregroundTypes(all, true)
        )
    }

    @Test
    fun `a type whose permission is missing is never requested, so startForeground cannot throw`() {
        assertEquals(
            setOf(HEALTH, LOCATION),
            ServicePermissions.foregroundTypes(all - BLUETOOTH, fromBackground = false)
        )
        assertEquals(
            setOf(HEALTH, CONNECTED_DEVICE),
            ServicePermissions.foregroundTypes(all - LOCATION_PERMISSION, fromBackground = false)
        )
    }

    @Test
    fun `health needs heart rate or activity recognition`() {
        val noHealth = all - HEART_RATE - ACTIVITY_RECOGNITION
        assertFalse(HEALTH in ServicePermissions.foregroundTypes(noHealth, false))
        assertTrue(
            HEALTH in ServicePermissions.foregroundTypes(noHealth + ACTIVITY_RECOGNITION, false)
        )
        assertTrue(HEALTH in ServicePermissions.foregroundTypes(noHealth + HEART_RATE, false))
    }

    @Test
    fun `a restart from the background keeps location only with background location`() {
        val whileInUse = all - BACKGROUND_LOCATION
        assertFalse(
            LOCATION in ServicePermissions.foregroundTypes(whileInUse, fromBackground = true)
        )
        assertTrue(
            LOCATION in ServicePermissions.foregroundTypes(whileInUse, fromBackground = false)
        )
    }

    @Test
    fun `a restart from the background keeps health only through activity recognition`() {
        // Heart rate (body sensors) is a while-in-use permission, like location.
        val heartRateOnly = setOf(HEART_RATE, LOCATION_PERMISSION, BACKGROUND_LOCATION)
        assertEquals(
            setOf(LOCATION),
            ServicePermissions.foregroundTypes(heartRateOnly, fromBackground = true)
        )
        assertEquals(
            setOf(HEALTH, LOCATION),
            ServicePermissions.foregroundTypes(
                heartRateOnly + ACTIVITY_RECOGNITION,
                fromBackground = true
            )
        )
    }

    @Test
    fun `background location without location grants nothing`() {
        assertEquals(
            emptySet(),
            ServicePermissions.foregroundTypes(setOf(BACKGROUND_LOCATION), true)
        )
    }

    @Test
    fun `a run can start when at least one type is allowed`() {
        assertTrue(ServicePermissions.readiness(all).canStart)
        assertTrue(ServicePermissions.readiness(setOf(ACTIVITY_RECOGNITION)).canStart)
        assertFalse(
            ServicePermissions.readiness(setOf(NOTIFICATIONS, BACKGROUND_LOCATION)).canStart
        )
        assertFalse(ServicePermissions.readiness(emptySet()).canStart)
    }

    @Test
    fun `missing permissions come in request order, background location last and on its own`() {
        val readiness = ServicePermissions.readiness(emptySet())
        assertEquals(
            listOf(LOCATION_PERMISSION, HEART_RATE, ACTIVITY_RECOGNITION, BLUETOOTH, NOTIFICATIONS),
            readiness.missingForeground
        )
        // Android only grants background location after location, in a request of its own.
        assertFalse(readiness.canAskBackgroundLocation)
        assertTrue(
            ServicePermissions.readiness(setOf(LOCATION_PERMISSION)).canAskBackgroundLocation
        )
        val both = setOf(LOCATION_PERMISSION, BACKGROUND_LOCATION)
        assertFalse(ServicePermissions.readiness(both).canAskBackgroundLocation)
    }

    @Test
    fun `everything granted means nothing to ask`() {
        val readiness = ServicePermissions.readiness(all)
        assertEquals(emptyList(), readiness.missingForeground)
        assertFalse(readiness.canAskBackgroundLocation)
        assertTrue(readiness.complete)
    }

    @Test
    fun `without background location the setup is not complete`() {
        // A restart after a kill would lose GPS.
        val readiness = ServicePermissions.readiness(all - BACKGROUND_LOCATION)
        assertEquals(emptyList(), readiness.missingForeground)
        assertFalse(readiness.complete)
    }
}
