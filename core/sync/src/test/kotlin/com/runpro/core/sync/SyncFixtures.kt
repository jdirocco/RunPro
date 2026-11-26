package com.runpro.core.sync

import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.SourceId
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

private const val ORIGIN_NANOS = 100_000_000_000L
private const val ORIGIN_WALL_MILLIS = 1_764_064_800_000L

/** [seconds] after the start of run number [run]; runs are a day apart on the wall clock. */
internal fun at(run: Int, seconds: Int) = Timestamp(
    elapsedRealtimeNanos = ORIGIN_NANOS + seconds * 1_000_000_000L,
    wallClockMillis = ORIGIN_WALL_MILLIS + run * 86_400_000L + seconds * 1000L
)

/** Writes the journal of a stopped run of [seconds] s (wrist heart rate and distance every second). */
internal fun writeStoppedRun(journals: Path, run: Int, seconds: Int = 60 + run * 7): Path {
    val runId = "run-%02d".format(run)
    val file = journals.resolve("$runId${FitOutbox.JOURNAL_SUFFIX}")
    JournalWriter.create(file).use { writer ->
        writer.append(JournalRecord.RunStarted(RunId(runId), at(run, 0)))
        for (s in 0..seconds) {
            writer.append(
                JournalRecord.Samples(
                    listOf(
                        HeartRateSample(
                            at(run, s),
                            SourceId.HEALTH_SERVICES,
                            HeartRate(130 + s % 20)
                        ),
                        DistanceSample(at(run, s), SourceId.HEALTH_SERVICES, Distance(s * 3.1))
                    )
                )
            )
        }
        writer.append(JournalRecord.Event(RunEvent.Stopped(at(run, seconds), StopReason.USER)))
    }
    return file
}

/** Writes the journal of a run that is still recording. */
internal fun writeOpenRun(journals: Path, runId: String): Path {
    val file = journals.resolve("$runId${FitOutbox.JOURNAL_SUFFIX}")
    JournalWriter.create(file).use { it.append(JournalRecord.RunStarted(RunId(runId), at(0, 0))) }
    return file
}

/** What goes wrong in one exchange between the watch and the phone. */
internal enum class Fault {
    NONE,

    /** No phone in range. */
    UNREACHABLE,

    /** The Data Layer refuses to open a channel. */
    OPEN_FAILS,

    /** The link breaks while the file is being sent: the phone gets only a prefix. */
    CUT_REQUEST,

    /** One byte of the file arrives changed. */
    CORRUPT_BYTE,

    /** The phone stores the file but the ack never reaches the watch. */
    ACK_LOST
}

/** A channel the phone gets: the bytes the watch sent (after the fault) and a buffer for the answer. */
internal class BufferChannel(bytes: ByteArray) : SyncChannel {
    val reply = ByteArrayOutputStream()
    override val input: InputStream = ByteArrayInputStream(bytes)
    override val output: OutputStream = reply
    override fun close() = Unit
}

/**
 * An in-process Data Layer: every [open] asks [faults] for the next fault. The phone runs [inbox] when the
 * watch starts reading the answer, i.e. after the whole offer was written, like over a real channel.
 */
internal class FaultyLink(
    private val inbox: FitInbox,
    private val random: java.util.Random = java.util.Random(0),
    private val faults: () -> Fault
) : PhoneLink {
    val outcomes = mutableListOf<FitInbox.Outcome>()

    /** Runs right after the phone answered, before the watch reads the ack. */
    var afterPhone: () -> Unit = {}

    override fun open(): SyncChannel? = when (val fault = faults()) {
        Fault.UNREACHABLE -> null
        Fault.OPEN_FAILS -> throw IOException("channel open failed")
        else -> WatchChannel(fault)
    }

    private inner class WatchChannel(private val fault: Fault) : SyncChannel {
        private val sent = ByteArrayOutputStream()
        override val output: OutputStream = sent
        override val input: InputStream by lazy { exchange() }
        override fun close() = Unit

        private fun exchange(): InputStream {
            val bytes = sent.toByteArray()
            val arriving = when (fault) {
                Fault.CUT_REQUEST -> bytes.copyOf(random.nextInt(bytes.size))

                Fault.CORRUPT_BYTE -> bytes.copyOf().also {
                    val i = bytes.size - 1 - random.nextInt(bytes.size / 2)
                    it[i] = (it[i].toInt() xor 0x40).toByte()
                }

                else -> bytes
            }
            val phone = BufferChannel(arriving)
            outcomes += inbox.receive(phone)
            afterPhone()
            return when (fault) {
                Fault.ACK_LOST, Fault.CUT_REQUEST -> ByteArrayInputStream(ByteArray(0))
                else -> ByteArrayInputStream(phone.reply.toByteArray())
            }
        }
    }
}
