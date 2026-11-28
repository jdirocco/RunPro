package com.runpro.wear.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.runpro.core.journal.JournalRecord
import com.runpro.core.journal.JournalWriter
import com.runpro.core.model.Cadence
import com.runpro.core.model.CadenceSample
import com.runpro.core.model.Distance
import com.runpro.core.model.DistanceSample
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.Pace
import com.runpro.core.model.RunEvent
import com.runpro.core.model.RunId
import com.runpro.core.model.SourceId
import com.runpro.core.model.StopReason
import com.runpro.core.model.Timestamp
import com.runpro.core.sync.FitAck
import com.runpro.core.sync.FitOutbox
import com.runpro.core.sync.RunHistoryEntry
import com.runpro.core.sync.RunHistoryStore
import com.runpro.core.sync.RunLapSummary
import com.runpro.core.sync.RunSummary
import com.runpro.core.sync.RunSyncState
import com.runpro.core.sync.SyncChannel
import com.runpro.core.sync.SyncProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en")
class RunHistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `empty history shows no runs yet`() {
        val state = MutableStateFlow(HistoryUiState(loading = false, runs = emptyList()))
        compose.setContent {
            RunHistoryHost(
                historyState = state,
                onRetryExport = {},
                onDelete = {},
                onClearFeedback = {},
                onClose = {}
            )
        }
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithText("No runs yet").assertIsDisplayed()
    }

    @Test
    fun `list displays runs with their sync states and detail shows summary and laps`() {
        val synced = sampleEntry("run-synced", 3_000_000L, RunSyncState.SYNCED)
        val waiting = sampleEntry("run-waiting", 2_000_000L, RunSyncState.PENDING_SYNC)
        val pending = sampleEntry("run-pending", 1_000_000L, RunSyncState.PENDING_EXPORT)
        val state = MutableStateFlow(
            HistoryUiState(loading = false, runs = listOf(synced, waiting, pending))
        )

        compose.setContent {
            RunHistoryHost(
                historyState = state,
                onRetryExport = {},
                onDelete = {},
                onClearFeedback = {},
                onClose = {},
                formatDate = { "Run ${it / 1_000_000L}" }
            )
        }

        compose.onNodeWithText("Synced").assertIsDisplayed()
        compose.onNodeWithText("Waiting for phone").assertIsDisplayed()
        scrollAndClick("Run 3")

        scrollTo("2.00 km · 10:00")
        scrollTo("Pace 5:00 /km")
        scrollTo("HR 150 / 168 bpm")
        scrollTo("Cadence 174 spm")
        scrollTo("Laps")
        scrollTo("Lap 1 · 1.00 km · 5:00 · 5:00 /km · 148 bpm")
        scrollTo("Lap 2 · 1.00 km · 5:00 · 5:00 /km · 152 bpm")
        compose.onNodeWithText("Retry export").assertDoesNotExist()
    }

    @Test
    fun `retry export creates verified FIT, requests phone sync, and updates state`() {
        val journals = tempDir.newFolder("journals").toPath()
        val exports = tempDir.newFolder("exports").toPath()
        val journal = writeStoppedJournal(journals, "run-retry")
        val syncRequests = AtomicInteger(0)
        val history = WatchHistory(
            store = RunHistoryStore(journals, exports),
            requestSync = { syncRequests.incrementAndGet() },
            executor = Executor { it.run() }
        )
        history.refresh()

        compose.setContent {
            RunHistoryHost(
                historyState = history.state,
                onRetryExport = history::retryExport,
                onDelete = history::delete,
                onClearFeedback = history::clearFeedback,
                onClose = {},
                formatDate = { "Morning Run" }
            )
        }

        compose.onNodeWithText("Pending export").assertIsDisplayed()
        compose.onNodeWithText("Morning Run").performClick()

        scrollAndClick("Retry export")

        assertEquals(1, syncRequests.get())
        assertTrue(exports.resolve("run-retry.fit").exists())
        // R3: journal remains until the phone acknowledges the FIT file.
        assertTrue(journal.exists())
        scrollTo("Waiting for phone")
        scrollTo("Exported · syncing to phone")
    }

    @Test
    fun `delete requires confirmation and cancel keeps files untouched`() {
        val journals = tempDir.newFolder("journals").toPath()
        val exports = tempDir.newFolder("exports").toPath()
        val journal = writeStoppedJournal(journals, "run-del")
        val history = WatchHistory(
            store = RunHistoryStore(journals, exports),
            requestSync = {},
            executor = Executor { it.run() }
        )
        history.refresh()

        compose.setContent {
            RunHistoryHost(
                historyState = history.state,
                onRetryExport = history::retryExport,
                onDelete = history::delete,
                onClearFeedback = history::clearFeedback,
                onClose = {},
                formatDate = { "Test Run" }
            )
        }

        compose.onNodeWithText("Test Run").performClick()
        scrollAndClick("Delete")

        compose.onNodeWithText("Delete run?").assertIsDisplayed()
        compose.onNodeWithText(
            "This run has not synced to the phone yet. Deleting it will permanently lose it."
        ).assertExists()

        // Cancel leaves the run intact.
        scrollAndClick("Cancel")
        assertTrue(journal.exists())
        compose.onNodeWithText("Test Run").assertExists()

        // Confirm removes the run from disk and returns to empty list.
        scrollAndClick("Delete")
        scrollAndClick("Confirm delete")
        assertFalse(journal.exists())
        compose.onNodeWithText("No runs yet").assertIsDisplayed()
    }

    @Test
    fun `delete confirmation for a synced run notes the phone already has a copy`() {
        val journals = tempDir.newFolder("journals").toPath()
        val exports = tempDir.newFolder("exports").toPath()
        writeStoppedJournal(journals, "run-synced")
        FitOutbox(journals, exports).sync { ackingChannel() }
        val history = WatchHistory(
            store = RunHistoryStore(journals, exports),
            requestSync = {},
            executor = Executor { it.run() }
        )
        history.refresh()

        compose.setContent {
            RunHistoryHost(
                historyState = history.state,
                onRetryExport = history::retryExport,
                onDelete = history::delete,
                onClearFeedback = history::clearFeedback,
                onClose = {},
                formatDate = { "Synced Run" }
            )
        }

        compose.onNodeWithText("Synced").assertIsDisplayed()
        compose.onNodeWithText("Synced Run").performClick()
        scrollAndClick("Delete")

        compose.onNodeWithText(
            "Delete this run from the watch? The phone already has a copy."
        ).assertExists()
        scrollAndClick("Confirm delete")
        assertFalse(exports.resolve("run-synced.fit").exists())
        compose.onNodeWithText("No runs yet").assertIsDisplayed()
    }

    @Test
    fun `WatchHistory runs disk work on the worker thread instead of the caller thread`() {
        val journals = tempDir.newFolder("journals").toPath()
        val exports = tempDir.newFolder("exports").toPath()
        writeStoppedJournal(journals, "run-worker")
        val workerName = AtomicReference<String>()
        val done = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor { task ->
            Thread(task, "runpro-history-test").apply { isDaemon = true }
        }
        val history = WatchHistory(
            store = RunHistoryStore(journals, exports),
            requestSync = {},
            executor = Executor { task ->
                pool.execute {
                    workerName.set(Thread.currentThread().name)
                    task.run()
                    done.countDown()
                }
            },
            onClose = pool::shutdown
        )
        history.use {
            it.refresh()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("runpro-history-test", workerName.get())
            assertEquals(listOf(RunId("run-worker")), it.state.value.runs.map { r -> r.runId })
        }
    }

    @Test
    fun `distance and pace format cleanly across boundaries`() {
        assertEquals("0 m", formatDistance(Distance.ZERO, Locale.US))
        assertEquals("850 m", formatDistance(Distance(850.0), Locale.US))
        assertEquals("1.00 km", formatDistance(Distance(1000.0), Locale.US))
        assertEquals("5.25 km", formatDistance(Distance(5250.0), Locale.US))
        assertEquals("5:00 /km", formatPace(Pace(299.8)))
        assertEquals("4:35 /km", formatPace(Pace(275.0)))
    }

    private fun scrollTo(label: String) {
        compose.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun scrollAndClick(label: String) {
        compose.onNode(hasText(label)).let { node ->
            runCatching {
                compose.onNode(
                    androidx.compose.ui.test.hasScrollAction()
                ).performScrollToNode(hasText(label))
            }
            node.performClick()
        }
    }

    private fun sampleEntry(id: String, startedAtMillis: Long, state: RunSyncState) =
        RunHistoryEntry(
            runId = RunId(id),
            startedAtMillis = startedAtMillis,
            syncState = state,
            summary = RunSummary(
                timer = 10.minutes,
                elapsed = 10.minutes,
                distance = Distance(2000.0),
                averagePace = Pace(300.0),
                averageHeartRate = HeartRate(150),
                maxHeartRate = HeartRate(168),
                averageCadence = Cadence(174.0),
                ascent = Distance(25.0),
                descent = Distance(20.0)
            ),
            laps = listOf(
                RunLapSummary(
                    index = 0,
                    timer = 5.minutes,
                    distance = Distance(1000.0),
                    averagePace = Pace(300.0),
                    averageHeartRate = HeartRate(148),
                    trigger = LapTrigger.DISTANCE
                ),
                RunLapSummary(
                    index = 1,
                    timer = 5.minutes,
                    distance = Distance(1000.0),
                    averagePace = Pace(300.0),
                    averageHeartRate = HeartRate(152),
                    trigger = LapTrigger.STOP
                )
            )
        )

    private fun writeStoppedJournal(journals: Path, id: String): Path {
        val file = journals.resolve("$id.journal")
        val originNanos = 10_000_000_000L
        val originWall = 1_764_064_800_000L
        fun ts(s: Int) = Timestamp(originNanos + s * 1_000_000_000L, originWall + s * 1000L)
        JournalWriter.create(file).use { writer ->
            writer.append(JournalRecord.RunStarted(RunId(id), ts(0)))
            for (s in 0..60) {
                writer.append(
                    JournalRecord.Samples(
                        listOf(
                            HeartRateSample(ts(s), SourceId.HEALTH_SERVICES, HeartRate(145)),
                            CadenceSample(ts(s), SourceId.HEALTH_SERVICES, Cadence(170.0)),
                            DistanceSample(ts(s), SourceId.HEALTH_SERVICES, Distance(s * 3.0))
                        )
                    )
                )
            }
            writer.append(JournalRecord.Event(RunEvent.Stopped(ts(60), StopReason.USER)))
        }
        return file
    }

    private fun ackingChannel(): SyncChannel = object : SyncChannel {
        private val sent = ByteArrayOutputStream()
        override val output: OutputStream = sent
        override val input: InputStream by lazy {
            val inputStream = DataInputStream(ByteArrayInputStream(sent.toByteArray()))
            val offer = SyncProtocol.readOffer(inputStream)
            val reply = ByteArrayOutputStream().also {
                SyncProtocol.writeAck(it, FitAck(offer.runId, offer.sha256, stored = true))
            }
            ByteArrayInputStream(reply.toByteArray())
        }
        override fun close() = Unit
    }
}
