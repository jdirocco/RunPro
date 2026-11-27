"""Complexity labels, display, and filtering in `./rp status`, `./rp next`, and `./rp agent`."""

import contextlib
import importlib.machinery
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch


loader = importlib.machinery.SourceFileLoader("rp", str(Path(__file__).resolve().parents[1] / "rp"))
spec = importlib.util.spec_from_loader(loader.name, loader)
rp = importlib.util.module_from_spec(spec)
loader.exec_module(rp)


def make_issue(number, title, milestone="M1 Recording core", labels=(), assignees=(), body=""):
    return {
        "number": number,
        "title": title,
        "milestone": {"title": milestone} if milestone else None,
        "labels": [{"name": name} for name in labels],
        "assignees": list(assignees),
        "body": body,
    }


SAMPLE_ISSUES = [
    make_issue(12, "Basic run UI", "M1 Recording core", ("status:in-progress", "complexity:s")),
    make_issue(14, "Field validation of the recording core", "M1 Recording core", ("field-test",),
               body="**Depends on:** #12"),
    make_issue(80, "Remove header-only journals", "M1 Recording core", ("complexity:xs",)),
    make_issue(82, "End a forgotten warm-up", "M1 Recording core", ("complexity:xs",),
               body="**Depends on:** #12"),
    make_issue(16, "BLE connection state machine", "M2 BLE sensors", ("complexity:m",)),
    make_issue(17, "Android GATT adapter", "M2 BLE sensors", ("complexity:l",),
               body="**Depends on:** #16"),
    make_issue(21, "Stryd footpod support", "M2 BLE sensors", ("complexity:ml",),
               body="**Depends on:** #17"),
    make_issue(29, "Run history on the watch", "M4 Export and sync", ("complexity:s",)),
]


class ComplexityParsingTest(unittest.TestCase):
    def test_aliases_normalise_case_dash_and_label_prefix(self):
        self.assertEqual(["XS", "S", "M-L", "L"],
                         rp.parse_complexity_filter("xs, S, ml, m-l, complexity:L"))

    def test_unknown_complexity_exits_with_choices(self):
        with self.assertRaises(SystemExit) as ctx:
            rp.parse_complexity_filter("xxl")
        self.assertIn("XS, S, M, M-L, L", str(ctx.exception))

    def test_milestone_summary_counts_open_tiers_and_field_tests(self):
        self.assertEqual("2 XS, 1 S, 1 field-test",
                         rp.milestone_open_summary("M1 Recording core", SAMPLE_ISSUES))
        self.assertEqual("1 M, 1 M-L, 1 L",
                         rp.milestone_open_summary("M2 BLE sensors", SAMPLE_ISSUES))

    def test_milestone_summary_stays_empty_when_all_issues_are_unclassified(self):
        unclassified = [make_issue(1, "Untriaged", "M0 Foundations")]
        self.assertEqual("", rp.milestone_open_summary("M0 Foundations", unclassified))


class NextComplexityTest(unittest.TestCase):
    def run_next(self, *args):
        out = io.StringIO()
        with patch.object(rp, "open_issues", return_value=SAMPLE_ISSUES), \
                patch.object(rp, "dependencies", return_value={14: {12}, 82: {12}, 17: {16}, 21: {17}}), \
                contextlib.redirect_stdout(out):
            rp.cmd_next(list(args))
        return out.getvalue()

    def test_default_next_shows_milestone_and_complexity(self):
        text = self.run_next()
        self.assertIn("#80 Remove header-only journals  [M1 Recording core · XS]", text)
        self.assertIn("Start with: ./rp start 80", text)

    def test_filter_picks_matching_ready_issue(self):
        text = self.run_next("s")
        self.assertIn("#29 Run history on the watch  [M4 Export and sync · S]", text)
        self.assertIn("Start with: ./rp start 29", text)

    def test_all_lists_every_ready_issue_with_complexity(self):
        lines = self.run_next("--all").strip().splitlines()
        self.assertEqual([
            "#80 Remove header-only journals  [M1 Recording core · XS]",
            "#16 BLE connection state machine  [M2 BLE sensors · M]",
            "#29 Run history on the watch  [M4 Export and sync · S]",
        ], lines)

    def test_filter_with_no_ready_match_explains_filter(self):
        text = self.run_next("l")
        self.assertIn("No ready issue with complexity L.", text)


class StatusComplexityTest(unittest.TestCase):
    MILESTONES = [
        {"title": "M0 Foundations", "open_issues": 0, "closed_issues": 10},
        {"title": "M1 Recording core", "open_issues": 4, "closed_issues": 9},
        {"title": "M2 BLE sensors", "open_issues": 3, "closed_issues": 1},
        {"title": "M4 Export and sync", "open_issues": 1, "closed_issues": 2},
    ]

    def gh(self, args):
        if args[0] == "api":
            return self.MILESTONES
        if args[:2] == ["pr", "list"]:
            return []
        raise AssertionError(f"unexpected gh call: {args}")

    def run_status(self, *args):
        out = io.StringIO()
        with patch.object(rp, "repo", return_value="owner/RunPro"), \
                patch.object(rp, "gh_json", side_effect=self.gh), \
                patch.object(rp, "open_issues", return_value=SAMPLE_ISSUES), \
                patch.object(rp, "dependencies", return_value={14: {12}, 82: {12}, 17: {16}, 21: {17}}), \
                patch.object(rp, "running_agents", return_value={}), \
                patch.object(rp, "read_agent_run", return_value=None), \
                contextlib.redirect_stdout(out):
            rp.cmd_status(list(args))
        return out.getvalue()

    def test_status_displays_milestone_breakdown_and_issue_badges(self):
        text = self.run_status()
        self.assertIn("M1 Recording core", text)
        self.assertIn("(open: 2 XS, 1 S, 1 field-test)", text)
        self.assertIn("#12 Basic run UI  [S]", text)
        self.assertIn("#80 Remove header-only journals  [XS]", text)
        self.assertIn("#16 BLE connection state machine  [M]", text)
        self.assertNotIn("== All open issues", text)

    def test_status_all_lists_blocked_and_ready_issues_with_state(self):
        text = self.run_status("--all")
        self.assertIn("== All open issues", text)
        self.assertIn("#17 Android GATT adapter  [M2 BLE sensors · L]  (waits for #16)", text)
        self.assertIn("#21 Stryd footpod support  [M2 BLE sensors · M-L]  (waits for #17)", text)
        self.assertIn("#14 Field validation of the recording core  [M1 Recording core · field-test]  (waits for #12)",
                      text)

    def test_status_filtered_by_complexity_shows_matching_open_issues(self):
        text = self.run_status("ml,l")
        self.assertIn("== Next ready (M-L, L)", text)
        self.assertIn("== Open issues (M-L, L)", text)
        self.assertIn("#17 Android GATT adapter  [M2 BLE sensors · L]  (waits for #16)", text)
        self.assertIn("#21 Stryd footpod support  [M2 BLE sensors · M-L]  (waits for #17)", text)
        self.assertNotIn("#80 Remove header-only journals", text)


class AgentComplexityArgTest(unittest.TestCase):
    def test_parse_agent_args_extracts_complexity(self):
        opts, flags, target, extra = rp.parse_agent_args(
            ["next", "codex", "--complexity", "xs,s", "--worktree", "--", "--oss"]
        )
        self.assertEqual("xs,s", opts["complexity"])
        self.assertEqual("codex", opts["runner"])
        self.assertIn("worktree", flags)
        self.assertEqual("next", target)
        self.assertEqual(["--oss"], extra)


if __name__ == "__main__":
    unittest.main()
