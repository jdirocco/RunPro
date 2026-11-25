package com.runpro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.descriptors.elementNames

class SensorEventTest {
    private val json = ModelJson.json
    private val t0 =
        Timestamp(elapsedRealtimeNanos = 2_000_000_000, wallClockMillis = 1_764_064_800_000)
    private val strap = SourceId.ble("AA:BB:CC:DD:EE:FF")
    private val gps = SourceId.HEALTH_SERVICES

    private val events: List<SensorEvent> = listOf(
        SensorEvent.Connected(t0, strap),
        SensorEvent.Disconnected(t0 + 1.seconds, strap, gattStatus = 133),
        SensorEvent.Disconnected(t0 + 2.seconds, strap),
        SensorEvent.GpsLost(t0 + 3.seconds, gps),
        SensorEvent.GpsAcquired(t0 + 4.seconds, gps)
    )

    @Test
    fun `sensor events survive a JSON round trip`() {
        events.forEach { event ->
            val text = json.encodeToString(SensorEvent.serializer(), event)
            assertEquals(event, json.decodeFromString(SensorEvent.serializer(), text), text)
        }
    }

    @Test
    fun `the JSON shape of sensor events is stable`() {
        // Golden strings: replay traces depend on these names. Their type names must not collide
        // with sample type names, because trace lines are told apart by their type.
        val time = """"time":{"elapsedNanos":2000000000,"wallMillis":1764064800000}"""
        assertEquals(
            """{"type":"sensor_connected",$time,"source":"ble:AA:BB:CC:DD:EE:FF"}""",
            json.encodeToString(SensorEvent.serializer(), events[0])
        )
        assertEquals(
            """{"type":"sensor_disconnected",""" +
                """"time":{"elapsedNanos":3000000000,"wallMillis":1764064801000},""" +
                """"source":"ble:AA:BB:CC:DD:EE:FF","gattStatus":133}""",
            json.encodeToString(SensorEvent.serializer(), events[1])
        )
        assertEquals(
            """{"type":"gps_lost",""" +
                """"time":{"elapsedNanos":5000000000,"wallMillis":1764064803000},""" +
                """"source":"watch:health-services"}""",
            json.encodeToString(SensorEvent.serializer(), events[3])
        )
    }

    @Test
    fun `sensor event type names differ from sample type names`() {
        val sampleTypes = Sample.serializer().descriptor.getElementDescriptor(
            1
        ).elementNames.toSet()
        val eventTypes = SensorEvent.serializer().descriptor.getElementDescriptor(
            1
        ).elementNames.toSet()
        assertEquals(
            setOf("sensor_connected", "sensor_disconnected", "gps_lost", "gps_acquired"),
            eventTypes
        )
        assertEquals(emptySet<String>(), sampleTypes intersect eventTypes)
    }

    @Test
    fun `a source delivers samples and events to its listener until stopped`() {
        val received = mutableListOf<Any>()
        val listener = object : SensorListener {
            override fun onSample(sample: Sample) {
                received += sample
            }

            override fun onEvent(event: SensorEvent) {
                received += event
            }
        }
        val source = PushSource(strap)
        source.start(listener)
        source.push(HeartRateSample(t0, strap, HeartRate(140)))
        source.push(events[0])
        source.stop()
        source.push(HeartRateSample(t0 + 1.seconds, strap, HeartRate(141)))

        assertEquals(listOf(HeartRateSample(t0, strap, HeartRate(140)), events[0]), received)
    }

    /** The smallest [SensorSource]: proves the contract can be implemented without Android. */
    private class PushSource(override val id: SourceId) : SensorSource {
        private var listener: SensorListener? = null

        override fun start(listener: SensorListener) {
            this.listener = listener
        }

        override fun stop() {
            listener = null
        }

        fun push(sample: Sample) = listener?.onSample(sample)

        fun push(event: SensorEvent) = listener?.onEvent(event)
    }
}
