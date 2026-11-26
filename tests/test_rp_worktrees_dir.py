"""New worktrees always go to `<main checkout>-worktrees`, even when ./rp runs from a linked worktree."""

import importlib.machinery
import importlib.util
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


class WorktreesDirTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        base = Path(directory.name).resolve()
        self.root = base / "repo"
        self.root.mkdir()
        git("init", "-q", "-b", "main", cwd=self.root)
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", "init",
            cwd=self.root)
        self.expected = str(base / "repo-worktrees")
        self.worktree = base / "repo-worktrees" / "issue-52-local-properties"
        git("worktree", "add", "-q", "-b", "issue-52-local-properties", str(self.worktree), cwd=self.root)

    def test_from_the_main_checkout(self):
        with patch.object(rp, "ROOT", str(self.root)):
            self.assertEqual(self.expected, rp.worktrees_dir())

    def test_from_a_linked_worktree_it_does_not_nest(self):
        with patch.object(rp, "ROOT", str(self.worktree)):
            self.assertEqual(self.expected, rp.worktrees_dir())


if __name__ == "__main__":
    unittest.main()
