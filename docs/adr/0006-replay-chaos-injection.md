# ADR 0006: Deterministic chaos injection for replay tests

- Status: proposed (accepted when issue #5 is merged)
- Date: 2026-11-26

## Context

Issue #5 needs reusable faults for reliability replay tests. Traces already preserve delivery order,
including late batches (ADR 0004). The recording engine exists, but the production journal, recovery
and BLE state machine are separate issues. Test infrastructure must expose real failure points without
pretending those missing production guarantees are implemented.

## Decision

- Add an optional `ChaosPlan` to `Replayer`. Plans contain immutable `ReplayFault` declarations; no
  randomness, new production dependencies, trace format changes or modifications of trace files.
- Offsets are durations from the first trace record's monotonic timestamp. Records keep delivery order;
  their scheduled offsets never decrease. Sensor outages apply at delivery time, so a late batch
  delivered during an outage is suppressed too.
- Merge fault boundaries into that timeline, even between records or after the last record. Replay
  speed affects waiting, not fault offsets or timestamps. Boundaries precede original records at the
  same time. Equal-time fault boundaries follow declaration order, except outage ends precede starts.
  Outages on the same source may abut but cannot overlap; absent sources and invalid offsets fail early.
- BLE disconnect injects `Disconnected` and `Connected` and suppresses that source's original records
  in the half-open outage interval. GATT 133 is a disconnect with `gattStatus = 133`. A silent stall
  suppresses only samples, without adding transport events. GPS loss injects `GpsLost`/`GpsAcquired`
  and suppresses location, distance, speed and elevation from that source; HR and cadence continue.
- Wall clock jumps accumulate, may be positive or negative, and change only wall timestamps. A late
  batch acquired before a jump retains its original wall time. Both original and injected sensor
  events and external callback timestamps use the adjusted wall time.
- Process death and storage full require a synchronous `ReplayFaultHandler`; omitting it fails at
  construction. The handler tears down the engine, restores a new instance from the test's journal,
  and rebinds sources, or makes its storage port fail writes. Handler exceptions fail the replay.
  `FaultInjectingOutputStream` provides an opt-in storage port that throws `IOException` on writes
  after its `full` flag is set, retaining the prefix already written.
- `ReplayResult` counts effective records, including injected sensor events. Suppressed records,
  callbacks and timing-only steps do not contribute to delivered/dropped counts. Cancellation covers
  all remaining scheduled work. The replay still waits through suppressed records and outage ends.
- Add a test-only dependency from `:tools:replay` to `:core:recording`. The sample destroys actual
  `RecordingEngine` instances and recovers their state from an in-memory input-log journal double.
  That double persists every input immediately and deliberately models no fsync or loss budget.

## Consequences

- Future R2, R4, R7 and R8 tests reuse declarations against their real journal/storage/GATT adapters.
  The injector supplies stimuli; it does not implement a watchdog, reconnect policy or crash recovery.
- `ChaosRecordingReplayTest` exercises all fault types with the real engine. Its storage-full branch
  demonstrates write failure handling with a test consumer; it cannot prove that a production journal
  closes or saves a run under ENOSPC. R1–R8 remain unchanged, and production persistence, recovery,
  fallback and reconnect guarantees remain in their existing issues.
- No watch setup, permissions or field-test procedure changes.
