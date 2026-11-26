# RunPro harness guide

How to drive RunPro development with coding agents (Claude Code or Codex) and the `./rp` harness.
Everything runs from the repository root: `/Users/juridirocco/development/RunPro`.

## 1. The pieces

| Piece | Where | What it is for |
|---|---|---|
| `./rp` | repo root | CLI that wraps git + GitHub: status, next task, start, launch an agent (Claude Code or Codex), check, PR, handoff, install |
| Backlog | GitHub issues (seeded from `plan/backlog.md`) | One issue = one branch = one PR |
| Milestones | GitHub milestones M0–M7, with due dates | Release phases (see `docs/PLAN.md`) |
| Board | https://github.com/users/jdirocco/projects/1 | Todo / In Progress / Done |
| Agent rules | `AGENTS.md` (loaded by Claude Code through `CLAUDE.md`) | The loop and the rules every agent follows |
| Skill | `.claude/skills/runpro-task/` | "Take the next task" in one sentence |
| Invariants | `docs/RELIABILITY.md` | R1–R8: what can never break |
| Field tests | `docs/FIELD_TESTING.md` + "Field report" issue template | Your runs on the watch, fed back to agents |
| Watch setup (Italian) | `docs/it/CONFIGURAZIONE_OROLOGIO.md` | Developer options, Wi-Fi ADB, install, permissions, battery, strap |
| Handoff log | `docs/HANDOFF.md` | Notes between sessions |
| CI | `.github/workflows/ci.yml` | Runs on every push to `main` and every PR: `./gradlew check :wear:assembleDebug` (tests, ktlint, detekt, lint). Artifacts: `wear-debug-apk` and `reports` (14 days) |

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
  #5 Chaos injector for replay tests
      running 12m: codex gpt-6.1-sol on openai (cloud), auto, pid 87776
  #55 rp: --worktree run from inside a worktree nests the new worktree under it
      no agent running (last: codex devstral-64k:latest on ollama (local), exited 0 25m ago)
  #47 Field validation of the phone app
      no agent running
== Decisions for the owner (needs-owner: answer in the issue, then remove the label)
  none
== Field tests to run now (./rp install, then docs/FIELD_TESTING.md)
  none
== Field tests not runnable yet
  #22 Field validation of BLE sensors  (waits for #19, #20)
  #14 Field validation of the recording core  (waits for #9, #12)
== Open field reports (for an agent to triage)
  #45 Field report: HR gap after tunnel
