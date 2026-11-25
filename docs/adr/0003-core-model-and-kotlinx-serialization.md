# ADR 0003: Core domain model and kotlinx.serialization

- Status: proposed (accepted when issue #3 is merged)
- Date: 2026-11-25

## Context

Issue #3 introduces `:core:model`: samples, metrics, source ids, runs, laps, run events and units.
The replay trace format (#4) is JSONL, test fixtures and diagnostics need a readable form, and the
model must stay pure Kotlin/JVM. Hand-written JSON codecs for nine sample types and eight event types
would be a lot of code to keep in sync with the types.

## Decision

- Add the Kotlin serialization compiler plugin (`org.jetbrains.kotlin.plugin.serialization`, same
  version as Kotlin) and `org.jetbrains.kotlinx:kotlinx-serialization-json` 1.13.0, built for
  Kotlin 2.4.10. `:core:model` exposes it as an `api` dependency; other modules add it when they need it.
- Model types are `@Serializable`. `ModelJson.json` is the shared configuration: a `type`
  discriminator for `Sample` and `RunEvent`, stable snake_case `@SerialName`s for types and enum
  values, null fields omitted, unknown fields ignored (older readers accept newer files).
- Golden JSON strings in `SerializationTest` pin the wire shape. Renaming a Kotlin type or property
  must not change it; changing the shape needs a format migration.
- Units are SI value classes (`Distance`, `Speed`, `Pace`, `Elevation`, `HeartRate`, `Cadence`,
  `Power`) plus `Acceleration`. Constructors reject impossible values; sensor adapters must validate
  raw readings and drop bad ones at the edge.
- Time is a `Timestamp` with both `elapsedRealtimeNanos` (monotonic, source of truth for durations
  and ordering within one boot) and `wallClockMillis` (display and export).
- A `Run` is its ordered list of `RunEvent`s; start, stop and laps are derived from it, so there is
  one source of truth. Event order is the recording order, not timestamp order, because the
  monotonic clock restarts when the watch reboots.

## Consequences

- The journal (#8) stays protobuf as described in `ARCHITECTURE.md`; it can map the model types or
  use `kotlinx-serialization-protobuf` (a later decision for #8).
- Upgrades of Kotlin and kotlinx.serialization must move together; the runtime declares the Kotlin
  stdlib version it requires.
- No recording, journal, BLE or export behaviour exists yet, so R1–R8 are unchanged. The model gives
  R5 its vocabulary (`RunEvent.SourceSwitched`) and R7 its stop reasons (`StopReason`).
