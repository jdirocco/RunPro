# Reliability invariants

These rules are the reason RunPro exists. A change that weakens one of them is not merged.
Each invariant has (or will have) a test; the test names are listed so agents can find them.

| Id | Invariant | Test |
|---|---|---|
| R1 | A started run is persisted within 1 s of the start tap | `JournalStartLatencyTest`, `RecordingJournalTest` |
| R2 | After process death at any point, restart restores the run with ≤ 5 s data loss | `CrashRecoveryReplayTest` (chaos replay) |
| R3 | A journal is deleted only after its FIT file is decoded and matches it | `ExportVerificationTest`, `PhoneSyncTest` |
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
- `RecordingJournalTest` checks real threaded start-input-to-file latency (< 1 s), one-second sample
  flushing and five-second sync during sensor silence, exact synthetic run read-back, closure before
  stop publication, distinct files for successive runs, and no leftover journal/reserve files after a
  rejected start (while `JournalWriterTest` and `ReservedStorageTest` confirm `abort()` removes only
  newly created files that never received `RunStarted`, preserving started/resumed journals under R3).
  `EngineJournalReplayTest` compares the
  committed dropout trace's accepted samples, adapter events, run events, laps and totals after reading
  the actual engine-written journal. `RecordingServiceTest` checks the watch service writes these files
  to the phone outbox and releases reserves on stop (R1/R2/R3/R6; ADR 0011).
- `JournalResumeTest` proves intact prefixes survive truncated/corrupt tails, no extra header is written,
  and completed or unknown-record journals are refused unchanged (R3 retention; FIT verification is below).

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
  finish saving, `LowResourceReplayTest` checks observable failure and no false `journalSaved` state.
- `JournalFailureTest` injects write, sync, close and file creation errors. The engine keeps processing
  after journal I/O failure, never reuses the failed writer, and exposes `failure` and `journalFailed`.
  A stopped phase alone is not a successful save; the service checks `journalSaved` before requesting
  phone sync. Recovery SAVE still propagates errors. This refines the original fail-closed behavior
  without claiming persistence when storage is unavailable (R7, ADR 0011).
- `LowResourceTest` checks exact 15%/3%/50 MB thresholds, start rejection/retry at critical resources,
  paused runs, repeated readings and pre-start checks against actual output capacity.

- `WatchBatteryTest` checks immediate sticky battery delivery, runtime changes, malformed readings and
  receiver cleanup. `RecordingServiceResourcesTest` checks start rejection/retry, subsequent sessions,
  running/paused battery stops, one-second storage polling off the main thread, and storage-full stops
  into real journals. The fake exercise observes `journalSaved` before END and before the foreground
  service leaves. Creation/write failures remain observable and never claim a successful save.
  `RunProScreenTest` checks warning thresholds, paused visibility, clearing and rejected-start messages.

These are JVM/Robolectric guarantees, with the reserve limits in ADR 0008. Physical resource input
delivery, warning readability and platform shutdown are the owner scenario F11; no field result is claimed.

## Export verification evidence

- `ExportVerificationTest` writes FIT files from real journals (easy run, intervals, strap dropout,
  every record kind, crash-recovered run, wall clock jump), reads each back from storage, decodes it with
  the Garmin FIT SDK and compares run id, devices, every run/sensor event, every record, laps and
  session with the journal. Only a match yields a `VerifiedExport`; a mismatch or damaged file is
  removed, and the journal bytes never change (R3). Source switches are FIT events carrying the exact
  RunPro event (R5; engine-generated switches are also covered by `SourceSwitchEventTest`).
- Journal deletion is in the phone sync below: only after the phone acknowledges a file whose
  `VerifiedExport` hash still matches. Strava/Garmin validator acceptance is an owner check
  (`FIELD_TESTING.md`). Format and mapping: ADR 0009.

## Phone sync evidence

- `PhoneSyncTest` sends 20 runs through an in-process link that fails half of the exchanges (phone out of
  range, channel open failure, transfer cut at a random byte, a corrupted byte, a lost ack) and restarts
  the watch and phone objects at random, for 5 seeds. After every pass each deleted journal's FIT file is
  on the phone byte for byte; at the end all 20 are there exactly once, no journal, reserve or partial file
  remains, and no run was stored twice or overwritten (R3).
- `FitOutboxTest`: no phone, a run still recording, a negative ack, an ack for other content or another
  run, and a FIT file changed after verification all keep the journal; after a restart in another time
  zone the earlier file is reused, so the phone sees the same hash.
- `FitInboxTest`: cut transfers store and acknowledge nothing, a hash mismatch gets a negative ack, a repeat
  offer is acked without a second file, partial files of a dead process are removed, run ids become safe,
  distinct file names.
- `ExportVerificationTest` covers `FitExporter.verifyExisting` (same bytes, a damaged file is removed).

