# Reliability invariants

These rules are the reason RunPro exists. A change that weakens one of them is not merged.
Each invariant has (or will have) a test; the test names are listed so agents can find them.

| Id | Invariant | Test |
|---|---|---|
| R1 | A started run is persisted within 1 s of the start tap | `JournalStartLatencyTest` |
| R2 | After process death at any point, restart restores the run with ≤ 5 s data loss | `CrashRecoveryReplayTest` (chaos replay) |
| R3 | A journal is deleted only after its FIT file is decoded and matches it | `ExportVerificationTest` |
| R4 | A sensor dropout never pauses or stops the recording | `SensorDropoutReplayTest` |
| R5 | Every source switch (strap ↔ wrist, footpod ↔ GPS) is recorded as a FIT event | `SourceSwitchEventTest` |
| R6 | The recording path never runs on the main thread and never waits for the UI | `RecordingThreadingTest` |
| R7 | Storage full or low battery ends the run cleanly and saves it | `LowResourceReplayTest` |
| R8 | BLE reconnect after a dropout takes < 10 s when the sensor is back in range | `BleReconnectStateMachineTest` |

## Chaos replay infrastructure

`Replayer(trace, chaos = ChaosPlan(...), faultHandler = ...)` supplies deterministic process death,
BLE disconnect (including GATT 133), silent stall, GPS loss, wall clock jump and storage-full faults.
See `docs/GUIDE.md` and ADR 0006 for declarations and scheduling semantics.

- `ChaosReplayTest` and `ChaosTimelineTest` verify the fault stimuli, source isolation, boundary timing,
  wall/monotonic clock separation, delivery order and cancellation.
- `ChaosRecordingReplayTest` runs every fault type through `RecordingEngine`, replaces closed engines
  from a journal double, and checks uninterrupted timer progression for sensor faults.
- `FaultInjectingOutputStreamTest` verifies write failures retain the existing storage prefix.

These are reusable infrastructure for R2, R4, R7 and R8, not substitutes for the invariant tests above.
The journal double persists every input immediately. That infrastructure sample does not prove clean
saving under full storage (R7), source fallback/events (R5) or BLE reconnect timing (R8). Real journal
persistence and recovery evidence follows below; R7 evidence is in the low-resource section.

## Journal recovery evidence

- `CrashRecoveryReplayTest` kills real engines at 20 seeded random offsets in the committed easy-run
  trace and restores them from actual journal files, without an orderly flush. Each restart retains
  every accepted sample older than 5 s since the preceding restart, keeps the run id and continues with
  one start and one stop. Final timer and cumulative distance match an uninterrupted baseline (R2).
- `JournalRecoveryTest` checks 20 power-loss snapshots containing only synced bytes (≤ 5 s timer loss),
  paused state, laps, totals/source gates, sensor-silent timer checkpoints and save after reboot (R2).
- `JournaledEngineTest` proves start publication follows journal sync and disk work runs on the engine
  thread without blocking submission (R1/R6); persistence failures are exposed to callers.
- `JournalResumeTest` proves intact prefixes survive truncated/corrupt tails, no extra header is written,
  and completed or unknown-record journals are refused unchanged (R3 retention; FIT verification is #24).

These are JVM guarantees. Watch service restart/boot detection and physical validation follow in the
Android adapter and field-test issues. Recovery policy and clock assumptions: ADR 0007.

## Low-resource saving evidence

- `LowResourceReplayTest` replays sensors through real engines, writers and file outputs. At 3% battery,
  Running and Paused runs stop with `LOW_BATTERY`, retaining all accepted samples and laps. The 15%
  warning is observable without stopping. A chaos storage-full callback makes the real file adapter's
  next write store a partial frame and throw ENOSPC. The adapter releases its 1 MiB reserve, rolls back
  the partial write and retries; the engine syncs all pending records and one `STORAGE_FULL` stop.
  Tests read the clean journal before orderly shutdown (R7).
- `ReservedStorageTest` verifies partial-write rollback, reserve release on ENOSPC during sync, complete
  synced stop records, and no retry/reserve release for unrelated I/O errors. If even the reserve cannot
  finish saving, `LowResourceReplayTest` checks observable failure and no falsely published Stopped state.
- `LowResourceTest` checks exact 15%/3%/50 MB thresholds, start rejection/retry at critical resources,
  paused runs, repeated readings and pre-start checks against actual output capacity.

These are JVM guarantees, with the reserve limits in ADR 0008. Resource input delivery, visible warnings
and platform exercise shutdown on the watch are tracked in #71 after #10/#12; no field result is claimed.

## Failure catalogue

Every real failure seen in the field gets a row here, a recorded trace in `testdata/traces/`, and a test.

| Date | Issue | What happened | Trace | Test |
|---|---|---|---|---|
| 2026-11-26 | #10 | Setup screen: "Open settings" for battery did nothing on the Galaxy Watch 5 Pro. On Wear OS `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` resolves to a placeholder activity that closes at once, so the fallback never ran. Now opens the app details page. | none (UI, no sensor data) | `BatterySettingsTest` |
| 2026-11-26 | #10 | The app details page has no Battery entry on the watch either, and `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is also a placeholder: no screen can exempt RunPro. The setup screen now shows the adb command instead of a button. | none (UI, no sensor data) | `BatterySettingsTest` |
