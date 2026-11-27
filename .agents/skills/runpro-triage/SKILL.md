---
name: runpro-triage
description: Triage a RunPro field-report issue by turning real watch traces and sensor logs into a committed replay trace, a failing test, a verified fix, and a failure catalogue entry. Use when asked to triage a field report or reproduce a field bug.
---

1. Read the report (`gh issue view <N>`) and inspect attached logs, sensor traces, and watch conditions. Never guess field behavior: if logs or reproduction details are missing, ask on the issue with `gh issue comment <N>`, add `--add-label needs-owner`, and stop.
2. Start the issue branch (`./rp start <N> --worktree` if not already started).
3. If sensor, GPS, BLE, or recording events are involved, place the recorded trace in `testdata/traces/` following `testdata/traces/README.md` and `docs/adr/0004-replay-trace-format.md` (for pure UI issues with no sensor stream, note `none (UI, no sensor data)` as in `docs/RELIABILITY.md`).
4. Write a failing test first (replay/chaos test in `:tools:replay` or `:core:*`, or adapter unit test for UI/platform issues) that reproduces the exact failure and identifies any affected R1–R8 invariant.
5. Implement the minimal fix and run `./rp check` until all modules and linters pass.
6. Append a row to the *Failure catalogue* in `docs/RELIABILITY.md` (`Date | Issue | What happened | Trace | Test`).
7. Commit with `<Imperative summary> (#<N>)` (no `Co-Authored-By` trailer). Ask the user before running `./rp pr` unless running in `--auto` mode.
