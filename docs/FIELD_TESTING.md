# Field testing on the Galaxy Watch 5 Pro

Agents cannot run on the watch. Field tests are done by the owner; the results come back as GitHub issues
(`field-report` template) so agents can turn them into traces and tests.

## Before a test run

Watch setup step by step (in Italian, for the owner): `docs/it/CONFIGURAZIONE_OROLOGIO.md`.

1. Install the build from the PR (`./rp install` with the watch on Wi-Fi ADB; see [WATCH_SETUP.md](WATCH_SETUP.md)) or from Play internal testing.
2. Check: RunPro not in "Sleeping apps", background activity allowed, Location on.
3. Make sure the strap is **not** connected to the phone, Samsung Health or Gear Tracker at the same time.
4. Enable "Record debug trace" in RunPro settings.

## Standard scenarios

| Id | Scenario | What to check |
|---|---|---|
| F1 | 30 min easy run, strap + GPS | No HR gaps > 10 s, distance vs known route |
| F2 | Strap dropout: take the strap off for 60 s mid-run, put it back | Switch to wrist HR, return to strap < 10 s |
| F3 | Force stop the app during a run (Settings → Apps → Force stop), reopen | Run restored, ≤ 5 s lost |
| F4 | Reboot the watch during a run | Run restored after reboot |
| F5 | 2–3 h long run | Battery %/h, no drops, file exported |
| F6 | Track or treadmill with footpod | Footpod distance vs GPS vs real |
| F7 | Run with the phone left at home | Watch-only GPS, sync later |

## After the run

1. Settings → Diagnostics → Export logs (sends trace + sensor log to the phone).
2. Open a `field-report` issue, attach files, fill in the scenario id and what went wrong.
