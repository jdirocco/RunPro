package com.runpro.core.journal

import com.runpro.core.model.Acceleration
import com.runpro.core.model.AccelerationSample
import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.Elevation
import com.runpro.core.model.ElevationSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.LocationSample
import com.runpro.core.model.Metric
import com.runpro.core.model.PauseReason
import com.runpro.core.model.Power
import com.runpro.core.model.PowerSample
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorLossReason
import com.runpro.core.model.SourceId
import com.runpro.core.model.Speed
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.StopReason
import com.runpro.core.model.SwitchReason
import com.runpro.core.model.Timestamp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

internal const val ORIGIN_NANOS = 100_000_000_000L
internal const val ORIGIN_WALL_MILLIS = 1_764_064_800_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MILLIS_PER_SECOND = 1_000.0

/** The timestamp [seconds] after an arbitrary monotonic origin. */
internal fun at(seconds: Double): Timestamp = Timestamp(
    elapsedRealtimeNanos = ORIGIN_NANOS + Math.round(seconds * NANOS_PER_SECOND),
    wallClockMillis = ORIGIN_WALL_MILLIS + Math.round(seconds * MILLIS_PER_SECOND)
)

internal fun at(seconds: Int): Timestamp = at(seconds.toDouble())

internal val RUN_ID = RunId("run-1")
internal val WATCH = SourceId.HEALTH_SERVICES
internal val STRAP = SourceId.ble("AA:BB:CC:DD:EE:FF")

internal fun hr(seconds: Number, bpm: Int, source: SourceId = STRAP): Sample =
    HeartRateSample(at(seconds.toDouble()), source, HeartRate(bpm))

internal fun started(seconds: Number = 0) = JournalRecord.RunStarted(RUN_ID, at(seconds.toDouble()))

internal fun samples(vararg samples: Sample) = JournalRecord.Samples(samples.asList())

internal fun event(event: RunEvent) = JournalRecord.Event(event)

/** One of every sample type, every run event and every sensor event. */
internal val everySample: List<Sample> = listOf(
    HeartRateSample(at(1), STRAP, HeartRate(148)),
    LocationSample(
        at(1),
        WATCH,
        latitude = 45.464_2,
        longitude = 9.19,
        horizontalAccuracy = Distance(4.5),
        altitude = Elevation(122.0)
    ),
    LocationSample(at(2), WATCH, latitude = -33.86, longitude = 151.21),
    LocationSample(at(2), WATCH, latitude = 0.0, longitude = 0.0, altitude = Elevation(0.0)),
    DistanceSample(at(2), WATCH, Distance(1234.5)),
    SpeedSample(at(2), WATCH, Speed(3.2)),
    CadenceSample(at(3), STRAP, Cadence(176.0)),
    PowerSample(at(3), STRAP, Power(265)),
    ElevationSample(at(3), WATCH, Elevation(-3.5)),
    AccelerationSample(at(3), SourceId.WATCH_IMU, Acceleration(0.1, -9.7, 1.2))
)

internal val everyRunEvent: List<RunEvent> = listOf(
    RunEvent.Paused(at(10), PauseReason.MANUAL),
    RunEvent.Paused(at(11), PauseReason.AUTO),
    RunEvent.Resumed(at(12)),
    RunEvent.LapMarked(at(13), LapTrigger.MANUAL),
    RunEvent.LapMarked(at(14), LapTrigger.DISTANCE),
    RunEvent.LapMarked(at(15), LapTrigger.TIME),
    RunEvent.LapMarked(at(16), LapTrigger.STOP),
    RunEvent.SourceSwitched(at(17), Metric.HEART_RATE, STRAP, WATCH, SwitchReason.STALE),
    RunEvent.SourceSwitched(at(18), Metric.HEART_RATE, null, STRAP, SwitchReason.HIGHER_PRIORITY),
    RunEvent.SourceSwitched(at(19), Metric.CADENCE, STRAP, null, SwitchReason.USER),
    RunEvent.SensorConnected(at(20), STRAP),
    RunEvent.SensorLost(at(21), STRAP, SensorLossReason.DISCONNECTED),
    RunEvent.SensorLost(at(22), STRAP, SensorLossReason.NO_DATA),
    RunEvent.SensorLost(at(23), STRAP, SensorLossReason.ERROR),
    RunEvent.Stopped(at(24), StopReason.USER),
    RunEvent.Stopped(at(25), StopReason.LOW_BATTERY),
    RunEvent.Stopped(at(26), StopReason.STORAGE_FULL),
    RunEvent.Stopped(at(27), StopReason.RECOVERY)
)

internal val everySensorEvent: List<SensorEvent> = listOf(
    SensorEvent.Connected(at(30), STRAP),
    SensorEvent.Disconnected(at(31), STRAP),
    SensorEvent.Disconnected(at(32), STRAP, gattStatus = 133),
    SensorEvent.GpsLost(at(33), WATCH),
    SensorEvent.GpsAcquired(at(34), WATCH)
)

/** Every kind of record, with the run start first. */
internal val everyRecord: List<JournalRecord> = buildList {
    add(started())
    add(JournalRecord.Samples(everySample))
    everySample.forEach { add(JournalRecord.Samples(listOf(it))) }
    everyRunEvent.forEach { add(JournalRecord.Event(it)) }
    everySensorEvent.forEach { add(JournalRecord.Sensor(it)) }
}

/** A clock that only moves when told to. */
internal class FakeClock(@Volatile var nanos: Long = 0) : MonotonicClock {
    override fun nanos(): Long = nanos

    fun advance(duration: Duration) {
        nanos += duration.inWholeNanoseconds
    }

    fun advanceTo(duration: Duration) {
        nanos = duration.inWholeNanoseconds
    }
}

/**
 * A [JournalOutput] that remembers what would survive each kind of failure: [written] is what the OS has
 * (survives process death), [synced] is what is on the flash (survives power loss or a reboot).
 */
internal class FakeOutput : JournalOutput {
    private val bytes = ByteArrayOutputStream()
    private var syncedLength = 0
    var syncCount = 0
        private set
    var writeCount = 0
        private set
    var closed = false
        private set

    /** When set, the next write stores this many bytes and then throws. */
    var failAfterBytes: Int? = null

    val written: ByteArray get() = bytes.toByteArray()
    val synced: ByteArray get() = bytes.toByteArray().copyOf(syncedLength)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "closed" }
        val fail = failAfterBytes
        if (fail != null) {
            failAfterBytes = null
            this.bytes.write(bytes, offset, minOf(fail, length))
            throw IOException("No space left on device")
        }
        this.bytes.write(bytes, offset, length)
        writeCount++
    }

    override fun sync() {
        check(!closed) { "closed" }
        syncedLength = bytes.size()
        syncCount++
    }

    override fun close() {
        closed = true
    }
}

internal fun read(bytes: ByteArray): JournalContents =
    JournalReader.read(ByteArrayInputStream(bytes))

/** The bytes of a journal holding [records], written in one go. */
internal fun journalBytes(records: List<JournalRecord>): ByteArray {
    val output = FakeOutput()
    JournalWriter(output).use { writer -> records.forEach(writer::append) }
    return output.written
}

internal val Number.secondsDuration: Duration
    get() = (toDouble() * NANOS_PER_SECOND).toLong().nanoseconds

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
