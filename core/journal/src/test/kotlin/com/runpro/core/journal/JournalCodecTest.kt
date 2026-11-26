package com.runpro.core.journal

import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.Metric
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.SensorLossReason
import com.runpro.core.model.SourceId
import com.runpro.core.model.StopReason
import com.runpro.core.model.SwitchReason
import com.runpro.core.model.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JournalCodecTest {
    @Test
    fun `timer checkpoint has a stable protobuf encoding`() {
        val record = JournalRecord.Checkpoint(Timestamp(1, 2))
        assertEquals("520408011002", JournalCodec.encode(record).toHex())
        assertEquals(record, JournalCodec.decode(JournalCodec.encode(record)))
    }

    @Test
    fun `every record kind survives a round trip`() {
        for (record in everyRecord) {
            assertEquals(record, JournalCodec.decode(JournalCodec.encode(record)), "$record")
        }
    }

    @Test
    fun `every enum value has a wire code`() {
        val records = buildList {
            Metric.entries.forEach {
                add(event(RunEvent.SourceSwitched(at(1), it, STRAP, WATCH, SwitchReason.STALE)))
            }
            PauseReason.entries.forEach { add(event(RunEvent.Paused(at(1), it))) }
            LapTrigger.entries.forEach { add(event(RunEvent.LapMarked(at(1), it))) }
            SwitchReason.entries.forEach {
                add(event(RunEvent.SourceSwitched(at(1), Metric.HEART_RATE, null, WATCH, it)))
            }
            SensorLossReason.entries.forEach { add(event(RunEvent.SensorLost(at(1), STRAP, it))) }
            StopReason.entries.forEach { add(event(RunEvent.Stopped(at(1), it))) }
        }
        for (record in records) {
            assertEquals(record, JournalCodec.decode(JournalCodec.encode(record)), "$record")
        }
        assertEquals(Metric.entries.toSet(), everySample.map { it.metric }.toSet())
    }

    @Test
    fun `zero timestamps and zero values survive a round trip`() {
        val zero = Timestamp(0, 0)
        val records = listOf(
            JournalRecord.RunStarted(RunId("r"), zero),
            JournalRecord.Samples(listOf(HeartRateSample(zero, STRAP, HeartRate(0)))),
            JournalRecord.Event(RunEvent.Resumed(zero))
        )
        for (record in records) {
            assertEquals(record, JournalCodec.decode(JournalCodec.encode(record)))
        }
    }

    @Test
    fun `negative wall clock survives a round trip`() {
        val record = JournalRecord.RunStarted(RUN_ID, Timestamp(5, -1_000))
        assertEquals(record, JournalCodec.decode(JournalCodec.encode(record)))
    }

    // The golden bytes pin the protobuf wire shape (ADR 0006): renaming Kotlin types or properties must not
    // change them. Changing them needs a new journal version.
    @Test
    fun `run started has a stable protobuf encoding`() {
        val record = JournalRecord.RunStarted(RunId("run-1"), Timestamp(1, 2))
        // Record.run_started(1) { run_id(1) = "run-1", time(2) { elapsed_nanos(1) = 1, wall_millis(2) = 2 } }
        assertEquals("0a0d0a0572756e2d31120408011002", JournalCodec.encode(record).toHex())
    }

    @Test
    fun `a sample batch has a stable protobuf encoding`() {
        val sample = HeartRateSample(Timestamp(1, 2), SourceId.HEALTH_SERVICES, HeartRate(150))
        val source = "watch:health-services".toByteArray().toHex()
        // Record.samples(2) { samples(1) { metric(1) = 1, time(2) {1, 2}, source(3), int_value(5) = 150 } }
        val expected = "1224" + "0a22" + "0801" + "120408011002" + "1a15" + source + "289601"
        assertEquals(expected, JournalCodec.encode(JournalRecord.Samples(listOf(sample))).toHex())
    }

    @Test
    fun `a payload with no known record kind decodes to null`() {
        // Field 15, varint 1: a record kind added by a newer writer.
        assertNull(JournalCodec.decode(byteArrayOf(0x78, 0x01)))
    }

    @Test
    fun `an unknown enum code is rejected`() {
        // Record.run_stopped(8) { time(1) {}, reason(2) = 99 }
        val payload = byteArrayOf(0x42, 0x04, 0x0a, 0x00, 0x10, 99)
        assertFailsWith<IllegalArgumentException> { JournalCodec.decode(payload) }
    }

    @Test
    fun `an empty sample batch is not a record`() {
        assertFailsWith<IllegalArgumentException> { JournalRecord.Samples(emptyList()) }
    }

    @Test
    fun `run start is its own record kind`() {
        assertFailsWith<IllegalArgumentException> { JournalRecord.Event(RunEvent.Started(at(0))) }
    }
}
