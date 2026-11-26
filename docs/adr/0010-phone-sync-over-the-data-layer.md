# ADR 0010: Phone sync over the Wearable Data Layer

- Status: proposed (accepted when issue #30 is merged)
- Date: 2026-11-26

## Context

Issue #30: the phone app receives the FIT files of finished runs, stores them and acknowledges them; the
watch keeps each journal until the ack arrives and retries when the phone is back in range. R3 adds that a
journal is deleted only after its FIT file was decoded and matched (`VerifiedExport`, ADR 0009). Done when
20 runs sync with no duplicates and none missing. The link is unreliable: the phone leaves Bluetooth range,
channels fail to open or break mid-file, either app can be killed at any point.

## Decision

- **New module `:core:sync`** (pure Kotlin, `api(:core:fit)`), so the protocol, the watch outbox and the
  phone inbox are JVM-tested with fault injection. Android code only adapts `ChannelClient` streams to
  `SyncChannel`.
- **Transport**: `ChannelClient` (streams, no 100 KB `MessageClient` limit, no `DataClient` sync of state
  we would have to clean up). One channel per run on path `/runpro/fit`, to a reachable node with the
  capability `runpro_fit_receiver` (declared in the phone's `wear.xml`), nearby nodes first.
- **Wire format** (`SyncProtocol`, version 1): the watch writes `RPF1`, version, run id, size, SHA-256 and
  exactly that many bytes; the phone answers `RPA1`, version, stored flag, run id, SHA-256, reason. The
  ack echoes run id and hash, so the watch accepts only an ack for the exact content it verified.
- **Phone (`FitInbox`)**: receives into a temporary file, checks the size and SHA-256, forces it to storage,
  renames it to `files/runs/<run id>.fit` and fsyncs the directory, then acks. A hash mismatch or a storage
  error is a negative ack; a broken stream stores and answers nothing. The same content offered again is
  acked without writing: no duplicates. New content for a stored run id replaces the file (only one file per
  run). Leftover temporary files of a killed process are removed at the next receive.
- **Watch (`FitOutbox`)**: the stopped journals in `files/journals` are the outbox; there is no separate
  queue that could disagree with them. Each is exported to `files/exports/<run id>.fit` (or, after a
  restart, the existing file is re-verified with `FitExporter.verifyExisting`, so its bytes and hash do not
  change with the time zone), offered, and deleted only when the ack confirms the offer and
  `FitExporter.isUnchanged` still holds. The storage reserve beside it goes first. FIT files stay on the
  watch for its history (#29). Running, unrecovered and unexportable journals are kept and skipped.
- **Retries** (`PhoneSync`, own thread, never main or recording thread, R6): a pass when the app opens,
  when a run stops and when Play services reports the phone capability (`PhoneSyncListenerService`,
  `CAPABILITY_CHANGED`); while runs wait, backoff from 30 s to 15 min. Data Layer calls time out after
  30 s and a channel is closed after 120 s, so a silent phone cannot block later passes.
- **Same application id**: the Data Layer only connects apps with the same package name and signing key.
  The phone's `applicationId` becomes `com.runpro.wear` (namespace stays `com.runpro.mobile`); the watch keeps
  its id, installed data and adb battery exemption (owner decision, 2026-11-26). Debug builds share the
  machine's debug key; release builds (#48, M6) must sign both apps with the same upload key.
- **Dependencies**: `com.google.android.gms:play-services-wearable` 19.0.0 in `:wear` and `:mobile`. It brings
  Fragment 1.0 transitively, which fails lint for `registerForActivityResult`; `:wear` constrains
  `androidx.fragment:fragment` to 1.8.5. `Tasks.await` on our worker threads avoids a coroutines-play-services
  dependency.

## Consequences

- R3 is now end to end: export proof, phone durability and ack, then deletion. `PhoneSyncTest` proves it
  on the JVM for 20 runs under random faults and restarts; the field check needs journals written on the
  watch (#60) and is the field test #75.
- The phone package name looks odd on Play (`com.runpro.wear`); changing it later means migrating the
  watch package as well.
- Strava (#31) and Health Connect (#32) read the phone's `files/runs`.
