# Backlog

Source of truth for GitHub issues. `./rp sync` creates an issue for every block below that has no
`issue:` number yet and writes the number back. After an issue exists, edit it on GitHub, not here.

Format of a block:

```
### <key>: <title>
- milestone: <M0..M6>
- labels: <comma separated>
- depends: <comma separated keys>   (optional)
- issue:                            (filled by ./rp sync)

<body in markdown, ends at the next ### heading>
```

---

### m0-gradle-skeleton: Gradle multi-module project skeleton
- milestone: M0
- labels: area:build, type:task
- issue:

Create the Gradle project described in `docs/ARCHITECTURE.md`.

- Gradle wrapper, version catalog (`gradle/libs.versions.toml`), Kotlin 2.x, JDK 21 toolchain.
- Modules: `:core:model`, `:core:recording`, `:core:journal`, `:core:fit`, `:core:dynamics`, `:core:ble`,
  `:tools:replay` (Kotlin/JVM); `:wear` (Wear OS app, Compose for Wear, minSdk 30, targetSdk latest);
  `:mobile` (empty phone companion).
- `:wear` shows a single "RunPro" screen and installs on the Galaxy Watch 5 Pro.
- ktlint + detekt configured.

**Done when** `./gradlew check` passes locally and `./gradlew :wear:assembleDebug` builds.

### m0-ci: CI on GitHub Actions
- milestone: M0
- labels: area:build, type:task
- depends: m0-gradle-skeleton
- issue:

Turn on the Gradle steps already in `.github/workflows/ci.yml` (they run when `gradlew` exists): unit
tests, ktlint, detekt, Android lint, `:wear:assembleDebug`. Upload the debug APK and test reports as artifacts.

**Done when** a PR shows a green CI run and the APK artifact.

### m0-model: Core domain model
- milestone: M0
- labels: area:core, type:task
- depends: m0-gradle-skeleton
- issue:

`:core:model`: `Sample` (timestamped, typed metric + source id), `Metric` enum (HR, location, distance,
speed, cadence, power, elevation, accel…), `SourceId`, `Run`, `Lap`, `RunEvent` (pause, resume, lap,
source switch, sensor connected/lost), units. Monotonic time (elapsedRealtimeNanos) plus wall clock.

**Done when** types exist with unit tests for unit conversions and serialisation.

### m0-trace-format: Replay trace format, recorder and replayer
- milestone: M0
- labels: area:testing, type:task
- depends: m0-model
- issue:

`:tools:replay`: a line-based (JSONL, gzip) trace of timestamped sensor samples and BLE/GPS events.
A `Replayer` feeds a trace into `SensorSource` fakes at real or accelerated speed. Add 3 synthetic traces
to `testdata/traces/` (30 min easy run, intervals, strap dropout).

**Done when** a JUnit test replays a trace into a fake consumer and asserts sample counts.

### m0-chaos: Chaos injector for replay tests
- milestone: M0
- labels: area:testing, type:task
- depends: m0-trace-format
- issue:

Wrap a trace with injected faults: process death at time t (engine torn down, restarted from journal),
BLE disconnect / GATT 133 / silent stall, GPS loss, clock jump, storage full. Faults are declared in the
test so every reliability test (see `docs/RELIABILITY.md`) can reuse them.

**Done when** a sample test runs a trace with each fault type.

### m0-watch-dev-setup: Watch development setup and install script
- milestone: M0
- labels: area:build, type:docs
- issue:

Document Wi-Fi ADB on the Galaxy Watch 5 Pro (developer options, pairing, `adb connect`) in
`docs/WATCH_SETUP.md` and add `./rp install` that builds and installs `:wear` on the connected watch.

**Done when** the owner installs a debug build on the watch with one command.

### m1-engine-state-machine: RecordingEngine state machine, totals and laps
- milestone: M1
- labels: area:core, type:feature, reliability
- depends: m0-model
- issue:

States: Idle → Preparing → Running ⇄ Paused → Stopped. Manual laps. Totals: elapsed and moving time,
distance, avg/max HR, avg pace, cadence, ascent. Engine is pure Kotlin, runs on its own dispatcher (R6),
exposes `StateFlow<RunState>`.

**Done when** replay of the 30 min trace produces golden totals within tolerance.

### m1-journal: Append-only journal
- milestone: M1
- labels: area:core, type:feature, reliability
- depends: m0-model
- issue:

