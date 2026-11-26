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
The journal double persists every input immediately; real durability/loss bounds (R1/R2), clean saving
under full storage (R7), source fallback/events (R5) and BLE reconnect timing (R8) remain unproven here.

## Failure catalogue

Every real failure seen in the field gets a row here, a recorded trace in `testdata/traces/`, and a test.

| Date | Issue | What happened | Trace | Test |
|---|---|---|---|---|
| 2026-11-26 | #10 | Setup screen: "Open settings" for battery did nothing on the Galaxy Watch 5 Pro. On Wear OS `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` resolves to a placeholder activity that closes at once, so the fallback never ran. Now opens the app details page. | none (UI, no sensor data) | `BatterySettingsTest` |
