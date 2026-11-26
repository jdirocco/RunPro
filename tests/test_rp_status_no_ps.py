"""./rp status must keep rendering when the sandbox denies `ps` (#68)."""

import contextlib
import importlib.machinery
import importlib.util
import io
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


loader = importlib.machinery.SourceFileLoader("rp", str(Path(__file__).resolve().parents[1] / "rp"))
spec = importlib.util.spec_from_loader(loader.name, loader)
rp = importlib.util.module_from_spec(spec)
loader.exec_module(rp)


def deny_ps(cmd, *args, **kwargs):
    if cmd and cmd[0] == "ps":
        raise PermissionError(1, "Operation not permitted", "ps")
    raise AssertionError(f"unexpected subprocess call: {cmd}")


RUN = {"issue": 5, "runner": "codex", "model": "gpt-6.1-sol", "provider": "openai", "location": "cloud",
       "mode": "auto", "pid": 87776, "started": 1000.0, "ended": None, "exit_code": None}


class RunningAgentsUnavailableTest(unittest.TestCase):
    def test_permission_error_means_unknown(self):
        with patch.object(rp.subprocess, "run", side_effect=deny_ps):
            self.assertIsNone(rp.running_agents())

    def test_missing_ps_means_unknown(self):
        with patch.object(rp.subprocess, "run", side_effect=FileNotFoundError(2, "No such file", "ps")):
            self.assertIsNone(rp.running_agents())

    def test_failing_ps_means_unknown(self):
        failed = subprocess.CompletedProcess(["ps"], 1, stdout="", stderr="ps: denied")
        with patch.object(rp.subprocess, "run", return_value=failed):
            self.assertIsNone(rp.running_agents())


class StatusLineUnknownTest(unittest.TestCase):
    def test_nothing_recorded(self):
        self.assertEqual(rp.agent_status_line(5, None, rp.UNKNOWN),
                         "agent status unknown (process list unavailable)")

    def test_keeps_a_recorded_run(self):
        line = rp.agent_status_line(5, RUN, rp.UNKNOWN, now=1000.0 + 12 * 60)
        self.assertEqual(line, "agent status unknown (process list unavailable; recorded: codex gpt-6.1-sol on "
                               "openai (cloud), auto, pid 87776, started 12m ago)")

    def test_keeps_a_finished_run(self):
        run = dict(RUN, ended=1000.0, exit_code=0)
        line = rp.agent_status_line(5, run, rp.UNKNOWN, now=1000.0 + 25 * 60)
        self.assertEqual(line, "agent status unknown (process list unavailable; last: codex gpt-6.1-sol on "
                               "openai (cloud), exited 0 25m ago)")


class StatusRendersTest(unittest.TestCase):
    ISSUES = [
        {"number": 5, "title": "Chaos injector", "labels": [{"name": "status:in-progress"}], "body": "",
         "milestone": {"title": "M0 Foundations"}, "assignees": []},
        {"number": 7, "title": "Pick a codec", "labels": [{"name": "needs-owner"}], "body": "",
         "milestone": {"title": "M0 Foundations"}, "assignees": []},
    ]

    def gh(self, args):
        if args[0] == "api":
            return [{"title": "M0 Foundations", "open_issues": 2, "closed_issues": 2}]
        if args[:2] == ["pr", "list"]:
            return [{"number": 40, "title": "Skeleton (#1)", "headRefName": "issue-1-skeleton"}]
        raise AssertionError(f"unexpected gh call: {args}")

    def test_status_renders_every_section_without_ps(self):
        out = io.StringIO()
        with patch.object(rp.subprocess, "run", side_effect=deny_ps), \
                patch.object(rp, "repo", return_value="owner/RunPro"), \
                patch.object(rp, "gh_json", side_effect=self.gh), \
                patch.object(rp, "open_issues", return_value=self.ISSUES), \
                patch.object(rp, "dependencies", return_value={}), \
                patch.object(rp, "read_agent_run", side_effect=lambda n: dict(RUN) if n == 5 else None), \
                contextlib.redirect_stdout(out):
            rp.cmd_status(None)
        text = out.getvalue()
        self.assertIn("process list unavailable", text)
        self.assertIn("recorded: codex gpt-6.1-sol on openai (cloud), auto, pid 87776", text)
        self.assertIn("#7 Pick a codec", text)
        self.assertIn("!40 Skeleton (#1)", text)
        self.assertIn("== Next ready", text)


if __name__ == "__main__":
    unittest.main()
