package com.runpro.core.ble

import com.runpro.core.model.CadenceSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.Timestamp
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [BleSensorLink] over a fake platform GATT, in virtual monotonic time. */
class BleSensorLinkTest {
    @Test
    fun `a strap connects, reports connected once and streams heart rate samples`() {
        val rig = Rig()
        rig.link.start(rig.listener, rig.time())
        val connects = rig.port.requests.filter { it.operation == GattOperation.CONNECT }
        assertEquals(listOf(false), connects.map { it.autoConnect })
        rig.ready()
        assertEquals(BleConnectionState.STREAMING, rig.link.state)
        assertEquals(
            listOf<SensorEvent>(SensorEvent.Connected(rig.time(), STRAP_SOURCE)),
            rig.events
        )
        rig.notify(bytes(0x00, 0x8C))
        val sample = assertIs<HeartRateSample>(rig.samples.single())
        assertEquals(HeartRate(140), sample.heartRate)
        assertEquals(STRAP_SOURCE, sample.source)
        assertEquals(rig.time(), sample.time)
        assertEquals(1, rig.events.size)
    }

    @Test
    fun `GATT 133 is reported with its status and autoConnect reconnects within R8`() {
        val rig = Rig()
        rig.link.start(rig.listener, rig.time())
        rig.ready()
        val dropped = rig.now
        rig.link.disconnected(rig.session, 133, rig.time())
        assertEquals(SensorEvent.Disconnected(rig.time(), STRAP_SOURCE, 133), rig.events.last())
        assertEquals(listOf(rig.session), rig.port.closed)
        rig.advance(1_000)
        assertTrue(rig.port.requests.last().autoConnect)
        rig.ready()
        rig.notify(bytes(0x00, 0x8C))
        assertIs<SensorEvent.Connected>(rig.events.last())
        assertTrue(rig.now - dropped < 10_000, "reconnected after ${rig.now - dropped} ms")
        assertEquals(3, rig.events.size)
        assertEquals(1, rig.samples.size)
    }

    @Test
    fun `a restored resubscription keeps the link up and a lasting stall reports a disconnect`() {
        val rig = Rig()
        rig.link.start(rig.listener, rig.time())
        rig.ready()
        rig.advance(5_000)
        assertEquals(BleConnectionState.RESUBSCRIBING, rig.link.state)
        rig.complete()
        rig.notify(bytes(0x00, 0x8C))
        assertEquals(BleConnectionState.STREAMING, rig.link.state)
        assertEquals(1, rig.events.size)
        assertEquals(1, rig.samples.size)

        rig.advance(5_000)
        rig.complete()
        rig.advance(1_000)
        assertEquals(BleConnectionState.BACKOFF, rig.link.state)
        val lost = assertIs<SensorEvent.Disconnected>(rig.events.last())
        assertNull(lost.gattStatus)
    }

    @Test
    fun `repeated setup failures refresh the GATT cache before the next discovery`() {
        val rig = Rig()
        rig.link.start(rig.listener, rig.time())
        repeat(2) { attempt ->
            rig.completeUntil(GattOperation.SUBSCRIBE)
            rig.complete(status = 6)
            assertEquals(BleConnectionState.BACKOFF, rig.link.state)
            rig.advance(1_000L shl attempt)
        }
        rig.completeUntil(GattOperation.DISCOVER_SERVICES)
        assertEquals(
            listOf("refresh:${rig.session}", "DISCOVER_SERVICES:${rig.session}"),
            rig.port.calls.takeLast(2)
        )
        assertTrue(rig.log.any { it.message.contains("refreshed the GATT cache") })
        rig.ready()
        rig.notify(bytes(0x00, 0x8C))

        // A fresh count: one failure after streaming again does not refresh.
        rig.link.disconnected(rig.session, 8, rig.time())
        rig.advance(1_000)
        rig.completeUntil(GattOperation.SUBSCRIBE)
        rig.complete(status = 6)
        rig.advance(2_000)
        rig.completeUntil(GattOperation.DISCOVER_SERVICES)
        assertEquals(1, rig.port.calls.count { it.startsWith("refresh") })
    }