These are JVM guarantees. The `ChannelClient` adapters and the 20-run check with the real watch and phone
are the owner field test #75; live journals are wired by #60 (ADR 0011); sync protocol is ADR 0010.

## Health Services evidence

- `ExerciseControllerTest`: an exercise ended by another app (`AUTO_END_SUPERSEDED`) or by a revoked
  permission is reported and loses the GPS fix, but later pause/resume of the run send nothing and the
  run is never paused or stopped by the adapter (R4). Failed checks, starts and registrations are reported
  and not retried in a loop; our exercise is adopted after a restart instead of started twice; the update
  listener registers after `prepare`/`start` completes so initial sensor availability is not dropped;
  requests never overlap and commands never block (R6: the listener runs on the Health Services thread).
- `ExerciseSampleMapperTest`: invalid readings are dropped at the edge, distance deltas add up, both
  clocks are set from the monotonic time.
- `HealthServicesSyntheticRunTest` (emulator, `./gradlew :wear:connectedDebugAndroidTest`) records a run
  with synthetic data through `RecordingService` and the real `ExerciseClient`, then stops it.

Watch behaviour (real GPS and wrist HR, another app taking over) is a field result (#14). ADR 0011.

## Workout evidence

- `WorkoutTest` checks real journal settings/laps survive recovery and rebuild identically for export,
  without duplicate automatic marks (R2/R3). Auto-laps count admitted active distance only; pauses,
  rejected sources and source resets do not fabricate laps, and interval completion keeps recording (R4).
- `IntervalWorkoutReplayTest` replays the committed 45-minute intervals trace into fourteen timed laps.
  Automatic marks use the existing synced event path; `JournaledEngineTest`, `RecordingThreadingTest`
  and `LowResourceReplayTest` continue to cover start-before-publication, worker isolation and emergency
  saves (R1/R6/R7). No sensor arbitration or BLE reconnect policy is changed (R5/R8).

## IMU capture evidence

The IMU capture (ADR 0012) is kept out of the recording path, so it cannot weaken R1–R8:

- R1/R2/R3: IMU batches never enter the engine or the run journal. They go to separate stream files
  with their own magic (`RPIM`), which the journal reader, recovery and phone sync refuse or never list.
  `ImuStreamTest` checks the separation, that a restarted capture opens a new segment without rewriting
  the old one, that process death keeps everything flushed (1 s) and that a cut tail keeps every complete
  batch. `RecordingServiceImuTest` records a run with a shadow accelerometer and checks the journal is
  where it was and stopped, and that a watch without motion sensors or with IMU off saves the run as usual.
- R6: `ImuCapture.follow` only queues work for the IMU thread, and only when the target state changed
  (`ImuCaptureTest`: `follow only queues work...`). Sensor callbacks and stream writes run on that thread.
- R4/R7: no sensor, a platform refusal, a storage failure, free space under 100 MB and the battery warning
  each end the capture for that run, keep what was written and never throw (`ImuCaptureTest`); the
  100 MB floor leaves the journal its space. Old streams are pruned to 300 MB, never the current run's.
- `ImuCodecTest` bounds the encoding (at most 12 bytes a reading, several times smaller than samples) and
  the quantisation (1 µs, 0.001 m/s²); `ImuTraceTest` and `ImuReportTest` cover traces and the report tool.

Gaps, real rate and battery cost on the Galaxy Watch 5 Pro are field results (F10), not claimed here.

## BLE connection evidence

- `BleReconnectStateMachineTest` injects normal disconnects, GATT 133, silent stalls, slow discovery,
  missing callbacks, platform request rejection and failed close through a fake `GattPort`. It rejects
  overlapping requests and connect-before-close, checks serial subscription writes and their
  cancellation, stale/duplicate callbacks, independent devices, stop/restart and transition logging.
- Five-second silence is checked per required measurement characteristic; battery readings and other
  live streams cannot conceal a stall. Successful resubscription needs actual notifications, including
  a deadline check between ticks. Retry grows 1/2/4/8 s and resets on data, not CCCD success.
- R8 timing tests cover dropout recovery, failed/silent resubscription and 20 seeded return-to-range
  offsets during capped backoff with 1.2 s discovery. Restored data arrives in < 10 s with the fake's
  responsive setup. A platform/radio that cannot complete setup or close is outside this JVM guarantee.
  Policy and budgets: ADR 0012. Physical validation remains #22, scenarios F1/F2. The controller never issues recording commands; R4 recording evidence remains
  `SensorDropoutReplayTest` and `ChaosRecordingReplayTest`.

## BLE adapter evidence (#17, ADR 0017)

- R8: `BleSensorLinkTest` reconnects a strap after GATT 133 with `autoConnect=true` and streams again in
  < 10 s through the link (events, parsing and the bond/cache checks in the path). A cache refresh after
  repeated setup failures and discovery held while bonding stay within the controller's deadlines, so
  neither can stall recovery; a rejected held discovery recovers instead of hanging.
- R4: the link and `WatchBle` only produce samples and `Connected` / `Disconnected(status)` events.
  `RecordingServiceBleTest` streams strap HR into a real service run (it becomes the active HR source),
  drops it with GATT 133 and checks the run keeps recording; missing permission, adapter or pairing
  leaves the run on watch sensors (`WatchBleTest`).
- R6: all GATT work runs on the `runpro-ble` thread; `follow` from the engine thread only posts.
  `AndroidGattPortTest` (Robolectric, API 30 and 34) checks every callback is posted, never inline, and
  that a closed session's callbacks are dropped.
- Not covered off the watch: real Bluetooth callback timing, the hidden cache refresh, companion
  association on the Galaxy Watch 5 Pro. The 30 min strap acceptance of #17 and F1/F2 (#22) are owner
  field tests; no field result is claimed.

## Source arbitration evidence

- `SensorDropoutReplayTest` records the committed strap-dropout trace and chaos GATT 133, silent stalls
  and GPS loss into real journals. It checks uninterrupted Running/timer state, exact HR fallback/return
  counts, and recovery with identical run events, laps and totals (R4/R2).
- `SourceArbitrationTest` checks per-metric priority lists and stale deadlines (including the exact
  boundary), paused loss, zero-HR contact loss, old samples/callbacks and independent cumulative
  distance/elevation offsets. Losing a source never creates distance, ascent or a lap.
- `SourceSwitchEventTest` in recording checks initial/fallback/return decisions are persisted before
  admitted measurements and remain identical after recovery. Its FIT counterpart records real engine
  strap/wrist and footpod/GPS switches, exports and decodes a verified FIT, and checks every source
  decision exactly (R5/R3). Replay honors recorded decisions rather than generating new ones.
- `HeartRateSourceTest` observes the strap/wrist icon through fallback, return and loss of all sources.
  These are JVM/Robolectric guarantees. BLE adapter ingestion is in the section above; physical field
  results are #22 (F1/F2). Policy and limits: ADR 0013.

## Failure catalogue

Every real failure seen in the field gets a row here, a recorded trace in `testdata/traces/`, and a test.

| Date | Issue | What happened | Trace | Test |
|---|---|---|---|---|
| 2026-11-26 | #10 | Setup screen: "Open settings" for battery did nothing on the Galaxy Watch 5 Pro. On Wear OS `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` resolves to a placeholder activity that closes at once, so the fallback never ran. Now opens the app details page. | none (UI, no sensor data) | `BatterySettingsTest` |
| 2026-11-26 | #10 | The app details page has no Battery entry on the watch either, and `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is also a placeholder: no screen can exempt RunPro. The setup screen now shows the adb command instead of a button. | none (UI, no sensor data) | `BatterySettingsTest` |
| 2026-11-27 | #12 | Warm-up screen: tapping "Start" ("Avvia") did nothing on the Galaxy Watch 5 Pro while "Back" returned to the start screen, leaving a 0-byte `.journal` file in `files/journals`. `FileJournalOutput.availableBytes` called `Files.getFileStore(path)`, which scans `/proc/mounts` and throws `IOException` ("Mount point not found") on Android `/data/user/0/...` before `RunStarted` is written; `RecordingSession` caught it, set `journalFailed` and stayed in `PREPARING` with no error shown on the warm-up screen. `FileJournalOutput` now queries `File.usableSpace` (`statvfs`), the warm-up screen shows storage errors, and `Reset` replaces a failed warm-up session. | none (pre-start storage check, no sensor data) | `ReservedStorageTest`, `JournalFailureTest`, `RunProScreenTest` |
| 2026-11-28 | #12 | Warm-up logged `W/ExerciseUpdateListener: Availability received without any requested DataTypes` on the Galaxy Watch 5 Pro and dropped initial availability events. `ExerciseController` called `setUpdateCallback` right after `inspect`, before `prepareExerciseAsync` / `startExerciseAsync` populated `ServiceBackedExerciseClient.requestedDataTypes`. The controller now registers the listener once `prepare` or `start` completes (or immediately when adopting an already-active exercise). | none (Health Services warm-up handshake, no sensor data) | `ExerciseControllerTest` |
| 2026-11-28 | #105 | IMU capture never started on the Galaxy Watch 5 Pro: at run start `ImuCapture` logged `state=OFF, problem=SENSOR_FAILED, error=SecurityException: getFileStore` and `files/imu` stayed empty, while the run itself was recorded and synced. The default `freeBytes` of `ImuCapture` called `Files.getFileStore(path).usableSpace`, the call already fixed for the journal in #12. It now queries `File.usableSpace` (`statvfs`); the Android-like file system used by both tests moved to `:tools:replay` (`noFileStorePath`) so new storage code can be checked against it. | none (pre-capture storage check, no sensor data) | `ImuCaptureTest`, `ReservedStorageTest` |
