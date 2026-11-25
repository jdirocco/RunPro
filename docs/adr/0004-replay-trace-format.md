# ADR 0004: Replay trace format and sensor source contract

- Status: proposed (accepted when issue #4 is merged)
- Date: 2026-11-25

## Context

Issue #4 introduces `:tools:replay`: a trace of what the sensors delivered during a run, a recorder
that writes it and a replayer that plays it back into fake sensor sources on the JVM. Field bugs
become traces (see `AGENTS.md`), the chaos injector (#5) and the reliability tests (R2, R4, R7)
replay them, and the watch will record them as debug traces (#20). The format must survive a
recorder that is killed mid-run, must stay readable by older code, and must not need new
dependencies.

## Decision

- **File**: JSON Lines, UTF-8, `\n` line ends, gzip-compressed (`*.jsonl.gz`). Readers detect gzip
  from the magic bytes, so plain `.jsonl` also works for hand-written fixtures.
- **Header**: the first line is `{"type":"trace_header","version":1,"name":...,"description":...,
  "device":...}`. Readers reject a version newer than theirs.
- **Records**: every further line is one `Sample` or `SensorEvent` in the `ModelJson` shape of
  `:core:model` (ADR 0003), told apart by `type`. Sample and sensor event type names never
  collide (a test pins this). Unknown types are skipped and counted, so older readers accept
  traces from newer writers.
- **Order**: lines are in delivery order, not timestamp order, because platform sources deliver
  batches late. The replayer delivers in file order; each record is due at its monotonic offset
  from the first record divided by the speed, and the replay clock never goes back.
- **Truncation**: the recorder writes gzip with sync flush. A trace whose recorder died (cut line,
  missing gzip trailer) is read up to the last complete line and flagged `truncated`; a bad line
  anywhere else is a `TraceFormatException` with its line number.
- **Sensor events** are a new model type, `SensorEvent` (`sensor_connected`,
  `sensor_disconnected` with an optional GATT status, `gps_lost`, `gps_acquired`): what an adapter
  reports, as opposed to `RunEvent`, what the engine decides.
- **`SensorSource`/`SensorListener`** live in `:core:model`: a callback contract (`start(listener)`,
  `stop()`) that the Health Services and GATT adapters, the accelerometer and the replayer all
  implement. It is callback based because the platform APIs are; the engine (#7) can wrap it in a
  flow. Putting it in the model module lets `:core:ble`, `:core:recording` and `:tools:replay` share it
  without depending on each other.
- **Pacing** goes through a `ReplayClock`: `SystemReplayClock` really waits (real time or N×),
  `VirtualReplayClock` advances instantly for tests. Unit tests replay at `ReplaySpeed.UNLIMITED`.
- **Synthetic traces** in `testdata/traces/` are generated deterministically by `SyntheticTraces`
  (StrictMath, no randomness); a test checks the committed files against the generator.

## Consequences

- No new dependencies: `:tools:replay` uses `:core:model` and kotlinx.serialization (ADR 0003).
- Changing a line shape needs a new trace version and a reader that still reads version 1, since
  recorded field traces are kept forever.
- A trace with six 1 Hz streams takes about 30 KB per 10 minutes (gzip). IMU traces at 50 Hz (#33)
  will be much larger; if that becomes a problem, a compact IMU record type is added in a later
  version.
- R1–R8 are unchanged by this ADR. It provides the replay infrastructure that R2, R4 and R7 tests
  will use; the strap dropout trace is the input for `SensorDropoutReplayTest` (R4).
