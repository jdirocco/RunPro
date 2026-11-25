"""Agent launch tests: the Codex sandbox must let the agent commit in a linked worktree."""

import importlib.machinery
import importlib.util
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


if __name__ == "__main__":
    unittest.main()
