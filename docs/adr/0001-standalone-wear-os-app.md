# ADR 0001: Standalone Wear OS app with own BLE stack and Health Services

- Status: accepted
- Date: 2026-11-25

## Context

Gear Tracker II has become unreliable with an external BLE heart-rate strap on the Galaxy Watch 5 Pro.
We want a running-only app that never loses workouts and handles internal and external sensors.

## Decision

- Native Kotlin Wear OS app, standalone (works without the phone during the run).
- Health Services `ExerciseClient` for GPS, distance, wrist HR, elevation and steps cadence.
- Our own BLE GATT stack for external sensors (Health Services does not manage third-party BLE sensors).
- Running dynamics from the raw accelerometer with our own estimators; Samsung Health's running analysis
  is not available to third parties. Samsung Health Sensor SDK can be evaluated later (ADR to follow).
- Append-only journal as the source of truth; FIT is an export format.
- Phone companion only for sync (Strava, Health Connect), never required to record.

## Consequences

- We own BLE reliability: more code, but we control reconnects, timeouts and logs.
- The recording core is pure Kotlin, so it can be developed and tested by agents on the JVM with replay traces.
