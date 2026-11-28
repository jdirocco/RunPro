# ADR 0015: Strava upload from the phone companion (private owner-only build)

- Status: proposed (accepted when issue #31 is merged)
- Date: 2026-11-28

## Context

Issue #31: the phone companion (`:mobile`) connects to Strava with OAuth, uploads each run's FIT file as
a Run, polls the upload until Strava has an activity, shows the link, and offers an opt-in automatic
upload. Done when a run appears on Strava with matching distance and time.

Strava's authorization-code exchange and every token refresh need the application's client secret
(https://developers.strava.com/docs/authentication/). Any secret compiled into an APK can be extracted
from it, so a publicly distributed app would need a backend for token exchange and refresh. The owner
decided (issue #31 comment) to scope this to a **private, owner-only build**: public client id 205420,
callback domain `localhost`, credentials read from git-ignored `local.properties`.

## Decision

- **Credentials**: `:mobile/build.gradle.kts` reads `strava.clientId`, `strava.clientSecret` and
  `strava.callbackHost` (default `localhost`) from the root `local.properties`, then from
  `mobile/local.properties` (overriding), into `BuildConfig` fields and the `stravaCallbackHost`
  manifest placeholder. Both files are git-ignored. Absent keys give blank fields: `StravaConfig`
  is then not configured, the Strava screen says how to configure it, and nothing calls Strava, so CI
  and `./rp check` build and test without secrets. The secret is never logged, printed or written to
  tracked files: `StravaConfig.toString` and `StravaTokens.toString` redact it and the tokens, the
  upload runner logs only outcome kinds, and tests use fake values and a fake transport.
- **OAuth** (`StravaOAuth`, `StravaAccount` in `:core:sync`): the Strava mobile authorization URL
  (`/oauth/mobile/authorize`, `approval_prompt=auto`, scope `activity:write` only) carries a 24-byte
  random `state`. The state is stored on disk (`files/strava/oauth-state.json`) because the browser or
  Strava app round trip can outlive the process, and every completion attempt consumes it: a replayed or
  forged callback is never exchanged twice. The callback must match `runpro://<callbackHost>/oauth/callback`,
  the state (constant-time compare) and include the `activity:write` grant. `StravaActivity` is
  `singleTask` and handles the callback in `onCreate` (fresh launch only) and `onNewIntent`.
- **Tokens** (`StravaTokenManager`, `FileStravaTokenStore`): stored in app-private
  `files/strava/tokens.json` (owner-only permissions, atomic temp-file replace and directory fsync,
  excluded from backup and device transfer by the existing backup rules). They are refreshed 5 minutes
  before expiry and once after an HTTP 401; the rotated refresh token is saved before it is used. A
  rejected refresh asks the runner to connect again. Disconnect forgets the tokens locally (the runner can
  also revoke RunPro in Strava's settings) and keeps the upload ledger and its links.
- **Upload** (`StravaApiClient`, `StravaUploader`): `POST /uploads` multipart with `data_type=fit`,
  `activity_type=run`, `external_id=<file name>`; then `GET /uploads/{id}` with bounded polling
  (8 attempts, 1.5 s doubling to 5 s). The ledger `files/strava/uploads.json` records each file's SHA-256,
  status (`PROCESSING` with the upload id, `UPLOADED` with the activity id, `FAILED` with the error), so a
  retry after a restart resumes polling instead of uploading again, an uploaded file is never re-sent, and
  a replaced FIT file (new hash) is pending again. A "duplicate of activity N" error resolves to that
  activity, so a lost response cannot create a second activity. HTTP 429 stops the batch without marking
  the run failed. One failing file never stops the others.
- **Transport**: `HttpUrlConnectionStravaTransport` (JDK `HttpURLConnection`, available on JVM and Android),
  no new dependency; 15 s connect / 30 s read timeouts, no redirects, responses capped at 1 MiB. The
  `StravaHttpTransport` interface is the test seam.
- **Android adapter** (`com.runpro.mobile.strava`): `StravaUploadRunner` runs all Strava work on one
  background thread (`runpro-strava`), so automatic and manual uploads never race. `FitReceiverService`
  schedules the automatic upload only after `FitInbox` stored and acknowledged the run and the channel
  output is closed, next to the Health Connect export. `StravaActivity` is the app's launcher: connect,
  disconnect, the auto-upload switch (off by default), "Upload pending runs" (manual upload and retry),
  and each run's status with an "Open on Strava" link. The manifest adds `INTERNET`.

## Consequences

- R1–R8 are unchanged: the watch, the journal and the Data Layer ack are not touched, Strava work starts
  after the phone has acknowledged the run, and `files/runs` is only read.
- This APK contains the client secret and must stay a private build installed by the owner. Before any
  public distribution (M6 Play Console tracks, or the M7 phone release) the token exchange and refresh
  must move to a backend that holds the secret, with the phone keeping only the per-athlete tokens; that
  needs its own issue and ADR. A leaked secret is rotated in Strava's API settings.
- Automatic upload is retried only by the next manual "Upload pending runs" (or the next run's automatic
  upload of that run): there is no background scheduler. A process killed mid-upload leaves the ledger in
  `PROCESSING` or pending, and the next pass resumes it.
- Strava's per-application rate limits are far above one runner's use; a 429 only postpones the batch.

## Evidence

- `StravaOAuthAndPolicyTest`: redacted `toString`, authorization URL, callback validation (state, scope,
  host, denial, missing code) and the opt-in auto-upload policy.
- `StravaPendingAuthTest`: durable single-use state across a restart, replay refused, a newer attempt
  invalidates the older state, unconfigured builds.
- `StravaUploaderTest`: full connect → upload as Run → poll → link flow across restarts with the FIT file
  byte-identical, proactive refresh and rotation, single 401 retry, resume polling instead of re-uploading,
  duplicate resolution, opt-in auto upload, failure isolation and manual retry, replaced files, revoked
  refresh, rate limit, unconfigured build.
- `HttpUrlConnectionStravaTransportTest`: the real transport against a local HTTP server (form and
  multipart encoding).
- `StravaActivityTest` (`:mobile`, Robolectric): `INTERNET`, launcher and callback intent filter, the
  unconfigured screen, connect → callback → tokens stored, upload → link opens the activity URL.
- A run appearing on Strava with matching distance and time is the owner's check in `docs/FIELD_TESTING.md`.
