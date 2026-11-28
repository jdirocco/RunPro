# ADR 0019: Fused location as a gated backup of the Health Services GPS

- Status: proposed (accepted when issue #96 is merged)
- Date: 2026-11-28

## Context

Field finding during #12 on the Galaxy Watch 5 Pro (`SM-R920`): the Health Services exercise binds to the
watch's own `gps` provider only. Near buildings, where the wrist antenna sees too few satellites
(`GnssLocationProvider_ex: SV Count: 0/2`), it stays in `ACQUIRING` with no location at all, while apps
using Play services' `FusedLocationProviderClient` (`PRIORITY_HIGH_ACCURACY`, e.g. Gear Tracker) get a
phone-assisted fix with `hAcc` about 14 m within about 6 s. The fused provider also returns Wi-Fi and cell
fixes (`hAcc` 50–120 m and more): admitted as they are, they would add phantom distance while standing
still and false auto-laps.

R4 says a lost source never pauses or stops the run, R5 that every source switch is a FIT event, R6 that
nothing on the recording path blocks.

## Decision

- **A new source, `watch:fused-location`** (`SourceId.FUSED_LOCATION`), next to `watch:health-services`.
  It produces location, cumulative distance and speed, like the Health Services GPS. It is not a
  replacement: Health Services keeps heart rate, cadence and elevation, and its GPS stays the first
  watch choice.
- **Priority**: the default arbitration order of ADR 0013 becomes BLE, Health Services, fused location,
  IMU, other/derived. A footpod keeps distance and speed; Health Services wins whenever it reports;
  fused location covers the gaps (still acquiring at the start, a lost fix mid-run). Every hand-over is an
  ordinary `SourceSwitched` (initial `HIGHER_PRIORITY`, Health Services returning `HIGHER_PRIORITY`,
  Health Services lost `STALE`), so it is in the journal and the FIT file (R5). An explicit
  `SourcePolicy` can still reorder sources per metric.
- **Accuracy gate** (`LocationFixGate`, `:core:recording` `location` package, one per run). A fix is
  admitted only with:
  - valid coordinates and measurement time, not from a mock provider;
  - a reported horizontal accuracy of at most **20 m** (a fix without accuracy is rejected);
  - a measurement time newer than the last admitted fix and no more than **10 s** before its arrival
    (the cached last location delivered when updates start is rejected);
  - a plausible speed from the last admitted fix: at most **12 m/s** beyond the two accuracies. A jump is
    rejected until **3** consecutive fixes agree on the new place; the track then restarts there and the
    jump adds no distance.
  Rejections are counted by reason in `LocationStatus.rejected` for diagnostics. A Doppler speed above
  12 m/s is dropped; the fix is kept.
- **Distance derivation**: the gate keeps its own cumulative distance from admitted fixes (great circle,
  mean Earth radius). While the Doppler speed is at least **0.8 m/s** every fix counts its distance from
  the last counted point. Otherwise (standing still, or no speed) a displacement counts only once it is
  more than twice the sum of both points' accuracies, so jitter inside the accuracy circle adds nothing.
  A `DistanceSample` is emitted for the first fix and whenever distance is counted. The engine's
  baselines (ADR 0013) restart at every switch, so the Health Services and fused counters are never
  added together; the uncovered seconds at a switch boundary are not reconstructed.
  Health Services distance is not derived from its fixes and is unchanged.
- **Fix state**: the first admitted fix emits `GpsAcquired(watch:fused-location)`; `GpsLost` follows
  10 s of rejected fixes, `LocationAvailability` false, a failed request or the end of the updates.
  These are adapter reports for the engine (ADR 0013), never pause or stop commands (R4).
- **Lifecycle** (`FusedLocationController`, a `SensorSource` behind `LocationPort`): updates run from
  the warm-up to the stop, only while the location permission is granted, with a new gate for every run.
  Callbacks of a removed request are ignored. A missing Play services or a refused request is reported
  in `LocationStatus` and the run records without it.
- **Warm-up**: `ExerciseStatus.withLocation` folds the fused readiness into the GPS line: GPS is ready
  when either source has a fix.
- **Wear adapter**: `com.google.android.gms:play-services-location` 21.4.0 (current stable) in `:wear`
  only. `FusedLocationPort` requests `PRIORITY_HIGH_ACCURACY` at 1 Hz with no batching and no wait for
  accuracy, on its own `runpro-location` thread: calls and callbacks run there, the engine thread only
  posts (R6). It runs inside `RecordingService`, whose foreground type already includes `location`.
  Updates are removed on stop and when the service is destroyed (unlike the exercise, they are not adopted
  after a restart; the restarted service requests them again for the restored run).

## Consequences

- Near buildings the run has location, distance and pace from the start instead of none; the price is
  that the first part of such a run is phone-assisted and less precise than a clean satellite fix.
- Battery: two location clients run during a run. On the watch both use the same GNSS chip, so the
  expected extra cost is the phone link and the 1 Hz callbacks, but this is not measured. Field scenario
  F13 measures it; if it is too high, the fused source can be limited to the times Health Services has
  no fix (a follow-up, not needed for correctness).
- Thresholds are starting values chosen from the field logs of #12, not measured guarantees. A real
  trace of a run near buildings should replace the synthetic one in the replay test.
- FIT: `watch:fused-location` appears as a source in the switch events; no format change.

## Evidence

- `LocationFixGateTest`: coarse (50/120/20.1 m), missing accuracy, mock, invalid, cached and out of order
  fixes are rejected; the 20 m limit is inclusive; ten minutes of standing still with jitter inside the
  accuracy (with and without Doppler speed) and with Wi-Fi fixes 80–150 m away add no distance; running
  counts with and without speed; a single jump is rejected and a lasting one restarts the track without
  distance; distance across a gap of rejected fixes is counted when the runner is seen again.
- `FusedLocationControllerTest`: updates from the warm-up to the stop, once; nothing without permission;
  coarse fixes never reach the engine; acquired/lost events; failures reported, never thrown; late
  callbacks ignored; a new run starts a new track; warm-up GPS readiness.
- `FusedLocationArbitrationTest`: default priority; fused covers Health Services acquiring, hands over
  when it fixes and takes back on loss, with three recorded switches, no pause and no double counting
  (R4/R5); a footpod keeps distance; a silent fused source records no switch and no distance.
- `FusedLocationReplayTest` (`:tools:replay`): the field timeline (waits with coarse fixes, a run with a
  Health Services fix in the middle) through the real gate, engine and journal: no distance while
  standing still, one 1 km auto-lap, three switches per metric, no pause, no coarse fix in the journal
  and identical recovery (R2/R3/R4/R5).
- `RecordingServiceLocationTest`, `FusedLocationMappingTest`, `RunProScreenTest` (Robolectric): the
  service feeds gated fixes to the engine and removes updates on stop; no request without permission;
  mapping and request parameters; the warm-up shows GPS ready from the fused fix.
- Not claimed: behaviour, accuracy and battery cost on the Galaxy Watch 5 Pro (field scenario F13).
