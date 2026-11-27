package com.runpro.core.ble

import java.util.UUID

/**
 * Deterministic per-device controller. The owner serializes calls (including callbacks) and supplies
 * monotonic milliseconds from the same boot. Call [tick] at least every 100 ms even during silence.
 * Port calls and [log] must return promptly; log must not throw or reenter this controller.
 *
 * [characteristics] are required periodic measurement notifications, not optional battery readings.
 * Each has a five-second watchdog. Resubscription must restore actual data, not just successful CCCD
 * writes. This class never pauses/stops a run, owns no thread, and does not parse or retain payloads.
 */
// Keep the lifecycle and its transition helpers together; the operation queue is separate.
@Suppress("TooManyFunctions")
class BleConnectionStateMachine(
    private val port: GattPort,
    characteristics: List<UUID>,
    private val log: (BleTransition) -> Unit
) {
    private val characteristics = characteristics.distinct().also { require(it.isNotEmpty()) }
    private val queue = GattOperationQueue(port)
    private val lastData = mutableMapOf<UUID, Long>()
    private val received = mutableSetOf<UUID>()
    private var now = 0L
    private var session = 0L
    private var hasSession = false
    private var wanted = false
    private var retry = 0
    private var deadline = 0L

    var state = BleConnectionState.IDLE
        private set

    fun start(nowMillis: Long) {
        time(nowMillis)
        if (wanted) return
        wanted = true
        retry = 0
        if (state == BleConnectionState.IDLE) connect(autoConnect = false)
    }

    fun stop(nowMillis: Long) {
        time(nowMillis)
        wanted = false
        queue.clear()
        if (hasSession) {
            if (state != BleConnectionState.CLOSING) transition(BleConnectionState.CLOSING, "stop")
            close()
        } else if (state != BleConnectionState.IDLE) {
            transition(BleConnectionState.IDLE, "stopped")
        }
    }

    fun completed(requestId: Long, status: Int, nowMillis: Long) {
        time(nowMillis)
        val request = queue.active?.takeIf { it.id == requestId } ?: return
        if (queue.timedOut(now)) {
            recover("${request.operation} timeout")
        } else if (status != 0) {
            recover("${request.operation} status=$status")
        } else {
            queue.complete()
            advance(request.operation)
            dispatch()
        }
    }

    fun disconnected(sessionId: Long, status: Int, nowMillis: Long) {
        time(nowMillis)
        if (sessionId != session || !hasSession || state == BleConnectionState.CLOSING) return
        recover("disconnect status=$status")
    }

    /** Call for a nonempty measurement notification, before delivering its payload to the consumer. */
    fun notification(sessionId: Long, characteristic: UUID, nowMillis: Long) {
        time(nowMillis)
        if (sessionId != session || characteristic !in characteristics ||
            state !in listOf(BleConnectionState.STREAMING, BleConnectionState.RESUBSCRIBING)
        ) {
            return
        }
        val expired = queue.timedOut(now) || (queue.isEmpty && now >= deadline)
        if (state == BleConnectionState.RESUBSCRIBING && expired) {
            recover("resubscribe timeout before notification")
            return
        }
        lastData[characteristic] = now
        received += characteristic
        if (received.containsAll(characteristics)) {
            retry = 0
            if (state == BleConnectionState.RESUBSCRIBING && queue.isEmpty) {
                transition(BleConnectionState.STREAMING, "notifications restored")
            }
        }
    }

    fun tick(nowMillis: Long) {
        time(nowMillis)
        when {
            queue.timedOut(now) -> recover("${queue.active?.operation} timeout")

            state == BleConnectionState.CLOSING && now >= deadline -> close()

            state == BleConnectionState.BACKOFF && now >= deadline -> connect(autoConnect = true)

            state == BleConnectionState.STREAMING &&
                lastData.values.any { now - it >= SILENCE_MILLIS } -> {
                transition(BleConnectionState.RESUBSCRIBING, "no data for 5000 ms")
                received.clear()
                subscribe()
            }

            state == BleConnectionState.RESUBSCRIBING && queue.isEmpty && now >= deadline -> {
                recover("no data after resubscribe")
            }
        }
    }

    private fun connect(autoConnect: Boolean) {
        check(!hasSession)
        session++
        hasSession = true
        transition(BleConnectionState.CONNECTING, "connect autoConnect=$autoConnect")
        queue.add(session, GattOperation.CONNECT, autoConnect = autoConnect)
        dispatch()
    }

    private fun advance(operation: GattOperation) {
        when (operation) {
            GattOperation.CONNECT -> {
                transition(BleConnectionState.DISCOVERING_SERVICES, "connected")
                queue.add(session, GattOperation.DISCOVER_SERVICES)
            }

            GattOperation.DISCOVER_SERVICES -> {
                transition(BleConnectionState.SUBSCRIBING, "services discovered")
                subscribe()
            }

            GattOperation.SUBSCRIBE -> if (queue.isEmpty) subscribed()
        }
    }

    private fun subscribe() {
        characteristics.forEach { queue.add(session, GattOperation.SUBSCRIBE, it) }
        dispatch()
    }

    private fun subscribed() {
        if (state == BleConnectionState.SUBSCRIBING) {
            lastData.clear()
            characteristics.forEach { lastData[it] = now }
            received.clear()
            transition(BleConnectionState.STREAMING, "subscriptions enabled")
        } else if (received.containsAll(characteristics)) {
            transition(BleConnectionState.STREAMING, "notifications restored")
        } else {
            deadline = now + RESUBSCRIBE_GRACE_MILLIS
        }
    }

    // The platform may throw runtime errors (including permissions and invalid GATT state).
    @Suppress("TooGenericExceptionCaught")
    private fun dispatch() {
        try {
            queue.dispatch(now)
        } catch (error: Exception) {
            recover("${queue.active?.operation} rejected: ${error.message}")
        }
    }

    private fun recover(reason: String) {
        queue.clear()
        transition(BleConnectionState.CLOSING, reason)
        close()
    }

    // A platform failure must keep the close barrier in place, whatever its exception type.
    @Suppress("TooGenericExceptionCaught")
    private fun close() {
        try {
            port.close(session)
        } catch (error: Exception) {
            deadline = now + CLOSE_RETRY_MILLIS
            transition(BleConnectionState.CLOSING, "close failed: ${error.message}")
            return
        }
        hasSession = false
        lastData.clear()
        received.clear()
        if (wanted) {
            val delay = BACKOFF_MILLIS[retry.coerceAtMost(BACKOFF_MILLIS.lastIndex)]
            retry = (retry + 1).coerceAtMost(BACKOFF_MILLIS.lastIndex)
            deadline = now + delay
            transition(BleConnectionState.BACKOFF, "retry in $delay ms")
        } else {
            transition(BleConnectionState.IDLE, "closed")
        }
    }

    private fun transition(next: BleConnectionState, reason: String) {
        val previous = state
        state = next
        log(BleTransition(now, session, previous, next, reason))
    }

    private fun time(value: Long) {
        require(value >= now) { "BLE time must be monotonic" }
        now = value
    }

    private companion object {
        const val SILENCE_MILLIS = 5_000L
        const val RESUBSCRIBE_GRACE_MILLIS = 1_000L
        const val CLOSE_RETRY_MILLIS = 1_000L

        // Eight seconds keeps retries below R8's ten-second target; never a ten-second sleep.
        val BACKOFF_MILLIS = listOf(1_000L, 2_000L, 4_000L, 8_000L)
    }
}
