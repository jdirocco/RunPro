package com.runpro.tools.replay

import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.SensorEvent
import com.runpro.tools.replay.Fixtures.strap
import com.runpro.tools.replay.Fixtures.t0
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TraceFormatTest {
    private fun encode(trace: Trace, compress: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        trace.writeTo(bytes, compress)
        return bytes.toByteArray()
    }

    private fun read(text: String): Trace =
        TraceReader.read(ByteArrayInputStream(text.toByteArray()))

    private val headerLine = """{"type":"trace_header","version":1,"name":"t"}"""

    @Test
    fun `a trace survives a round trip, compressed or not`() {
        for (compress in listOf(true, false)) {
            val decoded = TraceReader.read(ByteArrayInputStream(encode(Fixtures.trace, compress)))
            assertEquals(Fixtures.trace, decoded)
            assertFalse(decoded.truncated)
        }
    }

    @Test
    fun `the compressed form is gzip`() {
        val bytes = encode(Fixtures.trace, compress = true)
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
    }

    @Test
    fun `the line format is stable`() {
        // Golden lines: recorded field traces depend on this shape. Change it only with a new version.
        val text = encode(Fixtures.trace, compress = false).decodeToString()
        val lines = text.lines()
        assertEquals(
            """{"type":"trace_header","version":1,"name":"fixture",""" +
                """"description":"Three seconds of a run"}""",
            lines[0]
        )
        assertEquals(
            """{"type":"sensor_connected",""" +
                """"time":{"elapsedNanos":10000000000,"wallMillis":1764064800000},""" +
                """"source":"ble:AA:BB:CC:DD:EE:FF"}""",
            lines[1]
        )
        assertEquals(
            """{"type":"heart_rate",""" +
                """"time":{"elapsedNanos":10000000000,"wallMillis":1764064800000},""" +
                """"source":"ble:AA:BB:CC:DD:EE:FF","heartRate":120}""",
            lines[2]
        )
        assertEquals(Fixtures.trace.records.size + 2, lines.size, "header, records, final newline")
        assertEquals("", lines.last())
    }

    @Test
    fun `blank lines and unknown record types are skipped`() {
        val trace = read(
            "$headerLine\n\n" +
                """{"type":"future_thing","time":{"elapsedNanos":1,"wallMillis":2}}""" + "\n" +
                """{"type":"heart_rate","time":{"elapsedNanos":1,"wallMillis":2},""" +
                """"source":"ble:AA:BB:CC:DD:EE:FF","heartRate":99,"newField":true}""" + "\n"
        )
        assertEquals(1, trace.records.size)
        assertEquals(1, trace.skippedRecords)
    }

    @Test
    fun `a missing or unsupported header is rejected`() {
        assertFailsWith<TraceFormatException> { read("") }
        assertFailsWith<TraceFormatException> {
            read(
                """{"type":"heart_rate","time":{"elapsedNanos":1,"wallMillis":2},"source":"x","heartRate":1}"""
            )
        }
        val error = assertFailsWith<TraceFormatException> {
            read("""{"type":"trace_header","version":99,"name":"t"}""")
        }
        assertTrue("99" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a corrupt line in the middle is rejected with its line number`() {
        val error = assertFailsWith<TraceFormatException> {
            read(
                "$headerLine\n" +
                    """{"type":"heart_rate","time":{"elapsedNanos":1,"wallMillis":2},""" +
                    """"source":"ble:AA:BB:CC:DD:EE:FF","heartRate":-4}""" + "\n" +
                    """{"type":"gps_lost","time":{"elapsedNanos":1,"wallMillis":2},""" +
                    """"source":"w"}""" +
                    "\n"
            )
        }
        assertEquals(2, error.line)
    }

    @Test
    fun `a partial last line from an interrupted recording is dropped`() {
        val full = encode(Fixtures.trace, compress = false).decodeToString()
        val cut = full.substring(0, full.length - 20)
        val trace = read(cut)
        assertTrue(trace.truncated)
        assertEquals(Fixtures.trace.records.dropLast(1), trace.records)
    }

    @Test
    fun `a truncated gzip stream keeps every complete record before the cut`() {
        val many = Trace(
            Fixtures.header,
            (0 until 2_000).map { i ->
                TraceRecord.SampleRecord(
                    HeartRateSample(t0 + i.seconds, strap, HeartRate(100 + i % 80))
                )
            }
        )
        val bytes = encode(many, compress = true)
        val cut = bytes.copyOf(bytes.size / 2)
        val trace = TraceReader.read(ByteArrayInputStream(cut))
        assertTrue(trace.truncated)
        assertTrue(trace.records.isNotEmpty())
        assertEquals(many.records.subList(0, trace.records.size), trace.records)
    }

    @Test
    fun `a gzip stream that was flushed but never closed is readable`() {
        // What a recorder leaves behind when the process dies: no gzip trailer.
        val bytes = ByteArrayOutputStream()
        val recorder = TraceRecorder(bytes, Fixtures.header, compress = true)
        Fixtures.trace.records.forEach(recorder::record)
        recorder.flush()

        val trace = TraceReader.read(ByteArrayInputStream(bytes.toByteArray()))
        assertTrue(trace.truncated)
        assertEquals(Fixtures.trace.records, trace.records)
    }

    @Test
    fun `gzip input is detected from its content, not from a file name`() {
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write("$headerLine\n".toByteArray()) }
        assertEquals("t", TraceReader.read(ByteArrayInputStream(gz.toByteArray())).header.name)
    }

    @Test
    fun `a trace knows its sources, counts and duration`() {
        val trace = Fixtures.trace
        assertEquals(listOf(strap, Fixtures.watch), trace.sources)
        assertEquals(3.seconds, trace.duration)
        assertEquals(6, trace.samples.size)
        assertEquals(listOf(SensorEvent.Connected(t0, strap)), trace.events.take(1))
        assertEquals(mapOf(strap to 2, Fixtures.watch to 4), trace.sampleCountBySource())
    }

    @Test
    fun `traces are read from and written to files, compressed by extension`() {
        val dir = kotlin.io.path.createTempDirectory("trace-test")
        try {
            val gz = dir.resolve("run.jsonl.gz")
            val plain = dir.resolve("run.jsonl")
            Fixtures.trace.writeTo(gz)
            Fixtures.trace.writeTo(plain)
            assertEquals(0x1f, gz.toFile().readBytes()[0].toInt())
            assertTrue(plain.toFile().readText().startsWith("{"))
            assertEquals(Fixtures.trace, Trace.read(gz))
            assertEquals(Fixtures.trace, Trace.read(plain))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
