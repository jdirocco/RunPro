package com.runpro.core.ble

import java.util.UUID

/**
 * One device's asynchronous GATT adapter. Calls return immediately; rejection throws. The adapter
 * posts completions to [BleConnectionStateMachine.completed], never inline from [execute]. Connect
 * completes only once connected; discovery only after services are found; subscribe enables local
 * notifications and completes after the CCCD write. Missing services/descriptors are failures.
 *
 * Bind every callback to its request id and GATT session id, including disconnects and notifications.
 * Marshal all controller calls onto one serial dispatcher, off the recording/UI thread. No Android
 * types or scheduling policy belong here. Android implementation and sensor data delivery follow in #17.
 */
interface GattPort {
    fun execute(request: GattRequest)

    /**
     * Release this session, including a partially failed connect, before returning. Idempotent. A
     * failure throws: the controller retries close and cannot open another session until it succeeds.
     * Do not wait for a disconnect callback; the Android adapter must call BluetoothGatt.close().
     */
    fun close(sessionId: Long)
}

enum class GattOperation { CONNECT, DISCOVER_SERVICES, SUBSCRIBE }

/** Request ids and session ids increase throughout the lifetime of a controller, including stop/start. */
data class GattRequest(
    val id: Long,
    val sessionId: Long,
    val operation: GattOperation,
    val timeoutMillis: Long,
    val characteristic: UUID? = null,
    val autoConnect: Boolean = false
)

enum class BleConnectionState {
    IDLE,
    CONNECTING,
    DISCOVERING_SERVICES,
    SUBSCRIBING,
    STREAMING,
    RESUBSCRIBING,
    CLOSING,
    BACKOFF
}

/** The adapter's sensor log sink receives every transition, including GATT status and timeout causes. */
data class BleTransition(
    val atMillis: Long,
    val sessionId: Long,
    val from: BleConnectionState,
    val to: BleConnectionState,
    val reason: String
)
