package com.runpro.core.ble

import java.util.UUID

/** One in-flight operation; cleared before closing so old completions cannot release newer work. */
internal class GattOperationQueue(private val port: GattPort) {
    private val waiting = ArrayDeque<GattRequest>()
    private var nextId = 0L
    private var startedAt = 0L
    var active: GattRequest? = null
        private set

    fun add(
        session: Long,
        operation: GattOperation,
        characteristic: UUID? = null,
        autoConnect: Boolean = false
    ) {
        val timeout = when (operation) {
            GattOperation.CONNECT -> 3_000L
            GattOperation.DISCOVER_SERVICES -> 2_000L
            GattOperation.SUBSCRIBE -> 1_000L
        }
        waiting.addLast(
            GattRequest(++nextId, session, operation, timeout, characteristic, autoConnect)
        )
    }

    fun dispatch(now: Long) {
        if (active != null || waiting.isEmpty()) return
        val request = waiting.removeFirst()
        active = request
        startedAt = now
        port.execute(request)
    }

    fun timedOut(now: Long): Boolean = active?.let { now - startedAt >= it.timeoutMillis } ?: false

    fun complete() {
        active = null
    }

    fun clear() {
        active = null
        waiting.clear()
    }

    val isEmpty: Boolean get() = active == null && waiting.isEmpty()
}
