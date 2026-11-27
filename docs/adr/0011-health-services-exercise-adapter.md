# ADR 0011: Health Services exercise adapter

- Status: proposed (accepted when issue #11 is merged)
- Date: 2026-11-26

## Context

Issue #11 records the watch's own sensors during a run through the Health Services `ExerciseClient`
(`ExerciseType.RUNNING`): wrist heart rate, GPS location, distance, speed, steps per minute and elevation.
Only one app at a time can own the Health Services exercise; another app (e.g. Gear Tracker) may own it
before or during a RunPro run. The process can be killed during a run while Health Services keeps
RunPro's exercise running. Reliability comes first: a refused or lost exercise must never pause or stop
the recording (R4), and nothing on the recording path may block (R6).

## Decision

- Add `androidx.health:health-services-client` 1.1.0 (the current stable release) to `:wear` only. Its
  `ListenableFuture` API is used directly with a dedicated callback thread (`runpro-health`), so no
  coroutine bridge is needed. `androidx.test:runner` 1.7.0 and `androidx.test.ext:junit` 1.3.0 are added
  for the emulator test (`androidTest` only).
- All decisions are pure Kotlin in `:core:recording` (package `exercise`) behind `ExercisePort`, a
  platform-free view of `ExerciseClient`, and unit tested with a fake port:
  - `ExercisePlan` requests every data type that Health Services supports for running **and** whose
    permission is granted (heart rate → heart rate permission; location and elevation → location;
    distance, speed and cadence → activity recognition), because requesting a data type without its
    permission makes `startExercise` throw. GPS is enabled only with location. Auto-pause is always off:
    only the runner pauses.
  - `ExerciseController.follow(RunPhase)` makes the exercise follow the engine (preparing → warm-up,
    running → start or resume, paused → pause, stopped → end), including stops the engine decides itself
    (low battery, storage full). One request is in flight at a time; the next is chosen by
    `ExerciseSession` when it completes. A start requested during the capabilities check skips the
    warm-up.
  - Ownership (`getCurrentExerciseInfo`): our own exercise still running after a process restart is
    **adopted**, not started again (the first update says whether it is paused). Another app's exercise is
    **reported** during warm-up and not touched; the start of the run **takes it over**, which ends the
    other app's exercise, because the runner explicitly chose to record with RunPro. Taking over is shown
    in `ExerciseStatus.tookOverFromOtherApp`.
  - An exercise ended by the platform (another app took over, permission revoked, missing listener) only
    changes `ExerciseStatus` and emits `GpsLost`; it is not restarted during that run and the run goes on
    with its other sources (R4). A new exercise begins only with a new or restored run, or after a
    warm-up that expired.
  - `ExerciseSampleMapper` turns data points into `Sample`s of `watch:health-services`: boot-time
    timestamps with the wall clock derived from the update's arrival (as for every source), distance
    deltas summed into the cumulative `DistanceSample`, and invalid values dropped at the edge (NaN,
    out-of-range coordinates, heart rate 0, `NO_CONTACT` or `UNRELIABLE`).
  - Location availability becomes `SensorEvent.GpsAcquired` / `GpsLost`; GPS and heart rate readiness
    for the warm-up screen is in `ExerciseStatus`.
- `:wear` holds only the translation (`HealthServicesMapping`, `HealthServicesReadings`) and the port
  (`HealthServicesExercisePort`); `RecordingService` builds the controller, feeds its samples to the
  engine and exposes `ExerciseStatus` through its binder.

## Consequences

- Health Services grants location and body sensors only while RunPro is in the foreground. During a run
  that is the foreground service; the emulator test therefore goes through `RecordingService`.
- Sensor events are logged by the service until source arbitration (#19) consumes them.
- After a restart the distance sum starts again from zero; the engine treats the decrease as a new
  segment of the same source, so no distance is lost or double counted.
- Taking over another app's exercise is a product choice; if the owner prefers to ask first, the warm-up
  already reports `OTHER_APP_ACTIVE`, so only the start needs a confirmation.
- Evidence: `ExerciseControllerTest`, `ExerciseSampleMapperTest`, `ExercisePlanTest` (JVM),
  `HealthServicesMappingTest` (Robolectric) and `HealthServicesSyntheticRunTest`, which records a run with
  the emulator's synthetic data end to end. Behaviour on the Galaxy Watch 5 Pro is a field result (#14).
