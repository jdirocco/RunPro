package com.runpro.wear.health

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.exercise.ExerciseEndReason
import com.runpro.core.recording.exercise.ExercisePhase
import com.runpro.core.recording.exercise.Readiness
import com.runpro.core.recording.service.ServiceCommand
import com.runpro.wear.MainActivity
import com.runpro.wear.RecordingService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records a run end to end on the Wear OS emulator, whose Health Services produces synthetic heart rate,
 * location, distance, speed and cadence for a running exercise: `MainActivity` starts the real
 * `RecordingService`, which runs the real `ExerciseClient` through [HealthServicesExercisePort] and
 * `ExerciseController` into the recording engine, and stops it again.
 *
 * Health Services grants location and heart rate only while RunPro is in the foreground, which in a run
 * means the foreground service; that is why the test goes through it. See `docs/WATCH_SETUP.md`
 * ("Emulator test") for how to run it: `./gradlew :wear:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class HealthServicesSyntheticRunTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val binders = LinkedBlockingQueue<RecordingService.LocalBinder>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binders += binder as RecordingService.LocalBinder
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        val heartRate = if (Build.VERSION.SDK_INT >=
            BAKLAVA
        ) {
            READ_HEART_RATE
        } else {
            Manifest.permission.BODY_SENSORS
        }
        val permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
            heartRate
        )
        for (permission in permissions) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        context.startService(RecordingService.intent(context, ServiceCommand.STOP))
        context.unbindService(connection)
        scenario.close()
    }

    @Test
    fun recordsASyntheticRunEndToEnd() {
        // Started while the activity is in front, as the run screen does.
        scenario.onActivity {
            ContextCompat.startForegroundService(
                it,
                RecordingService.intent(it, ServiceCommand.START)
            )
        }
        context.bindService(
            Intent(context, RecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        val service =
            checkNotNull(binders.poll(STATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "service not bound"
            }

        waitFor("the exercise is active", service) {
            service.exercise.value.phase ==
                ExercisePhase.ACTIVE
        }
        waitFor("distance, heart rate and cadence in the run", service, RUN_TIMEOUT_MILLIS) {
            val totals = service.state.value.totals
            totals.timer >= MIN_RUN && totals.distance.meters > 0.0 &&
                totals.averageHeartRate != null && totals.averageCadence != null
        }
        val active = service.exercise.value
        assertEquals(Readiness.READY, active.heartRate)
        assertTrue("no data type missing: ${active.missing}", active.missing.isEmpty())

        context.startService(RecordingService.intent(context, ServiceCommand.STOP))
        waitFor("the run is stopped", service) { service.state.value.phase == RunPhase.STOPPED }
        waitFor("the exercise is ended", service) {
            service.exercise.value.phase ==
                ExercisePhase.ENDED
        }

        val ended = service.exercise.value
        assertEquals(ExerciseEndReason.USER, ended.endReason)
        assertNull(ended.error)
        val state = service.state.value
        assertEquals(true, state.run?.isStopped)
        assertTrue("timer ${state.totals.timer}", state.totals.timer.isPositive())
    }

    private fun waitFor(
        what: String,
        service: RecordingService.LocalBinder,
        timeoutMillis: Long = STATE_TIMEOUT_MILLIS,
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) {
                "timed out waiting for $what; exercise ${service.exercise.value}, " +
                    "run ${service.state.value.phase} ${service.state.value.totals}"
            }
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val BAKLAVA = 36
        const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        const val STATE_TIMEOUT_MILLIS = 30_000L
        const val RUN_TIMEOUT_MILLIS = 120_000L
        const val POLL_MILLIS = 500L
        val MIN_RUN = 15.seconds
    }
}