    @Test
    fun `a stream that ended normally never counts towards a cache refresh`() {
        val rig = Rig()
        rig.link.start(rig.listener, rig.time())
        repeat(3) {
            rig.ready()
            rig.notify(bytes(0x00, 0x8C))
            rig.link.disconnected(rig.session, 8, rig.time())
            rig.advance(1_000)
        }
        assertTrue(rig.port.calls.none { it.startsWith("refresh") })
    }

    @Test
    fun `discovery waits while the device is bonding and runs once the bond completes`() {
        val rig = Rig()
        rig.port.bond = BondState.BONDING
        rig.link.start(rig.listener, rig.time())
        rig.completeUntil(GattOperation.DISCOVER_SERVICES)
        assertTrue(rig.port.requests.none { it.operation == GattOperation.DISCOVER_SERVICES })
        assertTrue(rig.log.any { it.message.contains("bond=BONDING") })
        rig.advance(500)
        assertTrue(rig.port.requests.none { it.operation == GattOperation.DISCOVER_SERVICES })
        rig.port.bond = BondState.BONDED
        rig.advance(100)
        assertEquals(GattOperation.DISCOVER_SERVICES, rig.port.requests.last().operation)
        rig.ready()
        assertEquals(BleConnectionState.STREAMING, rig.link.state)
    }

    @Test
    fun `bonding past the discovery deadline recovers and never runs the stale discovery`() {
        val rig = Rig()
        rig.port.bond = BondState.BONDING
        rig.link.start(rig.listener, rig.time())
        rig.completeUntil(GattOperation.DISCOVER_SERVICES)
        rig.advance(5_000) // the discovery deadline (#120)
        assertEquals(BleConnectionState.BACKOFF, rig.link.state)
        rig.port.bond = BondState.BONDED
        rig.advance(100)
        assertTrue(rig.port.requests.none { it.operation == GattOperation.DISCOVER_SERVICES })
        rig.advance(900)
        assertEquals(GattOperation.CONNECT, rig.port.requests.last().operation)
    }

    @Test
    fun `a discovery rejected after bonding recovers instead of hanging`() {
        val rig = Rig()
        rig.port.bond = BondState.BONDING
        rig.link.start(rig.listener, rig.time())
        rig.completeUntil(GattOperation.DISCOVER_SERVICES)
        rig.port.bond = BondState.NONE
        rig.port.reject = true
        rig.advance(100)
        assertEquals(BleConnectionState.BACKOFF, rig.link.state)
        assertTrue(rig.log.any { it.message.contains("status=${BleSensorLink.STATUS_REJECTED}") })
    }

    @Test
    fun `a bonded strap that drops on encryption before streaming loses its stale bond once`() {
        // #117: the watch kept a system bond the H10 had forgotten (HCI_ERR_KEY_MISSING, status 5).
        val rig = Rig()
        rig.port.bond = BondState.BONDED
        rig.link.start(rig.listener, rig.time())
        rig.completeUntil(GattOperation.DISCOVER_SERVICES)
        rig.link.disconnected(rig.session, 5, rig.time())
        assertEquals(BleConnectionState.BACKOFF, rig.link.state)
        assertEquals(1, rig.port.calls.count { it == "removeBond" })
        assertTrue(rig.log.any { it.message.contains("removed the stale bond (status=5)") })
        assertTrue(rig.events.isEmpty())

        rig.advance(1_000)
        assertTrue(rig.port.requests.last().autoConnect)
        rig.ready()
        rig.notify(bytes(0x00, 0x8C))
        assertEquals(1, rig.samples.size)

        // Once per link: a later failure of the same kind does not remove again.
        rig.link.disconnected(rig.session, 8, rig.time())
        rig.advance(1_000)
        rig.port.bond = BondState.BONDED
        rig.completeUntil(GattOperation.DISCOVER_SERVICES)
        rig.link.disconnected(rig.session, 5, rig.time())
        assertEquals(1, rig.port.calls.count { it == "removeBond" })
    }

