# ADR 0016: A silent source keeps its metric, so late batches count

- Status: proposed (accepted when issue #108 is merged)
- Date: 2026-11-28
- Amends: ADR 0013 (source arbitration)

## Context

Field report #108: a 5 min walk with watch GPS and wrist HR only lost every sample measured while the
screen was off (about 60% of the run: no track, distance, HR or cadence from 123 s to the stop at 302 s).
The gaps match the screen-off periods in the watch logcat exactly.

Health Services holds exercise updates back while the screen is off and delivers them later in one batch,
with their original measurement times (`HealthServicesReadings` keeps `timeDurationFromBoot`). The service
keeps ticking meanwhile. ADR 0013 records a `SourceSwitched(from = watch, to = null, STALE)` when the only
source of a metric goes stale. Once that decision is recorded, `SampleGate` admits nothing from the batch:
its samples are too old to make the source eligible again at the current time, and any return switch
would be recorded at the current time, after every batched sample, so `admit()` rejects them as older
than the switch. Distance also restarted its baseline on the return, so the distance covered during the
gap was lost even without a batch.

`ScreenOffBatchReplayTest` reproduces the field timeline with the synthetic trace
`testdata/traces/screen-off-batching.jsonl.gz`: before this change it rejected every batched sample.
Whether the watch delivered the 123–302 s batch in the field run is not known (no debug trace was
recorded); the fix covers both cases.

## Decision

- When the active source of a metric is stale and no other source is eligible, no switch is recorded.
  The source keeps the metric and is marked *silent*: `RunState.activeSources` omits it (the HR icon goes,
  as before), the displayed value is cleared, and the hold limit (`maxSampleHold`) still stops its last
  heart rate or cadence from counting for longer than 10 s.
- Samples of a silent source are admitted under the existing rules (newer than the metric's admitted
  watermark and the last recorded switch). A late batch therefore fills the gap with its original times,
  and a cumulative distance counter that reports again counts the distance covered meanwhile.
  An admitted sample that is still stale at the current time counts but is not displayed.
- The first fresh sample of the silent source ends the silence without an event. A different eligible
  source still produces a recorded switch (`STALE` from the silent source, or `HIGHER_PRIORITY`), and a
  late batch of the replaced source stays out, as in ADR 0013.
- Journals: no format change. Recovery and export keep replaying recorded decisions; journals written
  before this change may contain `to = null` switches and are restored as they were. Silence is not
  persisted: after a restart the first tick recomputes it.

## Consequences

- R5 is unchanged for real switches (strap ↔ wrist, footpod ↔ GPS). "All sources lost" and the matching
  return are no longer FIT events; a gap is visible in the FIT records instead. This is the deliberate
  change to ADR 0013.
- R4 is strengthened: a sensor gap no longer drops the data a batching source delivers late.
- Whether to set an `ExerciseConfig` batching mode, so Health Services delivers more often with the screen
  off, is a separate question for the field: it costs battery and is not needed for correctness here.
- Field validation: scenario F12 in `docs/FIELD_TESTING.md`.

## Evidence

- `ScreenOffBatchReplayTest` (`:tools:replay`): the screen-off trace through a real journal; every
  batched sample is admitted, distance and moving time match the trace, no pause, one switch per metric,
  and the recovered run has the same samples, events and totals (R2/R4/R5).
- `SourceArbitrationTest`: a late batch from the only source fills the gap (distance, HR average and max),
  distance across a silent gap counts, a backup's takeover still keeps the replaced source's late batch
  out, and silence while paused records no switch.
