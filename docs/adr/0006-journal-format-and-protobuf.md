# ADR 0006: Journal format and kotlinx-serialization-protobuf

- Status: proposed (accepted when issue #8 is merged)
- Date: 2026-11-26

## Context

Issue #8 introduces `:core:journal`, the append-only file every run is written to while it is recorded.
It is what crash recovery (#9) rebuilds a run from, so it decides R1 (a started run is on disk within
1 s of the start tap) and the data-loss bound of R2 (≤ 5 s). `ARCHITECTURE.md` asks for length-prefixed
protobuf records with CRC32, a flush every second, an fsync every 5 s, and a reader that stops at the
first corrupt record. ADR 0003 left open how the protobuf payload is produced.

## Decision

- **File layout** (big-endian): an 8-byte header, magic `RPJN` plus the format version (`int32`, now 1),
  then records: payload length (`int32`), CRC32 of the payload (`int32`), payload. A valid length is
  `1..1 MiB`; zero is never valid, so the zero-filled blocks a file system can leave after a power loss
  are not read as records.
- **Payload**: one protobuf `Record` message with one field per record kind (oneof on the wire):
  `run_started = 1`, `samples = 2` (a batch), `lap = 3`, `pause = 4`, `resume = 5`, `source_switch = 6`,
  `sensor_event = 7` (adapter report, model `SensorEvent`), `run_stopped = 8`, `sensor_status = 9`
  (`RunEvent.SensorConnected` / `SensorLost`). Times are a `Time { int64 elapsed_nanos = 1;
  int64 wall_millis = 2 }` message. Enums are explicit integer codes, 0 meaning "missing". The messages
  are Kotlin classes private to the journal (`JournalProto.kt`) with explicit `@ProtoNumber`s, mapped to
  the model types by `JournalCodec`, so renaming a model type or property never changes the file.
  Golden bytes in `JournalCodecTest` pin the wire shape.
- **Encoder**: `org.jetbrains.kotlinx:kotlinx-serialization-protobuf` 1.13.0, the same release as the
  JSON format already used (ADR 0003), `implementation` scope in `:core:journal` only. It is pure Kotlin
  (no protoc, no code generation, no Android). Its API is marked experimental: the opt-in is limited
  to the two journal files that use it, and the golden-byte tests catch any change of behaviour on
  upgrade.
- **Reader**: keeps every record before the first damaged one. A cut tail (length, CRC or payload
  incomplete) ends the journal as `Truncated`; a bad length or CRC mismatch ends it as `Corrupt` and
  everything after it is ignored, even intact records. An intact record that does not decode (unknown
  kind or enum code from a newer writer) is skipped and counted, not treated as damage. A file without
  the magic, or with a newer version, is a `JournalFormatException`. `validLength` tells a writer that
  continues a recovered journal (#9) where to cut it.
- **Writer durability**:
  - `RunStarted` and every run event (pause, resume, lap, source switch, sensor status, stop) are written
    and `fsync`ed before `append` returns. That is R1 for the start tap; the other events are rare (a few
    per minute at most) and matter most for the FIT file.
  - Samples and sensor events are buffered in memory, written to the OS at least every second (survives
    process death) and `fsync`ed at least every 5 s (survives power loss or a reboot), or at once when
    the buffer passes 64 KiB. The intervals are checked on every append and on `tick()`, which the owner
    calls at least once a second; the writer starts no thread of its own.
  - Creating a journal never overwrites a file (`CREATE_NEW`) and also syncs the directory entry.
  - A failed write or sync (storage full) is rethrown and ends the journal; what reached storage stays
    readable. Ending the run cleanly is R7 (#13).
- `JournalOutput` abstracts the file (`write` = to the OS, `sync` = to storage). Tests use a fake that
  keeps what would survive process death and what would survive a power loss.

## Consequences

- One new dependency, from the kotlinx.serialization release already in the build; it moves with Kotlin
  and kotlinx.serialization (ADR 0003).
- A 1 Hz recording of six streams costs about 50 bytes per sample, roughly 3–4 MB for 3 hours. Raw IMU at
  50 Hz would be about ten times that; running dynamics (#33) should journal its derived values, not the
  raw accelerometer, or add a compact record kind.
- Adding a record kind or field is compatible (old readers skip it); renumbering a field or an enum code
  is not and needs a new format version.
- Fsyncs on the recording thread take milliseconds on watch flash and happen at most once per event and
  once every 5 s otherwise. The engine queue is unbounded (ADR 0005), so a slow fsync delays samples but
  loses none (R6).
- R1 is tested by `JournalStartLatencyTest`; the ≤ 1 s (process death) and ≤ 5 s (power loss) loss
  bounds that R2 relies on are tested in `JournalWriterTest`. Wiring the writer into the recording path
  and recovery are follow-up work (#9).
