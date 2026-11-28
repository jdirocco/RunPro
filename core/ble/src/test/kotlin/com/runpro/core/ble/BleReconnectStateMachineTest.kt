package com.runpro.core.ble

import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Virtual monotonic time and a GATT that can drop callbacks, fail requests and revive in range. */
class BleReconnectStateMachineTest {
    @Test
    fun `setup serializes discovery and each subscription and logs every transition`() {
        val rig = Rig(listOf(HR, RSC))
        rig.machine.start(0)
        assertEquals(listOf(GattOperation.CONNECT), rig.port.requests.map { it.operation })
        rig.ready()
        assertEquals(
            listOf(
                GattOperation.CONNECT,
                GattOperation.DISCOVER_SERVICES,
                GattOperation.SUBSCRIBE,
                GattOperation.SUBSCRIBE
            ),
            rig.port.requests.map { it.operation }
        )
        assertEquals(listOf(HR, RSC), rig.port.requests.mapNotNull { it.characteristic })
        assertEquals(
            listOf(
                BleConnectionState.CONNECTING,
                BleConnectionState.DISCOVERING_SERVICES,
                BleConnectionState.SUBSCRIBING,
                BleConnectionState.STREAMING
            ),
            rig.log.map { it.to }
        )
        assertTrue(
            rig.log.zipWithNext().all { (a, b) ->
                a.to == b.from && a.atMillis <= b.atMillis
            }
        )
    }

    @Test
    fun `disconnect and GATT 133 close before reconnect and stream within ten seconds`() {
        for (status in listOf(0, 133)) {
            val rig = Rig()
            rig.ready()
            val droppedAt = rig.now
            rig.machine.disconnected(rig.session, status, rig.now)
            assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
            assertEquals("close:${rig.session}", rig.port.calls.last())
            rig.advance(999)
            assertEquals(1, rig.port.requests.count { it.operation == GattOperation.CONNECT })
            rig.advance(1)
            assertTrue(rig.port.requests.last().autoConnect)
            rig.ready()
            assertTrue(rig.now - droppedAt < 10_000)
            assertTrue(rig.log.any { it.reason.contains(status.toString()) })
        }
    }

    @Test
    fun `five seconds without data resubscribes then requires actual notifications`() {
        val rig = Rig()
        rig.ready()
        rig.advance(4_999)
        assertEquals(BleConnectionState.STREAMING, rig.machine.state)
        rig.advance(1)
        assertEquals(BleConnectionState.RESUBSCRIBING, rig.machine.state)
        rig.complete()
        assertEquals(BleConnectionState.RESUBSCRIBING, rig.machine.state)
        rig.machine.notification(rig.session, HR, rig.now)
        assertEquals(BleConnectionState.STREAMING, rig.machine.state)
        assertTrue(rig.port.closed.isEmpty())
    }

    @Test
    fun `successful resubscription without data and failed resubscription both reconnect`() {
        for (status in listOf(0, 133)) {
            val rig = Rig()
            rig.ready()
            val stalledAt = rig.now
            rig.advance(5_000)
            rig.complete(status)
            if (status == 0) rig.advance(1_000)
            assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
            rig.advance(1_000)
            rig.ready()
            assertTrue(rig.now - stalledAt < 10_000)
        }
    }

    @Test
    fun `live streams and battery notifications cannot hide a stalled characteristic`() {
        val rig = Rig(listOf(HR, RSC))
        rig.ready()
        repeat(5) {
            rig.advance(1_000)
            rig.machine.notification(rig.session, HR, rig.now)
            rig.machine.notification(rig.session, GattUuids.BATTERY_LEVEL, rig.now)
        }
        assertEquals(BleConnectionState.RESUBSCRIBING, rig.machine.state)
        rig.complete()
        rig.complete()
        rig.machine.notification(rig.session, HR, rig.now)
        rig.advance(1_000)
        assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
    }

    @Test
    fun `every operation has a deadline including slow service discovery`() {
        for (operation in GattOperation.entries) {
            val rig = Rig()
            rig.machine.start(0)
            while (rig.port.requests.last().operation != operation) rig.complete()
            val late = rig.port.requests.last()
            rig.advance(late.timeoutMillis)
            assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
            assertTrue(rig.log.any { it.reason.contains("timeout") })
            rig.machine.completed(late.id, 0, rig.now)
            assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
            rig.advance(1_000)
            rig.ready()
        }
    }

    @Test
    fun `discovery just before deadline succeeds and errors at every stage recover`() {
        val slow = Rig()
        slow.machine.start(0)
        slow.complete()
        slow.advance(slow.port.requests.last().timeoutMillis - 11)
        slow.ready()
        for (operation in GattOperation.entries) {
            val rig = Rig()
            rig.machine.start(0)
            while (rig.port.requests.last().operation != operation) rig.complete()
            rig.complete(133)
            assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
            rig.advance(1_000)
            rig.ready()
        }
    }