`:core:journal` as described in `docs/ARCHITECTURE.md`: length-prefixed records with CRC32, flush every
second, fsync every 5 s, reader stops at the first corrupt record. Tests: truncated file, corrupt tail,
R1 start latency.

**Done when** `JournalStartLatencyTest` and corruption tests pass.

### m1-recovery: Crash recovery from the journal
- milestone: M1
- labels: area:core, type:feature, reliability
- depends: m1-journal, m1-engine-state-machine, m0-chaos
- issue:

On start, find journals without `RunStopped`, rebuild engine state, resume recording (if the service was
killed during a run) or offer to save. Covers R2.

**Done when** `CrashRecoveryReplayTest` kills the engine at 20 random points and loses ≤ 5 s each time.

### m1-foreground-service: RecordingService and permissions
- milestone: M1
- labels: area:wear, type:feature, reliability
- depends: m0-gradle-skeleton
- issue:

Foreground service with types `health|location|connectedDevice`, Ongoing Activity, notification.
Permission flow: location (fine + background if needed), body sensors, BLE scan/connect, notifications,
activity recognition. On start, check battery optimisation / Samsung "Sleeping apps" and guide the user.
The service restarts recording after being killed (START_STICKY + recovery).

**Done when** the service survives screen off for 60 min on the watch and recovers after `adb shell am kill`.

### m1-health-services: Health Services exercise adapter
- milestone: M1
- labels: area:wear, area:sensors, type:feature
- depends: m1-foreground-service, m1-engine-state-machine
- issue:

`ExerciseClient` for `RUNNING`: capabilities check, `prepareExercise` warm-up (GPS fix and HR ready
indicators), start/pause/resume/end, data types: location, distance, speed, HR, steps-per-minute,
elevation. Detect when another app owns the exercise and report it. Map updates to `Sample`s.
Use the Health Services synthetic data provider on the emulator for an instrumented test.

**Done when** an emulator test records a synthetic run end to end.

### m1-basic-ui: Basic run UI
- milestone: M1
- labels: area:wear, area:ui, type:feature
- depends: m1-health-services
- issue:

Start screen with GPS and HR readiness, one run screen (time, distance, pace, HR), pause/resume, lap,
stop with confirmation (no accidental stop), summary screen. Large fonts, round screen.

**Done when** a full run can be recorded on the watch.

### m1-low-resource: Low battery and storage handling
- milestone: M1
- labels: area:core, type:feature, reliability
- depends: m1-journal
- issue:

Warn at 15 % battery, end and save cleanly at 3 % (R7). Check free space before start; warn under 50 MB.

**Done when** `LowResourceReplayTest` passes.

### m1-field-validation: Field validation of the recording core
- milestone: M1
- labels: field-test
- depends: m1-basic-ui, m1-recovery
- issue:

Owner runs scenarios F1, F3, F4 from `docs/FIELD_TESTING.md`, 10 runs in total, with forced kills.

**Done when** 10 runs recorded with 0 lost; every failure has a `field-report` issue.

### m2-ble-parsers: BLE protocol parsers
- milestone: M2
- labels: area:ble, type:feature
- depends: m0-model
- issue:

`:core:ble` parsers: Heart Rate Measurement (0x2A37, uint8/uint16, RR intervals, contact status),
RSC Measurement (0x2A53: speed, cadence, stride length, distance, walk/run), Cycling Power Measurement
(0x2A63, used by Stryd for running power), Battery Level. Tests with byte vectors from the specs.

**Done when** all spec byte vectors decode correctly.

### m2-gatt-state-machine: BLE connection state machine
- milestone: M2
- labels: area:ble, type:feature, reliability
- depends: m2-ble-parsers, m0-chaos
- issue:

The state machine in `docs/ARCHITECTURE.md` behind a `GattPort` interface: serial operation queue with
timeouts, watchdog (no data 5 s → resubscribe → reconnect), close-before-reconnect, backoff 1/2/4/8 s
(max 10 s). Every transition is logged. Tested against a fake GATT with injected disconnects, GATT 133,
silent stalls and slow service discovery.

**Done when** `BleReconnectStateMachineTest` (R8) passes for all injected faults.

