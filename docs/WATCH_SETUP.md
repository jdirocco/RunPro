# Galaxy Watch 5 Pro development setup

Use this guide to connect a Wear OS watch over Wi-Fi ADB and install the RunPro debug build.
Run terminal commands from the repository root (or the issue worktree). Addresses and ports below
are examples: replace them with the values displayed on your watch. The owner's step-by-step version, in
Italian, is [docs/it/CONFIGURAZIONE_OROLOGIO.md](it/CONFIGURAZIONE_OROLOGIO.md): keep the two consistent.

## Development computer

Install Python 3 (for `./rp`), JDK 21, and the Android SDK. The build uses platform 37.0 and
build-tools 37.0.0; the watch does not need to run API 37. Configure `ANDROID_HOME`, or set
`sdk.dir=/path/to/sdk` in ignored `local.properties`. See [GUIDE.md](GUIDE.md#rp-check-local-quality-gate).

Install SDK packages and put ADB on your shell's path, using your own SDK location:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"   # common macOS location
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
sdkmanager --licenses
java -version                                 # Java 21
python3 --version
adb version
```

ADB comes from SDK Platform Tools. Use an up-to-date copy, including for pairing.
See the [Android ADB documentation](https://developer.android.com/tools/adb).

## Enable debugging on the watch

Connect the computer and watch to the same Wi-Fi network. On the Galaxy Watch 5 Pro:

1. Open **Settings → About watch → Software information** (or **Software**).
2. Tap **Software version** five times to enable developer mode.
3. Return to **Settings → Developer options** and enable **ADB debugging**.
4. Enable **Wireless debugging** and approve access on your development Wi-Fi network.
5. If available, enable **Turn off automatic Wi-Fi** in Developer options to keep Wi-Fi available
   during development. Menu wording varies with One UI Watch versions.

These settings follow [Samsung's Galaxy Watch connection guide](https://developer.samsung.com/health/sensor/guide/connect-watch.html).

## Pair once, then connect

In **Developer options → Wireless debugging → Pair new device**, keep the pairing screen open.
Use its IP address and **pairing port**:

```sh
adb pair 192.0.2.10:37123
# Enter the pairing code from the watch when prompted.
```

After pairing succeeds, return to the main **Wireless debugging** screen. Read its IP address and
**connection port**, then connect:

```sh
adb connect 192.0.2.10:39517
adb devices -l
# List of devices attached
# 192.0.2.10:39517  device ...
```

The pairing port and connection port differ. Pairing alone does not establish the install connection.
Use the exact serial shown by `adb devices -l`; it may also be an mDNS name. Reconnect with the current
address/port after restarting wireless debugging, rebooting, or changing networks. Pair again if the
watch has forgotten the computer. See [Android's Wear OS Wi-Fi debugging guide](https://developer.android.com/training/wearables/get-started/debug-wifi).

## Install with one command

Once the watch is connected and is the only ADB device:

```sh
./rp install
```

With multiple devices (for example, a phone and a watch), select the watch explicitly:

```sh
ANDROID_SERIAL=192.0.2.10:39517 ./rp install
# Equivalent, with the argument taking precedence over ANDROID_SERIAL:
./rp install 192.0.2.10:39517
```

The command checks that the selected device is online and identifies as a watch, builds
`:wear:assembleDebug`, then runs `adb -s SERIAL install -r` for
`wear/build/outputs/apk/debug/wear-debug.apk`. It exits on a build/install failure and never installs
on every connected device. `-r` updates the app while retaining its data. Finish any active workout
before installing a development build.

## Owner acceptance check for issue #6

This is a physical install smoke check, separate from the running scenarios in
[FIELD_TESTING.md](FIELD_TESTING.md). Automated harness and UI tests cannot confirm this result.

1. Follow the pairing and connection steps above on the Galaxy Watch 5 Pro.
2. Run `./rp install` (or its serial-selecting form). Confirm exit code 0 and ADB's `Success` result.
3. Open **RunPro** from the watch's app launcher and confirm the RunPro screen appears. Alternatively:

   ```sh
   export ANDROID_SERIAL=192.0.2.10:39517
   adb -s "$ANDROID_SERIAL" shell am start -W -n com.runpro.wear/.MainActivity
   ```

4. Record the commit, watch/One UI/Wear OS versions, exact command, install output, and launcher result
   on issue #6. Only the owner can confirm this acceptance criterion.

The current foundation build has a launcher screen. Recording, location/health/Bluetooth permissions,
battery checks and external strap setup belong to later recording/BLE issues. Follow those features'
setup and [field scenarios](FIELD_TESTING.md) when implemented; this install check proves none of them.

## Battery exemption (once per install)

The watch has no settings screen that exempts an app from battery optimisation (Wear OS 6: the app details
page has no Battery entry and the exemption intents resolve to a placeholder, #10). Do it with ADB:

```sh
adb shell dumpsys deviceidle whitelist +com.runpro.wear
adb shell dumpsys deviceidle whitelist | grep runpro   # must print one line
```

It survives `./rp install` and is lost only when RunPro is uninstalled. RunPro's setup screen shows the
command until the exemption is in place. Also check that RunPro is not in Samsung's "Sleeping apps"
(Galaxy Wearable on the phone, and the phone's own battery settings if the phone app is installed); see
the Italian guide, section 7.

## Troubleshooting

| Symptom | Action |
|---|---|
| `adb not found` | Install `platform-tools`, add its directory to `PATH`, and verify `adb version` in the same shell. |
| No device / connection refused | Keep the watch awake, check Wi-Fi and Wireless debugging, then run `adb connect` with the current connection port. Do not use the pairing port. |
| Pairing fails | Reopen **Pair new device** and use the fresh pairing port/code. |
| `unauthorized` | Accept the debugging prompt on the watch. If necessary, forget the paired computer and pair again. |
| `offline` | Run `adb disconnect SERIAL`, then reconnect using the current connection address. |
| Network timeout | Ensure the network permits device-to-device traffic; guest/enterprise isolation can block ADB. Try a shared hotspot. |
| Multiple devices / wrong device | Run `adb devices -l` and set `ANDROID_SERIAL` to the watch's exact serial, or pass it to `./rp install`. Update stale exported serials after reconnecting. |
| SDK or Java build error | Check Java 21, SDK location, licenses, and the packages above; run `./rp check` for diagnostics. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app uses a different signing key. Use the original key; do not uninstall or clear data if the watch contains workouts that still need exporting. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free unrelated storage and retry. Preserve RunPro data and unexported workouts. |

After development, disconnect with `adb disconnect SERIAL` and disable Wireless debugging on the watch.
Disable the development Wi-Fi override before measuring field-test battery use.