    @Test
    fun `the bond is kept for other drops, unbonded devices and streaming links`() {
        val other = Rig()
        other.port.bond = BondState.BONDED
        other.link.start(other.listener, other.time())
        other.completeUntil(GattOperation.DISCOVER_SERVICES)
        other.link.disconnected(other.session, 8, other.time())

        val unbonded = Rig()
        unbonded.link.start(unbonded.listener, unbonded.time())
        unbonded.completeUntil(GattOperation.DISCOVER_SERVICES)
        unbonded.link.disconnected(unbonded.session, 5, unbonded.time())

        val streaming = Rig()
        streaming.port.bond = BondState.BONDED
        streaming.link.start(streaming.listener, streaming.time())
        streaming.ready()
        streaming.link.disconnected(streaming.session, 5, streaming.time())

        listOf(other, unbonded, streaming).forEach { rig ->
            assertTrue(rig.port.calls.none { it == "removeBond" })
            assertEquals(BleConnectionState.BACKOFF, rig.link.state)
        }
    }

    @Test
    fun `a bond that cannot be removed is logged with the manual fix`() {
        val rig = Rig()
        rig.port.bond = BondState.BONDED
        rig.port.bondRemovable = false
        rig.link.start(rig.listener, rig.time())
        rig.completeUntil(GattOperation.DISCOVER_SERVICES)
        rig.link.disconnected(rig.session, 5, rig.time())
        assertTrue(rig.log.any { it.message.contains("cannot remove the stale bond") })
        assertEquals(BleConnectionState.BACKOFF, rig.link.state)
    }

    @Test
    fun `stale, empty, unknown and malformed notifications produce no samples`() {
        val rig = Rig()
        rig.link.start(rig.listener, rig.time())
        rig.ready()
        rig.notify(bytes(0x00, 0x8C), session = rig.session - 1)
        rig.notify(ByteArray(0))
        rig.notify(bytes(0x01, 0x02), characteristic = UUID.randomUUID())
        rig.notify(bytes(0x01, 0x8C))
        assertTrue(rig.samples.isEmpty())
        assertTrue(rig.log.any { it.message.contains("malformed") })
    }

    @Test
    fun `notifications before streaming are dropped`() {
        val rig = Rig()
        rig.link.start(rig.listener, rig.time())
        rig.completeUntil(GattOperation.SUBSCRIBE)
        rig.notify(bytes(0x00, 0x8C))
        assertTrue(rig.samples.isEmpty())
    }

    @Test
    fun `stop closes the session and nothing reaches the listener afterwards`() {
        val rig = Rig()
        rig.link.start(rig.listener, rig.time())
        rig.ready()
        val session = rig.session
        rig.link.stop(rig.time())
        assertEquals(BleConnectionState.IDLE, rig.link.state)
        assertEquals(listOf(session), rig.port.closed)
        rig.notify(bytes(0x00, 0x8C), session = session)
        rig.link.disconnected(session, 0, rig.time())
        rig.advance(10_000)
        assertEquals(1, rig.events.size)
        assertTrue(rig.samples.isEmpty())
        assertTrue(rig.port.calls.none { it.startsWith("refresh") })

        rig.link.start(rig.listener, rig.time())
        assertEquals(GattOperation.CONNECT, rig.port.requests.last().operation)
        assertTrue(rig.port.requests.last().sessionId > session)
    }

    @Test
    fun `every transition and note is logged with the sensor source and session`() {
        val rig = Rig()
        rig.link.start(rig.listener, rig.time())
        rig.ready()
        assertTrue(rig.log.all { it.source == STRAP_SOURCE })
        assertEquals(
            listOf("IDLE -> CONNECTING", "CONNECTING -> DISCOVERING_SERVICES"),
            rig.log.filter { "->" in it.message }.take(2).map { it.message.substringBefore(":") }
        )
        assertTrue(rig.log.any { it.message.contains("SUBSCRIBING -> STREAMING") })
        assertTrue(rig.log.zipWithNext().all { (a, b) -> a.atMillis <= b.atMillis })
        assertTrue(rig.log.last().toString().contains(STRAP_SOURCE.value))
    }

