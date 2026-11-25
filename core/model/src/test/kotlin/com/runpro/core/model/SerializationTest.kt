package com.runpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.SerializationException

class SerializationTest {
    private val json = ModelJson.json
    private val t0 =
        Timestamp(elapsedRealtimeNanos = 1_000_000_000, wallClockMillis = 1_764_064_800_000)
    private val strap = SourceId.ble("AA:BB:CC:DD:EE:FF")
    private val gps = SourceId.HEALTH_SERVICES

    private val samples: List<Sample> = listOf(
        HeartRateSample(t0, strap, HeartRate(148)),
        LocationSample(
            t0,
            gps,
            latitude = 45.464_2,
            longitude = 9.19,
            horizontalAccuracy = Distance(4.5),
            altitude = Elevation(122.0)
        ),
        LocationSample(t0, gps, latitude = -33.86, longitude = 151.21),
        DistanceSample(t0, gps, Distance(1234.5)),
        SpeedSample(t0, gps, Speed(3.2)),
        CadenceSample(t0, strap, Cadence(176.0)),
        PowerSample(t0, strap, Power(265)),
        ElevationSample(t0, gps, Elevation(-3.5)),
        AccelerationSample(t0, SourceId.WATCH_IMU, Acceleration(0.1, -9.7, 1.2))
    )

    private val events: List<RunEvent> = listOf(
        RunEvent.Started(t0),
        RunEvent.Paused(t0 + 1.minutes, PauseReason.AUTO),
        RunEvent.Resumed(t0 + 2.minutes),
        RunEvent.LapMarked(t0 + 3.minutes, LapTrigger.TIME),
        RunEvent.SensorConnected(t0 + 4.minutes, strap),
        RunEvent.SensorLost(t0 + 5.minutes, strap, SensorLossReason.DISCONNECTED),
        RunEvent.SourceSwitched(t0 + 5.minutes, Metric.HEART_RATE, strap, gps, SwitchReason.STALE),
        RunEvent.SourceSwitched(
            t0 + 6.minutes,
            Metric.CADENCE,
            from = null,
            to = strap,
            reason = SwitchReason.HIGHER_PRIORITY
        ),
        RunEvent.Stopped(t0 + 7.minutes, StopReason.LOW_BATTERY)
    )

    @Test
    fun `there is a sample type for every metric`() {
        assertEquals(Metric.entries.toSet(), samples.map { it.metric }.toSet())
    }

    @Test
    fun `samples survive a JSON round trip`() {
        samples.forEach { sample ->
            val text = json.encodeToString(Sample.serializer(), sample)
            assertEquals(sample, json.decodeFromString(Sample.serializer(), text), text)
        }
    }

    @Test
    fun `run events survive a JSON round trip`() {
        events.forEach { event ->
            val text = json.encodeToString(RunEvent.serializer(), event)
            assertEquals(event, json.decodeFromString(RunEvent.serializer(), text), text)
        }
    }

    @Test
    fun `a run survives a JSON round trip`() {
        val run = Run(RunId("run-1"), events)
        assertEquals(
            run,
            json.decodeFromString(Run.serializer(), json.encodeToString(Run.serializer(), run))
        )
    }

    @Test
    fun `the JSON shape of samples is stable`() {
        // Golden strings: traces and exports depend on these names.
        // Change them only together with a format migration.
        val time = """"time":{"elapsedNanos":1000000000,"wallMillis":1764064800000}"""
        assertEquals(
            """{"type":"heart_rate",$time,"source":"ble:AA:BB:CC:DD:EE:FF","heartRate":148}""",
            json.encodeToString(Sample.serializer(), samples[0])
        )
        assertEquals(
            """{"type":"location",$time,"source":"watch:health-services",""" +
                """"latitude":-33.86,"longitude":151.21}""",
            json.encodeToString(Sample.serializer(), samples[2])
        )
        assertEquals(
            """{"type":"acceleration",$time,"source":"watch:imu",""" +
                """"acceleration":{"x":0.1,"y":-9.7,"z":1.2}}""",
            json.encodeToString(Sample.serializer(), samples[8])
        )
    }

    @Test
    fun `the JSON shape of events is stable`() {
        assertEquals(
            """{"type":"source_switched",""" +
                """"time":{"elapsedNanos":301000000000,"wallMillis":1764065100000},""" +
                """"metric":"heart_rate","from":"ble:AA:BB:CC:DD:EE:FF",""" +
                """"to":"watch:health-services","reason":"stale"}""",
            json.encodeToString(RunEvent.serializer(), events[6])
        )
        assertEquals(
            """{"type":"paused","time":{"elapsedNanos":61000000000,"wallMillis":1764064860000},""" +
                """"reason":"auto"}""",
            json.encodeToString(RunEvent.serializer(), events[1])
        )
    }

    @Test
    fun `unknown fields from newer writers are ignored`() {
        val text =
            """{"type":"speed","time":{"elapsedNanos":1,"wallMillis":2},"source":"watch:gps",""" +
                """"speed":2.5,"extra":1}"""
        assertEquals(
            SpeedSample(Timestamp(1, 2), SourceId("watch:gps"), Speed(2.5)),
            json.decodeFromString(Sample.serializer(), text)
        )
    }

    @Test
    fun `decoding rejects invalid values and unknown types`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(
                Sample.serializer(),
                """{"type":"heart_rate","time":{"elapsedNanos":1,"wallMillis":2},""" +
                    """"source":"watch:hr","heartRate":-5}"""
            )
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(
                Sample.serializer(),
                """{"type":"teleport","time":{"elapsedNanos":1,"wallMillis":2},"source":"watch:hr"}"""
            )
        }
    }
}
