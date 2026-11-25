# Instructions for coding agents: RunPro

RunPro is a running-only Wear OS app (target device: Galaxy Watch 5 Pro) whose first priority is
reliability: it must never lose a workout and must keep external BLE sensors working.
Read `docs/GUIDE.md` (how the harness works), `docs/PLAN.md`, `docs/ARCHITECTURE.md` and `docs/RELIABILITY.md` before your first task.

## The loop

1. `./rp status` – see milestones, work in progress, what waits for the owner.
2. `./rp next` – pick the next ready issue (dependencies closed, not assigned). Work on one issue at a time.
3. `./rp start <N>` – assigns it, marks it In Progress on the board, creates `issue-<N>-<slug>` from `main`.
4. Read the issue (`gh issue view <N>`), implement it, write tests first for anything in `:core:*`.
5. `./rp check` – must pass before every commit you push.
6. Commit (`<Imperative summary> (#N)`), then `./rp pr` – opens the PR that closes the issue.
7. `./rp handoff "done / next / open problems"` before ending the session if anything is left.

If the work needs the owner (testing on the watch, a decision, credentials), comment on the issue,
add the label `needs-owner`, and stop. Never guess field results.

## Rules

- The invariants R1–R8 in `docs/RELIABILITY.md` are not negotiable. A PR that touches recording, journal,
  BLE or export must say which invariants it affects and which tests prove them.
- `:core:*` and `:tools:*` are pure Kotlin/JVM: no Android imports. Android code stays in thin adapters.
- Every field bug becomes: a trace in `testdata/traces/`, a failing test, a fix, a row in the failure
  catalogue of `docs/RELIABILITY.md`.
- New dependencies or architecture changes need an ADR in `docs/adr/`.
- New tasks discovered while working: add an issue with `gh issue create` (milestone + labels), do not
  widen the current PR.
- English for code, comments, commits, issues and docs.
- Commits are authored by the owner only: no `Co-Authored-By` trailers, no "Generated with" lines.
- Never commit secrets (Strava client secret, keystores). Use `local.properties` / GitHub secrets.

## Build and test

- `./rp check` (wraps `./gradlew check`): unit tests, replay tests, ktlint, detekt, lint.
- `./rp install`: install the debug build on the watch (see `docs/WATCH_SETUP.md`).
- Field tests: `docs/FIELD_TESTING.md` (done by the owner).

## Map

- `docs/GUIDE.md` harness guide; keep it current with concrete examples when `./rp` or the workflow changes.
- `docs/` plan, architecture, reliability, field testing, ADRs, handoff log.
- `plan/backlog.md` initial backlog (already synced to GitHub issues; GitHub is the source of truth now).
- `plan/config.json` repo and GitHub Project ids used by `./rp`.
