"""Install command tests: no physical device or Android SDK required."""

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

WATCH = "192.0.2.10:39517"
PHONE = "phone-serial"


class InstallTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        (self.root / "gradlew").touch()
        self.apk = self.root / "wear/build/outputs/apk/debug/wear-debug.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.touch()
        self.devices = f"List of devices attached\n{WATCH}\tdevice product:watch model:Galaxy_Watch5_Pro\n"
        self.characteristics = "nosdcard,watch\n"
        self.failures = {}
        self.calls = []
        self.output = io.StringIO()
        for patcher in (
            patch.object(rp, "ROOT", str(self.root)),
            patch.dict(os.environ, {}, clear=True),
            patch.object(rp.subprocess, "run", side_effect=self.run_command),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def run_command(self, command, **kwargs):
        self.calls.append(command)
        self.assertEqual(str(self.root), kwargs["cwd"])
        if command[0] == "adb" and not self.adb_available:
            raise FileNotFoundError("adb")
        stdout = ""
        if command == ["adb", "devices", "-l"]:
            stdout = self.devices
        elif command[-3:] == ["shell", "getprop", "ro.build.characteristics"]:
            stdout = self.characteristics
        status = self.failures.get(tuple(command), 0)
        return subprocess.CompletedProcess(command, status, stdout, "test command failure" if status else "")

    adb_available = True

    def install(self, *args):
        with contextlib.redirect_stdout(self.output):
            with self.assertRaises(SystemExit) as result:
                rp.cmd_install(list(args))
        return result.exception.code

    def assert_no_build_or_install(self):
        self.assertFalse(any(command[0] == "./gradlew" or "install" in command for command in self.calls))

    def test_single_watch_builds_then_reinstalls_only_that_serial(self):
        self.assertEqual(0, self.install())
        self.assertEqual([
            ["adb", "devices", "-l"],
            ["adb", "-s", WATCH, "shell", "getprop", "ro.build.characteristics"],
            ["./gradlew", ":wear:assembleDebug"],
            ["adb", "-s", WATCH, "install", "-r", str(self.apk)],
        ], self.calls)

    def test_android_serial_selects_watch_with_phone_also_connected(self):
        os.environ["ANDROID_SERIAL"] = WATCH
        self.devices += f"{PHONE}\tdevice model:Phone\n"
        self.assertEqual(0, self.install())
        self.assertEqual(["adb", "-s", WATCH, "install", "-r", str(self.apk)], self.calls[-1])

    def test_argument_takes_precedence_over_environment(self):
        os.environ["ANDROID_SERIAL"] = PHONE
        self.devices += f"{PHONE}\tdevice model:Phone\n"
        self.assertEqual(0, self.install(WATCH))
        self.assertEqual(["adb", "-s", WATCH, "install", "-r", str(self.apk)], self.calls[-1])

    def test_no_devices_explains_connection_setup_without_building(self):
        self.devices = "List of devices attached\n\n"
        self.assertIn("docs/WATCH_SETUP.md", self.install())
        self.assert_no_build_or_install()

    def test_multiple_devices_require_serial_without_building(self):
        self.devices += f"{PHONE}\tdevice\n"
        self.assertIn("ANDROID_SERIAL", self.install())
        self.assert_no_build_or_install()

    def test_requested_serial_must_exist_even_when_another_device_is_ready(self):
        os.environ["ANDROID_SERIAL"] = "missing"
        self.assertIn("missing", self.install())
        self.assert_no_build_or_install()

    def test_offline_and_unauthorized_devices_are_not_install_targets(self):
        for state in ("offline", "unauthorized"):
            with self.subTest(state=state):
                self.calls.clear()
                self.devices = f"List of devices attached\n{WATCH}\t{state}\n"
                self.assertIn(state, self.install())
                self.assert_no_build_or_install()

    def test_single_phone_is_rejected_without_building(self):
        self.characteristics = "nosdcard\n"
        self.assertIn("watch", self.install())
        self.assert_no_build_or_install()

    def test_missing_adb_explains_platform_tools_setup(self):
        self.adb_available = False
        self.assertIn("platform-tools", self.install())
        self.assert_no_build_or_install()

    def test_missing_gradle_wrapper_fails_before_adb(self):
        (self.root / "gradlew").unlink()
        self.assertIn("Gradle", self.install())
        self.assertEqual([], self.calls)

    def test_unexpected_arguments_are_rejected(self):
        self.assertIn("usage:", self.install(WATCH, PHONE))
        self.assertEqual([], self.calls)

    def test_device_query_failure_does_not_build(self):
        self.failures[("adb", "devices", "-l")] = 1
        self.assertIn("test command failure", self.install())
        self.assert_no_build_or_install()

    def test_watch_property_query_failure_does_not_build(self):
        self.failures[("adb", "-s", WATCH, "shell", "getprop", "ro.build.characteristics")] = 1
        self.assertIn("test command failure", self.install())
        self.assert_no_build_or_install()

    def test_failed_build_preserves_exit_code_and_does_not_install_stale_apk(self):
        self.failures[("./gradlew", ":wear:assembleDebug")] = 7
        self.assertEqual(7, self.install())
        self.assertFalse(any("install" in command for command in self.calls))

    def test_missing_apk_after_build_is_reported(self):
        self.apk.unlink()
        self.assertIn("wear-debug.apk", self.install())
        self.assertFalse(any("install" in command for command in self.calls))

    def test_failed_install_preserves_exit_code(self):
        self.failures[("adb", "-s", WATCH, "install", "-r", str(self.apk))] = 9
        self.assertEqual(9, self.install())


if __name__ == "__main__":
    unittest.main()
