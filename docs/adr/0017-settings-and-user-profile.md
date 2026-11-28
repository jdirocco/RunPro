# ADR 0017: Settings and user profile

- Status: accepted
- Date: 2026-11-28

## Decision

Issue #27 extends the existing workout preferences and journal start snapshot with `RunSettings`.
It contains display units, optional maximum/resting HR and weight (kg), auto-pause, a start countdown
and debug tracing. Defaults retain metric units, unspecified physiology, auto-pause off, no countdown
and debug tracing off. Validation rejects invalid ranges, non-finite weight and resting HR at or above
maximum HR. The watch editor offers bounded increments, explicit save/cancel/clear, and kg/lb conversion.
Profile values are retained for consumers such as zones/analysis; this issue does not estimate missing
values or add zones. Recording and FIT measurements always use SI.

The existing `ConfigureWorkout` submission copies the saved selection before Start. Active runs reject
configuration changes. The additive `runSettings` JSON property in journal start field 3 defaults when
reading old journals; recovery/export rebuild from that snapshot. No journal format version or dependency
is added. Live distance/pace and the finished-run summary use the run's selected km/mi units.

## Auto-pause

Only fresh, admitted speed samples can trigger an automatic pause: below 0.5 m/s for five seconds,
with no gap over three seconds and no source change. A fresh speed of at least 1 m/s resumes an automatic
pause. Manual pauses never resume automatically. Stale/late batches reset the candidate; ticks and sensor
loss cannot pause a run. A dropout while auto-paused leaves it paused until fresh movement is observed.
The speed sample is counted before its derived pause/resume event, so recovery/export replay the same
order. Rebuilding uses stored events, never regenerates automatic pauses, and restarts the low-speed
candidate after recovery. Health Services keeps collecting while auto-paused so speed can resume the run;
manual pause retains the existing exercise pause behavior. BLE connection handling is unchanged.

## Countdown and diagnostics

The optional 3/5/10-second countdown is a cancellable warm-up action in the UI. No run exists during it;
START is sent only at expiry and follows the existing durable start path (R1). Back, activity stop,
recreation or process death cancels the countdown rather than starting an unseen run. The default sends
START immediately. A new run still persists before publication on the recording thread.

Debug traces contain raw sensor samples/events (including location), in replay-compatible JSONL, apart
from journals in `files/traces`. The toggle is copied from the run snapshot, including after recovery;
a restart creates a new segment. A separate worker serializes and flushes each record. Its bounded
256-task queue disables tracing on overload instead of blocking a source or engine. I/O errors also
disable tracing. Each segment is capped at 10 MB; before creating one, old traces are pruned to 20 MB.
Tracing stops below 100 MB free. These best-effort diagnostics never gate recording, saving, export or
journal deletion. The initial callback racing run publication may precede trace activation; the durable
journal remains authoritative. The UI describes local raw-data capture; copying uses adb until the
separate diagnostics/export UI is available.

## Evidence

Model `RunSettingsTest` checks defaults, validation and JSON compatibility. Recording `RunSettingsTest`
checks immutable settings, real journal recovery, hysteresis, manual pauses and silence/late batches.
`AutoPauseReplayTest` checks exact traffic-light pause/resume timing and identical reconstruction of real
journals, plus a silent-stall chaos case that must never pause (R2/R4). `RecordingServiceTest` proves the
saved profile reaches the next durable run and active settings do not change. `SettingsTest`,
`SettingsScreenTest`, `RunFormatTest` and `DebugTraceTest` check persistence, edits, countdown cancellation,
unit conversions, continued exercise collection and failure isolation. Existing start latency, threading,
source-switch, recovery, low-resource and FIT suites remain in `./rp check` (R1–R8).
Physical readability, pace thresholds and countdown timing are covered by the settings check in
`FIELD_TESTING.md`; no watch result is claimed.
