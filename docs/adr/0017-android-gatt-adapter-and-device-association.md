# ADR 0017: Android GATT adapter and device association

- Status: proposed (accepted when issue #17 is merged)
- Date: 2026-11-28

## Context

Issue #17: the real `GattPort` over `BluetoothGatt` for the controller of ADR 0012, with `TRANSPORT_LE`,
`autoConnect=true` after the first connection, a `CompanionDeviceManager` association to keep background
rights, bond state checks and a refresh of the GATT cache on repeated failures, running inside
`RecordingService`. Done when the owner's strap streams HR into the app on the watch for 30 minutes.
ADR 0013 already prefers fresh BLE samples over the wrist; nothing reached the engine from BLE yet.

## Decision

- **Pure link in `:core:ble`.** `BleSensorLink` wraps one `BleConnectionStateMachine` per paired sensor
  and owns what is policy, not platform, so it is tested on the JVM:
  - bytes of a required measurement go to `notification` first, then `GattParsers` and `BleSamples`;
    samples reach the listener only while the link streams and only from the current session;
  - `SensorEvent.Connected` when streaming starts, `SensorEvent.Disconnected` (with the status of a
    disconnect callback, null for a stall) when a streaming link closes. Resubscribing after silence
    that restores data produces no events. No event is a recording command (R4); the engine falls back
    to the wrist from freshness (R5, ADR 0013);
  - **GATT cache refresh**: two consecutive sessions that close before streaming (`CACHE_REFRESH_AFTER`)
    refresh the platform cache right before the next discovery; the count resets after a refresh and
    whenever a session streams. Stop is never counted. Two, not more: a stale cache fails every attempt,
    and each extra attempt costs a backoff step of the R8 budget;
  - **bond check**: discovery is held while the device reports `BONDING` and released on the first tick
    after the bond ends, within the unchanged 2 s discovery deadline (a timeout recovers as before and the
    held request is dropped by close). A platform rejection of the held request is reported as
    `STATUS_REJECTED` so it cannot hang. The bond state is logged at every discovery.
  - `GattDevicePort` extends `GattPort` with `refreshServiceCache` and `bondState`; `BleLogLine` is the
    sensor log line (transitions and notes, with source and session). #20 persists and exports it; until
    then it goes to logcat (`RunProBle`).
- **Android adapter** (`:wear`, `ble` package, thin):
  - `AndroidGattPort`: `connectGatt(context, autoConnect, callback, TRANSPORT_LE)`; the first connect has
    `autoConnect=false`, every reconnect `true` (decided by the controller). One `BluetoothGattCallback`
    per session: callbacks copy their arguments on the binder thread and post to the BLE thread, so no
    completion is inline and a closed session's late callbacks are dropped. A connect that ends
    disconnected completes with its status (`-2` when the platform says success). Subscribing enables
    local notifications and writes the CCCD (notify, else indicate), with the API 33 `writeDescriptor`
    / `onCharacteristicChanged` overloads and the older ones below 33. Missing characteristics or CCCD
    and refused calls throw (rejection). `close` calls `disconnect()` then `close()` and never waits for a
    callback. `refreshServiceCache` calls the hidden `BluetoothGatt.refresh()` by reflection and returns
    false when it is unavailable.
  - `WatchBle`: owned by `RecordingService`, on its own `runpro-ble` `HandlerThread`. It follows the
    engine phases like the exercise: links for every paired sensor from `PREPARING` through `RUNNING` /
    `PAUSED`, none in `IDLE` / `STOPPED`; the pairing is read when a run prepares (or is restored after a
    kill). It ticks every 100 ms while links exist. `follow` only posts (R6). No `BLUETOOTH_CONNECT`, no
    adapter or no pairing: the run records from the watch sensors. Samples and events go to the same
    engine feed as Health Services.
  - **Association**: `SensorActivity` (setup screen → **Sensors**) pairs one HR strap and one footpod.
    Where the watch has `android.software.companion_device_setup`, `BleAssociation` asks
    `CompanionDeviceManager` with a `BluetoothLeDeviceFilter` on the kind's service UUID (0x180D strap,
    0x1814 footpod): the system chooser needs no scan permission, and the association grants the declared
    `REQUEST_COMPANION_RUN_IN_BACKGROUND`, `REQUEST_COMPANION_USE_DATA_IN_BACKGROUND` and
    `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` (the first exempts RunPro from battery
    optimisation, which the watch has no screen for, see #10). API 33+ uses the executor callback and
    `AssociationInfo` (MAC, display name, id); older APIs the handler callback and the selected
    `ScanResult`. Where companion setup is missing, `BleScan` runs a 15 s low-latency scan filtered on
    the same UUID and lists the devices. Replacing or forgetting a sensor drops its association.
    `PairedSensors` (pure) stores the choice as one preference string, one sensor per kind, with the
    upper-case address as the stable `ble:<address>` source id.
- No new dependency. Stryd (CPS and extra metrics, #21) and the diagnostics screen / sensor-log export
  (#20) are out of scope.

## Evidence and limits

- `BleSensorLinkTest` (JVM): connect/stream/HR samples with events, GATT 133 with the status in
  `Disconnected` and reconnect with `autoConnect=true` in under 10 s (R8), resubscription without
  events, stall reported as a disconnect, cache refresh after two setup failures and its reset, no
  refresh for normal drops, discovery held while bonding and released, bonding past the deadline,
  rejected held discovery, stale/empty/unknown/malformed notifications, stop, logging, footpod RSC.
  `PairedSensorsTest`: codec, normalisation, one sensor per kind.
- `AndroidGattPortTest` (Robolectric, API 30 and 34): one client per session, posted (never inline)
  completions, failed connect versus later disconnect, discovery and CCCD write, missing
  characteristic and refused notifications, copied notification bytes, idempotent close that silences
  late callbacks, bond mapping, no second client before close.
- `WatchBleTest`: phases, re-reading the pairing per run, 100 ms tick driving the watchdog, permission
  and empty pairing, close. `RecordingServiceBleTest`: a run's strap HR becomes the engine's heart rate
  and active source; a GATT 133 drop leaves the run recording (R4); stop closes the link.
- Robolectric does not run a Bluetooth stack: `TRANSPORT_LE`, real callback order, the hidden refresh,
  companion association on the Galaxy Watch 5 Pro and CPU suspend during long silences are unverified.
  Whether Wear OS on the watch offers companion device setup decides which pairing path the owner sees.
  The acceptance (30 min of strap HR on the watch) and R8 in the field (#22, F1/F2) are owner tests; no
  field result is claimed.
