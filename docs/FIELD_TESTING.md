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
| F9 | Auto-lap and time/distance intervals | Correct lap summaries, countdowns and haptics with screen on/off; pauses freeze targets (see below) |
| F8 | Phone sync: phone app installed, several runs with the phone out of range for some of them, then in range | Every run in the phone's `files/runs` once, journals gone from the watch (see below) |

## Auto-lap and interval check (F9)

When the sensor adapter delivers distance, select **Workout: Free run**, leave **Auto lap: 1000 m**,
and cross a kilometre. Check one vibration pattern and a five-second lap summary with distance/time;
a manual lap must not reset the next kilometre target. Repeat with auto-lap off.

Select **Workout: Intervals** before starting. Each target button cycles time then distance presets;
repetitions cycle 1–20 and warm-up/cool-down can be off. Try short timed stages (30 s), then 100 m work
with timed recovery. Check stage/repetition, remaining time/metres, 3–2–1 countdown vibrations and
transition vibration with the screen off. Pause mid-stage: countdown must freeze. Completion must show
that recording continues until Stop. Report actual observations; JVM replay is not a physical result.

## FIT file acceptance (no watch needed)

`./rp check` writes `core/fit/build/fit-samples/easy-run-30min.fit` and `every-record.fit`.

1. Open each file in Garmin's FIT File Tools / FitCSVTool (FIT SDK) or https://www.fitfileviewer.com:
   no errors, one running session, laps and events present.
2. Upload `easy-run-30min.fit` to Strava (Upload activity → File): it is accepted as a run of about
   4.91 km, 30:00 elapsed, 3 laps, average HR 139 (Strava computes its own moving time). Delete it afterwards.
3. Report the result on the export issue (#28): accepted or the exact error message.

## Phone sync check (F8)

Needs journals written during a run (#60). Watch and phone apps must be the same kind of build (both debug
from this Mac, or both release): the Data Layer pairs them by application id and signing key.

1. Install the phone app: `./gradlew :mobile:installDebug` with only the phone on adb (or
   `ANDROID_SERIAL=<phone serial>`), and the watch app with `./rp install`.
2. Record runs; for some, keep the phone away (or Bluetooth off) until after the stop. Open RunPro on the
   watch or bring the phone back: the runs move within a minute or two.
3. Check on the phone: `adb -s <phone> shell run-as com.runpro.wear ls files/runs` lists one `.fit` per run.
   On the watch: `adb -s <watch> shell run-as com.runpro.wear ls files/journals` is empty.
4. `adb logcat -s PhoneSync FitReceiver` on each device shows the passes; report the counts on the field-test
   issue.

## After the run

1. Settings → Diagnostics → Export logs (sends trace + sensor log to the phone).
2. Open a `field-report` issue, attach files, fill in the scenario id and what went wrong.
