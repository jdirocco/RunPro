# RunPro release plan

RunPro is a standalone Wear OS running app for the Galaxy Watch 5 Pro (and other Wear OS 4+ watches),
followed after the watch 1.0 by a full Android phone version (milestone M7).
It does one sport, running, and it does it reliably: it never loses a workout, and it keeps external
BLE sensors (heart-rate strap, footpod, Stryd) connected for the whole run.

The reference app is Gear Tracker II. RunPro keeps the parts that matter for running (configurable data
screens, laps, intervals, zones, alerts, sensor sources, FIT export, Strava) and drops everything else.

## 1. Goals and non-goals

**Goals (1.0)**

1. Zero lost workouts. Every second of a started run survives app crashes, process death, watch reboots,
   low battery and full storage warnings.
2. Robust external sensors. A BLE HR strap stays connected for a 3-hour run, reconnects by itself after a
   dropout in under 10 s, and falls back to wrist HR when the strap is gone, without user action.
3. Accurate core data: time, distance, pace, HR, cadence, elevation, laps.
4. Running analysis on the watch: cadence, stride length, and estimated vertical oscillation, ground
   contact time and asymmetry from the IMU (labelled as estimates); real running power and dynamics from Stryd.
5. Export: FIT file, Strava upload, Health Connect (via the phone companion).

**Non-goals (1.0)**: other sports, maps and navigation, social features, training plans, ANT+ (Wear OS has
no ANT+), iOS.

**After 1.0 (M7, phone app)**: record runs on the phone (phone GPS, BLE sensors connected to the phone),
full history and richer analysis (charts, run comparison), configure the watch (data screens, workouts,
sensors) from the phone. The phone reuses the pure Kotlin core (engine, journal, FIT, BLE protocol,
dynamics), so the same reliability invariants R1–R8 apply to it.

## 2. Why Gear Tracker loses the strap (hypotheses to design against)

| Cause | Design answer in RunPro |
|---|---|
| Strap bonded to the phone or Samsung Health, which takes the single BLE connection | Sensor setup checks bonds and tells the user; the diagnostics screen shows who owns the link |
| Wear OS / One UI background limits ("Sleeping apps", Doze) kill the scan or the GATT callback | Foreground service with `health|location|connectedDevice` types, Ongoing Activity, CompanionDeviceManager association, battery-optimisation exemption check at start |
| GATT error 133 and stale GATT objects after a dropout | One GATT client per device, serialised operation queue, always `close()` before reconnect, `autoConnect=true` after the first connection, exponential backoff |
| Silent stalls: link "connected" but no notifications | Watchdog: no HR notification for 5 s marks the source stale, triggers a resubscribe, then a full reconnect |
| Health Services used by another app (only one app at a time) | Detect `ExerciseClient` ownership conflicts at start and show them |
| Phone GPS used while the phone is left behind ("GPS(auto)") | Watch GPS only |

These are hypotheses until the field tests in milestone M2 confirm them. Field reports go in GitHub issues
with the `field-report` template, including the exported sensor log.

## 3. Architecture (summary, details in `ARCHITECTURE.md`)

- **Watch app** (`:wear`): Kotlin, Jetpack Compose for Wear OS, Hilt, Coroutines/Flow.
- **Recording engine** (`:core:recording`): pure Kotlin, no Android dependencies. Receives samples from
  `SensorSource`s, applies source priority and fallback, computes laps and derived metrics, writes the journal.
- **Health Services** (`:wear:health`): `ExerciseClient` for GPS location, distance, speed, wrist HR, steps
  cadence, elevation. It also gives us batching and wake-lock handling.
- **BLE sensors** (`:wear:ble`): own GATT stack for Heart Rate (0x180D), Running Speed and Cadence (0x1814),
  Cycling Power (0x1818, used by Stryd for running power) and Stryd extensions.
- **Running dynamics** (`:core:dynamics`): IMU accelerometer at 50 Hz during the run; on-watch estimates of
  cadence, vertical oscillation, ground contact time, asymmetry, regularity. Algorithms are pure Kotlin and
  tested on recorded traces.
- **Journal** (`:core:journal`): append-only, length-prefixed protobuf records, flushed every second, fsync every
  5 s, checksums per record. A run becomes a FIT file only after the journal is closed; the journal is deleted
  only after the FIT file is written, verified and acknowledged by the phone.
