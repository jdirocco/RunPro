# Architecture

## Modules

```
:core:model        samples, units, run/lap/event types (pure Kotlin)
:core:recording    RecordingEngine: source arbitration, laps, derived metrics, state machine
:core:journal      append-only journal, recovery reader
:core:fit          FIT encoder/decoder wrapper, verification
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

## Recording data flow

```
Health Services ExerciseClient ─┐
Android GATT (strap, footpod) ──┼─► SensorSource flows ─► RecordingEngine ─► Journal (disk)
SensorManager accelerometer ────┘                           │                    │
                                                           ▼                    ▼
                                                     UI state (StateFlow)   FIT export ─► phone
```

- `RecordingService` is a foreground service (`health|location|connectedDevice`) with an Ongoing Activity.
  It owns the engine. The UI binds to it and only reads `StateFlow`s.
- Source arbitration: per metric a priority list (e.g. HR: strap → wrist). A source is *stale* after a
  timeout; the engine switches to the next one and writes a source-switch event (R5).

## Recording engine (`:core:recording`)

- `RecordingStateMachine` is pure and deterministic: phases (Idle → Preparing → Running ⇄ Paused → Stopped,
  Reset back to Idle), run events, manual laps and totals (elapsed, timer, moving time, distance, average and
  max HR, average pace, cadence, ascent and descent). Inputs carry their own monotonic timestamps; samples
  are judged by their timestamp against the run timeline, so late batched samples still count.
- `RecordingEngine` runs it on its own thread (R6): `submit` never blocks, and the state is published as a
  `StateFlow<RunState>` that never waits for the UI. Design and defaults: ADR 0004.

## BLE connection state machine

```
Idle → Connecting → DiscoveringServices → Subscribing → Streaming
Streaming --(no data 5 s)--> Resubscribing --(fail)--> Reconnecting
any --(disconnect / GATT 133)--> Closing → Backoff(1,2,4,8 s, max 10 s) → Connecting (autoConnect=true)
```

All GATT operations go through one serial queue per device with timeouts. `BluetoothGatt.close()` is always
called before a new `connectGatt`. Every transition is written to the sensor log (exportable from the
diagnostics screen and attached to field reports).

## Journal format

Length-prefixed protobuf records with CRC32: `RunStarted`, `Sample(batch)`, `Lap`, `Pause`, `Resume`,
`SourceSwitch`, `SensorEvent`, `RunStopped`. A reader stops at the first corrupt record and keeps everything
before it. On app start, any journal without `RunStopped` is offered for recovery (and auto-recovered if the
service was killed during a run).
