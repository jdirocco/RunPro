# ADR 0009: FIT export with the Garmin FIT SDK and verification against the journal

- Status: proposed (accepted when issue #28 is merged)
- Date: 2026-11-26

## Context

R3 says a journal is deleted only after its FIT file is decoded and matches it. Issue #28 asks for
`:core:fit`: journal → FIT activity (session, laps, records, events including source switches, device
info of the sensors), decoded and compared with the journal before the run counts as exported. The
phone sync (#30) deletes the journal only after the phone acknowledges the file, so export must hand it
a proof that cannot be forged by mistake.

## Decision

- **Library**: `com.garmin:fit` 21.176.0 (Maven Central, pure Java, no Android, no transitive
  dependencies), `implementation` scope in `:core:fit` only. Licence: the FIT Protocol License
  Agreement, which allows using the SDK to read and write FIT files in our own app. A hand-written
  encoder would be verified by our own decoder; the SDK decoder is Garmin's reference implementation,
  the same family Garmin Connect uses, so the check is independent of our mapping code.
- **Totals come from the engine**: `RecordedRun.from(journal)` (`:core:recording`) replays the journal
  through `RecordingStateMachine`, the code that produced what the runner saw, sharing the replay loop
  with crash recovery. The FIT session and laps therefore match the watch. Only stopped journals with no
  unknown records can be exported. `:core:fit` depends on `:core:recording` (api).
- **File content** (protocol 2.0): `file_id` (activity, manufacturer `development`, product "RunPro"),
  a developer data id with three string fields (`runpro_run_id` on the session, `runpro_run_event` and
  `runpro_sensor_event` on events), `device_info` 0 for RunPro and one per source with the source id as
  descriptor (BLE sources typed `bluetooth_low_energy`), then `event`, `record`, `lap` in wall time
  order, one running `session` and one `activity`.
- **Events**: every journal `RunEvent` and `SensorEvent` becomes one FIT `event` whose developer field
  holds the event as `ModelJson`, so the exact event (source switch metric/from/to/reason, GATT status,
  pause reason, stop reason, nanosecond timestamps) round-trips. The standard event is the closest one:
  start/resume `timer/start`, pause/stop `timer/stop_all` (timer trigger auto for auto-pause), lap
  `lap/marker`, sensor lost / disconnected / GPS lost `comm_timeout/marker`, everything else
  (source switch, sensor connected, GPS acquired) `user_marker/marker`. FIT has no source-switch event;
  this is how R5 is carried. A field longer than 254 bytes fails the export instead of being cut.
- **Records**: one per wall clock second with the last admitted sample of each metric in it. Distance is
  the engine's run distance (pauses excluded), altitude the barometric elevation when the second has one,
  else the location altitude. Acceleration has no record field and is not exported. Heart rate above
  254 bpm (FIT `invalid`) is left out.
- **Time**: FIT times are the wall clock derived from the start and the monotonic clock
  (`Timestamp.wallClockFrom`), so a wall clock jump during the run does not move the file.
  Durations (`total_*_time`) come from the monotonic clock.
- **Verification**: `FitExporter.export(journal, fit)` writes `<fit>.tmp`, forces it, renames it to
  `fit`, reads `fit` back, checks header and CRC (`Decode.checkFileIntegrity`), decodes it and compares
  every field with the rebuilt run (`ExportVerifier`): run id, file type, devices, every run and sensor
  event exactly and in order, event times, every record, every lap (start, end, trigger, totals) and the
  session totals, within the FIT resolution of each field plus float rounding. A mismatch or a decode
  error removes `fit` and throws; the journal is never written or deleted. Success returns a
  `VerifiedExport` (internal constructor) with the file's SHA-256; `FitExporter.isUnchanged` rechecks it
  before a later deletion (#30).
- Export is disk work and runs on a worker, never on the recording or main thread (R6).

## Consequences

- The FIT SDK adds about 1 MB to the watch app once `:wear` uses `:core:fit`; R8 shrinking can trim it.
- Acceptance by external validators (Garmin FIT File Tools, Strava upload) cannot run in CI: the test
  run writes sample files to `core/fit/build/fit-samples/` for the owner check in `FIELD_TESTING.md`.
- When source arbitration (#19) starts admitting strap samples, records follow automatically; the
  verification compares against whatever the engine admitted.

## Evidence

- `ExportVerificationTest` (`:core:fit`): the three committed scenarios recorded through real
  `RecordingSession`s and journals export verified with the golden totals; a journal with every record
  kind keeps every event (three source switches, GATT 133, auto pause, every lap trigger); FIT times
  survive a one-hour wall clock jump; a run saved by crash recovery exports; the journal bytes never
  change; a wrong or damaged file is removed and no proof is given; an unstopped journal writes nothing;
  the verifier reports each tampered field.
- `RecordedRunTest` (`:core:recording`): the rebuilt state equals the live state, unfinished journals
  are refused.