- **Export** (`:core:fit`): FIT encoder (Garmin FIT SDK). **Phone companion** (`:mobile`): receives FIT files over
  the Data Layer, uploads to Strava, writes to Health Connect (Samsung Health reads from Health Connect / Strava).
- **Replay and simulation** (`:tools:replay`): plays recorded sensor traces (HR, GPS, IMU, BLE events including
  disconnects) into the engine on the JVM. Most of the code is testable without a watch; this is what lets
  coding agents work on it.

## 4. Reliability invariants (enforced by tests, see `RELIABILITY.md`)

- R1: A started run is on disk within 1 s of the start tap.
- R2: After process death at any point, restarting the app restores the run with at most 5 s of data lost.
- R3: A run is never deleted before its FIT file is verified (decode and compare with the journal).
- R4: A sensor dropout never stops or pauses the recording.
- R5: Every source change (strap → wrist and back) is recorded in the FIT file as an event.
- R6: The UI thread never blocks the recording path.

## 5. Milestones

| Milestone | Content | Exit criterion |
|---|---|---|
| **M0 Foundations** | Gradle multi-module project, CI, harness, ADRs, replay format, sample traces | CI green; `./rp status` works; one replay test passes |
| **M1 Recording core** | Foreground service, Health Services exercise, journal, crash recovery, basic run screen, start/pause/lap/stop | 10 indoor/outdoor runs with forced kills, 0 lost |
| **M2 BLE sensors** | HR strap, RSC footpod, Stryd power, auto-reconnect, wrist fallback, diagnostics screen, sensor log export | 3 h run with strap, all dropouts recovered < 10 s; field tests on your strap |
| **M3 Running UI** | Configurable data screens, auto-lap, HR/pace zones, alerts (vibration), intervals, ambient mode | Field tests: screens readable while running, battery < 8 %/h with GPS + strap |
| **M4 Export and sync** | FIT export, phone companion, Strava upload, Health Connect, history on watch | 20 runs imported into Strava with matching distance/time |
| **M5 Running analysis** | IMU dynamics estimates, post-run analysis screens, comparison vs Galaxy Health / Stryd | Estimates within agreed tolerance on 10 reference runs |
| **M6 Release** | Play Console internal → closed testing, privacy policy, store listing, 1.0 | 2 weeks of daily use with 0 lost runs, then 1.0 |
| **M7 Phone app** | Recording on the phone, BLE sensors on the phone, phone run UI, history and analysis, watch configuration from the phone, phone release | 10 phone-only runs with 0 lost, then phone 1.1 release |

M1 and M2 come before any UI polish: the main reason this app exists is reliability.

**Target dates** (GitHub milestone due dates; field tests set the real pace, move them when needed):

| Milestone | Due |
|---|---|
| M0 Foundations | 2026-12-06 |
| M1 Recording core | 2027-01-10 |
| M2 BLE sensors | 2027-02-07 |
| M3 Running UI | 2027-03-07 |
| M4 Export and sync | 2027-04-04 |
| M5 Running analysis | 2027-05-02 |
| M6 Release (watch 1.0) | 2027-05-30 |
| M7 Phone app | 2027-07-31 |

## 6. Testing strategy

1. **JVM unit tests** for the engine, journal, FIT, dynamics, BLE state machine (with a fake GATT).
2. **Replay tests**: recorded traces from real runs (`testdata/traces/`) with expected results (golden files).
   Every field bug becomes a trace and a test.
3. **Chaos tests**: replay with injected process deaths, BLE disconnects, GATT 133, GPS loss, clock jumps.
4. **Instrumented tests** on the Wear OS emulator in CI (Health Services has a synthetic data provider).
5. **Field tests on the Galaxy Watch 5 Pro** following `FIELD_TESTING.md`; results as `field-report` issues.

## 7. How work is tracked

- Every task is a GitHub issue, created from `plan/backlog/*.md` with `./rp sync`.
- Milestones M0–M6 are GitHub milestones; the GitHub Project "RunPro" is the board (Todo / In Progress / Done).
- One branch per issue (`issue-<N>-<slug>`), one PR per issue, PR closes the issue.
- Agents follow `AGENTS.md`; the loop is `./rp next` → `./rp start N` → code + tests → `./rp check` → `./rp pr`.
- Decisions go in `docs/adr/`; session handoffs in `docs/HANDOFF.md`.
