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

## Failure catalogue

Every real failure seen in the field gets a row here, a recorded trace in `testdata/traces/`, and a test.

| Date | Issue | What happened | Trace | Test |
|---|---|---|---|---|
