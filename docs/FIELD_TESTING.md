# Field testing on the Galaxy Watch 5 Pro

Agents cannot run on the watch. Field tests are done by the owner; the results come back as GitHub issues
(`field-report` template) so agents can turn them into traces and tests.

## Before a test run

Watch setup step by step (in Italian, for the owner): `docs/it/CONFIGURAZIONE_OROLOGIO.md`.

1. Install the build from the PR (`./rp install` with the watch on Wi-Fi ADB; see [WATCH_SETUP.md](WATCH_SETUP.md)) or from Play internal testing.
2. Check: RunPro not in "Sleeping apps", background activity allowed, Location on.
3. Make sure the strap is **not** connected to the phone, Samsung Health or Gear Tracker at the same time,
   and do not pair it in the watch's Bluetooth settings (forget it there if it is listed). Pair it once in
   RunPro (**Sensors → Pair HR strap**, #17): wear the strap, the screen lists what RunPro's own scan finds
   (Wear OS refuses the system chooser to third-party apps, #115). If the watch still holds an old bond
   the strap has forgotten, RunPro removes it on the first failed connection (`removed the stale bond` in
   the log, #117). Keep `adb logcat -s RunProBle` for the connection log until the diagnostics export
   (#20) exists.
4. In warm-up (**Get ready**) wait for the strap icon below "Heart rate" before **Start**: "HR strap:
   connecting" means the strap is not the heart rate source yet (#123). "Heart rate: ready" alone is the
   wrist sensor.
5. Enable "Record debug trace" in RunPro settings.

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
| F10 | IMU capture and battery cost (see below) | 50 Hz without gaps, battery %/h with IMU off, accelerometer, accelerometer + gyroscope |
| F11 | Low-resource warnings and automatic saving (see below) | Initial battery, running/paused warnings, saved automatic stop and exercise shutdown |
| F12 | Screen off (#108): 10 min walk or run with watch GPS and wrist HR only, wrist down so the screen stays off for at least 3 min at a time | Continuous track, distance, HR and cadence in the FIT and on Strava, including the screen-off stretches |
| F13 | Fused location near buildings (#96, see below) | GPS ready during warm-up where Health Services stays acquiring; no distance while standing; switch events; battery %/h |
| F9 | Start a workout in Gear Tracker (or another Health Services app), then a RunPro run; during it start a workout in the other app again | RunPro takes over at its start; when the other app takes over, the run keeps recording (timer, strap) without wrist HR and GPS, and the log shows it |

## Auto-lap and interval check (F9)

When the sensor adapter delivers distance, select **Workout: Free run**, leave **Auto lap: 1000 m**,
and cross a kilometre. Check one vibration pattern and a five-second lap summary with distance/time;
a manual lap must not reset the next kilometre target. Repeat with auto-lap off.

Select **Workout: Intervals** before starting. Each target button cycles time then distance presets;
repetitions cycle 1–20 and warm-up/cool-down can be off. Try short timed stages (30 s), then 100 m work
with timed recovery. Check stage/repetition, remaining time/metres, 3–2–1 countdown vibrations and
transition vibration with the screen off. Pause mid-stage: countdown must freeze. Completion must show
that recording continues until Stop. Report actual observations; JVM replay is not a physical result.

## IMU capture and reference runs (F10)

The setup screen has **IMU: accelerometer** (default), **IMU: accel + gyro** and **IMU: Off**; a run keeps
the setting it started with. Streams stay on the watch in `files/imu` (phone sync does not send them).

1. Battery cost: three runs of the same length (≥ 45 min) and conditions, one per setting, all with GPS
   and the strap as usual. Write down the battery % at start and stop (the log also prints it:
   `adb logcat -s ImuCapture` shows each capture change with `battery=..%`).
2. After each run copy the streams to the Mac and check them:

   ```bash
   adb exec-out run-as com.runpro.wear tar -c files/imu > imu.tar && tar -xf imu.tar
   ./gradlew :tools:replay:imuReport --args="$PWD/files/imu --csv $PWD/imu-csv"
   ```

   Each sensor should show about 50.0 Hz and `0 gaps`; report the lines as they are, including gaps,
   with the screen off most of the run.
3. Reference runs for #34: record 10 runs with IMU on, each with a reference for the same run: Stryd
   recorded by its own app (phone), or Galaxy Health data. A Samsung Health workout cannot run on the watch
   next to RunPro (only one app owns the Health Services exercise), so Galaxy Health data must come from a
   source that does not start a second workout on the watch. Keep, per run: the `.imu` file(s) (named by
   run id), the FIT from the phone and the reference export. Report them on the field-test issue; nothing
   about accuracy is claimed until #34 compares them.

## Fused location check (F13)

Phone paired over Bluetooth with location on. Keep `adb logcat -s RunProLocation RecordingService` and the
debug trace on.

1. Stand next to a tall building (where Health Services stayed acquiring in #12) and choose **Get ready**.
   **GPS: ready** should appear within about 10 s. Note the time.
2. Start, stand still for 2 min, then run 10 min, partly in the open, and stand still for 2 min at the end.
   The distance must not grow while standing; the pace must look plausible while running.
3. After the run, check in the FIT file (or the debug trace) the source switches for location, distance
   and speed between `watch:fused-location` and `watch:health-services`, and that the track has no jumps
   of 50 m or more.
4. Repeat a 30 min run in the open with and without the phone (F7): compare battery %/h with an F1 run.

Report the build, the times, the distance against a known route, the switches and the battery numbers.
These are procedures awaiting physical results; no field result is claimed by #96.

## Low-resource check (F11)

Use a short test run with the phone out of range, so its journal stays on the watch for inspection.
Battery levels can be simulated through adb without draining the watch:

```bash
adb shell dumpsys battery unplug
adb shell dumpsys battery set level 15
```

1. Open RunPro and choose **Get ready**. The initial 15% reading must show the low-battery warning.
   Start a run: it must continue recording at 15%. Set the level to 16: the warning must clear.
2. Set the level to 3 during the run. The summary must explain the automatic battery stop and show
   **Saved**; the Ongoing Activity disappears and RunPro's Health Services exercise ends. Check the
   `.journal` remains in `files/journals` and its `.reserve` is gone. Copy the journal for the report.
3. Set 16, start another run and pause it. Repeat 15 then 3: the warning and automatic save must also
   work while paused. Repeat with the screen off, then reopen RunPro and inspect the summary.
4. While still at 3, choose **Get ready**, then **Start**: it must stay in warm-up and explain the
   rejected start. Set 16 and retry **Start**: a new run must begin normally.
5. Always restore real battery reporting after the check:

   ```bash
   adb shell dumpsys battery reset
   ```

Storage checks belong on a disposable Wear OS emulator with no valuable data: consume space on the
journal's `/data` filesystem with a removable test file. Below 50,000,000 bytes the warning must appear
within the next one-second refresh, including while paused. At zero ordinary free bytes the engine
must attempt a clean automatic stop using its reserve. Check **Saved**, a retained stopped journal,
removed reserve, and exercise/foreground shutdown. A start at zero must remain in warm-up with a
storage explanation (or the storage error if reserve allocation fails). Remove the test file and
retry. Do not fill the owner's watch for this check. ENOSPC retry and exhausted-reserve failures also
have deterministic real-file JVM coverage in `LowResourceReplayTest` and `ReservedStorageTest`.

Report the build, simulated levels, screen/paused state, summary and journal observations as a field
report. These are procedures awaiting physical results, not a claim that F11 has passed.

## FIT file acceptance (no watch needed)

`./rp check` writes `core/fit/build/fit-samples/easy-run-30min.fit` and `every-record.fit`.

1. Open each file in Garmin's FIT File Tools / FitCSVTool (FIT SDK) or https://www.fitfileviewer.com:
   no errors, one running session, laps and events present.
2. Upload `easy-run-30min.fit` to Strava (Upload activity → File): it is accepted as a run of about
   4.91 km, 30:00 elapsed, 3 laps, average HR 139 (Strava computes its own moving time). Delete it afterwards.
3. Report the result on the export issue (#28): accepted or the exact error message.

## Phone sync check (F8)

The recording service writes journals during a run (#60). Watch and phone apps must be the same kind of build (both debug
from this Mac, or both release): the Data Layer pairs them by application id and signing key.

1. Install the phone app: `./gradlew :mobile:installDebug` with only the phone on adb (or
   `ANDROID_SERIAL=<phone serial>`), and the watch app with `./rp install`.
2. Record runs; for some, keep the phone away (or Bluetooth off) until after the stop. Open RunPro on the
   watch or bring the phone back: the runs move within a minute or two.
3. Check on the phone: `adb -s <phone> shell run-as com.runpro.wear ls files/runs` lists one `.fit` per run.
   On the watch: `adb -s <watch> shell run-as com.runpro.wear ls files/journals` is empty.
4. `adb logcat -s PhoneSync FitReceiver` on each device shows the passes; report the counts on the field-test
   issue.

## Health Connect check (#32)

Physical verification on the phone is an owner check (unit and Robolectric tests do not talk to the
live Health Connect provider or Samsung Health):

1. Install the phone build (`./gradlew :mobile:installDebug`) and sync at least one outdoor run with GPS,
   heart rate, cadence and laps from the watch (see F8 above).
2. Grant RunPro's Health Connect write permissions either in Android Settings → Health Connect → App
   permissions → RunPro (exercise, exercise route, heart rate, distance, steps), or by opening the
   rationale screen directly:

   ```bash
   adb -s <phone> shell am start -n com.runpro.wear/com.runpro.mobile.healthconnect.PermissionsRationaleActivity
   ```

   Tap **Grant Health Connect permissions** (or **Export pending runs** for runs synced earlier).
3. Check `adb -s <phone> logcat -s HealthConnectExport` and open Health Connect → Data and access →
   Activity → Exercise to verify the run appears as Running with route, HR series, distance, steps and
   laps. Re-tapping **Export pending runs** must not create duplicates.
4. If Samsung Health is configured to read workouts from Health Connect, confirm whether the run appears
   in Samsung Health and report actual observations on issue #32.

## Strava check (#31)

Needs a phone build with Strava credentials. Add to the git-ignored `local.properties` (root of the checkout,
or `mobile/local.properties`), never to a tracked file or an issue:

```properties
strava.clientId=205420
strava.clientSecret=<from https://www.strava.com/settings/api>
strava.callbackHost=localhost
```

1. Install the phone build (`./gradlew :mobile:installDebug`) and open **RunPro** from the launcher. Without
   the keys the screen says Strava is not configured.
2. Tap **Connect Strava**, approve in the browser or Strava app (upload permission): back in RunPro the
   status reads "Connected to Strava as <name>".
3. Sync a run from the watch (F8). With **Upload new runs automatically** on, it is uploaded within a minute;
   otherwise tap **Upload pending runs**. Check `adb -s <phone> logcat -s StravaUpload`.
4. Tap **Open on Strava** for the run: the activity is a Run whose distance and elapsed time match the
   watch summary (Strava computes its own moving time). Tapping **Upload pending runs** again must not
   create a second activity.
5. Report the run id, watch distance/time and Strava distance/time on issue #31 (20 runs for M4).

## After the run

1. Settings → Diagnostics → Export logs (sends trace + sensor log to the phone).
2. Open a `field-report` issue, attach files, fill in the scenario id and what went wrong.

## Settings check (#27)

1. Before a run, open **Settings**. Set units to Imperial, maximum/resting HR, weight, auto-pause on,
   countdown 3 s and debug trace on. Close/reopen RunPro; verify every value remains. Switch units and
   check weight converts between kg/lb without changing the profile. Clear a value and verify it stays
   unspecified. Restore Metric if desired.
2. **Get ready**, then **Start**. During the countdown cancel it: no run starts. Try again and verify
   recording starts at expiry. Leaving the activity during the countdown must cancel it. With countdown
   off, Start must retain its immediate behavior.
3. Run with speed data; stand still for at least 5 s and verify automatic pause. Move above 1 m/s and
   verify resume and increasing distance/moving time afterward. Normal delivery latency (up to 3 s)
   must allow both transitions; a screen-off batch older than 3 s must not trigger either transition.
   Manually pause and move: it must remain paused until Resume. A GPS/speed dropout must
   never itself cause a pause. Stop/save and confirm distance/pace units in the summary.
4. Copy the optional raw traces and inspect/replay the JSONL. They contain location when GPS is available:

   ```sh
   adb exec-out run-as com.runpro.wear tar -c files/traces > traces.tar
   tar -xf traces.tar
   ```

   Debug tracing off on the next run must create no new trace. Trace segments are capped at 10 MB;
   old traces are pruned before a new segment. They are separate from journals and phone sync. Attach
   relevant traces to a field report; the diagnostics/export screen is a separate backlog task.

Record the watch observations in a field report. No physical result for these steps is claimed by JVM tests.

## Data screens check (#23)

1. Before **Get ready**, open **Data screens**. Select a page, tap a numbered field and choose from the
   catalogue. Add/remove fields (1–5); add/remove pages (1–10), and move pages earlier/later. Changes save
   immediately. Recreate the owner's Gear Tracker layout, then close/reopen the app and verify the
   field and page order. Check layouts with each of 1, 2, 3, 4 and 5 fields on the round watch.
2. Start a run. Each page must fit its fields together. Turn the bezel in both directions, swipe
   horizontally, or use the arrows: page numbers wrap at the ends. Swipe vertically to reach lap,
   pause/resume and Stop; Stop must still ask for confirmation. Check navigation while paused and after
   leaving/reopening the activity. Verify readability while running and with long timers (> 1 hour).
3. Compare current pace, average pace and lap pace; mark a lap and check only lap values reset. Check
   Metric and Imperial display units on separate runs. Cadence is steps/min, power is watts, elevation
   is absolute altitude (m/ft), clock is local 24-hour time, battery is watch percentage.
4. HR zone uses the saved maximum HR: zone 0 below 50%, then zones 1–5 starting at 50/60/70/80/90%.
   Without maximum HR or current HR it reads `--`. Missing/stale sensor values, including power without
   a compatible sensor, must also read `--`; no dropout may pause or stop recording. Power ingestion
   from a real footpod still depends on its sensor support.

Record the reproduced Gear Tracker layout and actual readability/navigation observations in a field
report. JVM/Robolectric coverage does not claim physical acceptance or bezel timing on the watch.
