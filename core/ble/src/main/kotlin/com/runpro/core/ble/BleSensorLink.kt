package com.runpro.core.ble

import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import java.util.UUID

/** The bond state of the device, as the platform reports it. */
enum class BondState { NONE, BONDING, BONDED }

/**
 * A [GattPort] with the platform checks [BleSensorLink] needs between connect and discovery. Both
 * return promptly; a throw is treated as a rejected request.
 */
interface GattDevicePort : GattPort {
    /** Drops the platform's cached services of [sessionId]'s device; false when that is unsupported. */
    fun refreshServiceCache(sessionId: Long): Boolean

    fun bondState(): BondState
}

/** One line of the sensor log (#20 persists and exports it). */
data class BleLogLine(
    val atMillis: Long,
    val source: SourceId,
    val sessionId: Long,
    val message: String
) {
    override fun toString(): String = "$atMillis $source session=$sessionId $message"
}

/**
 * The link to one paired sensor: the [BleConnectionStateMachine] over the platform [GattDevicePort],
 * plus what the adapter adds around it (ADR 0017):
 *
 * - measurement bytes are parsed and delivered as samples only while the link streams, from the
 *   current session ([BleSamples] drops what the model cannot hold);
 * - [SensorEvent.Connected] when streaming starts, [SensorEvent.Disconnected] (with the GATT status of
 *   a disconnect) when a streaming link closes. Neither is a recording command (R4);
 * - [CACHE_REFRESH_AFTER] consecutive sessions that fail before streaming refresh the platform GATT
 *   cache before the next discovery;
 * - discovery waits while the device is bonding, within its normal deadline.
 *
 * Not thread safe: the owner calls everything on one serial dispatcher, off the recording/UI thread,
 * ticks at least every 100 ms, and passes times of one boot. [log] must be prompt and never throw.
 */
class BleSensorLink(
    val sensor: PairedSensor,
    private val port: GattDevicePort,
    private val log: (BleLogLine) -> Unit
) {
    private val gate = Gate()
    private val machine = BleConnectionStateMachine(gate, sensor.kind.measurements, ::transition)
    private var listener: SensorListener? = null
    private var now = Timestamp(0, 0)
    private var session = 0L
    private var connected = false
    private var disconnectStatus: Int? = null
    private var setupFailures = 0
    private var held: GattRequest? = null
    private var malformed = 0

    val state: BleConnectionState get() = machine.state

    fun start(listener: SensorListener, time: Timestamp) {
        now = time
        if (this.listener != null) return
        this.listener = listener
        machine.start(millis())
    }

    /** Closes the session; nothing reaches the listener afterwards. */
    fun stop(time: Timestamp) {
        now = time
        listener = null
        connected = false
        machine.stop(millis())
    }

    // A held request is executed here, outside the controller: report a platform throw as a failure.
    @Suppress("TooGenericExceptionCaught")
    fun tick(time: Timestamp) {
        now = time
        held?.let { request ->
            try {
                if (port.bondState() == BondState.BONDING) return@let
                held = null
                note(request.sessionId, "bonding finished, discovering services")
                port.execute(request)
            } catch (error: Exception) {
                held = null
                note(request.sessionId, "discovery rejected: ${error.message}")
                machine.completed(request.id, STATUS_REJECTED, millis())
            }
        }
        machine.tick(millis())
    }

    fun completed(requestId: Long, status: Int, time: Timestamp) {
        now = time
        machine.completed(requestId, status, millis())
    }

    fun disconnected(sessionId: Long, status: Int, time: Timestamp) {
        now = time
        disconnectStatus = status
        machine.disconnected(sessionId, status, millis())
        disconnectStatus = null
    }

    fun notified(sessionId: Long, characteristic: UUID, value: ByteArray, time: Timestamp) {
        now = time
        if (value.isEmpty() || listener == null) return
        if (characteristic in sensor.kind.measurements) {
            machine.notification(sessionId, characteristic, millis())
        }
        // The controller may just have closed a link whose resubscription expired.
        val target = listener?.takeIf { sessionId == session && connected } ?: return
        when (val result = GattParsers.parse(characteristic, value)) {
            is ParseResult.Parsed ->
                BleSamples.from(result.value, now, sensor.source).forEach(target::onSample)

            is ParseResult.Malformed -> if (malformed++ < MALFORMED_LOG_LIMIT) {
                note(sessionId, "malformed $characteristic: ${result.reason}")
            }

            null -> Unit
        }
    }

    private fun transition(change: BleTransition) {
        session = change.sessionId
        note(change.sessionId, "${change.from} -> ${change.to}: ${change.reason}")
        val target = listener
        when {
            change.to == BleConnectionState.STREAMING && !connected -> {
                connected = true
                setupFailures = 0
                malformed = 0
                target?.onEvent(SensorEvent.Connected(now, sensor.source))
            }

            change.to != BleConnectionState.CLOSING ||
                change.from == BleConnectionState.CLOSING -> Unit

            connected -> {
                connected = false
                target?.onEvent(SensorEvent.Disconnected(now, sensor.source, disconnectStatus))
            }

            target != null -> setupFailures++
        }
    }

    private fun note(sessionId: Long, message: String) {
        log(BleLogLine(millis(), sensor.source, sessionId, message))
    }

    private fun millis(): Long = now.elapsedRealtimeNanos / NANOS_PER_MILLI

    /** Adds the cache refresh and bond check in front of discovery; everything else passes through. */
    private inner class Gate : GattPort {
        override fun execute(request: GattRequest) {
            if (request.operation == GattOperation.DISCOVER_SERVICES) {
                if (setupFailures >= CACHE_REFRESH_AFTER) {
                    setupFailures = 0
                    val refreshed = port.refreshServiceCache(request.sessionId)
                    val outcome = if (refreshed) "refreshed" else "cannot refresh"
                    note(request.sessionId, "$outcome the GATT cache")
                }
                val bond = port.bondState()
                note(request.sessionId, "bond=$bond")
                if (bond == BondState.BONDING) {
                    held = request
                    note(request.sessionId, "discovery waits for bonding")
                    return
                }
            }
            port.execute(request)
        }

        override fun close(sessionId: Long) {
            held = null
            port.close(sessionId)
        }
    }

    companion object {
        /** The status a held request reports when the platform rejects it. */
        const val STATUS_REJECTED = -1

        /** Consecutive sessions failing before streaming that trigger a GATT cache refresh. */
        const val CACHE_REFRESH_AFTER = 2

        private const val MALFORMED_LOG_LIMIT = 5
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
