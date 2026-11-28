# Architecture

## Modules

```
:core:model        samples, units, run/lap/event types (pure Kotlin)
:core:recording    RecordingEngine: source arbitration, laps, derived metrics, state machine
:core:journal      append-only journal, recovery reader
:core:fit          FIT encoder/decoder wrapper, verification
:core:sync         watch outbox / phone inbox for FIT files, ack protocol (transport behind SyncChannel)
:core:dynamics     IMU running dynamics estimators
:core:ble          BLE protocol parsers (0x180D, 0x1814, 0x1818, Stryd) and the connection state machine,
                   behind a GattPort interface (fakeable)
:wear              Wear OS app: Compose UI, RecordingService, Health Services adapter, Android GATT adapter
                   and sensor pairing
:mobile            Phone companion: Data Layer receiver, Strava, Health Connect
:tools:replay      trace format, recorder, replayer, chaos injector (JVM)
```

Rule: everything under `:core:*` and `:tools:*` is plain Kotlin/JVM and fully unit-testable.
Android code in `:wear` and `:mobile` is a thin adapter layer.

## Domain model (`:core:model`)

- `Sample`: one reading of one `Metric` (heart rate, location, distance, speed, cadence, power, elevation,
  acceleration) from one `SourceId` (`watch:health-services`, `watch:fused-location`, `watch:imu`, `ble:<address>`,
  `derived`).
- `Timestamp`: monotonic `elapsedRealtimeNanos` (durations, ordering within one boot) plus wall clock millis.
- `Run`: the ordered `RunEvent`s (started, paused, resumed, lap, source switched, sensor connected/lost,
  stopped); laps, start and stop are derived from them.
- Units are SI value classes with conversions for display and FIT. JSON via `ModelJson` (ADR 0003).
- `SensorEvent`: a change in a sensor's state reported by its adapter (connected, disconnected with the
  GATT status, GPS lost / acquired). Inputs to the engine, unlike `RunEvent`s, which are its decisions.
- `SensorSource` / `SensorListener`: the callback contract every producer of samples implements (Health
  Services, GATT, accelerometer, replayed trace). `start(listener)` until `stop()`; listeners never block (R6).
- `ImuBatch`: about one second of one IMU sensor (`ImuSensor`: accelerometer, gyroscope) at 50 Hz, as
  offsets and floats. Not a `Sample`: IMU data bypasses the engine (ADR 0012); `SensorListener.onImu`.

## Recording data flow

```
Health Services ExerciseClient ─┐
FusedLocationProvider (gated) ──┤
Android GATT (strap, footpod) ──┴─► SensorSource flows ─► RecordingEngine ─► Journal (disk)
                                                           │                    │
                                                           ▼                    ▼
                                                     UI state (StateFlow)   FIT export ─► phone
                                                           │ follow
SensorManager accel/gyro 50 Hz ─► ImuCapture (runpro-imu) ◄┘ ─► IMU stream files/imu/<run>.imu
```

