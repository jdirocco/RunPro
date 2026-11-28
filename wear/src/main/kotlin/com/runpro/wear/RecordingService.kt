package com.runpro.wear

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.runpro.core.model.Sample
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SensorListener
import com.runpro.core.model.Timestamp
import com.runpro.core.recording.EngineInput
import com.runpro.core.recording.RecordingEngine
import com.runpro.core.recording.RecordingSession
import com.runpro.core.recording.RunPhase
import com.runpro.core.recording.RunState
import com.runpro.core.recording.exercise.ExerciseController
import com.runpro.core.recording.exercise.ExerciseStatus
import com.runpro.core.recording.imu.ImuCapture
import com.runpro.core.recording.imu.ImuMode
import com.runpro.core.recording.imu.ImuStatus
import com.runpro.core.recording.service.RunRecovery
import com.runpro.core.recording.service.ServiceCommand
import com.runpro.core.recording.service.ServiceStartPolicy
import com.runpro.core.recording.service.ServiceStartPolicy.Decision
import com.runpro.core.recording.service.WarmUpTimeout
import com.runpro.wear.health.HealthServicesExercise
import com.runpro.wear.imu.ImuPreferences
import com.runpro.wear.imu.WatchImu
import com.runpro.wear.sync.PhoneSync
import com.runpro.wear.sync.WatchStorage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service (`health|location|connectedDevice`) that owns the [RecordingEngine] of a run.
 *
 * - The engine runs on its own thread (R6); this service only submits inputs, which never blocks.
 * - [ForegroundController] requests only the service types whose permissions are granted, so
 *   `startForeground` cannot throw and take the run down.
 * - `START_STICKY`: when the system kills the process during a run it recreates the service with a null
 *   intent, and [RunRecovery] restores the run (the journal implementation is #9).
 * - The Health Services exercise follows the phases of the engine ([ExerciseController.follow], #11) and
 *   its samples go to the engine. A refused or lost exercise never pauses or stops the run (R4).
 * - The IMU capture ([ImuCapture], #33) follows the run on its own thread into `files/imu`, apart from
 *   the journal; it never affects the run (ADR 0012).
 * - A warm-up left alone ends after [WarmUpTimeout.DEFAULT_LIMIT] (#82): the service resets the engine and
 *   leaves the foreground, so GPS and the notification do not stay on. A recording run is never affected (R1).
 * - The UI binds to it and only reads [LocalBinder.state] and [LocalBinder.exercise].
 */
class RecordingService : Service() {
    private lateinit var engine: RecordingEngine
    private val main = Handler(Looper.getMainLooper())
    private val ticker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "runpro-tick").apply { isDaemon = true }
        }
    private lateinit var feedback: WorkoutFeedback
    private lateinit var notification: RunNotification
    private lateinit var exercise: ExerciseController
    private lateinit var foreground: ForegroundController
    private val imuThread = HandlerThread("runpro-imu").apply { start() }
    private lateinit var imu: ImuCapture
    private lateinit var battery: WatchBattery

    internal var createSession: () -> RecordingSession = {
        RecordingSession.create(
            WatchStorage.journals(this).toPath(),
            SystemClock::elapsedRealtimeNanos
        )
    }
    internal var createExercise: () -> ExerciseController = {
        HealthServicesExercise.create(this, ::now)
    }

    // Match the journal's ordinary free-byte policy; reclaimable cache is not reserved capacity.
    @SuppressLint("UsableSpace")
    internal var readAvailableBytes: () -> Long = {
        // Before the first Start the journals directory does not exist yet. Its parent is on the
        // same filesystem; never query an unrelated volume or create files on the main thread.
        val directory = WatchStorage.journals(this)
        (if (directory.exists()) directory else filesDir).usableSpace
    }

    /** Read and used only on the ticker thread; tests shorten the limit. */
    @Volatile internal var warmUpTimeout = WarmUpTimeout()

    /** The IMU setting of the current run, read when it starts (or is restored). */
    @Volatile private var imuMode = ImuMode.OFF
    private var recovery: RunRecovery = RunRecovery.NONE

    /** A run was started or restored; the engine may not have processed the start yet. */
    @Volatile private var requestedRun = false

    @Volatile private var lastPhase = RunPhase.IDLE

    @Volatile private var lastJournalFailed = false

    inner class LocalBinder : Binder() {
        val state: StateFlow<RunState> get() = engine.state
        val failure: StateFlow<Throwable?> get() = engine.failure
        val exercise: StateFlow<ExerciseStatus> get() = this@RecordingService.exercise.status
        val imu: StateFlow<ImuStatus> get() = this@RecordingService.imu.status
    }

    override fun onCreate() {
        super.onCreate()
        // A restored run keeps capturing with the current setting. The capture exists before the
        // engine, so it follows the first published state.
        imuMode = ImuPreferences.load(this)
        imu = WatchImu.capture(this, imuThread, ::now)
        feedback = WorkoutFeedback(this)
        engine = RecordingEngine(
            sessionFactory = { createSession() },
            onProcessed = ::onProcessed
        )
        notification = RunNotification(this).also { it.createChannel() }
        foreground = ForegroundController(this, notification)
        exercise = createExercise().also { it.start(EngineFeed()) }
        battery = WatchBattery(this) { percent ->
            engine.submit(EngineInput.Resources(now(), batteryPercent = percent))
        }.also { it.start() }
        ticker.scheduleWithFixedDelay(::tick, 0, 1, TimeUnit.SECONDS)
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = if (intent == null) ServiceCommand.RESTART else commandOf(intent.action)
        val phase = engine.state.value.phase.let {
            if (requestedRun && !ServiceStartPolicy.needsForeground(it)) RunPhase.PREPARING else it
        }
        val decision = ServiceStartPolicy.decide(command, phase)
        Log.i(TAG, "command $command in $phase: $decision")
        when (decision) {
            Decision.START_RUN -> startRun(warmUp = false)
            Decision.PREPARE_RUN -> startRun(warmUp = true)
            Decision.FORWARD -> forward(command)
            Decision.RECOVER -> recover()
            Decision.KEEP_RUNNING -> Unit
            Decision.STOP_SERVICE -> leave()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // The exercise is not ended here: after a restart its owner adopts it (ExerciseController).
        exercise.stop()
        battery.close()
        ticker.shutdownNow()
        engine.close()
        imu.close()
        imuThread.quitSafely()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** For tests and, later, the journal recovery (#9). */
    internal fun useRecovery(recovery: RunRecovery) {
        this.recovery = recovery
    }

    /**
     * A new run: with [warmUp] it stays in the warm-up (GPS and heart rate acquire, the start screen shows
     * their readiness) until [ServiceCommand.START] or [ServiceCommand.CANCEL]; otherwise it starts at once.
     */
    private fun startRun(warmUp: Boolean) {
        if (!foreground.enter(engine.state.value, fromBackground = false)) {
            leave()
            return
        }
        requestedRun = true
        imuMode = ImuPreferences.load(this)
        if (engine.state.value.phase == RunPhase.STOPPED) engine.submit(EngineInput.Reset)
        val now = now()
        engine.submit(EngineInput.Resources(now, batteryPercent = battery.percent))
        engine.submit(EngineInput.ConfigureWorkout(WorkoutPreferences.load(this)))
        engine.submit(EngineInput.Prepare(now))
        if (!warmUp) engine.submit(EngineInput.Start(now))
    }

    private fun forward(command: ServiceCommand?) {
        if (command == ServiceCommand.START &&
            !foreground.enter(engine.state.value, fromBackground = false)
        ) {
            leave()
            return
        }
        val now = now()
        val input = when (command) {
            ServiceCommand.START -> {
                engine.submit(EngineInput.Resources(now, batteryPercent = battery.percent))
                engine.submit(EngineInput.ConfigureWorkout(WorkoutPreferences.load(this)))
                EngineInput.Start(now)
            }

            ServiceCommand.CANCEL -> EngineInput.Reset

            ServiceCommand.PAUSE -> EngineInput.Pause(now)

            ServiceCommand.RESUME -> EngineInput.Resume(now)

            ServiceCommand.LAP -> EngineInput.MarkLap(now)

            ServiceCommand.STOP -> EngineInput.Stop(now)

            else -> return
        }
        engine.submit(input)
    }

    private fun recover() {
        if (!foreground.enter(engine.state.value, fromBackground = true)) {
            Log.w(TAG, "restarted after a kill; no foreground type allowed from the background")
            leave()
            return
        }
        if (recovery.recover(engine, now())) {
            engine.submit(EngineInput.Resources(now(), batteryPercent = battery.percent))
            requestedRun = true
            Log.i(TAG, "run restored after a restart")
        } else {
            Log.w(TAG, "restarted after a kill: no run to restore")
            leave()
        }
    }

    private fun leave() {
        requestedRun = false
        foreground.leave()
        stopSelf()
    }

    private fun tick() {
        val phase = lastPhase
        val time = now()
        // R1: only a warm-up is cancelled; the engine also ignores a reset once the run records.
        if (warmUpTimeout.shouldCancel(phase, time)) {
            Log.i(TAG, "warm-up left alone for ${warmUpTimeout.limit}: cancelled")
            engine.submit(EngineInput.Reset)
            return
        }
        if (ServiceStartPolicy.needsForeground(phase)) {
            engine.submit(EngineInput.Resources(time, battery.percent, readAvailableBytes()))
            engine.submit(EngineInput.Tick(time))
        }
    }

    /** Engine thread: must not block (R6), so the notification work goes to the main thread. */
    private fun onProcessed(state: RunState) {
        val previous = lastPhase
        val changed = state.phase != previous
        val newlyFailed = state.journalFailed && !lastJournalFailed
        lastPhase = state.phase
        lastJournalFailed = state.journalFailed
        // Non-blocking: the controller only queues requests to Health Services.
        if (changed) exercise.follow(state.phase)
        // Only queues to the IMU thread, and only when something changed.
        imu.follow(state, imuMode)
        main.post {
            feedback.update(state)
            if (newlyFailed && state.phase != RunPhase.STOPPED) {
                Log.e(TAG, "journal failed in ${state.phase}", engine.failure.value)
            }
            if (!changed) return@post
            when {
                ServiceStartPolicy.needsForeground(state.phase) -> notification.update(state)

                state.phase == RunPhase.STOPPED -> {
                    leave()
                    if (state.journalSaved) PhoneSync.request(this)
                    if (state.journalFailed) {
                        Log.e(
                            TAG,
                            "journal failed; retained file needs recovery",
                            engine.failure.value
                        )
                    }
                }

                // The warm-up was cancelled: no run, so no foreground and no notification.
                state.phase == RunPhase.IDLE && previous == RunPhase.PREPARING -> leave()

                else -> Unit
            }
        }
    }

    /** Health Services samples and sensor events go to the engine, which journals both (#60). */
    private inner class EngineFeed : SensorListener {
        override fun onSample(sample: Sample) {
            engine.submit(EngineInput.SampleReceived(sample))
        }

        override fun onEvent(event: SensorEvent) {
            engine.submit(EngineInput.SensorReceived(event))
        }
    }

    companion object {
        private const val TAG = "RecordingService"

        fun actionOf(command: ServiceCommand) = "com.runpro.wear.action.${command.name}"

        fun intent(context: Context, command: ServiceCommand): Intent =
            Intent(context, RecordingService::class.java).setAction(actionOf(command))
    }
}

private fun commandOf(action: String?): ServiceCommand? =
    ServiceCommand.entries.firstOrNull { action == RecordingService.actionOf(it) }

private fun now() = Timestamp(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