    @Test
    fun `a footpod subscribes to RSC and streams speed and cadence`() {
        val rig = Rig(PairedSensor("AA:BB:CC:DD:EE:02", BleSensorKind.FOOTPOD))
        rig.link.start(rig.listener, rig.time())
        rig.ready()
        assertEquals(
            listOf(GattUuids.RSC_MEASUREMENT),
            rig.port.requests.mapNotNull { it.characteristic }
        )
        rig.notify(bytes(0x04, 0x00, 0x03, 0xAA), characteristic = GattUuids.RSC_MEASUREMENT)
        assertEquals(3.0, assertIs<SpeedSample>(rig.samples[0]).speed.metersPerSecond)
        assertEquals(170.0, assertIs<CadenceSample>(rig.samples[1]).cadence.stepsPerMinute)
    }

    private class Rig(sensor: PairedSensor = STRAP) {
        val port = FakeDevicePort()
        val log = mutableListOf<BleLogLine>()
        val link = BleSensorLink(sensor, port, log::add)
        val samples = mutableListOf<Sample>()
        val events = mutableListOf<SensorEvent>()
        val listener = object : SensorListener {
            override fun onSample(sample: Sample) {
                samples += sample
            }

            override fun onEvent(event: SensorEvent) {
                events += event
            }
        }
        var now = 0L
        val session get() = port.requests.last().sessionId

        fun time() = Timestamp(now * NANOS_PER_MILLI, WALL_START + now)

        fun advance(millis: Long) {
            val end = now + millis
            while (now < end) {
                now = minOf(end, now + TICK)
                link.tick(time())
            }
        }

        fun complete(status: Int = 0) {
            val request = port.requests.last()
            now += 10
            port.finish(request.id)
            link.completed(request.id, status, time())
        }

        /** Completes requests until [operation] is the one in flight (or held by the link). */
        fun completeUntil(operation: GattOperation) {
            val target = when (operation) {
                GattOperation.CONNECT -> BleConnectionState.CONNECTING
                GattOperation.DISCOVER_SERVICES -> BleConnectionState.DISCOVERING_SERVICES
                GattOperation.SUBSCRIBE -> BleConnectionState.SUBSCRIBING
            }
            repeat(10) {
                if (link.state == target) return
                complete()
            }
            error("Never reached $operation")
        }

        fun ready() {
            repeat(10) {
                if (link.state == BleConnectionState.STREAMING) return
                complete()
            }
            error("Did not reach streaming: ${link.state}")
        }

        fun notify(
            value: ByteArray,
            characteristic: UUID = GattUuids.HEART_RATE_MEASUREMENT,
            session: Long = this.session
        ) {
            link.notified(session, characteristic, value, time())
        }
    }

    private class FakeDevicePort : GattDevicePort {
        val requests = mutableListOf<GattRequest>()
        val calls = mutableListOf<String>()
        val closed = mutableListOf<Long>()
        var pending: Long? = null
        private var liveSession: Long? = null
        var reject = false
        var bond = BondState.NONE

        override fun execute(request: GattRequest) {
            check(pending == null) { "Overlapping GATT operations" }
            if (request.operation == GattOperation.CONNECT) {
                check(liveSession == null) { "Connect before previous GATT closed" }
                liveSession = request.sessionId
            }
            check(liveSession == request.sessionId)
            check(!reject) { "request rejected" }
            pending = request.id
            requests += request
            calls += "${request.operation}:${request.sessionId}"
        }

        fun finish(requestId: Long) {
            check(pending == requestId)
            pending = null
        }

        override fun close(sessionId: Long) {
            calls += "close:$sessionId"
            closed += sessionId
            pending = null
            if (liveSession == sessionId) liveSession = null
        }

        override fun refreshServiceCache(sessionId: Long): Boolean {
            calls += "refresh:$sessionId"
            return true
        }

        override fun bondState(): BondState = bond

        var bondRemovable = true

        override fun removeBond(): Boolean {
            calls += "removeBond"
            if (bondRemovable) bond = BondState.NONE
            return bondRemovable
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val WALL_START = 1_700_000_000_000L
        const val TICK = 100L
        val STRAP = PairedSensor("AA:BB:CC:DD:EE:01", BleSensorKind.HEART_RATE, "Strap")
        val STRAP_SOURCE = SourceId.ble("AA:BB:CC:DD:EE:01")
    }
}
