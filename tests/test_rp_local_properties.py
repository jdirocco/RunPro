"""Worktrees get the main checkout's ignored local.properties (sdk.dir): no Android SDK required."""

import contextlib
import importlib.machinery
import importlib.util
import io
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

SDK = "sdk.dir=/opt/android-sdk\n"


def git(*args, cwd):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


class LocalPropertiesTest(unittest.TestCase):
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
        (self.root / "local.properties").write_text(SDK)
        for patcher in (patch.object(rp, "ROOT", str(self.root)), patch.dict(os.environ, {}, clear=False)):
            patcher.start()
            self.addCleanup(patcher.stop)
        for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            os.environ.pop(name, None)

    def test_new_worktree_gets_a_copy_from_the_main_checkout(self):
        copied = rp.ensure_local_properties(str(self.worktree))
        self.assertEqual(str(self.worktree / "local.properties"), copied)
        self.assertEqual(SDK, (self.worktree / "local.properties").read_text())

    def test_works_when_rp_runs_from_another_worktree(self):
        with patch.object(rp, "ROOT", str(self.worktree)):
            rp.ensure_local_properties(str(self.worktree))
        self.assertEqual(SDK, (self.worktree / "local.properties").read_text())

    def test_existing_worktree_file_is_never_overwritten(self):
        (self.worktree / "local.properties").write_text("sdk.dir=/custom\n")
        self.assertIsNone(rp.ensure_local_properties(str(self.worktree)))
        self.assertEqual("sdk.dir=/custom\n", (self.worktree / "local.properties").read_text())

    def test_main_checkout_without_file_copies_nothing(self):
        (self.root / "local.properties").unlink()
        self.assertIsNone(rp.ensure_local_properties(str(self.worktree)))
        self.assertFalse((self.worktree / "local.properties").exists())

    def test_main_checkout_is_left_alone(self):
        self.assertIsNone(rp.ensure_local_properties(str(self.root)))
        self.assertEqual(SDK, (self.root / "local.properties").read_text())

    def check(self, root):
        (root / "gradlew").touch()
        calls = []
        real_run = subprocess.run

        def fake_run(command, **kwargs):
            if command[0] == "./gradlew":
                calls.append(command)
                return subprocess.CompletedProcess(command, 0)
            return real_run(command, **kwargs)

        with patch.object(rp, "ROOT", str(root)), patch.object(rp.subprocess, "run", side_effect=fake_run), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit) as result:
                rp.cmd_check([])
        self.assertEqual(0, result.exception.code)
        self.assertEqual([["./gradlew", "check", "--continue"]], calls)

    def test_check_in_an_older_worktree_falls_back_to_the_main_checkout(self):
        self.check(self.worktree)
        self.assertEqual(SDK, (self.worktree / "local.properties").read_text())

    def test_check_respects_android_home(self):
        os.environ["ANDROID_HOME"] = "/elsewhere/sdk"
        self.check(self.worktree)
        self.assertFalse((self.worktree / "local.properties").exists())


if __name__ == "__main__":
    unittest.main()
