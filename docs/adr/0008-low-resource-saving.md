# ADR 0008: Low-resource policy and reserved journal capacity

- Status: proposed (accepted when issue #13 is merged)
- Date: 2026-11-26

## Context

R7 requires a clean saved run at critical battery or full storage. A stop event cannot be appended to a
full filesystem without capacity held in advance. The recording service and UI are separate work
(#10/#12); the core must expose deterministic policy and a testable filesystem adapter first.

## Decision

- Add `EngineInput.Resources(time, batteryPercent, availableBytes)`. Readings are device state, not
  workout data, and are not journaled/recovered. Null fields retain the previous reading. The immutable
  `RunState.resources` exposes warnings at battery **<= 15%** and space **< 50,000,000 bytes** (decimal
  MB). The adapter displays these states without blocking recording. No new external dependency.
- Battery **<= 3%** or available space **== 0** stops Running or Paused with `LOW_BATTERY` or
  `STORAGE_FULL`. Full storage takes precedence when both arrive together. Stop time cannot precede
  the latest processed sample/tick or run event. Repeated readings do not create duplicate stops.
  Critical readings block a new start; low space above zero warns but permits starting.
- A journal-backed session queries its actual output's available capacity before every valid start
  attempt, after reserving emergency capacity. File outputs query their own filesystem, not an unrelated
  directory. Memory outputs default to unbounded capacity. A rejected start remains Preparing and can
  be retried after resources recover. A standalone state machine can require an explicit space check.
- Each created/resumed file journal holds a **1 MiB** sibling `<name>.journal.reserve`. Every block is
  written and forced, rather than extending a sparse file. Allocation failure prevents recording from
  being published. An orphan reserve from process death is reused/replenished on recovery. Closing or
  aborting the output removes only the reserve; it never deletes the journal (R3).
- `FileJournalOutput` handles ENOSPC during write or sync by truncating a partial write back to that
  write's original boundary, releasing the reserve, and retrying exactly once. The flag `storageFull`
  stays true. `RecordingSession` observes it after persistence, appends a stop immediately, and syncs
  the pending samples/checkpoints plus stop before publishing Stopped. The default 64 KiB journal
  buffer and individual engine records fit within the reserve. The journal framing/reader are unchanged.
- JVM `FileChannel` exposes ENOSPC as an `IOException` containing `No space left on device` or `ENOSPC`
  (including causes). Other failures do not consume the reserve or retry. If the retry/stop also fails,
  the engine exposes `failure`, closes submission and retains the readable prefix for recovery; it
  never publishes a successful save. A reserve cannot guarantee success under device failure or
  another process immediately consuming the released capacity.
- Disk work remains confined to the recording thread through `sessionFactory` (R6). Adapters must
  submit battery readings initially and on changes, refresh storage/ticks at least once a second,
  and stop platform exercise/sensors only after observing the persisted Stopped state. Watch wiring
  and runner-facing warnings are tracked in #71. No physical watch behavior is claimed by this ADR.
- `:core:journal` tests depend on `:core:recording` and `:tools:replay` to inject partial failures at the
  file adapter seam while replaying through the real engine. Production module dependencies are unchanged.

## Evidence

- `LowResourceTest`: exact warning/stop boundaries, paused stop, duplicate readings, rejected/retried
  starts, and the actual output space check.
- `LowResourceReplayTest`: sensor replay through real engines/writers/file outputs; battery stop while
  running and paused retains every accepted sample and laps; a chaos storage-full fault causes an
  actual partial write exception, reserve release, rollback/retry and clean stop. Journals are read
  before orderly shutdown. Exhausting the reserve exposes failure without publishing Stopped (R7).
- `ReservedStorageTest`: no duplicate bytes after a partial ENOSPC write, ENOSPC during sync retries
  and forces a complete stop, unrelated I/O failures preserve reserve capacity.
- Existing `JournalStartLatencyTest`, `JournaledEngineTest`, `CrashRecoveryReplayTest`,
  `JournalRecoveryTest` and `JournalResumeTest` retain the R1/R2/R3/R6 guarantees.
