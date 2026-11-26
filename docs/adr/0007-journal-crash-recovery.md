# ADR 0007: Journal-backed recording and crash recovery

- Status: proposed (accepted when issue #9 is merged)
- Date: 2026-11-26

## Context

The journal writer (#8) has flush and sync bounds, but the engine (#7) does not yet use it. Issue #9
must restore the same run after process death, retain pauses, laps and totals, and continue appending
without duplicating records. Monotonic timestamps are comparable only within a boot. The foreground
service and its Android startup wiring are separate tasks (#10/#11).

## Decision

- Add an API dependency from `:core:recording` to `:core:journal`, without new external dependencies.
  `RecordingSession` owns the deterministic state machine and an optional writer. It journals accepted
  run samples and the resulting run events in processing order, before the engine publishes the state.
  Preparing samples and rejected commands are not journaled. Start clears warmup current values and
  source gates, so live and recovered runs make the same source choices. A journal-backed session owns one run;
  create another session/file for the next run instead of resetting it.
- `RecordingEngine.sessionFactory` creates or recovers the session on the recording thread. Callers
  still submit without blocking. Disk reads, writes, truncation and sync never run on the submitting
  thread. Persistence/recovery failures close submission and appear in `failure`; clean low-resource
  saving remains #13. The engine's abrupt `close` aborts the writer without flushing its memory buffer;
  a processed Stop already synced the complete run. Standalone `RecordingSession.close` flushes normally.
- Add protobuf field **10**, `checkpoint: Time`, to the journal Record. An accepted engine tick while
  recording appends this buffered record under the existing sample flush/sync policy. Without it, a
  long sensor silence would lose unbounded timer state on recovery. Older readers skip this new kind;
  no existing field numbers or record bytes change. The service must submit ticks at least once a
  second, using the same boot clock as its samples and journal writer.
- `JournalRecovery.scan` reads `.journal` files in a dedicated directory, returning unfinished runs
  separately from unreadable files. Scanning never changes or deletes a file. Empty/unstarted files and
  completed runs are not recovery candidates. Multiple candidates remain separate; the caller must
  select the interrupted service's run rather than start several recordings.
- `JournalRecovery.restore` replays intact records in file order to reconstruct the original run id,
  state, laps, totals, latest values and source gates. It validates the history before opening it for
  writes. Existing records are never written a second time. `JournalWriter.resume` independently checks
  the file, truncates only the damaged suffix at `validLength`, syncs that truncation, and continues
  after the prefix without another header. No other writer may be using the file during recovery.
- Recovery defaults to **SAVE**: after the runner accepts an offer to save, append a synced stop with
  `StopReason.RECOVERY` at the latest retained timestamp. A reboot or unknown boot identity always uses
  this mode. It never compares old uptime with new uptime, even if the new uptime is numerically larger,
  and never estimates elapsed time from wall time.
- **RESUME_SAME_BOOT** is an explicit service-restart policy: the Android caller must verify the same
  boot identity and active service/run ownership before choosing it. It restores Running or Paused
  exactly; a paused run stays paused. The caller supplies the original engine configuration, rebinds
  sensors and resumes its regular ticks. Boot identity detection belongs in the Android service adapter;
  decreasing uptime alone is not a sufficient reboot test.
- Unknown records or semantically invalid histories are refused before mutation, preserving bytes for
  a compatible reader. Recovery never deletes a journal or performs export verification (R3).

## Consequences and evidence

- R2: `CrashRecoveryReplayTest` replays the committed 30-minute trace, kills real engines at 20 seeded
  random fractional offsets, discards unflushed buffers, restores from actual journal files, rebinds
  sources and continues. Each death loses at most 5 s, and every accepted sample older than that budget
  since the previous restart survives. Run identity, one start/stop, final timer and cumulative distance
  are checked. `JournalRecoveryTest` additionally restores only synced bytes at 20 power-loss points,
  restores paused state/laps/source gates, retains timers during sensor silence and saves old-boot runs.
- R1/R6: `JournaledEngineTest` blocks a real journal sync to prove submission remains nonblocking, the
  start is not published before persistence, and session creation/storage stay on the engine thread.
- R3: `JournalResumeTest` verifies prefix preservation, damaged-tail continuation, no duplicate header,
  unknown-record refusal and completed-run refusal. FIT verification/deletion remains #24.
- This is JVM recovery infrastructure. Watch startup UI, service restart and boot identity checks are
  wired by the subsequent Android service/UI issues; no physical watch result is claimed here.
