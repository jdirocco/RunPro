# ADR 0014: Health Connect export on the phone companion

- Status: proposed (accepted when issue #32 is merged)
- Date: 2026-11-28

## Context

Issue #32 asks to write `ExerciseSessionRecord` (running) with route, heart-rate series, distance,
steps and laps to Health Connect from the phone companion (`:mobile`) so finished runs synced from
the watch (`files/runs`, ADR 0010) are visible in Health Connect and in downstream consumers such as
Samsung Health.

Health Connect enforces strict client-side and platform validation on record bounds (including
half-open route timestamps `[startTime, endTime)`, non-overlapping laps/segments, heart rate
`1..300` bpm, and Binder IPC limits), requires explicit write permissions plus a manifest-declared
permissions rationale activity on both Android 11–13 and Android 14+, and must never block or
interfere with watch-to-phone FIT durability and acknowledgement (R3/R6).

## Decision

- **Dependency**: add `androidx.health.connect:connect-client` 1.1.0 (stable), `androidx.activity:activity`
  1.13.0, `androidx.core:core` 1.18.0 and `kotlinx-coroutines-core` / `kotlinx-coroutines-android`
  1.11.0 to `:mobile` only, alongside the `androidx.fragment:fragment` 1.8.5 constraint already used
  by `:wear` (ADR 0010) and offline Robolectric SDKs (API 30 and 34). `:core:sync` stays pure
  Kotlin/JVM with no Android imports and no new runtime dependencies.
- **Pure JVM mapping (`:core:sync`, `com.runpro.core.sync.healthconnect`)**:
  - `FitToHealthConnectMapper` maps a decoded `FitActivity` (`FitDecoder.decode`) to a validated
    `HealthConnectRun`.
  - Session bounds enforce `startTime < endTime` (bumping sub-second runs truncated to the same FIT
    whole second using `elapsedSeconds`/`timerSeconds`).
  - Laps are clamped to `[startTime, endTime]`, sorted, de-overlapped and dropped when zero-length.
  - When pauses occur strictly inside the session (`RunEvent.Paused`/`Resumed` or FIT timer events),
    alternating `RUNNING` and `PAUSE` segments are emitted.
  - GPS route points are filtered to valid coordinates (`[-90, 90]`, `[-180, 180]`), deduplicated by
    timestamp and constrained to `[startTime, endTime)`: a finish-line point at exact `endTime`
    (common because FIT truncates timestamps to whole seconds) is shifted by 1 ms to `endTime - 1 ms`
    so `ExerciseSessionRecord` accepts it without dropping the final location.
  - Heart rate samples are filtered to `1..300` bpm, deduplicated and split into chunks of at most
    3,600 samples (one hour at 1 Hz) so multi-hour runs stay well under Binder IPC transaction limits.
  - Distance comes from the session total (falling back to the maximum in-bounds record distance)
    and is omitted when absent or zero.
  - Steps are integrated from active (non-paused) cadence records with a 5 s hold cap per sample
    (matching ADR 0013's cadence stale timeout so sensor dropouts do not fabricate steps), falling
    back to session average cadence × active time when records carry no cadence, and omitted when no
    cadence exists.
- **Export coordination and idempotency (`HealthConnectExporter`)**:
  - Reads `.fit` files in `files/runs` through `HealthConnectPort` and records each exported file's
    SHA-256 and `clientRecordVersion` in an atomic ledger (`files/health-connect/exported.tsv`).
  - Never modifies or deletes `.fit` files in `files/runs`, keeping them intact for Strava (#31) and
    history.
  - Every Health Connect record carries a deterministic `clientRecordId` (`runpro:<runKey>:session`,
    `:hr` / `:hr:<chunk>`, `:distance`, `:steps`) and `clientRecordVersion`, so retries never create
    duplicates and a replaced FIT file updates in place with a bumped version.
  - Batch export stops early when Health Connect is unavailable, requires an update or lacks
    permissions, while per-file decode or write errors are isolated so other runs still export.
- **Thin `:mobile` adapter (`com.runpro.mobile.healthconnect`)**:
  - `HealthConnectRecordMapper` converts `HealthConnectRun` to real `ExerciseSessionRecord`
    (`EXERCISE_TYPE_RUNNING`, `Metadata.activelyRecorded`, `Device.TYPE_WATCH`), `HeartRateRecord`,
    `DistanceRecord` and `StepsRecord`.
  - `AndroidHealthConnectPort` adapts `HealthConnectClient`: checks SDK status, verifies only the
    permissions required by the run's non-empty record types (`HealthConnectPermissions.requiredFor`,
    so indoor runs without GPS do not require `WRITE_EXERCISE_ROUTE`), inserts records and maps
    `SecurityException`/IPC/I/O failures cleanly. RunPro is write-only and requests no Health Connect
    `READ_*` permissions.
  - `FitReceiverService` schedules a best-effort background export on a dedicated single-thread
    executor (`runpro-health-connect`) only *after* `FitInbox.receive` has durably stored the file,
    acknowledged the watch and closed the channel output stream. Health Connect failures cannot affect
    watch sync or R3.
  - Manifest declares the five write permissions, `<queries>` for `com.google.android.apps.healthdata`,
    and `PermissionsRationaleActivity` for `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`
    (API 30–33) plus the `ViewPermissionUsageActivity` alias (`START_VIEW_PERMISSION_USAGE`,
    `VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS`) for API 34+. The activity explains write-only
    usage and offers permission request (`PermissionController.createRequestPermissionResultContract`)
    and on-demand pending export without adding a launcher icon that could conflict with #31 or M7.

## Consequences

- No reliability invariant R1–R8 is weakened: recording, journal durability and watch-to-phone R3
  verification/acknowledgement are unchanged, and Health Connect export runs asynchronously after the
  phone inbox has stored and acknowledged the run.
- Because `FitActivity` currently stores UTC timestamps without the watch's original local offset,
  the phone adapter supplies `ZoneId.systemDefault()` offsets at the run's start and end instants.
- Steps are derived from cadence until a raw step-count field is carried in FIT.
- Physical phone verification (visibility in Health Connect and Samsung Health sync on a real device)
  remains an owner check in `docs/FIELD_TESTING.md` and is not claimed by JVM/Robolectric tests.

## Evidence

- `FitToHealthConnectMapperTest` (`:core:sync`): maps an exported `easy-run-30min` FIT file (route,
  HR, distance, steps, 3 laps), verifies pause segments and zero paused steps, minimal indoor runs,
  invalid coordinate/HR/out-of-window filtering, cadence dropout hold cap and summary fallback,
  >3,600-sample HR chunking, overlapping/out-of-bounds lap normalization, sub-second sessions and
  missing-session errors.
- `HealthConnectExporterTest` (`:core:sync`): idempotency across restarts, untouched `.fit` files,
  replaced-file version bumps, early stop on unavailable/missing permissions, and per-file failure
  isolation and retry.
- `HealthConnectRecordMapperTest` (`:mobile`): end-to-end journal → FIT → `FitDecoder` →
  `FitToHealthConnectMapper` → real Health Connect SDK `Record` instances (validating SDK
  invariants including half-open route range and segment compatibility), plus indoor runs.
- `AndroidHealthConnectPortTest` (`:mobile`): SDK availability mapping, full/partial permission
  enforcement and `SecurityException`/`RemoteException`/`IOException` handling via a fake
  `HealthConnectClient`.
- `HealthConnectManifestAndRationaleTest` (`:mobile`, Robolectric API 30 and 34): verifies write-only
  manifest permissions, both API 30–33 and API 34+ rationale intents, and `PermissionsRationaleActivity`
  state handling.
