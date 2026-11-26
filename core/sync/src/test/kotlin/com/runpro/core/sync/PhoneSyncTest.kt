package com.runpro.core.sync

import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Issue #30 acceptance on the JVM: 20 runs reach the phone with no duplicates and none missing, through an
 * unreliable link (phone out of range, channels that fail to open, transfers cut at random byte offsets,
 * corrupted bytes, lost acks) and restarts of both apps. R3: a journal disappears from the watch only after
 * the phone stored exactly the verified FIT file.
 */
class PhoneSyncTest {
    @Test
    fun `20 runs sync once each through lost acks, broken transfers and restarts`(
        @TempDir dir: Path
    ) {
        for (seed in 1L..SEEDS) syncWithFaults(seed, dir.resolve("seed-$seed"))
    }

    private fun syncWithFaults(seed: Long, dir: Path) {
        val journals = dir.resolve("watch/journals").createDirectories()
        val exports = dir.resolve("watch/exports")
        val phoneRuns = dir.resolve("phone/runs")
        val runIds = (1..RUNS).map { writeStoppedRun(journals, it).name.removeSuffix(".journal") }
        val random = Random(seed)
        val faults = Fault.entries
        var outbox = FitOutbox(journals, exports)
        var inbox = FitInbox(phoneRuns)
        val outcomes = mutableListOf<FitInbox.Outcome>()
        var passes = 0

        while (journals.listDirectoryEntries("*.journal").isNotEmpty()) {
            check(++passes <= MAX_PASSES) { "not synced after $MAX_PASSES passes" }
            // Half of the exchanges go wrong, each fault equally likely.
            val link = FaultyLink(inbox, random) {
                if (random.nextBoolean()) {
                    Fault.NONE
                } else {
                    faults[
                        1 + random.nextInt(
                            faults.size - 1
                        )
                    ]
                }
            }
            val report = outbox.sync(link)
            outcomes += link.outcomes
            assertPhoneHasEveryDeletedJournal(runIds, journals, exports, phoneRuns)
            assertEquals(journals.listDirectoryEntries("*.journal").size, report.waiting)
            // Now and then a process dies between passes and starts with nothing in memory.
            if (random.nextInt(4) ==
                0
            ) {
                outbox = FitOutbox(journals, exports, utcOffsetSeconds = { 3600 })
            }
            if (random.nextInt(4) == 0) inbox = FitInbox(phoneRuns)
        }

        assertEquals(runIds.map { "$it.fit" }, inbox.stored().map { it.name })
        assertEquals(
            runIds.map {
                "$it.fit"
            }.toSet(),
            phoneRuns.listDirectoryEntries().map { it.name }.toSet()
        )
        for (id in runIds) {
            assertContentEquals(
                Files.readAllBytes(exports.resolve("$id.fit")),
                Files.readAllBytes(phoneRuns.resolve("$id.fit"))
            )
        }
        assertEquals(emptyList(), journals.listDirectoryEntries().map { it.name })
        // No run was ever stored twice or overwritten with other content.
        assertEquals(RUNS, outcomes.count { it is FitInbox.Outcome.Stored })
        assertTrue(outcomes.none { it is FitInbox.Outcome.Stored && it.replaced })
        // The faults happened: repeated offers of stored runs, refused and interrupted transfers.
        assertTrue(outcomes.any { it is FitInbox.Outcome.AlreadyStored }, "no duplicate offer")
        assertTrue(outcomes.any { it is FitInbox.Outcome.Rejected }, "no corrupted transfer")
        assertTrue(outcomes.any { it is FitInbox.Outcome.Interrupted }, "no cut transfer")
    }

    @Test
    fun `a perfect link delivers 20 runs in one pass, a second pass sends nothing`(
        @TempDir dir: Path
    ) {
        val journals = dir.resolve("journals").createDirectories()
        (1..RUNS).forEach { writeStoppedRun(journals, it) }
        val inbox = FitInbox(dir.resolve("phone"))
        val outbox = FitOutbox(journals, dir.resolve("exports"))

        val first = outbox.sync(FaultyLink(inbox) { Fault.NONE })
        val second = FaultyLink(inbox) { Fault.NONE }
        val again = outbox.sync(second)

        assertEquals(RUNS, first.delivered.size)
        assertEquals(0, first.waiting)
        assertEquals(emptyList(), first.problems)
        assertEquals(RUNS, inbox.stored().size)
        assertEquals(emptyList(), again.delivered)
        assertEquals(emptyList(), second.outcomes)
    }

    private fun assertPhoneHasEveryDeletedJournal(
        runIds: List<String>,
        journals: Path,
        exports: Path,
        phoneRuns: Path
    ) {
        for (id in runIds) {
            if (Files.exists(journals.resolve("$id.journal"))) continue
            val onPhone = phoneRuns.resolve("$id.fit")
            assertTrue(Files.exists(onPhone), "journal $id deleted but the phone has no file")
            assertContentEquals(
                Files.readAllBytes(exports.resolve("$id.fit")),
                Files.readAllBytes(onPhone)
            )
        }
    }

    private companion object {
        const val RUNS = 20
        const val SEEDS = 5L
        const val MAX_PASSES = 400
    }
}
