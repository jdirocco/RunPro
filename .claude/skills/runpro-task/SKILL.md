---
name: runpro-task
description: Work on the next RunPro issue end to end with the ./rp harness. Use when asked to "take the next task", "work on issue N", or continue RunPro development.
---

1. Run `./rp status`. If the user named an issue, use it; otherwise `./rp next`.
2. `./rp start <N>`, then `gh issue view <N>` and read the docs it points to.
3. Plan the change in a few steps; tests first for `:core:*` code (replay/chaos tests for reliability work).
4. Implement. Keep the PR to this issue only; open new issues for anything else found.
5. `./rp check` until green. Commit `<summary> (#N)` with no Co-Authored-By trailer.
6. Ask the user before `./rp pr` (it pushes). Then report the PR link.
7. If the work needs the watch or a decision: comment on the issue, label `needs-owner`, stop.
8. If you stop with work left: `./rp handoff "done / next / open problems"`.
