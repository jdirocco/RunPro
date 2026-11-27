# ADR 0011: Recording journal integration and observable save failures

- Status: proposed (accepted when issue #60 is merged)
- Date: 2026-11-26

## Context

Recovery and low-resource saving introduced `RecordingSession`, but the watch service still creates an
engine without a writer. Adapter sensor reports also have no engine input. Issue #60 requires the live
recording path to journal its accepted data, close after stop and remain operational after journal I/O
errors. ADR 0007's original fail-closed engine policy needs to distinguish persistence from processing
failures without ever reporting an unsuccessful save as successful (R7).

## Decision

- `RecordingSession.create(directory, clock)` assigns a random run id and lazily creates
  `<run id>.journal` on the first Start in Preparing. Directory creation, reserve allocation, storage
  checks and all journal work stay on the engine thread. Warmup and rejected samples are excluded.
  The Android service supplies `WatchStorage.journals(context)` (`files/journals`) and
  `SystemClock::elapsedRealtimeNanos`; the existing one-second ticker drives writer flush/sync bounds.
- `EngineInput.SensorReceived` accepts adapter reports while Running or Paused and journals them as
  `JournalRecord.Sensor`. They advance the monotonic timer but do not change recording phase or source
  policy. Source arbitration and its run events remain #19. All resulting `RunEvent` variants already
  pass through the session's generic event persistence path.
- A stop, including resource-triggered stops, persists its event and closes the writer before state is
  published. `RunState.journalSaved` is true only after successful sync and close of a stopped journal.
  Later rejected inputs cannot reopen or append to the closed file. Reset after Stopped obtains a fresh
  session from the engine factory, preserving the engine's StateFlow while assigning a new run/file.
  A standalone session with an injected writer continues to own just one run and rejects Reset.
- Journal `IOException`s disable further writes, abort the failed writer and appear in both
  `RecordingEngine.failure` and `RunState.journalFailed`. Submission and in-memory input processing
  continue. A stopped phase alone is **not** a successful save: adapters must check `journalSaved`.
  `RecordingService.LocalBinder` exposes failure, and the service requests phone sync after a stop only
  when `journalSaved` is true. The readable prefix remains on disk for recovery; there is no deletion
  or attempted reuse of the failed writer. A creation failure keeps the run Preparing.
- Factory/recovery and unexpected processing exceptions still close submission. Recovery SAVE rethrows
  persistence errors rather than returning a falsely saved session. This refines ADR 0007 and ADR 0008:
  exhaustion of reserved capacity exposes an unsaved run while leaving the engine operational.
- No dependencies, journal framing or protobuf fields change. Recovery ownership/boot detection and
  watch resource warnings retain their existing separate issue scope.

## Evidence

- R1/R6: `RecordingJournalTest` starts ten real threaded engines and reads their files within one second
  of submission. `JournaledEngineTest` blocks fsync to prove publication follows sync and submission
  remains nonblocking.
- R2/R3/R6: `RecordingJournalTest` checks one-second flush/five-second sync during silence, full synthetic
  run read-back, closure on stop and separate files on Reset. `EngineJournalReplayTest` replays the
  committed strap-dropout trace through the real engine/writer and compares every admitted sample,
  adapter report, run event, lap and total with an independent state machine.
- R7: `JournalFailureTest` injects write, sync, close and creation errors and checks continued processing
  without claiming a save. `LowResourceReplayTest` retains real-file ENOSPC reserve retry/clean saving
  and explicitly checks `journalFailed`/`journalSaved` when the reserve is exhausted.
- Android: `RecordingServiceTest` checks live journal creation, paused/resumed events, successful closure,
  reserve removal and two successive run files in the phone outbox. These are JVM/Robolectric results;
  watch and phone field validation remain F1–F3/F8, not field results claimed by this change.
