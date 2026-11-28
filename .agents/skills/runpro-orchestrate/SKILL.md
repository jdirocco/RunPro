---
name: runpro-orchestrate
description: Orchestrate parallel RunPro issues across Claude Code, Codex, and Antigravity CLI workers using ./rp, complexity-based routing, isolated worktrees, and PR audits. Use when asked to act as orchestrator, dispatch parallel tasks, or coordinate multiple agents on the backlog.
---

1. **Survey the board and available runners**:
   - Run `./rp runners` to check which agent CLIs (`claude`, `codex`, `agy`) are installed, instead of `command -v` or `which` (which silently hide symlinked installs such as `~/.local/bin/claude -> ~/.local/share/claude/...` when a command sandbox blocks `stat` on `~/.local/share/claude`). Treat every runner reported as `installed` — including `sandbox-blocked` — as available, and verify Ollama is reachable (`ollama list`) before choosing a local Ollama worker. Never dispatch to a `missing` binary or offline local provider.
   - Run `./rp status` and `./rp next --all` (outside the command sandbox, e.g. `BypassSandbox: true` in Antigravity, whenever `gh` config or network is blocked by the sandbox) to inspect ready issues, in-progress runs, open PRs, `needs-owner` decisions, and runnable `field-test` issues. Use the `gh` CLI for GitHub operations, not an MCP connector.
   - Flag stale `status:in-progress` issues (`no agent running` with no open PR or `needs-owner` label) to the owner before resetting or reassigning them.
2. **Audit existing open PRs**:
   - Run the `runpro-review` skill on any unreviewed open PRs so the owner gets merge verdicts alongside new work.
3. **Select a conflict-free parallel batch**:
   - Inspect candidate ready issues (`gh issue view <N>`) for touched Gradle modules, files, and `area:*` / `reliability` labels.
   - Pick at most 2–3 issues per batch that have **no dependency on each other** and modify **disjoint modules/files** (for example, one `:core:*` issue and one `:wear` or `:mobile` issue; never two `:wear` UI issues together).
4. **Route each issue by complexity and reliability impact**:
   - **`XS` / `S` (`complexity:xs`, `complexity:s`, non-`reliability`)**: Prefer Codex on local Ollama when running (`./rp agent <N> codex --auto --headless --worktree -- --oss --local-provider ollama`, adding `-m <model>` if overriding `~/.codex/config.toml`), or fast Antigravity (`./rp agent <N> agy --model flash --auto --headless --worktree`).
   - **`M` (`complexity:m`)**: Route to standard cloud Codex or Antigravity (`./rp agent <N> codex --auto --headless --worktree` or `./rp agent <N> agy --auto --headless --worktree`).
   - **`M-L` / `L` (`complexity:ml`, `complexity:l`) or any `reliability` (`R1–R8`) issue**: Route to an available frontier cloud runner (`./rp agent <N> claude --auto --headless --worktree`, `./rp agent <N> codex --auto --headless --worktree`, or `./rp agent <N> agy --auto --headless --worktree -- --effort high`).
   - Use `--dry-run` first when verifying the resolved `model`, `provider`, and `cwd`.
5. **Dispatch workers in isolated worktrees and wait reactively**:
   - Start each `./rp agent <N> ... --auto --headless --worktree` process in the background (relying on completion notifications rather than `sleep` polling). When `./rp runners` marks a runner `sandbox-blocked` (such as `claude` under `~/.local/share/claude`) or `gh`/network requires unsandboxed execution, run `./rp agent` outside the orchestrator's command sandbox (`BypassSandbox: true` in Antigravity) rather than skipping `claude` or asking the owner to modify Claude's settings. Each worker runs inside `../RunPro-worktrees/issue-<N>-<slug>`, passes `./rp check`, commits without trailers, and opens its PR via `./rp pr` (or writes `./rp handoff` / adds `needs-owner` if blocked).
6. **Audit outcomes and report to the owner**:
   - Once workers exit, check `./rp status` and inspect `docs/HANDOFF.md` in any worktree that stopped without opening a PR.
   - Run the full `runpro-review` checklist on every newly opened PR (`R1–R8` invariant tests, pure-JVM `:core:*` / `:tools:*` boundary, ADRs, commit hygiene, and GitHub Actions CI). If fixes are needed, either run a follow-up fix in that worktree or report exact findings.
   - Never run `gh pr merge`. Summarize the batch for the owner: `READY TO MERGE` PR links, `CHANGES NEEDED` verdicts, `needs-owner` questions, and newly unblocked `field-test` issues. Clean up merged worktrees with `git worktree remove ../RunPro-worktrees/<branch>`.