### m2-android-gatt-adapter: Android GATT adapter and device association
- milestone: M2
- labels: area:ble, area:wear, type:feature, reliability
- depends: m2-gatt-state-machine, m1-foreground-service
- issue:

Real `GattPort` with `BluetoothGatt`: `TRANSPORT_LE`, `autoConnect=true` after the first connection,
CompanionDeviceManager association to keep background rights, bond state checks, refresh of the GATT
cache on repeated failures. Runs inside `RecordingService`.

**Done when** the owner's strap streams HR into the app on the watch for 30 min.

### m2-sensor-pairing-ui: Sensor pairing and source priorities
- milestone: M2
- labels: area:ble, area:ui, type:feature
- depends: m2-android-gatt-adapter
- issue:

Screen to scan, add, rename and remove sensors (filter by service UUID), show battery and signal. Per
metric source priority (e.g. HR: strap → wrist; cadence: footpod → watch; distance: GPS or footpod).
Warn when the sensor seems connected to another device.

**Done when** a strap and a footpod can be added and prioritised from the watch.

### m2-source-arbitration: Source arbitration and fallback in the engine
- milestone: M2
- labels: area:core, type:feature, reliability
- depends: m1-engine-state-machine, m2-gatt-state-machine
- issue:

Per-metric priority lists, stale timeouts, automatic fallback and return, `SourceSwitch` events in the
journal (R4, R5). UI shows the active source (strap icon vs wrist icon).

**Done when** `SensorDropoutReplayTest` and `SourceSwitchEventTest` pass.

### m2-diagnostics: Diagnostics screen and sensor log export
- milestone: M2
- labels: area:ble, area:wear, type:feature
- depends: m2-android-gatt-adapter
- issue:

Live view of every source (state, last sample age, RSSI, reconnect count). Rolling sensor log on disk.
"Record debug trace" setting records a replay trace of the run. Export logs and trace to the phone.

**Done when** a field run produces a trace that `:tools:replay` can play back.

### m2-stryd: Stryd footpod support
- milestone: M2
- labels: area:ble, type:feature
- depends: m2-android-gatt-adapter
- issue:

Running power (CPS), speed/cadence/distance (RSC), and Stryd extra metrics if available (form power,
ground time, vertical oscillation, leg spring stiffness) via its custom characteristics.

**Done when** a run with Stryd shows power and its distance matches Stryd within 1 %.

### m2-field-validation: Field validation of BLE sensors
- milestone: M2
- labels: field-test
- depends: m2-source-arbitration, m2-diagnostics
- issue:

Owner runs F2 and F5 with their strap (and F6 with a footpod if available). Compare with Gear Tracker on
the same route if possible.

**Done when** a 3 h run has no HR gap > 10 s except during intentional dropouts.

### m3-data-screens: Configurable data screens
- milestone: M3
- labels: area:ui, type:feature
- depends: m1-basic-ui
- issue:

Field catalogue (time, lap time, distance, pace current/avg/lap, HR, HR zone, cadence, power, elevation,
clock, battery). Layouts with 1–5 fields, several pages, rotary bezel to change page. Configured on the
watch (later also on the phone).

**Done when** the owner can reproduce their Gear Tracker screen layout.

### m3-autolap-intervals: Auto-lap and interval workouts
- milestone: M3
- labels: area:core, area:ui, type:feature
- depends: m1-engine-state-machine
- issue:

Auto-lap by distance (default 1 km) with a lap summary popup. Simple interval workouts (warm-up, N × work
/ recovery by time or distance, cool-down) with countdowns and haptics.

**Done when** replay of the intervals trace produces the right laps.

### m3-zones-alerts: Zones and alerts
- milestone: M3
- labels: area:core, area:ui, type:feature
- depends: m3-data-screens
- issue:

HR zones (from max HR or LTHR), pace zones. Alerts with vibration patterns: HR high/low, pace out of range,
sensor lost / back, GPS lost / back. Alerts are rate-limited.

**Done when** alerts fire correctly in replay tests and on the watch.

### m3-ambient-battery: Always-on display and battery budget
- milestone: M3
- labels: area:wear, area:ui, type:feature
- depends: m3-data-screens
- issue:

Ambient mode with reduced updates, Ongoing Activity chip on the watch face, tile to start a run.
Measure battery use with GPS + strap; target < 8 %/h on the Galaxy Watch 5 Pro.

**Done when** a 1 h run uses < 8 % battery.

