package com.runpro.core.recording

import com.runpro.core.ble.BleConnectionState
import com.runpro.core.ble.BleSensorKind
import com.runpro.core.ble.BleSensorLink
import com.runpro.core.ble.BondState
import com.runpro.core.ble.GattDevicePort
import com.runpro.core.ble.GattRequest
import com.runpro.core.ble.GattUuids
import com.runpro.core.ble.PairedSensor
import com.runpro.core.model.Metric
import com.runpro.core.model.Power
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * #21: a Stryd paired as [BleSensorKind.STRYD] feeds a run through [BleSensorLink]. Its RSC distance
 * wins over the watch's and its Cycling Power notifications become the current power.
 */
class StrydRunTest {
    private val stryd = PairedSensor.of("C0:FF:EE:00:00:21", BleSensorKind.STRYD, "Stryd")
    private val machine = runningMachine()
    private val port = Port()
    private val link = BleSensorLink(stryd, port) {}
    private val listener = object : SensorListener {
        override fun onSample(sample: Sample) {
            machine.handle(EngineInput.SampleReceived(sample))
        }

        override fun onEvent(event: SensorEvent) {
            machine.handle(EngineInput.SensorReceived(event))
        }
    }

    @Test
    fun `run distance follows the Stryd within 1 percent and its power is shown`() {
        link.start(listener, TestTime.at(0))
        while (link.state != BleConnectionState.STREAMING) {
            link.completed(port.requests.last().id, 0, TestTime.at(0))
        }
        // The pod was not reset at the start: 4321.0 m already on its counter, then 3.05 m/s.
        val startTenths = 43_210L
        val seconds = 600
        for (s in 0..seconds) {
            val time = TestTime.at(s)
            val tenths = startTenths + Math.round(30.5 * s)
            link.notified(1, GattUuids.RSC_MEASUREMENT, rsc(tenths), time)
            link.notified(1, GattUuids.CYCLING_POWER_MEASUREMENT, power(240 + s % 20), time)
            // The watch GPS reads 4 % short.
            machine.handle(dist(s, 2.928 * s))
            link.tick(time)
            machine.handle(tick(s))
        }
        val strydMeters = 30.5 * seconds / 10
        val state = machine.state
        assertEquals(stryd.source, state.activeSources[Metric.DISTANCE])
        assertEquals(strydMeters, state.totals.distance.meters, strydMeters * 0.01)
        assertEquals(stryd.source, state.activeSources[Metric.POWER])
        assertEquals(Power(240 + seconds % 20), state.current.power)
        assertEquals(RunPhase.RUNNING, state.phase)
    }

    /** RSC Measurement, running, total distance present: 3.05 m/s, 172 spm, [tenths] of a metre. */
    private fun rsc(tenths: Long): ByteArray {
        val speed = Math.round(3.05 * 256).toInt()
        return byteArrayOf(
            0x06,
            speed.toByte(),
            (speed shr 8).toByte(),
            172.toByte(),
            tenths.toByte(),
            (tenths shr 8).toByte(),
            (tenths shr 16).toByte(),
            (tenths shr 24).toByte()
        )
    }

    /** Cycling Power Measurement with instantaneous power only, as Stryd sends it. */
    private fun power(watts: Int): ByteArray =
        byteArrayOf(0x00, 0x00, watts.toByte(), (watts shr 8).toByte())

    private class Port : GattDevicePort {
        val requests = mutableListOf<GattRequest>()

        override fun execute(request: GattRequest) {
            requests += request
        }

        override fun close(sessionId: Long) = Unit

        override fun refreshServiceCache(sessionId: Long) = false

        override fun bondState() = BondState.NONE

        override fun removeBond() = false
    }
}
