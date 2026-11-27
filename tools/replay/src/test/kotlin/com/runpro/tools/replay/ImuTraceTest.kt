package com.runpro.tools.replay

import com.runpro.core.model.ImuBatch
import com.runpro.core.model.ImuReading
import com.runpro.core.model.ImuSensor
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.SourceId
import com.runpro.tools.replay.Fixtures.t0
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ImuTraceTest {
    private val imu = SourceId.WATCH_IMU

    private fun batch(second: Int, sensor: ImuSensor = ImuSensor.ACCELEROMETER) = ImuBatch(
        t0 + second.seconds,
        imu,
        sensor,
        listOf(ImuReading(0, 0.5f, -9.75f, 1.25f), ImuReading(20_000_000, 0.25f, -9.5f, 1f))
    )

    /** The fixture run with one accelerometer batch a second, in delivery (time) order. */
    private val trace = Trace(
        Fixtures.header,
        (
            Fixtures.trace.records +
                (0..3).map { TraceRecord.ImuRecord(batch(it)) } +
                TraceRecord.ImuRecord(batch(3, ImuSensor.GYROSCOPE))
            ).sortedBy { it.time }
    )

    @Test
    fun `IMU batches survive a trace round trip`() {
        for (compress in listOf(true, false)) {
            val bytes = ByteArrayOutputStream().also { trace.writeTo(it, compress) }.toByteArray()
            val decoded = TraceReader.read(ByteArrayInputStream(bytes))
            assertEquals(trace, decoded)
            assertEquals(5, decoded.imuBatches.size)
        }
    }

    @Test
    fun `the IMU line format is stable`() {
        val line = TraceCodec.encodeRecord(TraceRecord.ImuRecord(batch(0)))
        assertEquals(
            """{"type":"imu_batch",""" +
                """"time":{"elapsedNanos":10000000000,"wallMillis":1764064800000},""" +
                """"source":"watch:imu","sensor":"accelerometer","readings":[""" +
                """{"o":0,"x":0.5,"y":-9.75,"z":1.25},{"o":20000000,"x":0.25,"y":-9.5,"z":1.0}]}""",
            line
        )
    }

    @Test
    fun `the recorder writes what an IMU source delivers`() {
        val bytes = ByteArrayOutputStream()
        TraceRecorder(bytes, Fixtures.header, compress = false).use { recorder ->
            recorder.onImu(batch(0))
            recorder.onImu(batch(1, ImuSensor.GYROSCOPE))
        }
        val decoded = TraceReader.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(listOf(batch(0), batch(1, ImuSensor.GYROSCOPE)), decoded.imuBatches)
    }

    @Test
    fun `the replayer delivers IMU batches to the IMU source`() {
        val received = mutableListOf<ImuBatch>()
        val replayer = Replayer(trace)
        replayer.source(imu).start(ImuCollector(received))
        replayer.run()
        assertEquals(trace.imuBatches, received)
    }

    @Test
    fun `a silent stall of the IMU suppresses its batches`() {
        val received = mutableListOf<ImuBatch>()
        val replayer = Replayer(
            trace,
            chaos = ChaosPlan(ReplayFault.SilentStall(1.seconds, 1500.milliseconds, imu))
        )
        replayer.source(imu).start(ImuCollector(received))
        replayer.run()
        assertEquals(listOf(batch(0), batch(3), batch(3, ImuSensor.GYROSCOPE)), received)
    }

    private class ImuCollector(private val batches: MutableList<ImuBatch>) : SensorListener {
        override fun onSample(sample: Sample) = Unit

        override fun onEvent(event: SensorEvent) = Unit

        override fun onImu(batch: ImuBatch) {
            batches += batch
        }
    }
}