### m3-settings: Settings and user profile
- milestone: M3
- labels: area:ui, type:feature
- depends: m1-basic-ui
- issue:

Units, max/resting HR, weight, auto-pause (off by default), countdown, debug trace toggle.

**Done when** settings persist and are applied to the next run.

### m4-fit-export: FIT export with verification
- milestone: M4
- labels: area:core, type:feature, reliability
- depends: m1-journal
- issue:

`:core:fit`: journal → FIT activity file (session, laps, records, events incl. source switches, device info
of the sensors). Decode the written file and compare with the journal before marking the run exported (R3).

**Done when** `ExportVerificationTest` passes and Strava/Garmin FIT validator accept the file.

### m4-history: Run history on the watch
- milestone: M4
- labels: area:ui, type:feature
- depends: m4-fit-export
- issue:

List of runs with sync state, run detail (summary, laps), retry export, delete (with confirmation).

**Done when** runs are listed with correct sync state.

### m4-phone-companion: Phone companion and Data Layer sync
- milestone: M4
- labels: area:mobile, type:feature, reliability
- depends: m4-fit-export
- issue:

`:mobile` app receives FIT files through the Wearable Data Layer (`ChannelClient`), stores them, sends an
ack; the watch keeps the journal until the ack arrives. Retries when the phone comes back in range.

**Done when** 20 runs sync with no duplicates and none missing.

### m4-strava: Strava upload
- milestone: M4
- labels: area:mobile, type:feature
- depends: m4-phone-companion
- issue:

OAuth on the phone, upload FIT, poll upload status, show the link. Auto-upload option. Activity type Run.

**Done when** a run appears on Strava with matching distance and time.

### m4-health-connect: Health Connect export
- milestone: M4
- labels: area:mobile, type:feature
- depends: m4-phone-companion
- issue:

Write `ExerciseSessionRecord` (running) with route, HR series, distance, steps, laps to Health Connect.

**Done when** the run is visible in Health Connect (and in Samsung Health if it reads it).

### m5-imu-capture: IMU capture during runs
- milestone: M5
- labels: area:dynamics, area:wear, type:feature
- depends: m1-foreground-service, m0-trace-format
- issue:

Record the accelerometer (and gyroscope if useful) at 50 Hz during runs into the trace and a compact
journal stream. Measure the battery cost.

**Done when** 10 reference runs are recorded with IMU data, plus Stryd or Galaxy Health data for the same runs.

### m5-dynamics-estimators: Running dynamics estimators
- milestone: M5
- labels: area:dynamics, type:feature
- depends: m5-imu-capture
- issue:

Pure Kotlin estimators from wrist IMU: cadence, stride length (with GPS speed), vertical oscillation,
ground contact time, asymmetry, regularity. Validate against the reference runs; document error and
tolerances in `docs/DYNAMICS.md`. Values are shown as estimates.

**Done when** cadence error < 2 spm and other metrics are within the documented tolerance.

### m5-post-run-analysis: Post-run analysis screens
- milestone: M5
- labels: area:dynamics, area:ui, type:feature
- depends: m5-dynamics-estimators, m4-history
- issue:

Watch: summary of dynamics per run and per lap with simple ratings. Phone: charts of pace, HR, cadence,
dynamics over the run. Dynamics also written to the FIT file as developer fields.

**Done when** the owner can review a run's analysis on the watch and the phone.

### m6-play-console: Play Console setup and store listing
- milestone: M6
- labels: area:release, type:task
- depends: m4-strava
- issue:

App signing, internal testing track for `:wear` and `:mobile`, privacy policy, data safety form,
health permissions declaration, store listing. Release workflow in GitHub Actions on tags.

**Done when** a tagged build reaches the internal testing track.

### m6-beta-soak: Two-week soak test
- milestone: M6
- labels: field-test, reliability
- depends: m6-play-console, m2-field-validation
- issue:

Daily use for two weeks as the only running app. Local crash log (no third-party analytics) exported with
the diagnostics.

**Done when** 0 lost runs and 0 unrecovered sensor dropouts in two weeks.

### m6-release-1-0: Release 1.0
- milestone: M6
- labels: area:release, type:task
- depends: m6-beta-soak
- issue:

Changelog, version 1.0.0, promote to production (or keep private), tag `v1.0.0`.

**Done when** 1.0.0 is published.
