# ADR 0020: Zones and alerts

- Status: accepted
- Date: 2026-11-28

## Decision

Issue #25 adds heart-rate zones (from maximum HR or lactate threshold HR), pace zones (from
threshold pace), and rate-limited runner alerts with distinct vibration patterns.

### Zones (`:core:model`)

- `UserProfile` adds optional `lactateThresholdHeartRate` (60–250 bpm, strictly above resting HR
  and below max HR when both are set) and `thresholdPace` (`Pace`, 2:00–15:00 min/km, stored in SI
  seconds/km). Missing profile values stay unknown and never invent assumed physiology.
- `HrZoneMethod` selects `MAX_HR` (default, 50/60/70/80/90% of max HR, rounded up so integer
  percentages match) or `LTHR` (65/85/90/95/100% of LTHR). Both produce a `HeartRateZones` with
  five inclusive lower bpm bounds; below zone 1 is zone 0, and `0 bpm` (lost skin contact) has no
  zone.
- `PaceZones.fromThreshold` computes five running pace zones from four strictly decreasing
  seconds/km boundaries at 129/114/106/99% of threshold pace (zone 1 slowest, zone 5 fastest).
- `DataField.HR_ZONE` uses the run's active HR zone method; `DataField.PACE_ZONE` formats the
  current pace zone (`--` when stopped, slower than 30:00/km, or without threshold pace).

### Alert settings and evaluation (`:core:model`, `:core:recording`)

- `RunSettings` adds `hrZoneMethod` (default `MAX_HR`) and `alerts: AlertSettings` (default: HR
  and pace limits off, sensor and GPS alerts on, 30 s cooldown in 5–300 s). All new JSON properties
  are additive with defaults so older journals decode unchanged and recovery preserves the active
  run's immutable settings snapshot (R2).
- `AlertMonitor` runs inside `RecordingStateMachine` on the recording thread (R6) and emits
  transient `RunAlert` values on `RunState.alerts` while `RunPhase.RUNNING`:
  - `HR_HIGH` / `HR_LOW`: fresh (≤ 3 s old), live, admitted non-zero HR samples outside configured
    bounds. Late screen-off batches and `0 bpm` contact loss never fire HR alerts.
  - `PACE_TOO_FAST` / `PACE_TOO_SLOW`: fresh, live, admitted speed samples with valid running pace
    (≤ 30:00/km) outside configured bounds.
  - `SENSOR_LOST` / `SENSOR_BACK`: tracked per BLE `SourceId` across explicit `Disconnected` /
    `Connected` events, silent-stall switches/silencing, and `0 bpm` skin-contact loss. Multi-metric
    sensors (footpods) emit one alert per device; initial connection emits no `SENSOR_BACK`.
  - `GPS_LOST` / `GPS_BACK`: tracked across `GpsLost` / `GpsAcquired` events and location
    silencing. A seamless fallback to `watch:fused-location` keeps GPS live with no false loss;
    indoor runs that never acquired GPS never fire `GPS_LOST`.
- Rate limiting uses monotonic `Timestamp.elapsedSince` per `AlertKind` (and per BLE `SourceId` for
  sensor alerts), so wall-clock jumps cannot trigger or suppress alerts and opposite transitions
  (`SENSOR_LOST` → `SENSOR_BACK`, `GPS_LOST` → `GPS_BACK`, `HR_HIGH` → `HR_LOW`) are never blocked
  by each other.
- Alerts are not persisted as `RunEvent`s, never alter FIT export (R3, R5), never pause or stop a
  run (R4), and are never replayed for historical samples during crash recovery (`restore*` tracks
  sensor/GPS state silently).

### Watch feedback and configuration (`:wear`)

- `AlertFeedback` defines eight unique vibration waveforms (distinct from countdown and lap
  patterns). `WorkoutFeedback` vibrates on the main thread from `RecordingService.onProcessed` (R6),
  working with the screen off and staying silent on bind/recovery or while paused.
- `AlertPopup` shows the latest live alert for 4 s of run time (tap to dismiss).
- `SettingsScreen` adds LTHR and threshold pace editors plus a **Zones and alerts** sub-screen for
  HR zone method, zone previews, HR/pace alert bounds, sensor/GPS toggles and cooldown.

## Evidence

- `:core:model`: `ZonesAndAlertsTest` (Max HR/LTHR/pace zone math, bounds, validation, zone-to-alert
  helpers, JSON round-trip and legacy defaults).
- `:core:recording`: `AlertMonitorTest` (all 8 alert kinds, freshness, 0 bpm, paused suppression,
  multi-metric BLE deduplication, fused-location backup, monotonic rate limiting across wall-clock
  jumps, real journal recovery without historical replay).
- `:tools:replay`: `AlertsReplayTest` (committed `strap-dropout` and `intervals-6x3min` traces plus
  chaos `BleDisconnect`, `SilentStall`, `GpsLoss` and `ClockJump` through real journals).
- `:wear`: `DataFieldTest`, `WorkoutFeedbackTest`, `ZonesAndAlertsScreenTest` (unique waveforms,
  vibrator behaviour, alert popup, settings/zones/alerts UI).
- Physical watch validation is *Zones and alerts check (#25)* in `docs/FIELD_TESTING.md`; no
  physical watch result is claimed here.
