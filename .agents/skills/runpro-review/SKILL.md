---
name: runpro-review
description: Review an open RunPro pull request or issue branch against reliability invariants R1-R8, pure JVM module boundaries, tests, and PR rules without merging. Use when asked to review a PR, audit an agent branch, or prepare a merge verdict.
---

1. Inspect the PR and linked issue with the `gh` CLI: `gh pr view <PR> --json number,title,body,headRefName,commits,files`, `gh pr diff <PR>`, `gh pr checks <PR>`, and `gh issue view <N>`.
2. Verify scope and hygiene:
   - One issue = one branch (`issue-<N>-<slug>`) = one PR with `Closes #<N>`.
   - No unrelated edits or scope creep; commit messages end with `(#<N>)` and carry no `Co-Authored-By` or "Generated with" lines.
3. Verify architecture and documentation rules (`AGENTS.md`, `docs/ARCHITECTURE.md`):
   - No Android framework imports inside `:core:*` or `:tools:*`.
   - New dependencies or architectural decisions have an ADR in `docs/adr/`.
   - Changes to `./rp` or the workflow update `docs/GUIDE.md` with concrete examples; changes to watch setup, permissions, or field procedures also update `docs/it/`.
4. Verify reliability invariants (`docs/RELIABILITY.md` R1–R8):
   - Check that the PR's *Reliability impact* section accurately names every affected invariant (`R1–R8`, or `None` when strictly outside recording, journal, BLE, sync, and export).
   - Read the added/updated unit, replay, and chaos tests to confirm the assertions genuinely prove those invariants rather than happy-path behavior.
5. Verify quality gates: confirm GitHub Actions CI (`check`) is green, and run `./rp check` locally if reviewing inside the branch checkout or worktree.
6. Report a concise verdict (`READY TO MERGE` or `CHANGES NEEDED`) with file and line references for any findings. Never run `gh pr merge`; merging is reserved for the owner.
