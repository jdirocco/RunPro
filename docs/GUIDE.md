# RunPro harness guide

How to drive RunPro development with coding agents (Claude Code or others) and the `./rp` harness.
Everything runs from the repository root: `/Users/juridirocco/development/RunPro`.

## 1. The pieces

| Piece | Where | What it is for |
|---|---|---|
| `./rp` | repo root | CLI that wraps git + GitHub: status, next task, start, check, PR, handoff, install |
| Backlog | GitHub issues (seeded from `plan/backlog.md`) | One issue = one branch = one PR |
| Milestones | GitHub milestones M0–M6 | Release phases (see `docs/PLAN.md`) |
| Board | https://github.com/users/jdirocco/projects/1 | Todo / In Progress / Done |
| Agent rules | `AGENTS.md` (loaded by Claude Code through `CLAUDE.md`) | The loop and the rules every agent follows |
| Skill | `.claude/skills/runpro-task/` | "Take the next task" in one sentence |
| Invariants | `docs/RELIABILITY.md` | R1–R8: what can never break |
| Field tests | `docs/FIELD_TESTING.md` + "Field report" issue template | Your runs on the watch, fed back to agents |
| Handoff log | `docs/HANDOFF.md` | Notes between sessions |
| CI | `.github/workflows/ci.yml` | Runs on every push and PR |

## 2. Roles

- **You (owner)**: decide priorities, review and merge PRs, run field tests on the Galaxy Watch 5 Pro,
  answer issues labelled `needs-owner`.
- **Agent**: picks ready issues, writes code and tests, opens PRs, never merges, never invents field results.

## 3. `./rp` commands with examples

### `./rp status`: where are we?

```
$ ./rp status
== jdirocco/RunPro
  M0 Foundations           [#######             ] 2/6
  M1 Recording core        [                    ] 0/8
  ...
== In progress
  #3 Core domain model
== Waiting for the owner (field tests, decisions)
  #14 Field validation of the recording core
== Open PRs
  !40 Gradle multi-module project skeleton (#1) (issue-1-gradle-multi-module-project-skeleton)
== Next ready
  #4 Replay trace format, recorder and replayer
```

"Waiting for the owner" lists issues labelled `field-test`, `field-report` or `needs-owner`: that is your to-do list.

### `./rp next`: what should be done now?

```
$ ./rp next
#1 Gradle multi-module project skeleton  [M0 Foundations]
Start with: ./rp start 1
```

An issue is *ready* when it is open, unassigned, not blocked, not a field test, and all the issues it
depends on are closed. Lower milestones come first.

### `./rp start <N>`: begin an issue

```
$ ./rp start 1
On branch issue-1-gradle-multi-module-project-skeleton. Issue #1 is In Progress.
```

It refuses to run with uncommitted changes. It updates `main`, creates (or reuses) the branch, assigns
the issue to you, adds `status:in-progress` and moves the card to In Progress.

### `./rp check`: local quality gate

Runs `./gradlew check --continue` (unit, replay and chaos tests, ktlint, detekt, lint). Before issue #1
there is no Gradle project and it only prints a notice.

### `./rp pr`: open the pull request

```
$ git commit -m "Add Gradle multi-module skeleton (#1)"
$ ./rp pr
https://github.com/jdirocco/RunPro/pull/40
```

Pushes the branch and opens a PR titled `<issue title> (#N)` with `Closes #N` and the checklist from
`.github/pull_request_template.md`. When you merge it, the issue closes and its dependents become ready.

### `./rp handoff "<text>"`: leave notes for the next session

```
$ ./rp handoff "Journal writer done, reader missing corrupt-tail test. Next: CRC on partial records."
```

Appends a dated entry to `docs/HANDOFF.md` with the current branch.

### `./rp install`: put the build on the watch

Builds `:wear` and installs it with `adb` on the watch connected over Wi-Fi ADB (setup comes with issue #6,
`docs/WATCH_SETUP.md`).

### `./rp sync`: push new backlog items to GitHub

Creates missing labels and milestones, then an issue for every block in `plan/backlog.md` with an empty
`issue:` line, adds it to the board and writes the number back. Use it when you add a batch of tasks:

```
### m3-race-mode: Race mode with target pace
- milestone: M3
- labels: area:ui, type:feature
- depends: m3-data-screens
- issue:

Show the gap to a target finish time...
```

```
$ ./rp sync
#39 Race mode with target pace
$ git commit -am "Add race mode to the backlog" && git push
```

For a single task, `gh issue create` (or the "Task" template on GitHub) is enough.

## 4. Typical workflows

### A. Let an agent take the next task

Open Claude Code in `/Users/juridirocco/development/RunPro` and say:

> Take the next RunPro task.

The `runpro-task` skill runs `./rp status`, `./rp next`, `./rp start N`, implements with tests, runs
`./rp check`, asks before `./rp pr`, and reports the PR link. You review and merge on GitHub.

To pick a specific issue: "Work on RunPro issue #16".

### B. Several agents in parallel

Only on issues with no dependency on each other and in different modules (for example #3 `:core:model`
and #6 watch setup docs). Use one git worktree or one Claude Code session with its own worktree per issue,
so branches do not collide.

### C. A field test

1. `./rp status` shows e.g. `#14 Field validation of the recording core` waiting for you.
2. Install the build (`./rp install`), follow the scenario in `docs/FIELD_TESTING.md`.
3. After the run, export the logs from Diagnostics and open a **Field report** issue with the files.
4. Tell an agent: "Triage field report #45". It adds the trace to `testdata/traces/`, writes a failing
   test, fixes it, and adds a row to the failure catalogue in `docs/RELIABILITY.md`.
5. Close the field-test issue when the exit criterion is met.

### D. The agent needs you

The agent comments on the issue, labels it `needs-owner` and stops. You see it under "Waiting for the
owner" in `./rp status`. Answer in the issue, remove the label, and the issue is ready again.

### E. A design decision

New dependency or architecture change: the agent writes `docs/adr/NNNN-title.md` in the same PR.
You accept it by merging.

## 5. Labels

| Label | Meaning |
|---|---|
| `type:task`, `type:feature`, `type:docs`, `bug` | Kind of work |
| `area:*` | Module or topic (core, wear, ble, ui, mobile, dynamics, testing, build, release) |
| `reliability` | Touches R1–R8; the PR must name the invariants and their tests |
| `field-test` | Needs you on the watch; never picked by `./rp next` |
| `field-report` | Result of a run, with trace and logs |
| `needs-owner` | Waiting for your answer |
| `status:in-progress`, `status:blocked` | Set by `./rp start` / by hand |

## 6. Rules worth remembering

- One issue → one branch `issue-N-slug` → one PR that closes it. Branch from `main`.
- Commit messages end with `(#N)`; commits are yours only, no `Co-Authored-By` lines.
- Agents never merge and never push without your OK.
- Every field bug becomes a trace plus a test before the fix.
- GitHub is the source of truth for tasks; `plan/backlog.md` only seeds new ones.

## 7. Keeping this guide current

When `./rp`, the labels or the workflow change, update this guide in the same PR with a concrete example.
