package com.runpro.core.recording

import com.runpro.core.model.Metric
import com.runpro.core.model.Power
import com.runpro.core.model.PowerSample
import com.runpro.core.model.SourceId
import com.runpro.core.model.Timestamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CurrentPowerTest {
    @Test
    fun `admitted power reaches the snapshot then clears on silence without stopping the run`() {
        val machine = RecordingStateMachine()
        val time = Timestamp(0, 0)
        machine.handle(EngineInput.Prepare(time))
        machine.handle(EngineInput.Start(time))
        machine.handle(
            EngineInput.SampleReceived(PowerSample(time, SourceId("ble:power"), Power(240)))
        )
        assertEquals(Power(240), machine.state.current.power)
        machine.handle(EngineInput.Tick(Timestamp(5_000_000_000, 5000)))
        assertNull(machine.state.current.power)
        assertEquals(RunPhase.RUNNING, machine.state.phase)
    }

    @Test
    fun `power is published including zero and cleared when its source goes silent`() {
        val sample = PowerSample(Timestamp(0, 0), SourceId("ble:power"), Power(240))
        val current = CurrentValues().updatedWith(sample)
        assertEquals(Power(240), current.power)
        assertEquals(Power(0), current.updatedWith(sample.copy(power = Power(0))).power)
        assertNull(current.cleared(Metric.POWER).power)
        assertEquals(Power(240), current.cleared(Metric.SPEED).power)
    }
}
