# ADR 0012: IMU capture into per-run IMU streams

- Status: proposed (accepted when issue #33 is merged)
- Date: 2026-11-27

## Context

M5 (running dynamics, #34) needs the watch accelerometer, and possibly the gyroscope, at 50 Hz for every
run, plus reference runs with Stryd or Galaxy Health data to validate the estimators against. 50 Hz is
50 to 100 times the rate of every other source. As `Sample`s (one object, one journal entry and one
`SampleProto` per reading) an hour of accelerometer would be 180 000 objects in the engine, about 3 KB/s
in the journal, and would sit in `RecordedRun` during FIT export, which cannot store it anyway. The run
journal is also deleted after phone sync (R3), so IMU data written there would be gone before anyone
analyses it. And the IMU is optional: nothing about it may put R1–R8 at risk.

## Decision

- **Model** (`:core:model`): `ImuSensor` (accelerometer m/s² with gravity, gyroscope rad/s), `ImuReading`
  (offset from the batch start in ns, three floats) and `ImuBatch` (one sensor, about one second, time of
  the first reading on both clocks). IMU readings are not `Sample`s and never reach the recording engine.
  `SensorListener.onImu(batch)` has a default no-op, so existing listeners are unchanged; trace recorders
  and future live estimators override it.
- **Compact encoding** (`:core:journal`): `JournalRecord.Imu` / `ImuBatchProto` (record field 11).
  Offsets are rounded to 1 µs, accelerometer values to 0.001 m/s² and gyroscope values to 0.0001 rad/s
  (both finer than the watch sensors; the resolution is stored in every batch). Each column is delta
  encoded, zigzagged by hand (kotlinx `@ProtoType` does not apply to list elements) and packed. A second of
  running is about 500 bytes per sensor (10 bytes a reading, about 1.8 MB an hour per sensor), five to six
  times less than samples. `ImuCodecTest` pins the bound.
- **IMU streams, not the run journal.** Each run gets segment files `files/imu/<run id>.imu`,
  `<run id>.2.imu`, ... in the journal layout (length, CRC32, protobuf record) with the magic `RPIM`, a
  run start record, then IMU records. A different magic means the journal reader refuses an IMU stream
  and the other way round; recovery (`files/journals`) and phone sync never see them, so R2/R3 code is
  untouched. Same durability as journal samples (`JournalPolicy`: written every 1 s, synced every 5 s),
  so process death loses at most the last second, a power loss the last five. No storage reserve (the
  reserve exists to save the run; an IMU stream just stops). A capture that restarts during a run
  (process death, same-boot resume) opens the next segment instead of rewriting a file.
- **Capture** (`:core:recording` `imu`): `ImuCapture` follows the published `RunState`: sensors on while
  Running, off while Paused (partial batches flushed to the OS), segment closed on Stop. `ImuPort` is the
  platform seam (`AndroidImuPort` on `SensorManager`); `ImuBatcher` groups readings into batches spanning
  less than 1 s, drops readings that go back in time or are not finite, and derives the wall clock from
  the monotonic `SensorEvent.timestamp`. Everything runs on one executor (the `runpro-imu`
  `HandlerThread` on the watch); `follow` only queues, and only when the target changed, so the recording
  thread that calls it never waits (R6).
- **The run never depends on the IMU.** No sensor, a platform refusal (any `RuntimeException`), a file
  error, free space below 100 MB (twice the low-storage warning, so the journal keeps its space, R7) or
  the low-battery warning end the capture for the rest of the run, keep what was written and are reported
  in `ImuStatus` and the log. Nothing is retried within the run (no loops on a broken sensor); the next run
  tries again. Old streams are pruned, least recently written first, to 300 MB (about 80 hours of both
  sensors) when a capture opens; the current run is never pruned.
- **Rate and battery**: 20 ms sampling period with a 1 s maximum report latency, so the sensor hub
  batches and the processor wakes about once a second. The wake-up variant of each sensor is preferred,
  so readings are not lost while the watch sleeps. These are the parameters the battery measurement
  (FIELD_TESTING F10) checks; they are in `ImuRate` if the field result says otherwise.
- **Setting**: the setup screen cycles **IMU: accelerometer** (default), **accel + gyro** and **Off**. The
  mode of a run is the one it started with. The gyroscope is opt-in until the reference runs show whether
  #34 needs it and what it costs. The service logs every capture state change with the battery level.
- **Traces**: an IMU batch is a trace line `{"type":"imu_batch",...}` (`TraceRecord.ImuRecord`). The trace
  version stays 1: older readers skip unknown types. The replayer delivers batches to `onImu`; a silent
  stall suppresses them like samples. The watch debug trace (#20) only has to attach a `TraceRecorder` as
  the capture's `observer`.
- **Analysis**: `./gradlew :tools:replay:imuReport --args="<files or dir> [--csv <dir>]"` summarises
  segments (readings, rate, gaps over 100 ms, bytes per reading) and exports CSV for comparison with
  Stryd/Galaxy Health. `:tools:replay` now depends on `:core:journal` for it. No new external dependency.

## Consequences

- The run journal, FIT export, recovery and phone sync are unchanged; IMU data stays on the watch until
  copied with adb (`docs/FIELD_TESTING.md` F10). Sending it to the phone is a later decision (#34/#35).
- `ImuCapture` and the stream format are JVM tested (`ImuCaptureTest`, `ImuStreamTest`, `ImuCodecTest`,
  `ImuBatcherTest`, `ImuTraceTest`, `ImuReportTest`); the service wiring is Robolectric tested
  (`RecordingServiceImuTest`). Whether the Galaxy Watch 5 Pro delivers 50 Hz without gaps with the screen
  off, and the battery cost, are field results the owner records; this ADR claims neither.