- `RecordingService` is a foreground service (`health|location|connectedDevice`) with an Ongoing Activity.
  It owns the engine. The UI binds to it and only reads `StateFlow`s. The decisions are pure Kotlin in
  `:core:recording` (`service` package, ADR 0007): `ServicePermissions` picks only the service types whose
  permissions are granted (fewer from the background), `ServiceStartPolicy` maps start commands, and a
  `START_STICKY` restart (null intent) goes to `RunRecovery` (#9). The service supplies the journal
  directory (`files/journals`) and Android boot clock; the core creates one file per run on Start.
  `BatteryGuidance` lists the battery
  settings the setup screen asks the runner to change (battery optimisation, exempted with adb because the
  watch has no screen for it, and Samsung "Sleeping apps").
- Run UI (`RunProScreen`, #12): one screen by phase. Idle: setup checks and **Get ready**, which sends
  `PREPARE` (foreground, engine `Prepare`, Health Services warm-up). Preparing: GPS and heart-rate
  readiness from `ExerciseStatus`, **Start** (`START`, forwarded as engine `Start`) or **Back** (`CANCEL`,
  engine `Reset`; the service then leaves the foreground). `CANCEL` is ignored once the run records, and a
  journaled session accepts `Reset` only while it has no run, so a run is never discarded. A warm-up left
  alone ends after 10 minutes without Start (`WarmUpTimeout`, #82): the service ticker sends `Reset` and the
  service leaves the foreground, so GPS and the notification do not stay on; it never fires outside
  `PREPARING` (R1). Running/paused:
  time, distance, pace, heart rate, lap, pause/resume; **Stop** asks for a confirmation whose first choice
  keeps running. Stopped: a summary that says whether the journal was saved.
- Source arbitration: per metric a priority list (e.g. HR: strap → wrist; location: Health Services →
  fused location, ADR 0019). A source is *stale* after a
  timeout; the engine switches to the next one and writes a source-switch event (R5). `SourcePolicy`
  configures priorities/timeouts independently; fresh BLE measurements win by default. With no
  other eligible source a stale source keeps its metric while *silent*: the value and icon clear, no
  switch is recorded, and its late batches (Health Services with the screen off) still count (ADR 0016).
  The journal stores decisions before admitted samples; recovery/export replay them exactly. Cumulative
  distance/elevation baselines restart across switches. `RunState.activeSources` drives the HR
  strap/wrist icon. Defaults, late data and recovery semantics: ADR 0013.

## IMU capture (`:core:recording` imu / `:core:journal` / `:wear` imu)

`ImuCapture` follows the published `RunState` on its own executor (the `runpro-imu` thread): sensors on
while running, off while paused, stream closed on stop. `ImuPort` is the platform seam (`AndroidImuPort`:
`SensorManager`, 20 ms period, 1 s report latency, wake-up sensors preferred). `ImuBatcher` makes one
batch per sensor and second; `ImuStream` writes them as compact `JournalRecord.Imu` records (delta encoded,
packed, about 10 bytes a reading) into per-run segment files with the `RPIM` magic, apart from the run
journal: recovery and phone sync never touch them. No sensor, a storage error, free space under 100 MB or
the battery warning end the capture for that run only (`ImuStatus.problem`); the run never depends on it.
Old streams are pruned to 300 MB. The runner picks accelerometer, accelerometer + gyroscope or off on the
setup screen. `./gradlew :tools:replay:imuReport` summarises and exports streams copied from the watch.
Decisions: ADR 0012.

## Replay traces (`:tools:replay`)

A trace is gzip-compressed JSON Lines: a `trace_header` line, then one `Sample`, `SensorEvent` or
`imu_batch` per line in delivery order (ADR 0004, ADR 0012). `TraceRecorder` is a `SensorListener` that writes one (thread safe, gzip sync
flush); `TraceReader` reads it back and keeps every complete record of a trace whose recorder was killed.
`Replayer` exposes one fake `SensorSource` per source in the trace and delivers the records in file order,
paced by a `ReplayClock` at real time, N× or without waiting. Traces live in `testdata/traces/`.

```
testdata/traces/*.jsonl.gz ─► TraceReader ─► Trace ─► Replayer ─► ReplaySensorSource (one per SourceId)
                                                                          │ SensorListener
real or replayed SensorSource ─► TraceRecorder ─► *.jsonl.gz              ▼
                                                                 engine / test consumer
```

## BLE protocol parsers (`:core:ble`)

`GattParsers.parse(characteristic, bytes)` decodes Heart Rate Measurement (0x2A37: uint8/uint16 value,
sensor contact, energy expended, RR intervals in 1/1024 s), RSC Measurement (0x2A53: speed, cadence,
stride length, total distance, walking/running), Cycling Power Measurement (0x2A63, every optional field;
Stryd sends running power here) and Battery Level (0x2A19). UUIDs are in `GattUuids`. Parsers never
throw: a value that ends early is `ParseResult.Malformed` (for the sensor log); reserved flag bits and
extra trailing bytes are ignored, and a dangling half RR interval is skipped so the heart rate is kept.
`BleSamples.from` maps measurements to model samples and drops what the model cannot hold at the edge:
heart rate above 300 bpm and negative power are dropped, lost skin contact becomes 0 bpm, RSC cadence is
taken as steps per minute (footpods that send strides are a device quirk for #21), battery level gives no
sample.

## BLE connection state machine

```
Idle → Connecting → DiscoveringServices → Subscribing → Streaming
Streaming --(no data 5 s)--> Resubscribing --(fail)--> Reconnecting
any --(disconnect / GATT 133)--> Closing → Backoff(1,2,4,8 s, repeat 8 s) → Connecting (autoConnect=true)
```

All GATT operations go through one serial queue per device with timeouts. `BluetoothGatt.close()` is always
called before a new `connectGatt`. Every transition is written to the sensor log (exportable from the
diagnostics screen and attached to field reports).

`BleConnectionStateMachine` in `:core:ble` implements this policy behind `GattPort`, with a
`GattOperationQueue` (one in flight), per-request deadlines, session/request ids that reject stale
callbacks, and a required `BleTransition` log sink. The adapter serializes calls and ticks at least
every 100 ms using monotonic milliseconds. Each required measurement characteristic has its own
five-second watchdog; resubscription must restore real notifications within one second after its last
CCCD write. Backoff resets only after every required stream sends data. Failed close stays in Closing;
stop cancels pending work and retries. Defaults, port contract and R8 timing limits: ADR 0012.
Sensor-log persistence/export follows in #20.

## BLE adapter and pairing (`:core:ble` link, `:wear` ble)

```
SensorActivity ─► CompanionDeviceManager (or BleScan) ─► BlePreferences (PairedSensors, one per kind)
                                                                │ read when a run prepares
RecordingService ─► WatchBle (runpro-ble thread, 100 ms tick) ──┘
                        │ one per paired sensor
                        ▼
                  BleSensorLink ─► BleConnectionStateMachine ─► AndroidGattPort ─► BluetoothGatt (TRANSPORT_LE)
                        │ parsed samples, Connected / Disconnected(status)
                        ▼
                  engine feed (same as Health Services) ─► source arbitration (ADR 0013)
```

`BleSensorLink` (pure) adds to the controller what is policy: samples only while streaming and from the
current session, connect/disconnect `SensorEvent`s, a GATT cache refresh before discovery after two
consecutive sessions that never streamed, and discovery held while the device is bonding. The Android
`AndroidGattPort` posts every `BluetoothGattCallback` (one per session) to the BLE thread, so nothing
completes inline and closed sessions stay silent; the first connect uses `autoConnect=false`, reconnects
`true`. `WatchBle` follows the run phases: links from the warm-up to the stop. Pairing (**Sensors** on
the setup screen) uses a `CompanionDeviceManager` association where the watch supports it, which also
grants the companion background rights, and a short filtered scan otherwise or when the chooser fails
or closes (#115). Decisions: ADR 0018.

## Journal format (`:core:journal`)

Length-prefixed protobuf records with CRC32: `RunStarted`, `Sample(batch)`, `Lap`, `Pause`, `Resume`,
`SourceSwitch`, `SensorEvent`, `RunStopped`, the engine's sensor status (connected / lost), and timer
checkpoints during sensor silence. A reader stops at the first corrupt record and keeps everything
before it. On app start, any journal without `RunStopped` is offered for recovery (and auto-recovered if
the service was killed during a run). Details: ADR 0006 and ADR 0007.

```
file:    "RPJN" | version (int32) | record | record | ...
record:  length (int32) | CRC32 (int32) | protobuf Record { one of: run_started, samples, lap, ... }
```

- `JournalWriter` (thread safe, no own thread): run start and run events are written and fsynced before
  `append` returns (R1); samples and sensor events are buffered, written every 1 s and fsynced every 5 s,
  checked on each `append` and `tick()`. `JournalWriter.create` never overwrites a file.
- `JournalReader` returns `JournalContents`: the intact records, how the file ended (`Clean`, `Truncated`,
  `Corrupt`), the valid length, the run events and whether the run stopped. Intact records it cannot decode
  (newer writer) are skipped and counted.
- `JournalOutput` is the storage seam (`write` reaches the OS, `sync` reaches the flash); tests use a fake
  that models process death and power loss.

## Recording engine (`:core:recording`)

- `RecordingStateMachine` is pure and deterministic: phases (Idle → Preparing → Running ⇄ Paused → Stopped,
  Reset back to Idle), run events, manual laps and totals (elapsed, timer, moving time, distance, average and
  max HR, average pace, cadence, ascent and descent). Inputs carry their own monotonic timestamps; samples
  are judged by their timestamp against the run timeline, so late batched samples still count.
- `RecordingEngine` runs it on its own thread (R6): `submit` never blocks, and the state is published as a
  `StateFlow<RunState>` that never waits for the UI. Design and defaults: ADR 0005.

## Crash recovery (`:core:recording`)

`RecordingSession` connects the engine to `JournalWriter`: accepted run samples, run events and tick
checkpoints are persisted before publication. Supply it through `RecordingEngine.sessionFactory` so
creation/recovery and journal I/O run on the recording thread. Submit ticks at least once a second.
`EngineInput.SensorReceived` retains adapter reports while running or paused. The stopped journal is
synced and closed before publishing `RunState.journalSaved`. A stopped phase alone is not proof of a save.
Journal I/O failures disable further writes, retain the readable prefix and appear in
`RecordingEngine.failure` and `RunState.journalFailed`; in-memory processing and submission continue.
Factory/recovery or unexpected processing failures close submission. Reset after Stopped creates a fresh
session/file through the factory. Details and evidence: ADR 0011.

At startup, `JournalRecovery.scan(directory)` finds unfinished `.journal` files without changing them.
`JournalRecovery.restore(file)` rebuilds state and saves with `StopReason.RECOVERY` at the latest retained
time. A service restart that verifies the same boot and run ownership can instead select
`RecoveryMode.RESUME_SAME_BOOT`, retaining Running/Paused state and appending after the valid prefix.
After reboot or when boot continuity is unknown, offer to save; never compare timestamps across boots.
Unknown records are refused, completed journals are not reopened, and recovery never deletes a file.
The Android service/startup adapter follows in #10/#11; policy and evidence are in ADR 0007.

## Health Services exercise (`:core:recording` exercise / `:wear` health)

`ExerciseController` drives the `ExerciseClient` running exercise through `ExercisePort` (fakeable; the
`:wear` adapter is `HealthServicesExercisePort`) and is a `SensorSource` of `watch:health-services`.
`RecordingService` calls `follow(phase)` on every engine phase change: warm-up while preparing, start,
pause, resume, end when stopped. One request is in flight at a time; `ExerciseSession` picks the next.

```
engine phase ─► ExerciseController.follow ─► ExerciseSession.next ─► ExercisePort ─► ExerciseClient
                         ▲                                                              │ updates
                         └─ ExerciseSampleMapper (Samples) / ExerciseSignals (GPS events) ◄┘
```

- `ExercisePlan` requests only data types that are supported and permitted; no auto-pause.
- Our exercise still running after a restart is adopted; another app's exercise is reported during
  warm-up and taken over at the start; an exercise ended by the platform is reported in
  `ExerciseStatus`, loses the GPS fix and never pauses or stops the run (R4).
- The mapper drops invalid readings and sums distance deltas into the cumulative `DistanceSample`.
  Decisions: ADR 0011.

## Fused location (`:core:recording` location / `:wear` location)

Health Services binds to the watch's own `gps` provider and can stay acquiring near buildings; the fused
provider gets a phone-assisted fix there (#96). `FusedLocationController` is the `SensorSource` of
`watch:fused-location`: from the warm-up to the stop, with the location permission, it requests updates
through `LocationPort` (`:wear` `FusedLocationPort`: `PRIORITY_HIGH_ACCURACY`, 1 Hz, no batching, on the
`runpro-location` thread, R6).

```
FusedLocationProviderClient ─► FusedLocationPort ─► LocationFixGate (per run) ─► location/distance/speed
         (runpro-location)          │ LocationFix         │ rejected: coarse > 20 m, stale, mock, jump
                                    ▼                     ▼
                            GpsAcquired / GpsLost    engine arbitration: BLE → Health Services → fused
```

- The gate admits fixes with accuracy ≤ 20 m, measured ≤ 10 s before arrival, newer than the last one and
  at a plausible speed (a jump needs 3 agreeing fixes and then adds no distance). It derives its own
  cumulative distance: every fix while the Doppler speed says moving, otherwise only displacements beyond
  twice the combined accuracy, so standing still adds nothing.
- Arbitration (ADR 0013) prefers Health Services; every hand-over is a recorded switch (R5) and
  distance baselines restart, so the counters never add up. The warm-up GPS line shows the better of the
  two (`ExerciseStatus.withLocation`). Decisions: ADR 0019.

## Low resources (`:core:recording` / `:core:journal`)

`EngineInput.Resources` supplies battery and journal-filesystem capacity. `RunState.resources` exposes
warnings at <= 15% battery and < 50 MB (50,000,000 bytes). At <= 3% or zero space, Running/Paused stops
and the stop is synced before publication. Journal sessions query space before each valid start attempt.
`FileJournalOutput` holds a physically allocated 1 MiB reserve beside each journal. ENOSPC releases it,
rolls back a partial write and retries once; the session immediately saves with `STORAGE_FULL`. Other
I/O failures and exhausted reserves remain observable unsaved states with a recoverable prefix; the
engine continues processing inputs without writing to the failed journal. ADR 0008
explains the limits. The watch service registers for sticky and changing `ACTION_BATTERY_CHANGED`
readings and submits the latest battery before each Start, including after session reset. Its
`runpro-tick` worker refreshes journal-filesystem usable space every second while preparing, running
or paused, alongside the existing journal ticks. Disk queries never run on the main thread.
The UI observes the resource warnings and rejected-start reasons; summaries explain automatic stops.
Platform exercise/IMU shutdown follows the engine's published state, after persistence was handled;
foreground shutdown and phone sync follow on the main thread, with sync gated on `journalSaved`.
The existing failure flow and `journalFailed` state expose allocation/write failures without claiming
a save. Watch verification is scenario F11 in `FIELD_TESTING.md`.

## Phone sync (`:core:sync`)

The stopped journals in the watch's `files/journals` are the outbox. `FitOutbox.sync(link)` exports each
to a verified FIT file in `files/exports` (re-verifying an existing one after a restart), offers it on its
own `SyncChannel` and deletes the journal only when the phone's ack names the same run id and SHA-256 and
the file is unchanged (R3). `FitInbox.receive` on the phone stores the file durably in `files/runs` before
acking and recognises a run it already has, so retries never duplicate it. `:wear` adapts `ChannelClient`
(`DataLayerPhoneLink`) and schedules passes (`PhoneSync`: app open, run stop, phone capability change,
backoff 30 s to 15 min); `:mobile` receives in `FitReceiverService`. Both apps share the application id
`com.runpro.wear`. Protocol and decisions: ADR 0010.

Watch history (`RunHistoryStore` in `:core:sync`, `WatchHistory` and `RunHistoryHost` in `:wear`) reads
the same `files/journals` and `files/exports` directories on its own worker thread (`runpro-history`, R6),
deriving each finished run's sync state (`PENDING_EXPORT`, `PENDING_SYNC`, `SYNCED`, `EXPORT_FAILED`)
directly from disk without a separate index. Open journals are excluded and cannot be exported or deleted
from history. Retry export re-verifies or writes the FIT file without removing the journal (R3) and triggers
`PhoneSync`; delete requires explicit confirmation (with a warning when unsynced).

## Health Connect export (`:core:sync` healthconnect / `:mobile` healthconnect)

`FitToHealthConnectMapper` (`:core:sync`) maps a decoded `FitActivity` to a validated `HealthConnectRun`
(running session bounds, de-overlapped laps, active/pause segments, GPS route in `[startTime, endTime)`,
3,600-sample HR chunks, distance, and active-only cadence-derived steps). `HealthConnectExporter` scans
`files/runs/*.fit`, writes through `HealthConnectPort` with deterministic `clientRecordId`s, and tracks
exported SHA-256 hashes and versions in `files/health-connect/exported.tsv` without modifying or deleting
the FIT files. `:mobile` provides `HealthConnectRecordMapper`, `AndroidHealthConnectPort` (write-only
permissions via `HealthConnectClient`), `PermissionsRationaleActivity` (API 30–33 rationale and API 34+
`ViewPermissionUsageActivity` alias), and schedules background export from `FitReceiverService` after
`FitInbox` has stored and acknowledged the FIT file. Decisions: ADR 0014.

## Strava upload (`:core:sync` strava / `:mobile` strava)

`StravaAccount` (`:core:sync`) runs Strava OAuth with a durable single-use `state` and stores the tokens in
`files/strava`; `StravaUploader` uploads `files/runs/*.fit` as Run activities (`POST /uploads`, bounded
polling of `GET /uploads/{id}`), refreshes and rotates tokens, and keeps an atomic ledger of each file's
SHA-256, upload id and activity id so retries resume instead of duplicating. HTTP goes through
`StravaHttpTransport` (`HttpURLConnection` in production, fakes in tests). `:mobile` provides `StravaActivity`
(launcher: connect, opt-in auto upload, upload/retry pending runs, activity links; also the
`runpro://<host>/oauth/callback` target) and `StravaUploadRunner`, one background thread that
`FitReceiverService` calls after `FitInbox` has stored and acknowledged a run. Credentials come from
git-ignored `local.properties` for a private owner-only build. Decisions: ADR 0015.

## Auto-lap and intervals (`:core:recording`)

`ConfigureWorkout` selects a free run (default 1 km auto-lap) or warm-up, repeated work/recovery,
and cool-down by time or distance. The recording thread emits ordinary TIME/DISTANCE lap events;
manual laps do not advance the workout. `RunState.workout` exposes stage and remaining target.
Settings are retained in the journal start, and recovery/export replay existing marks without
regenerating them. The service handles countdown/transition haptics independently of the activity;
Compose observes countdown and lap summaries. Semantics and durability: ADR 0011.

## Settings and user profile (`:core:model` / `:wear`)

`WorkoutSettings.runSettings` contains optional HR/weight profile values, display units, auto-pause,
start countdown and debug tracing. The existing pre-start configuration path copies preferences into
the journal start; active runs and recovery use that immutable snapshot. Only fresh admitted speed
readings can auto-pause/resume; sensor silence cannot pause recording. Health Services continues to
collect speed during automatic pauses. The countdown remains in warm-up until START is sent.
`DebugTrace` writes optional raw replay JSONL on a bounded, separate worker to `files/traces` and fails
independently of recording. Units change presentation; measurements/export remain SI. ADR 0017.
