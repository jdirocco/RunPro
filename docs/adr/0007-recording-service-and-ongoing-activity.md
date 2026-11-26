# ADR 0007: Recording service, permissions and the Wear Ongoing Activity

- Status: proposed (accepted when issue #10 is merged)
- Date: 2026-11-26

## Context

Issue #10 adds `RecordingService`, the foreground service that owns the recording engine during a run,
its notification and Ongoing Activity, and the permission and battery checks before a run. One UI Watch
and Doze stop background work (PLAN.md), and from Android 14 `startForeground` throws a
`SecurityException` when a requested service type lacks its runtime permission. A service that the system
restarts after killing the process (`START_STICKY`) starts from the background, where while-in-use
permissions (location, body sensors) are not available.

## Decision

- Add `androidx.wear:wear-ongoing` 1.1.0 for the Ongoing Activity (the run icon on the watch face and in
  the recents) and declare `androidx.core:core` 1.18.0 (already on the classpath through
  `activity-compose`) for `NotificationCompat` and `ServiceCompat`. Both only in `:wear`.
- The decisions are pure Kotlin in `:core:recording` (package `service`) and unit tested:
  - `ServicePermissions` chooses the service types from the granted permissions: `health` with heart
    rate or activity recognition, `location` with location, `connectedDevice` with Bluetooth. From the
    background (sticky restart) `location` also needs background location and heart rate does not count.
    The service never requests a type it cannot have, so a missing permission removes a sensor instead of
    crashing the run.
  - `ServiceStartPolicy` maps a start command and the engine phase to an action; a null intent is a
    restart after a kill and goes to `RunRecovery` (the journal implementation is #9).
  - `BatteryGuidance` lists the battery settings to change: unrestricted battery when RunPro is not
    exempt from battery optimisation, and Samsung "Sleeping apps", which has no API and is confirmed by
    the runner once.
- `AndroidPermissions` maps each `RunPermission` to the platform permissions of the running SDK
  (`BODY_SENSORS` up to API 35, `health.READ_HEART_RATE` from API 36; Bluetooth and notifications are
  install-time below API 31 and 33).
- Background location is optional and asked after the others, on its own, as Android requires. Without
  it a run restored after a kill continues without GPS.
- The battery settings screen is opened with `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, not the
  direct exemption request, which needs a special permission and Play policy review.

## Consequences

- R6 still holds: the service only submits inputs; engine callbacks post notification work to the main
  thread.
- R2 is not met by this issue alone: after a kill the service comes back in the foreground and asks
  `RunRecovery`, which restores nothing until #60 writes the journal and #9 reads it. Until then the
  service stops itself after a restart instead of showing an empty run.
- Whether the watch keeps the service alive with the screen off for 60 minutes is a field result
  (owner, on the Galaxy Watch 5 Pro).
