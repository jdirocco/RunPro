# ADR 0011: Auto-lap and interval workouts

- Status: accepted
- Date: 2026-11-26

## Decision

Issue #24 extends the existing recording state machine; no dependency is added. Workout targets and
settings are serializable model values. `ConfigureWorkout` is accepted only before start. Free runs
mark laps at multiples of counted run distance (default 1000 m); manual marks do not reset that grid.
Intervals suppress free-run auto-laps, including after completion. A plan has optional warm-up/cool-down
and 1–100 repetitions of work followed by recovery, each target independently in seconds or metres.
Completion closes the cool-down (or final recovery) lap and keeps recording until the runner stops.

Time targets use active timer time, excluding pauses. Ticks catch up missed time boundaries exactly.
Distance targets close at the first admitted sample reaching the target; overshoot stays in that lap.
Sparse distance updates produce one real lap, not several empty laps. Distance, moving time and ascent
between samples retain the engine's existing rule: the contribution belongs to the lap in which the
second sample was processed. We do not interpolate sensor readings or invent distance during dropouts.
Manual laps split recorded laps without advancing the interval stage.

The service submits the saved selection before start. The watch editor cycles 0.5/1/2 km or disabled
auto-lap; interval repetitions 1–20; stage targets 30/60/120/180/300/600 s or 100/200/400/800/1000 m,
with an off option for warm-up/cool-down. Settings survive reopening the app. It defaults to the committed
6 × (3 min work + 2 min recovery), with 10 min warm-up and 5 min cool-down when intervals are enabled.

## Durability and feedback

Journal `run_started` gains optional field 3, a JSON string containing the settings. Explicit nulls are
retained because a null auto-lap target means disabled. Old records without settings remain readable.
Samples precede the lap events they cause, so replay rebuilds identical lap totals. Each automatic mark
uses the existing synced lap record. Recovery/export apply stored events with automatic generation
disabled; recovery enables it after rebuilding the prefix. Applying stored non-manual laps also rebuilds
the interval index and stage baselines. No existing event is generated twice, and FIT keeps the existing
TIME/DISTANCE triggers. A crash between a sample/checkpoint and its derived mark can recover that mark
on the next live input, using the retained prefix.

Recording stays on its worker thread. Main-thread service feedback observes published snapshots and
vibrates at lap/stage changes and once per final countdown second (3, 2, 1); it works with the activity
closed. Conflated updates use the latest stage rather than replaying obsolete alerts. Compose displays
remaining time/metres and a tap-dismissible lap summary for five seconds of engine run time, driven
by the service ticks so a reopened app shows no stale summary. No feedback blocks the engine.

## Evidence and limits

`WorkoutTest` covers distance thresholds, sparse updates, manual laps, rejected sources, source resets,
pauses, exact time catch-up, mixed targets, configuration validation and real journal recovery/export
rebuild. `IntervalWorkoutReplayTest` replays the committed intervals trace into fourteen timed laps.
`WorkoutFeedbackTest` and `WorkoutScreenTest` exercise preferences, countdown/transition vibration,
selection and popup dismissal on the JVM. Existing recovery, resource, FIT and threading invariant
suites remain in `./rp check`. Physical haptics/readability and sensor delivery are owner field checks
(F9); no hardware result is claimed here.
