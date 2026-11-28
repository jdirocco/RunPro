# ADR 0013: Per-metric source arbitration and recorded decisions

- Status: proposed (accepted when issue #19 is merged); amended by ADR 0016 and ADR 0019
- Date: 2026-11-27

> ADR 0016 (#108): when no other source is eligible, a stale source keeps its metric (silent, not shown)
> and no `to = null` switch is recorded, so late Health Services batches still count.
>
> ADR 0019 (#96): `watch:fused-location` ranks after Health Services and before the IMU by default.

## Context

The first-source gate cannot recover from a silent strap or select a returning footpod. R4 requires the
run to continue through sensor loss, and R5 requires every source change to survive journal and FIT
export. Sources report independent cumulative distance/elevation offsets. Health Services may deliver
late batches, and recovery sees admitted samples rather than every standby reading.

## Decision

- Replace the first-source gate with deterministic per-metric arbitration, configured with
  `EngineConfig.sourcePolicy`. Each `MetricSourcePolicy` has an ordered list of stable source ids and a
  positive finite stale timeout. Explicit ids win; remaining sources prefer BLE, Health Services, IMU,
  then other/derived sources. Equal priority retains the active source; otherwise source id breaks ties.
  Defaults are 5 s for HR, distance, speed, cadence, power and acceleration, 15 s for location and 30 s
  for elevation. These are starting values for field validation, not measured watch guarantees.
- Actual nonzero HR measurements establish availability; connection reports alone do not. Zero HR
  invalidates contact immediately. Disconnect invalidates all metrics of that device; GPS loss only
  invalidates location, distance, speed and elevation. Old loss reports cannot erase newer readings.
  Repeated/older measurements are dropped using monotonic per-source and admitted per-metric watermarks.
  Late batches can contribute on an unchanged active source; measurements preceding a recorded source
  switch cannot undo that decision.
- On a measurement, arbitrate its metric; on ticks and adapter reports, arbitrate every metric. A source
  is stale at the exact timeout boundary. Select the freshest eligible source by priority or select none.
  Emit `SourceSwitched` for initial selection, fallback, loss of all sources and return, while Running or
  Paused. Initial selection/return uses `HIGHER_PRIORITY`; an unavailable active source uses `STALE`.
  This never issues pause or stop commands. One-second service ticks bound silent-loss detection to the
  first tick after expiry; reconnect remains the independent BLE controller (ADR 0012).
- A switch clears the displayed metric until the next admitted measurement. Standby samples establish
  availability but are not replayed as new measurements. Distance and elevation restart their baselines
  on every switch: the first new-source reading establishes its offset and adds no distance/ascent.
  HR/cadence account for the old held value up to the switch (within the existing hold limit), then clear.
  This avoids double counting and phantom laps while accepting that an uncovered boundary interval cannot
  be reconstructed safely from unrelated cumulative counters.
- Persist switch events before their admitted sample, and automatic laps after that sample. Decisions
  caused by invalid contact are persisted even though the zero-HR sample is rejected. The existing synced
  event path ensures a decision and all earlier pending samples are durable before state publication.
  No journal schema or dependencies change.
- Recovery and export replay source decisions exactly, without reevaluating timeouts/priorities. Legacy
  journals with no switch decision for a metric retain first-source admission. Same-boot recovery rebuilds
  active readings and their timestamps, honoring retained loss reports for inactive standby sources;
  other standby availability is relearned from subsequent measurements.
  A source decision retained just before a crash but without its following sample remains a valid empty
  baseline. Replaying checkpoints cannot invent additional switches or laps.
- `RunState.activeSources` is an immutable snapshot. The run screen uses its HR selection for distinct
  strap/wrist icons with localized accessibility descriptions, and removes the icon when no source is
  available. Actual BLE watch ingestion/association: ADR 0018 (#17).

## Evidence

- `SourceArbitrationTest`: independent priorities/deadlines, exact timeout boundaries, paused silence,
  disconnect/contact loss and return, old callbacks/readings, and distance/elevation offset isolation.
- `SensorDropoutReplayTest`: committed dropout trace and injected GATT 133, silent stall and GPS loss,
  with real journals; uninterrupted timer, fallback/return counts and identical recovered totals (R4/R2).
- `SourceSwitchEventTest` in recording: event ordering, journal read-back and same-boot continuation.
  In FIT: actual engine strap/wrist and footpod/GPS decisions decode exactly from a verified FIT (R5/R3).
- `HeartRateSourceTest`: visible accessible icon follows selection, fallback, return and silence.
  Existing journal/threading/low-resource suites continue to check R1/R2/R6/R7. No physical watch result
  is claimed; BLE field validation remains #22, F1/F2.