    @Test
    fun `retry delays grow to eight seconds and reset only when all streams deliver data`() {
        val rig = Rig()
        rig.machine.start(0)
        for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L)) {
            rig.complete(133)
            val count = rig.port.requests.size
            rig.advance(delay - 1)
            assertEquals(count, rig.port.requests.size)
            rig.advance(1)
            assertEquals(count + 1, rig.port.requests.size)
        }
        rig.ready()
        rig.machine.notification(rig.session, HR, rig.now)
        rig.machine.disconnected(rig.session, 0, rig.now)
        rig.advance(1_000)
        assertEquals(BleConnectionState.CONNECTING, rig.machine.state)
    }

    @Test
    fun `old and duplicate callbacks cannot advance a new session or reset its watchdog`() {
        val rig = Rig()
        rig.ready()
        val old = rig.port.requests.last()
        rig.machine.disconnected(rig.session, 133, rig.now)
        rig.advance(1_000)
        rig.machine.completed(old.id, 0, rig.now)
        rig.machine.disconnected(old.sessionId, 133, rig.now)
        assertEquals(BleConnectionState.CONNECTING, rig.machine.state)
        rig.ready()
        repeat(5) {
            rig.advance(1_000)
            rig.machine.notification(old.sessionId, HR, rig.now)
        }
        assertEquals(BleConnectionState.RESUBSCRIBING, rig.machine.state)
        val current = rig.port.requests.last()
        rig.complete()
        rig.machine.completed(current.id, 133, rig.now)
        assertEquals(BleConnectionState.RESUBSCRIBING, rig.machine.state)
    }

    @Test
    fun `stop cancels all phases and restart never reuses a GATT session`() {
        for (phase in BleConnectionState.entries.filter { it != BleConnectionState.CLOSING }) {
            val rig = Rig()
            when (phase) {
                BleConnectionState.IDLE -> Unit

                BleConnectionState.CONNECTING -> rig.machine.start(0)

                BleConnectionState.DISCOVERING_SERVICES -> {
                    rig.machine.start(0)
                    rig.complete()
                }

                BleConnectionState.SUBSCRIBING -> {
                    rig.machine.start(0)
                    rig.complete()
                    rig.complete()
                }

                else -> {
                    rig.ready()
                    if (phase == BleConnectionState.RESUBSCRIBING) rig.advance(5_000)
                    if (phase == BleConnectionState.BACKOFF) {
                        rig.machine.disconnected(rig.session, 0, rig.now)
                    }
                }
            }
            val old = rig.port.requests.lastOrNull()
            rig.machine.stop(rig.now)
            rig.machine.stop(rig.now)
            assertEquals(BleConnectionState.IDLE, rig.machine.state)
            val count = rig.port.requests.size
            rig.advance(30_000)
            old?.let { rig.machine.completed(it.id, 0, rig.now) }
            assertEquals(count, rig.port.requests.size)
            rig.ready()
            if (old != null) assertTrue(rig.session > old.sessionId)
        }
    }

    @Test
    fun `port exceptions retry and a failed close prevents overlapping GATT clients`() {
        val rig = Rig()
        rig.port.reject = true
        rig.machine.start(0)
        assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
        rig.port.reject = false
        rig.advance(1_000)
        rig.ready()
        rig.port.failClose = true
        rig.machine.disconnected(rig.session, 133, rig.now)
        assertEquals(BleConnectionState.CLOSING, rig.machine.state)
        val count = rig.port.requests.size
        rig.advance(10_000)
        assertEquals(count, rig.port.requests.size)
        rig.machine.stop(rig.now)
        rig.port.failClose = false
        rig.advance(1_000)
        assertEquals(BleConnectionState.IDLE, rig.machine.state)
        assertFalse(rig.port.closed.isEmpty())
    }

    @Test
    fun `return during capped backoff restores data within ten seconds with slow discovery`() {
        val offsets = listOf(0L, 7_999L) + List(20) { Random(it).nextLong(8_000) }
        for (offset in offsets) {
            val rig = Rig()
            rig.machine.start(0)
            for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L)) {
                rig.complete(133)
                rig.advance(delay)
            }
            rig.complete(133)
            rig.advance(offset)
            val backInRange = rig.now
            rig.advance(8_000 - offset)
            rig.complete()
            rig.advance(1_200) // Delayed service discovery, still within its deadline.
            rig.ready()
            rig.machine.notification(rig.session, HR, rig.now)
            assertEquals(BleConnectionState.STREAMING, rig.machine.state)
            assertTrue(rig.now - backInRange < 10_000, "offset=$offset")
        }
    }

    @Test
    fun `subscription failure discards queued writes while other devices keep streaming`() {
        val rig = Rig(listOf(HR, RSC, GattUuids.CYCLING_POWER_MEASUREMENT))
        val other = Rig()
        other.ready()
        rig.machine.start(0)
        rig.complete()
        rig.complete()
        rig.complete(133)
        assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
        assertEquals(1, rig.port.requests.count { it.operation == GattOperation.SUBSCRIBE })
        assertEquals(BleConnectionState.STREAMING, other.machine.state)
        rig.advance(1_000)
        rig.ready()
    }

    @Test
    fun `a late completion times out without waiting for tick`() {
        val rig = Rig()
        rig.machine.start(0)
        val request = rig.port.requests.last()
        rig.now += request.timeoutMillis
        rig.machine.completed(request.id, 0, rig.now)
        assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
    }

    @Test
    fun `notifications after resubscription grace cannot conceal timeout between ticks`() {
        val rig = Rig()
        rig.ready()
        rig.advance(5_000)
        rig.complete()
        rig.now += 1_001
        rig.machine.notification(rig.session, HR, rig.now)
        assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
    }

    @Test
    fun `backoff resets only after every required stream has delivered data`() {
        val rig = Rig(listOf(HR, RSC))
        rig.machine.start(0)
        rig.complete(133)
        rig.advance(1_000)
        rig.complete(133)
        rig.advance(2_000)
        rig.ready()
        rig.machine.notification(rig.session, HR, rig.now)
        rig.machine.disconnected(rig.session, 133, rig.now)
        rig.advance(3_999)
        assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
        rig.advance(1)
        rig.ready()
        rig.machine.notification(rig.session, HR, rig.now)
        rig.machine.notification(rig.session, RSC, rig.now)
        rig.machine.disconnected(rig.session, 133, rig.now)
        rig.advance(1_000)
        assertEquals(BleConnectionState.CONNECTING, rig.machine.state)
    }

    @Test
    fun `restored data waits for queued resubscriptions and missing write callbacks reconnect`() {
        val rig = Rig(listOf(HR, RSC))
        rig.ready()
        rig.advance(5_000)
        rig.machine.notification(rig.session, HR, rig.now)
        rig.machine.notification(rig.session, RSC, rig.now)
        rig.complete()
        assertEquals(BleConnectionState.RESUBSCRIBING, rig.machine.state)
        rig.complete()
        assertEquals(BleConnectionState.STREAMING, rig.machine.state)
        rig.advance(5_000)
        rig.advance(1_000)
        assertEquals(BleConnectionState.BACKOFF, rig.machine.state)
    }

    private class Rig(characteristics: List<UUID> = listOf(HR)) {
        val port = FakeGatt()
        val log = mutableListOf<BleTransition>()
        val machine = BleConnectionStateMachine(port, characteristics, log::add)
        var now = 0L
        val session get() = port.requests.last().sessionId

        fun advance(millis: Long) {
            now += millis
            machine.tick(now)
        }

        fun complete(status: Int = 0) {
            val request = port.requests.last()
            now += 10
            port.finish(request.id)
            machine.completed(request.id, status, now)
        }

        fun ready() {
            if (machine.state == BleConnectionState.IDLE) machine.start(now)
            repeat(10) {
                if (machine.state == BleConnectionState.STREAMING) return
                complete()
            }
            error("Did not reach streaming: ${machine.state}")
        }
    }

    private class FakeGatt : GattPort {
        val requests = mutableListOf<GattRequest>()
        val calls = mutableListOf<String>()
        val closed = mutableListOf<Long>()
        private var pending: Long? = null
        private var liveSession: Long? = null
        var reject = false
        var failClose = false

        override fun execute(request: GattRequest) {
            check(pending == null) { "Overlapping GATT operations" }
            if (request.operation == GattOperation.CONNECT) {
                check(liveSession == null) { "Connect before previous GATT closed" }
                liveSession = request.sessionId
            }
            check(liveSession == request.sessionId)
            pending = request.id
            requests += request
            calls += "${request.operation}:${request.sessionId}"
            check(!reject) { "request rejected" }
        }

        fun finish(requestId: Long) {
            check(pending == requestId)
            pending = null
        }

        override fun close(sessionId: Long) {
            calls += "close:$sessionId"
            check(!failClose) { "close failed" }
            closed += sessionId
            pending = null
            liveSession = null
        }
    }

    private companion object {
        val HR = GattUuids.HEART_RATE_MEASUREMENT
        val RSC = GattUuids.RSC_MEASUREMENT
    }
}
