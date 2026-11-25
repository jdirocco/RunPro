# ADR 0005: Recording engine design and kotlinx.coroutines

- Status: proposed (accepted when issue #7 is merged)
- Date: 2026-11-25

## Context

Issue #7 introduces the recording engine in `:core:recording`: the state machine
(Idle → Preparing → Running ⇄ Paused → Stopped), manual laps and the run totals. R6 requires that the
recording path never runs on the main thread and never waits for the UI, and the issue asks the engine to
expose a `StateFlow<RunState>`. Replay tests (#4, #5) need the same engine to be deterministic on the JVM.

## Decision

- Add `org.jetbrains.kotlinx:kotlinx-coroutines-core` 1.11.0 (pure JVM, no Android). `:core:recording`
  exposes it as an `api` dependency because `RecordingEngine.state` is a `StateFlow`.
- Split the engine in two layers:
  - `RecordingStateMachine`: pure, synchronous and deterministic. It has no clock and no threads; every
    `EngineInput` carries its own monotonic timestamp. Replay tests feed it directly.
  - `RecordingEngine`: runs the state machine on its own single thread (`runpro-recording`, or an
    injected single-threaded dispatcher). `submit` puts inputs in an unbounded channel with `trySend`,
    so callers (sensor callbacks, the UI) never block or suspend. After each batch of queued inputs it
    publishes the state with `MutableStateFlow.value`, which never waits for collectors.
- Samples are judged by their own timestamp against the run timeline (start, pauses, stop), not by when
  they arrive: Health Services batches samples, so a sample taken before the pause tap can arrive after it.
- Commands older than the last run event are moved to that event's time instead of being rejected, so a
  stop is never lost and the event list stays ordered.
- Totals (all pure Kotlin, configurable in `EngineConfig`):
  - elapsed (pauses included), timer (pauses excluded), moving time (distance intervals at or above
    `minMovingSpeed`, default 0.5 m/s);
  - distance from the deltas of cumulative `DistanceSample`s over fully active intervals; a decrease is a
    source reset and starts a new segment;
  - heart rate and cadence averages weighted by time, each value held until the next sample for at most
    `maxSampleHold` (default 10 s); 0 bpm and 0 spm are ignored;
  - ascent and descent with a hysteresis of `elevationThreshold` (default 2 m), restarted after pauses;
  - average pace = distance / timer time.
- Until source arbitration exists (#19) the first source that reports a metric is its only source.

## Consequences

- R6 is tested by `RecordingThreadingTest`: processing happens on the engine thread, submitting never
  waits for a busy engine, and a stuck collector does not stall the recording.
- The defaults for moving speed, sample hold and elevation hysteresis are starting points; field tests
  (M1) may change them.
- Samples that arrive after the stop are dropped: the Health Services adapter (#11) must flush its batch
  before submitting the stop.
- The journal (#8) and source arbitration (#19) plug into the engine: the journal can use the
  `onProcessed` hook or the run events in `RunState.run`, arbitration replaces `SampleGate`.
