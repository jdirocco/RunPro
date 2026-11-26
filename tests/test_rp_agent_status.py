"""Status tests: ./rp status must say whether an agent works on an issue and where it runs (#62)."""

import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


loader = importlib.machinery.SourceFileLoader("rp", str(Path(__file__).resolve().parents[1] / "rp"))
spec = importlib.util.spec_from_loader(loader.name, loader)
rp = importlib.util.module_from_spec(spec)
loader.exec_module(rp)


class ResolveTargetTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.home = Path(directory.name)
        (self.home / ".codex").mkdir()
        (self.home / ".claude").mkdir()
        self.cwd = self.home / "repo"
        self.cwd.mkdir()

    def resolve(self, runner, model=None, extra=(), env=None):
        return rp.resolve_agent_target(runner, model, list(extra), cwd=str(self.cwd), env=env or {},
                                       home=str(self.home))

    def test_codex_null_model_reads_codex_config(self):
        (self.home / ".codex" / "config.toml").write_text('model = "gpt-6.1-sol"\nmodel_reasoning_effort = "high"\n')
        self.assertEqual(self.resolve("codex"), ("gpt-6.1-sol", "openai", "cloud"))

    def test_codex_oss_is_local_with_its_provider(self):
        (self.home / ".codex" / "config.toml").write_text('model = "gpt-6.1-sol"\n')
        extra = ["--oss", "--local-provider", "ollama"]
        self.assertEqual(self.resolve("codex", "devstral-64k:latest", extra), ("devstral-64k:latest", "ollama", "local"))

    def test_codex_profile_and_local_base_url(self):
        (self.home / ".codex" / "config.toml").write_text(
            'model = "gpt-6.1-sol"\n'
            '[profiles.lab]\nmodel = "qwen"\nmodel_provider = "lab"\n'
            '[model_providers.lab]\nbase_url = "http://127.0.0.1:8080/v1"\n')
        self.assertEqual(self.resolve("codex", extra=["--profile", "lab"]), ("qwen", "lab", "local"))

    def test_codex_without_config_falls_back(self):
        self.assertEqual(self.resolve("codex"), ("(codex default)", "openai", "cloud"))

    def test_claude_model_precedence(self):
        (self.home / ".claude" / "settings.json").write_text(json.dumps({"model": "opus"}))
        self.assertEqual(self.resolve("claude"), ("opus", "anthropic", "cloud"))
        (self.cwd / ".claude").mkdir()
        (self.cwd / ".claude" / "settings.local.json").write_text(json.dumps({"model": "sonnet"}))
        self.assertEqual(self.resolve("claude")[0], "sonnet")
        self.assertEqual(self.resolve("claude", env={"ANTHROPIC_MODEL": "haiku"})[0], "haiku")
        self.assertEqual(self.resolve("claude", "fable")[0], "fable")

    def test_claude_local_base_url_is_local(self):
        env = {"ANTHROPIC_BASE_URL": "http://localhost:11434"}
        self.assertEqual(self.resolve("claude", "qwen", env=env)[2], "local")
        self.assertEqual(self.resolve("claude", env={"ANTHROPIC_BASE_URL": "https://api.anthropic.com"})[2], "cloud")
        self.assertEqual(self.resolve("claude", env={"CLAUDE_CODE_USE_BEDROCK": "1"})[1:], ("bedrock", "cloud"))


PS = """\
  87776   06:19 python3 ./rp agent 5 codex --worktree --auto
  77702   24:12 python3 ./rp agent 55 codex --model devstral-64k:latest --worktree -- --oss --local-provider ollama
  62069 01-18:44:13 python3 ./rp agent 3 claude --worktree --auto
  90001   00:03 grep -E rp agent|codex -s
  90002   00:01 python3 ./rp agent next
  90003   00:01 vim notes-about-rp
"""


class RunningAgentsTest(unittest.TestCase):
    def test_parses_issue_pid_and_elapsed(self):
        found = rp.running_agents(PS)
        self.assertEqual(sorted(found), [3, 5, 55])
        self.assertEqual(found[5]["pid"], 87776)
        self.assertEqual(found[5]["seconds"], 6 * 60 + 19)
        self.assertEqual(found[3]["seconds"], 86400 + 18 * 3600 + 44 * 60 + 13)
        self.assertIn("--oss", found[55]["args"])


class StatusLineTest(unittest.TestCase):
    RUN = {"issue": 5, "runner": "codex", "model": "gpt-6.1-sol", "provider": "openai", "location": "cloud",
           "mode": "auto", "pid": 87776, "started": 1000.0, "ended": None, "exit_code": None}

    def test_running_from_record(self):
        line = rp.agent_status_line(5, self.RUN, {"pid": 87776, "seconds": 720, "args": []})
        self.assertEqual(line, "running 12m: codex gpt-6.1-sol on openai (cloud), auto, pid 87776")

    def test_finished_run(self):
        run = dict(self.RUN, ended=1000.0, exit_code=0)
        line = rp.agent_status_line(5, run, None, now=1000.0 + 25 * 60)
        self.assertEqual(line, "no agent running (last: codex gpt-6.1-sol on openai (cloud), exited 0 25m ago)")

    def test_killed_run_without_exit_code(self):
        self.assertIn("stopped without an exit code", rp.agent_status_line(5, self.RUN, None))

    def test_nothing_recorded(self):
        self.assertEqual(rp.agent_status_line(5, None, None), "no agent running")

    def test_process_list_fallback_resolves_where_it_runs(self):
        live = {"pid": 77702, "seconds": 3 * 3600 + 5 * 60,
                "args": "55 codex --model devstral --worktree -- --oss --local-provider ollama".split()}
        line = rp.agent_status_line(55, None, live)
        self.assertEqual(line, "running 3h05m: codex devstral on ollama (local), pid 77702 (from the process list)")


class RunRecordTest(unittest.TestCase):
    def test_records_live_in_the_shared_git_dir(self):
        with tempfile.TemporaryDirectory() as d, patch.object(rp, "git_common_dir", return_value=d):
            rp.write_agent_run(dict(StatusLineTest.RUN))
            self.assertTrue(os.path.isfile(os.path.join(d, "rp-agents", "5.json")))
            self.assertEqual(rp.read_agent_run(5)["model"], "gpt-6.1-sol")
            self.assertIsNone(rp.read_agent_run(6))


if __name__ == "__main__":
    unittest.main()