== Open PRs
  !40 Gradle multi-module project skeleton (#1) (issue-1-gradle-multi-module-project-skeleton)
== Next ready
  #4 Replay trace format, recorder and replayer
```

Under each issue **In progress** a second line says whether an agent works on it and where it runs: runner,
model, provider and `local` or `cloud`. `./rp agent` records each run in `.git/rp-agents/<N>.json` (shared by
all worktrees) with the model resolved even when `plan/config.json` leaves it `null`: Codex reads
`~/.codex/config.toml` (`--oss` means local, with `--local-provider`), Claude reads `--model`,
`ANTHROPIC_MODEL` or `.claude/settings.json` (a localhost `ANTHROPIC_BASE_URL` means local). Agents not
started by `./rp agent` with a record are still found from the process list. When the process list cannot be read (a
sandbox such as Codex `workspace-write` denies `ps`), the section says so and each issue shows
`agent status unknown (process list unavailable; recorded: ...)` with what the run record holds; the rest of
the report renders as usual. "no agent running" on an issue
nobody waits for means it was left In Progress by mistake: unassign it, drop `status:in-progress` and set the
board back to Todo.

Your to-do list is the first two owner sections:

- **Decisions for the owner**: issues labelled `needs-owner`. Read it with `gh issue view <N>`, answer in a
  comment, remove the label (`gh issue edit <N> --remove-label needs-owner`). You can also open one yourself
  for a decision you want to think about: `gh issue create --label needs-owner ...`.
- **Field tests to run now**: `field-test` issues whose dependencies are all closed, so the code to test exists.
  Install with `./rp install` and follow the scenarios named in the issue (`docs/FIELD_TESTING.md`).
- **Field tests not runnable yet** is only a preview: each one waits for the listed open issues.
- **Open field reports** are for the agents, not for you (see workflow C).

Dependencies come from `plan/backlog.md` and from the `**Depends on:** #a, #b` line in the issue body.

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

With `--worktree` the branch is created in `../RunPro-worktrees/<branch>` and this checkout is left alone
(use it for parallel work). `git worktree add` does not carry ignored files, so `./rp` copies this
checkout's `local.properties` (the `sdk.dir` line) into the new worktree; an existing one is kept.

### `./rp agent`: launch Claude Code or Codex on an issue

```
./rp agent [N|next] [claude|codex] [--model M] [--auto] [--headless] [--worktree] [--prompt TEXT] [--dry-run] [-- ARGS]
```

It does `./rp start` for you (assign, branch, In Progress), then launches the agent CLI with a prompt that
tells it which issue and branch it is on and to follow `AGENTS.md`. Both CLIs read `AGENTS.md`
(Claude Code through `CLAUDE.md`).

| Option | Default | Effect |
|---|---|---|
| `N` / `next` | `next` | Issue to work on; `next` = first ready issue |
| `claude` / `codex` (or `--runner`) | `agents.default_runner` in `plan/config.json` (`claude`) | Which agent CLI |
| `--model M` | `agents.<runner>.model` in `plan/config.json`; `null` = the CLI's own default | Model passed as `claude --model M` or `codex -m M` |
| `--auto` | off | Autonomous mode: the agent never asks you anything (see below). It pushes and opens the PR itself with `./rp pr`; it never merges |
| `--headless` | off (interactive) | `claude -p` / `codex exec`: no chat, the agent runs to the end and exits. Without `--auto` it commits but does not push and ends with `./rp handoff` |
| `--worktree` | off | Work in `../RunPro-worktrees/<branch>` |
| `--prompt TEXT` | none | Extra instructions appended to the prompt |
| `--dry-run` | off | Print the command without starting the issue or the agent |
| `-- ARGS` | none | Passed unchanged to the agent CLI |

Examples:

```
$ ./rp agent                                   # next ready issue, Claude Code, default model, interactive
$ ./rp agent 16 codex                          # issue #16 with Codex, default model
$ ./rp agent 16 claude --model opus            # Claude Code with a chosen model
$ ./rp agent next codex --model gpt-5.5 --headless --worktree
$ ./rp agent 1 claude --auto                  # works without asking, opens the PR
$ ./rp agent next codex --auto --headless --worktree   # fully unattended
$ ./rp agent 4 --prompt "Use kotlinx.serialization for the trace" -- --add-dir ../traces
$ ./rp agent 3 codex --dry-run
# #3 Core domain model
# runner=codex model=(tool default) mode=interactive cwd=/Users/juridirocco/development/RunPro
codex -s workspace-write -c 'sandbox_workspace_write.writable_roots=[...]' -c sandbox_workspace_write.network_access=true -C /Users/juridirocco/development/RunPro 'You are working on RunPro issue #3: ...'
```

Change the defaults in `plan/config.json`:

```json
"agents": {
  "default_runner": "claude",
  "claude": {
    "model": null,
    "headless_args": ["--permission-mode", "auto"],
    "auto_args": ["--permission-mode", "bypassPermissions"]
  },
  "codex": {
    "model": null,
    "sandbox_writable_roots": [".git", "~/.gradle", "~/.m2", "~/.android", "~/.konan"],
    "network_access": true,
    "headless_args": [],
    "auto_args": ["-a", "never"]
  }
}
```

#### Autonomous mode (`--auto`)

The agent works without asking for any authorisation and without waiting for you:

| Runner | What `--auto` does |
|---|---|
| Claude Code | `--permission-mode bypassPermissions`: every tool call is allowed, no prompts |
| Codex | `-a never` (interactive) or `codex exec` (headless, never asks): no approval prompts. Commands still run in the Codex sandbox (see below) |

The prompt also changes: the agent must not ask questions, opens the PR with `./rp pr` when `./rp check`
passes, never merges, and writes `./rp handoff` if it cannot finish. Your control point is the PR review.

Safety:

- Use `--worktree` with `--auto`, so the agent works in its own folder and cannot disturb your checkout.
- Claude Code with `bypassPermissions` has no sandbox: it can run any command as your user. Use it only on
  this repository, and do not keep secrets (keystores, Strava secrets) in the folder.
- To make Claude less permissive, set `claude.auto_args` to `["--permission-mode", "auto"]`
  (a classifier blocks risky actions instead of asking you).

#### Codex sandbox

Codex always runs with `-s workspace-write` plus:

- write access to `sandbox_workspace_write.writable_roots` = the repository `.git` (otherwise commits fail:
  the sandbox protects `.git`) and the build caches (`~/.gradle`, `~/.m2`, `~/.android`, `~/.konan`);
- with `--worktree`, also the worktree's own git dir (`.git/worktrees/<branch>`). Codex reads the worktree's
  `.git` file and makes that directory read-only even though it lies under `.git`, so without it `git add`
  fails with `index.lock: Operation not permitted`. `./rp agent` adds it at launch; `--dry-run` shows it:
  ```
  $ ./rp agent 6 codex --worktree --dry-run
  codex -s workspace-write -c 'sandbox_workspace_write.writable_roots=[".../RunPro/.git", ...,
    ".../RunPro/.git/worktrees/issue-6-watch-development-setup-and-install-scri"]' ...
  ```
- network access (`network_access: true`) for Gradle dependencies, `gh` and `git push`.

Agents use the `gh` CLI for issue comments and labels: in `-a never` mode a GitHub connector/MCP tool is
rejected because it needs an approval. Edit these in `plan/config.json`. To drop the sandbox completely (not recommended), set
`codex.auto_args` to `["--dangerously-bypass-approvals-and-sandbox"]`.

Notes:

- After a headless run: read `docs/HANDOFF.md`, `git log`, then `./rp check` and `./rp pr` from the
  branch (or its worktree).

### `./rp check`: local quality gate

Runs `./gradlew check --continue`: Python harness tests, JVM tests, replay tests, local Android UI tests,
ktlint, detekt and Android lint. Chaos tests join this gate as their issues are implemented.

For the Gradle skeleton, install JDK 21 and Android SDK platform 37.0 with build-tools 37.0.0. Point
`ANDROID_HOME` at the SDK, or create an ignored `local.properties` containing `sdk.dir=/path/to/sdk`.
In a worktree without `local.properties` and with neither `ANDROID_HOME` nor `ANDROID_SDK_ROOT` set,
`./rp check` first copies the file from the main checkout (this fixes worktrees created before #52):

```
$ cd ../RunPro-worktrees/issue-3-core-domain-model && ./rp check
Copied local.properties from the main checkout to .../issue-3-core-domain-model/local.properties
```
No globally installed Gradle is needed. The first build downloads Gradle and Maven dependencies.

```
$ java -version                              # must report Java 21
$ sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
$ ./rp check                                 # all nine modules + root build-script formatting
$ ./gradlew :wear:assembleDebug               # wear/build/outputs/apk/debug/wear-debug.apk
$ ./gradlew :core:model:test :tools:replay:test # JVM tests without a watch or emulator
$ python3 -B -m unittest discover -s tests -v  # harness tests without an SDK or device
$ ./gradlew ktlintFormat                      # format Kotlin and Gradle Kotlin scripts
$ ./gradlew :tools:replay:generateSyntheticTraces  # rewrite testdata/traces/ after changing SyntheticTraces
```

Replay traces live in `testdata/traces/` (see its `README.md`; format in `docs/adr/0004-replay-trace-format.md`).
A test replays one like this:

```kotlin
val replayer = Replayer(Trace.read(Paths.get(System.getProperty("runpro.traces"), "strap-dropout.jsonl.gz")))
val strap = CollectingListener()                    // any SensorListener: the engine, a fake
replayer.source(SyntheticTraces.STRAP).start(strap)  // sources nobody starts are dropped
replayer.run()                                       // ReplaySpeed.UNLIMITED by default
assertEquals(1_125, strap.samples.size)
```

Chaos faults are declared in the test, without changing a trace file:

```kotlin
val plan = ChaosPlan(
    ReplayFault.BleDisconnect(10.seconds, 3.seconds, SyntheticTraces.STRAP, gattStatus = 133),
    ReplayFault.SilentStall(20.seconds, 6.seconds, SyntheticTraces.STRAP),
    ReplayFault.GpsLoss(30.seconds, 5.seconds),
    ReplayFault.ClockJump(40.seconds, (-3600).seconds)
)
val replayer = Replayer(trace, chaos = plan)
replayer.sources.forEach { it.start(consumer) }
replayer.run()
```

Offsets use monotonic time from the first record. Outages exclude the end boundary; GPS loss removes
location, distance, speed and elevation while wrist HR/cadence continue. Clock jumps adjust wall time
without changing pacing. `BleDisconnect` without a status models a normal disconnect; status 133
models GATT_ERROR. Boundaries also run in gaps and after the trace ends. Same-source outages cannot
overlap. `ReplayResult` counts delivered/dropped effective records, including injected sensor events,
excluding suppressed records and fault callbacks.

`ReplayFault.ProcessDeath(at)` and `ReplayFault.StorageFull(at)` require a `ReplayFaultHandler` passed
as `faultHandler` to `Replayer`. The callback must replace the engine from its journal and rebind the
sources, or make storage writes fail (for example, set `FaultInjectingOutputStream.full = true`). Missing
handlers fail early; callback exceptions fail the test. See `ChaosRecordingReplayTest` for a sample of
every fault type using the real engine and an input-log journal double. Real journal recovery is covered
separately by `CrashRecoveryReplayTest` (ADR 0007).
BLE fallback/reconnect and full-storage saving still need their own invariant tests; this sample does
not establish them. Scheduling details and limits are in ADR 0006.

The JVM modules share build contract tests from `build-support/src/test/kotlin`: they verify Java 21
and that Android framework classes are absent. The watch launcher has a Robolectric Compose test on
API 30. This does not replace a physical install or field testing. The phone module is an empty APK
placeholder with no launcher activity. See `docs/adr/0002-gradle-build-foundation.md` for version choices.

CI runs the same gate plus `:wear:assembleDebug` on every PR and push to `main`. When it fails, open the
`reports` artifact (test, ktlint, detekt and lint reports). To try a PR build on the watch without
building locally:

```
$ gh run list --branch issue-2-ci-on-github-actions --limit 1   # find the run id
$ gh run download <run-id> -n wear-debug-apk -D /tmp/runpro-apk
$ adb install -r /tmp/runpro-apk/wear-debug.apk
```

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

### `./rp install [SERIAL]`: put the build on the watch

Follow [WATCH_SETUP.md](WATCH_SETUP.md) to enable developer options, pair, and connect the Galaxy Watch
5 Pro over Wi-Fi ADB. Put SDK `platform-tools` on `PATH`. Once connected:

```sh
./rp install                                 # use the sole connected ADB device
ANDROID_SERIAL=192.0.2.10:39517 ./rp install   # select the watch when a phone is also connected
./rp install 192.0.2.10:39517                 # explicit serial overrides ANDROID_SERIAL
```

Use your watch's serial from `adb devices -l` in place of the example. The command rejects ambiguous,
offline, unauthorized and non-watch targets before building. It builds `:wear:assembleDebug` and installs
`wear/build/outputs/apk/debug/wear-debug.apk` with `adb -s SERIAL install -r`, retaining app data.
Finish any active workout before installing. Confirm `Success` and open RunPro on the watch; the
physical acceptance check and troubleshooting steps are in [WATCH_SETUP.md](WATCH_SETUP.md).

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

From the terminal, with either agent:

```
$ ./rp agent              # Claude Code
$ ./rp agent codex        # Codex
```

Or open Claude Code in `/Users/juridirocco/development/RunPro` and say "Take the next RunPro task"
(the `runpro-task` skill does the same loop). Either way the agent implements with tests, runs
`./rp check`, asks before `./rp pr`, and you review and merge on GitHub.

### B. Several agents in parallel

Only on issues with no dependency on each other and in different modules (for example #3 `:core:model`
and #6 watch setup docs). Give each one its own worktree, in separate terminals:

```
$ ./rp agent 3 claude --worktree
$ ./rp agent 6 codex --worktree
```

Each works in `../RunPro-worktrees/<branch>`. When done, run `./rp pr` inside that folder, and after the
merge remove it with `git worktree remove ../RunPro-worktrees/<branch>`.

### C. A field test

1. `./rp status` shows e.g. `#14 Field validation of the recording core` waiting for you.
2. Install the build (`./rp install`), follow the scenario in `docs/FIELD_TESTING.md`.
3. After the run, export the logs from Diagnostics and open a **Field report** issue with the files.
4. Tell an agent: "Triage field report #45". It adds the trace to `testdata/traces/`, writes a failing
   test, fixes it, and adds a row to the failure catalogue in `docs/RELIABILITY.md`.
5. Close the field-test issue when the exit criterion is met.

### D. The agent needs you

The agent comments on the issue, labels it `needs-owner` and stops. You see it under "Decisions for the
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
