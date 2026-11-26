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
:mobile            Phone companion: Data Layer receiver, Strava, Health Connect
:tools:replay      trace format, recorder, replayer, chaos injector (JVM)
```

Rule: everything under `:core:*` and `:tools:*` is plain Kotlin/JVM and fully unit-testable.
Android code in `:wear` and `:mobile` is a thin adapter layer.

## Domain model (`:core:model`)

- `Sample`: one reading of one `Metric` (heart rate, location, distance, speed, cadence, power, elevation,
  acceleration) from one `SourceId` (`watch:health-services`, `watch:imu`, `ble:<address>`, `derived`).
- `Timestamp`: monotonic `elapsedRealtimeNanos` (durations, ordering within one boot) plus wall clock millis.
- `Run`: the ordered `RunEvent`s (started, paused, resumed, lap, source switched, sensor connected/lost,
  stopped); laps, start and stop are derived from them.
- Units are SI value classes with conversions for display and FIT. JSON via `ModelJson` (ADR 0003).
- `SensorEvent`: a change in a sensor's state reported by its adapter (connected, disconnected with the
  GATT status, GPS lost / acquired). Inputs to the engine, unlike `RunEvent`s, which are its decisions.
- `SensorSource` / `SensorListener`: the callback contract every producer of samples implements (Health
  Services, GATT, accelerometer, replayed trace). `start(listener)` until `stop()`; listeners never block (R6).

## Recording data flow

```
Health Services ExerciseClient ─┐
Android GATT (strap, footpod) ──┼─► SensorSource flows ─► RecordingEngine ─► Journal (disk)
SensorManager accelerometer ────┘                           │                    │
                                                           ▼                    ▼
                                                     UI state (StateFlow)   FIT export ─► phone
```

- `RecordingService` is a foreground service (`health|location|connectedDevice`) with an Ongoing Activity.
  It owns the engine. The UI binds to it and only reads `StateFlow`s. The decisions are pure Kotlin in
  `:core:recording` (`service` package, ADR 0007): `ServicePermissions` picks only the service types whose
  permissions are granted (fewer from the background), `ServiceStartPolicy` maps start commands, and a
  `START_STICKY` restart (null intent) goes to `RunRecovery` (#9). `BatteryGuidance` lists the battery
  settings the setup screen asks the runner to change (battery optimisation, exempted with adb because the
  watch has no screen for it, and Samsung "Sleeping apps").
- Source arbitration: per metric a priority list (e.g. HR: strap → wrist). A source is *stale* after a
  timeout; the engine switches to the next one and writes a source-switch event (R5).

## Replay traces (`:tools:replay`)

A trace is gzip-compressed JSON Lines: a `trace_header` line, then one `Sample` or `SensorEvent` per line in
delivery order (ADR 0004). `TraceRecorder` is a `SensorListener` that writes one (thread safe, gzip sync
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
any --(disconnect / GATT 133)--> Closing → Backoff(1,2,4,8 s, max 10 s) → Connecting (autoConnect=true)
```

All GATT operations go through one serial queue per device with timeouts. `BluetoothGatt.close()` is always
called before a new `connectGatt`. Every transition is written to the sensor log (exportable from the
diagnostics screen and attached to field reports).

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
Persistence failures close submission and are exposed in `RecordingEngine.failure`.

At startup, `JournalRecovery.scan(directory)` finds unfinished `.journal` files without changing them.
`JournalRecovery.restore(file)` rebuilds state and saves with `StopReason.RECOVERY` at the latest retained
time. A service restart that verifies the same boot and run ownership can instead select
`RecoveryMode.RESUME_SAME_BOOT`, retaining Running/Paused state and appending after the valid prefix.
After reboot or when boot continuity is unknown, offer to save; never compare timestamps across boots.
Unknown records are refused, completed journals are not reopened, and recovery never deletes a file.
The Android service/startup adapter follows in #10/#11; policy and evidence are in ADR 0007.

## Low resources (`:core:recording` / `:core:journal`)

`EngineInput.Resources` supplies battery and journal-filesystem capacity. `RunState.resources` exposes
warnings at <= 15% battery and < 50 MB (50,000,000 bytes). At <= 3% or zero space, Running/Paused stops
and the stop is synced before publication. Journal sessions query space before each valid start attempt.
`FileJournalOutput` holds a physically allocated 1 MiB reserve beside each journal. ENOSPC releases it,
rolls back a partial write and retries once; the session immediately saves with `STORAGE_FULL`. Other
I/O failures and exhausted reserves remain observable failures with a recoverable prefix. ADR 0008
explains the limits. Watch resource monitoring and warning UI integration are tracked in #71.

## Phone sync (`:core:sync`)

The stopped journals in the watch's `files/journals` are the outbox. `FitOutbox.sync(link)` exports each
to a verified FIT file in `files/exports` (re-verifying an existing one after a restart), offers it on its
own `SyncChannel` and deletes the journal only when the phone's ack names the same run id and SHA-256 and
the file is unchanged (R3). `FitInbox.receive` on the phone stores the file durably in `files/runs` before
acking and recognises a run it already has, so retries never duplicate it. `:wear` adapts `ChannelClient`
(`DataLayerPhoneLink`) and schedules passes (`PhoneSync`: app open, run stop, phone capability change,
backoff 30 s to 15 min); `:mobile` receives in `FitReceiverService`. Both apps share the application id
`com.runpro.wear`. Protocol and decisions: ADR 0010.
