package com.runpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ZonesAndAlertsTest {
    @Test
    fun `max heart rate zones match explicit percentage boundaries`() {
        val zones = HeartRateZones.fromMaxHeartRate(200)
        assertEquals(listOf(100, 120, 140, 160, 180), zones.boundaries)
        listOf(
            99 to 0,
            100 to 1,
            119 to 1,
            120 to 2,
            139 to 2,
            140 to 3,
            159 to 3,
            160 to 4,
            179 to 4,
            180 to 5,
            210 to 5
        ).forEach { (bpm, expected) ->
            assertEquals(expected, zones.zoneFor(HeartRate(bpm)), "HR $bpm")
        }
        assertNull(zones.zoneFor(HeartRate(0)))
        assertEquals(100..119, zones.rangeFor(1))
        assertEquals(160..179, zones.rangeFor(4))
        assertEquals(180..HeartRate.MAX_BPM, zones.rangeFor(5))
    }

    @Test
    fun `max heart rate zones round boundaries up so integer percentages stay exact`() {
        val zones = HeartRateZones.fromMaxHeartRate(195)
        // 50% of 195 is 97.5 -> 98 bpm is the first integer >= 50%.
        assertEquals(98, zones.boundaries[0])
        assertEquals(0, zones.zoneFor(HeartRate(97)))
        assertEquals(1, zones.zoneFor(HeartRate(98)))
    }

    @Test
    fun `lthr zones derive five aerobic to anaerobic bands`() {
        val zones = HeartRateZones.fromLactateThreshold(170)
        // Ceiling of 65%, 85%, 90%, 95%, 100% of 170.
        assertEquals(listOf(111, 145, 153, 162, 170), zones.boundaries)
        listOf(
            110 to 0,
            111 to 1,
            144 to 1,
            145 to 2,
            152 to 2,
            153 to 3,
            161 to 3,
            162 to 4,
            169 to 4,
            170 to 5,
            190 to 5
        ).forEach { (bpm, expected) ->
            assertEquals(expected, zones.zoneFor(HeartRate(bpm)), "LTHR HR $bpm")
        }
        assertNull(zones.zoneFor(HeartRate(0)))
    }

    @Test
    fun `heart rate zones reject non-increasing or out-of-range boundaries`() {
        assertFailsWith<IllegalArgumentException> { HeartRateZones(listOf(100, 120, 140, 160)) }
        assertFailsWith<IllegalArgumentException> {
            HeartRateZones(listOf(100, 120, 120, 160, 180))
        }
        assertFailsWith<IllegalArgumentException> { HeartRateZones(listOf(0, 120, 140, 160, 180)) }
        assertFailsWith<IllegalArgumentException> {
            HeartRateZones(listOf(100, 120, 140, 160, 301))
        }
        assertFailsWith<IllegalArgumentException> { HeartRateZones.fromMaxHeartRate(59) }
        assertFailsWith<IllegalArgumentException> { HeartRateZones.fromLactateThreshold(251) }
        assertFailsWith<IllegalArgumentException> {
            HeartRateZones.fromMaxHeartRate(200).rangeFor(0)
        }
        assertFailsWith<IllegalArgumentException> {
            HeartRateZones.fromMaxHeartRate(200).rangeFor(6)
        }
    }

    @Test
    fun `pace zones derive five zones from threshold pace`() {
        val zones = PaceZones.fromThreshold(Pace(300.0)) // 5:00/km
        assertEquals(listOf(387.0, 342.0, 318.0, 297.0), zones.boundariesSecondsPerKm)
        // Slower pace = larger s/km -> lower zone number.
        assertEquals(1, zones.zoneFor(Pace(400.0)))
        assertEquals(2, zones.zoneFor(Pace(387.0)))
        assertEquals(2, zones.zoneFor(Pace(350.0)))
        assertEquals(3, zones.zoneFor(Pace(342.0)))
        assertEquals(3, zones.zoneFor(Pace(320.0)))
        assertEquals(4, zones.zoneFor(Pace(318.0)))
        assertEquals(4, zones.zoneFor(Pace(300.0)))
        assertEquals(5, zones.zoneFor(Pace(297.0)))
        assertEquals(5, zones.zoneFor(Pace(240.0)))

        val z1 = zones.rangeFor(1)
        assertEquals(Pace(387.0), z1.fastest)
        assertNull(z1.slowest)
        val z3 = zones.rangeFor(3)
        assertEquals(Pace(318.0), z3.fastest)
        assertEquals(Pace(342.0), z3.slowest)
        val z5 = zones.rangeFor(5)
        assertNull(z5.fastest)
        assertEquals(Pace(297.0), z5.slowest)
    }

    @Test
    fun `pace zones reject wrong count or non-decreasing boundaries`() {
        assertFailsWith<IllegalArgumentException> { PaceZones(listOf(360.0, 330.0, 300.0)) }
        assertFailsWith<IllegalArgumentException> { PaceZones(listOf(360.0, 360.0, 330.0, 300.0)) }
        assertFailsWith<IllegalArgumentException> { PaceZones(listOf(300.0, 330.0, 360.0, 390.0)) }
        assertFailsWith<IllegalArgumentException> { PaceZones(listOf(360.0, 330.0, 300.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> {
            PaceZones.fromThreshold(Pace(300.0)).rangeFor(0)
        }
        assertFailsWith<IllegalArgumentException> {
            PaceZones.fromThreshold(Pace(300.0)).rangeFor(6)
        }
    }

    @Test
    fun `run settings compute zones only from configured profile values`() {
        val empty = RunSettings()
        assertNull(empty.hrZones())
        assertNull(empty.paceZones())

        val maxOnly = RunSettings(
            profile = UserProfile(maxHeartRate = 190),
            hrZoneMethod = HrZoneMethod.LTHR
        )
        assertNull(maxOnly.hrZones())

        val withLthr = RunSettings(
            profile = UserProfile(
                maxHeartRate = 190,
                restingHeartRate = 50,
                lactateThresholdHeartRate = 170,
                thresholdPace = Pace(270.0)
            ),
            hrZoneMethod = HrZoneMethod.LTHR
        )
        assertEquals(HeartRateZones.fromLactateThreshold(170), withLthr.hrZones())
        assertEquals(PaceZones.fromThreshold(Pace(270.0)), withLthr.paceZones())
    }

    @Test
    fun `profile validates lthr and threshold pace bounds and ordering`() {
        val valid = UserProfile(
            maxHeartRate = 190,
            restingHeartRate = 50,
            weightKg = 70.0,
            lactateThresholdHeartRate = 170,
            thresholdPace = Pace(270.0)
        )
        assertEquals(170, valid.lactateThresholdHeartRate)
        assertEquals(Pace(270.0), valid.thresholdPace)

        assertFailsWith<IllegalArgumentException> {
            UserProfile(lactateThresholdHeartRate = 59)
        }
        assertFailsWith<IllegalArgumentException> {
            UserProfile(lactateThresholdHeartRate = 251)
        }
        assertFailsWith<IllegalArgumentException> {
            UserProfile(maxHeartRate = 180, lactateThresholdHeartRate = 180)
        }
        assertFailsWith<IllegalArgumentException> {
            UserProfile(restingHeartRate = 65, lactateThresholdHeartRate = 65)
        }
        assertFailsWith<IllegalArgumentException> {
            UserProfile(thresholdPace = Pace(119.0))
        }
        assertFailsWith<IllegalArgumentException> {
            UserProfile(thresholdPace = Pace(901.0))
        }
    }

    @Test
    fun `alert settings validate bounds zone helpers and round trip json`() {
        val defaults = AlertSettings()
        assertNull(defaults.hrLowBpm)
        assertNull(defaults.hrHighBpm)
        assertNull(defaults.paceFast)
        assertNull(defaults.paceSlow)
        assertTrue(defaults.sensorAlerts)
        assertTrue(defaults.gpsAlerts)
        assertEquals(AlertSettings.DEFAULT_COOLDOWN_SECONDS, defaults.cooldownSeconds)

        val custom = AlertSettings(
            hrLowBpm = 130,
            hrHighBpm = 165,
            paceFast = Pace(270.0),
            paceSlow = Pace(330.0),
            sensorAlerts = false,
            gpsAlerts = true,
            cooldownSeconds = 15
        )
        assertFalse(custom.sensorAlerts)
        val settings = RunSettings(
            profile = UserProfile(
                maxHeartRate = 195,
                lactateThresholdHeartRate = 175,
                thresholdPace = Pace(280.0)
            ),
            hrZoneMethod = HrZoneMethod.LTHR,
            alerts = custom
        )
        val encoded = WorkoutJson.json.encodeToString(WorkoutSettings(runSettings = settings))
        assertEquals(
            settings,
            WorkoutJson.json.decodeFromString<WorkoutSettings>(encoded).runSettings
        )

        // Old JSON without hrZoneMethod / alerts / lthr / thresholdPace decodes cleanly to defaults.
        val legacy = """{"runSettings":{"units":"METRIC","profile":{"maxHeartRate":190}}}"""
        val decoded = WorkoutJson.json.decodeFromString<WorkoutSettings>(legacy)
        assertEquals(HrZoneMethod.MAX_HR, decoded.runSettings.hrZoneMethod)
        assertEquals(AlertSettings(), decoded.runSettings.alerts)
        assertNull(decoded.runSettings.profile.lactateThresholdHeartRate)
        assertNull(decoded.runSettings.profile.thresholdPace)
    }

    @Test
    fun `alert settings reject invalid limits and build target ranges from zones`() {
        assertFailsWith<IllegalArgumentException> { AlertSettings(hrLowBpm = 39) }
        assertFailsWith<IllegalArgumentException> { AlertSettings(hrHighBpm = 251) }
        assertFailsWith<IllegalArgumentException> { AlertSettings(hrLowBpm = 160, hrHighBpm = 160) }
        assertFailsWith<IllegalArgumentException> {
            AlertSettings(paceFast = Pace(300.0), paceSlow = Pace(300.0))
        }
        assertFailsWith<IllegalArgumentException> {
            AlertSettings(paceFast = Pace(330.0), paceSlow = Pace(270.0))
        }
        assertFailsWith<IllegalArgumentException> { AlertSettings(cooldownSeconds = 4) }
        assertFailsWith<IllegalArgumentException> { AlertSettings(cooldownSeconds = 301) }

        val hrZones = HeartRateZones.fromMaxHeartRate(200)
        val z2 = AlertSettings().withHrZone(hrZones, 2)
        assertEquals(120, z2.hrLowBpm)
        assertEquals(139, z2.hrHighBpm)
        val z5 = AlertSettings().withHrZone(hrZones, 5)
        assertEquals(180, z5.hrLowBpm)
        assertNull(z5.hrHighBpm)

        val paceZones = PaceZones.fromThreshold(Pace(300.0))
        val pz2 = AlertSettings().withPaceZone(paceZones, 2)
        assertEquals(Pace(342.0), pz2.paceFast)
        assertEquals(Pace(387.0), pz2.paceSlow)
        val pz1 = AlertSettings().withPaceZone(paceZones, 1)
        assertEquals(Pace(387.0), pz1.paceFast)
        assertNull(pz1.paceSlow)
        val pz5 = AlertSettings().withPaceZone(paceZones, 5)
        assertNull(pz5.paceFast)
        assertEquals(Pace(297.0), pz5.paceSlow)
    }
}
