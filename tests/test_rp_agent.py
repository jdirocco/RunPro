"""Agent launch tests: the Codex sandbox must let the agent commit in a linked worktree."""

import contextlib
import errno
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


loader = importlib.machinery.SourceFileLoader("rp", str(Path(__file__).resolve().parents[1] / "rp"))
spec = importlib.util.spec_from_loader(loader.name, loader)
rp = importlib.util.module_from_spec(spec)
loader.exec_module(rp)


def git(*args, cwd):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


def writable_roots(args):
    value = next(a for a in args if a.startswith("sandbox_workspace_write.writable_roots="))
    return json.loads(value.split("=", 1)[1])


class CodexSandboxTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        base = Path(directory.name).resolve()
        self.root = base / "repo"
        self.root.mkdir()
        git("init", "-q", "-b", "main", cwd=self.root)
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", "init",
            cwd=self.root)
        self.worktree = base / "repo-worktrees" / "issue-3-core"
        git("worktree", "add", "-q", "-b", "issue-3-core", str(self.worktree), cwd=self.root)
        self.acfg = rp.agent_config()
        patcher = patch.object(rp, "ROOT", str(self.root))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_main_checkout_gets_only_configured_roots(self):
        roots = writable_roots(rp.codex_sandbox_args(self.acfg, str(self.root)))
        self.assertIn(str(self.root / ".git"), roots)
        self.assertFalse(any("/worktrees/" in r for r in roots))

    def test_linked_worktree_gets_its_own_git_dir(self):
        roots = writable_roots(rp.codex_sandbox_args(self.acfg, str(self.worktree)))
        self.assertIn(str(self.root / ".git"), roots)
        self.assertIn(str(self.root / ".git" / "worktrees" / "issue-3-core"), roots)

    def test_rp_run_from_a_worktree_still_uses_the_shared_git_dir(self):
        with patch.object(rp, "ROOT", str(self.worktree)):
            roots = writable_roots(rp.codex_sandbox_args(self.acfg, str(self.worktree)))
        self.assertIn(str(self.root / ".git"), roots)
        self.assertNotIn(str(self.worktree / ".git"), roots)

    def test_missing_worktree_is_ignored(self):
        """--dry-run prints the command before the worktree exists."""
        roots = writable_roots(rp.codex_sandbox_args(self.acfg, str(self.root.parent / "absent")))
        self.assertFalse(any("/worktrees/" in r for r in roots))

    def test_prompt_points_to_gh_cli(self):
        self.assertIn("gh CLI", rp.agent_prompt(3, "Core", "issue-3-core", False, None, auto=True))


class AgyAgentCommandTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        base = Path(directory.name).resolve()
        self.root = base / "repo"
        self.root.mkdir()
        git("init", "-q", "-b", "main", cwd=self.root)
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", "init",
            cwd=self.root)
        self.worktree = base / "repo-worktrees" / "issue-83-agy"
        git("worktree", "add", "-q", "-b", "issue-83-agy", str(self.worktree), cwd=self.root)
        self.acfg = rp.agent_config()
        patcher = patch.object(rp, "ROOT", str(self.root))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_default_runner_stays_claude(self):
        self.assertEqual("claude", self.acfg["default_runner"])
        self.assertIn("agy", self.acfg)

    def test_parse_agent_args_accepts_agy(self):
        opts, flags, target, extra = rp.parse_agent_args(
            ["83", "agy", "--model", "gemini-pro", "--worktree", "--auto", "--", "--effort", "high"]
        )
        self.assertEqual("agy", opts["runner"])
        self.assertEqual("gemini-pro", opts["model"])
        self.assertEqual({"worktree", "auto"}, flags)
        self.assertEqual("83", target)
        self.assertEqual(["--effort", "high"], extra)

    def test_interactive_on_main_uses_prompt_interactive_flag(self):
        cmd = rp.agy_command(self.acfg, None, False, False, str(self.root), [], "Prompt text")
        self.assertEqual(["agy", "-i", "Prompt text"], cmd)

    def test_linked_worktree_adds_shared_git_dir(self):
        cmd = rp.agy_command(self.acfg, None, False, False, str(self.worktree), [], "Prompt text")
        self.assertEqual(["agy", "--add-dir", str(self.root / ".git"), "-i", "Prompt text"], cmd)

    def test_headless_uses_mode_auto_and_print_flag(self):
        cmd = rp.agy_command(self.acfg, None, True, False, str(self.root), [], "Prompt text")
        self.assertEqual(["agy", "--mode", "auto", "-p", "Prompt text"], cmd)

    def test_auto_headless_with_model_and_extra_args(self):
        cmd = rp.agy_command(
            self.acfg, "gemini-pro", True, True, str(self.worktree), ["--effort", "high"], "Prompt text"
        )
        self.assertEqual(
            ["agy", "--model", "gemini-pro", "--dangerously-skip-permissions",
             "--add-dir", str(self.root / ".git"), "--effort", "high", "-p", "Prompt text"],
            cmd,
        )


class ClaudeAgentCommandTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        base = Path(directory.name).resolve()
        self.root = base / "repo"
        self.root.mkdir()
        git("init", "-q", "-b", "main", cwd=self.root)
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", "init",
            cwd=self.root)
        self.worktree = base / "repo-worktrees" / "issue-101-claude"
        git("worktree", "add", "-q", "-b", "issue-101-claude", str(self.worktree), cwd=self.root)
        self.acfg = rp.agent_config()
        patcher = patch.object(rp, "ROOT", str(self.root))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_interactive_on_main_passes_positional_prompt(self):
        cmd = rp.claude_command(self.acfg, None, False, False, str(self.root), [], "Prompt text")
        self.assertEqual(["claude", "Prompt text"], cmd)

    def test_linked_worktree_adds_shared_git_dir(self):
        cmd = rp.claude_command(self.acfg, None, False, False, str(self.worktree), [], "Prompt text")
        self.assertEqual(["claude", "--add-dir", str(self.root / ".git"), "Prompt text"], cmd)

    def test_auto_headless_with_extra_args_places_extra_before_print_flag(self):
        cmd = rp.claude_command(
            self.acfg, "opus", True, True, str(self.worktree), ["--effort", "high"], "Prompt text"
        )
        self.assertEqual(
            ["claude", "--model", "opus", "--permission-mode", "bypassPermissions",
             "--add-dir", str(self.root / ".git"), "--effort", "high", "-p", "Prompt text"],
            cmd,
        )

    def test_child_env_strips_nested_claude_session_variables(self):
        env = {"PATH": "/usr/bin", "CLAUDECODE": "1", "CLAUDE_CODE_ENTRYPOINT": "cli", "ANTHROPIC_MODEL": "opus"}
        cleaned = rp.agent_child_env("claude", env)
        self.assertNotIn("CLAUDECODE", cleaned)
        self.assertNotIn("CLAUDE_CODE_ENTRYPOINT", cleaned)
        self.assertEqual("opus", cleaned["ANTHROPIC_MODEL"])
        self.assertEqual("1", rp.agent_child_env("codex", env)["CLAUDECODE"])


class RunnerProbeTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.base = Path(directory.name).resolve()
        self.bin_dir = self.base / "bin"
        self.bin_dir.mkdir()

    def test_executable_on_path_is_ready(self):
        exe = self.bin_dir / "agy"
        exe.write_text("#!/bin/sh\n")
        exe.chmod(0o755)
        info = rp.probe_runner("agy", path_env=str(self.bin_dir))
        self.assertTrue(info["installed"])
        self.assertEqual("ready", info["status"])
        self.assertEqual(str(exe), info["path"])

    def test_sandbox_blocked_symlink_target_is_detected_as_installed(self):
        target = self.base / "protected" / "claude" / "versions" / "2.1.332"
        link = self.bin_dir / "claude"
        link.symlink_to(target)
        real_stat = os.stat

        def fake_stat(path, *args, **kwargs):
            if os.fspath(path) == str(link):
                raise PermissionError(errno.EPERM, "Operation not permitted", str(link))
            return real_stat(path, *args, **kwargs)

        with patch.object(rp.shutil, "which", return_value=None), patch.object(rp.os, "stat", side_effect=fake_stat):
            info = rp.probe_runner("claude", path_env=str(self.bin_dir))
        self.assertTrue(info["installed"])
        self.assertEqual("sandbox-blocked", info["status"])
        self.assertEqual(str(link), info["path"])
        self.assertEqual(str(target), info["target"])

    def test_broken_symlink_with_missing_target_is_missing(self):
        link = self.bin_dir / "claude"
        link.symlink_to(self.base / "absent" / "claude")
        info = rp.probe_runner("claude", path_env=str(self.bin_dir))
        self.assertFalse(info["installed"])
        self.assertEqual("missing", info["status"])

    def test_unreadable_path_directory_does_not_fabricate_runner(self):
        real_lstat = os.lstat

        def deny_lstat(path, *args, **kwargs):
            if os.fspath(path).startswith("/restricted/"):
                raise PermissionError(errno.EPERM, "Operation not permitted", str(path))
            return real_lstat(path, *args, **kwargs)

        with patch.object(rp.shutil, "which", return_value=None), patch.object(rp.os, "lstat", side_effect=deny_lstat):
            info = rp.probe_runner("claude", path_env=f"/restricted/bin{os.pathsep}{self.bin_dir}")
        self.assertFalse(info["installed"])
        self.assertEqual("missing", info["status"])

    def test_cmd_runners_formats_ready_sandbox_blocked_and_missing(self):
        probes = [
            {"runner": "claude", "installed": True, "status": "sandbox-blocked",
             "path": "/Users/u/.local/bin/claude", "target": "/Users/u/.local/share/claude/versions/2.1.332"},
            {"runner": "codex", "installed": True, "status": "ready",
             "path": "/Users/u/.local/bin/codex", "target": None},
            {"runner": "agy", "installed": False, "status": "missing", "path": None, "target": None},
        ]
        out = io.StringIO()
        with patch.object(rp, "probe_runners", return_value=probes), \
                patch.object(rp, "resolve_agent_target", return_value=("opus", "anthropic", "cloud")), \
                contextlib.redirect_stdout(out):
            rp.cmd_runners([])
        text = out.getvalue()
        self.assertIn("claude  installed (/Users/u/.local/bin/claude -> "
                      "/Users/u/.local/share/claude/versions/2.1.332; sandbox-blocked", text)
        self.assertIn("codex   installed (/Users/u/.local/bin/codex)", text)
        self.assertIn("agy     missing", text)

    def test_cmd_agent_stops_before_start_issue_when_runner_is_sandbox_blocked(self):
        blocked = {
            "runner": "claude", "installed": True, "status": "sandbox-blocked",
            "path": "/Users/u/.local/bin/claude", "target": "/Users/u/.local/share/claude/versions/2.1.332",
        }
        with patch.object(rp, "probe_runner", return_value=blocked), \
                patch.object(rp, "start_issue") as start_mock:
            with self.assertRaises(SystemExit) as ctx:
                rp.cmd_agent(["17", "claude", "--auto", "--headless", "--worktree"])
            start_mock.assert_not_called()
            self.assertIn("outside the sandbox", str(ctx.exception))


class SharedSkillsContractTest(unittest.TestCase):
    def test_claude_skills_symlink_to_canonical_agents_skills(self):
        repo_root = Path(__file__).resolve().parents[1]
        agents_skills = repo_root / ".agents" / "skills"
        claude_skills = repo_root / ".claude" / "skills"
        canonical = sorted(p.name for p in agents_skills.iterdir() if p.is_dir())
        self.assertEqual(["runpro-orchestrate", "runpro-review", "runpro-task", "runpro-triage"], canonical)
        for name in canonical:
            skill_md = agents_skills / name / "SKILL.md"
            self.assertTrue(skill_md.is_file(), f"missing {skill_md}")
            text = skill_md.read_text()
            self.assertIn(f"name: {name}", text)
            self.assertIn("description:", text)
            link = claude_skills / name
            self.assertTrue(link.is_symlink(), f"{link} must be a symlink")
            self.assertEqual((agents_skills / name).resolve(), link.resolve())
        orchestrate_text = (agents_skills / "runpro-orchestrate" / "SKILL.md").read_text()
        self.assertIn("./rp runners", orchestrate_text)
        self.assertNotIn("command -v claude", orchestrate_text)


if __name__ == "__main__":
    unittest.main()
