# ADR 0012: BLE connection state machine

- Status: proposed (accepted when issue #16 is merged)
- Date: 2026-11-27

## Context

A stale GATT client, concurrent descriptor writes, error 133 and connected links that stop notifying
must all recover without user action. The connection policy belongs in pure Kotlin and must be testable
without a watch. The Android adapter is #17, sensor arbitration is #19 and diagnostic log storage/export
is #20. This issue implements the policy already specified in `ARCHITECTURE.md`, with no dependencies.

## Decision

- One `BleConnectionStateMachine` and `GattPort` per device. The adapter serializes commands, callbacks
  and ticks on its own dispatcher, off the UI/recording thread; the controller owns no thread or clock.
  All calls supply monotonic milliseconds from one boot. Tick at least every 100 ms during silence.
- `GattOperationQueue` permits one asynchronous request at a time: connect, service discovery, then one
  subscription per required measurement characteristic. A subscription includes local notification
  enablement and a CCCD write; completion means that write succeeded. Missing services/descriptors,
  immediate platform rejection, nonzero statuses and absent callbacks all enter recovery.
- Deadlines are 3 s for connect, 5 s for discovery (2 s until #120: a Polar H10 takes 2.0–2.2 s on an
  encrypted link) and 1 s per subscription. Completions at or after a
  deadline fail even if the next periodic tick has not run. A callback reports the request id assigned
  by the controller. Disconnects and notifications report the GATT session id. The adapter must bind
  callbacks to their original ids, rather than substituting the currently active request/session.
  It posts completions asynchronously and reports immediate platform rejections by throwing or posting
  failure; it never calls back inline from `execute`.
- Recovery clears queued work, enters Closing and calls `GattPort.close(sessionId)` before scheduling
  another connect. Close releases even partially created sessions and is idempotent. If close throws,
  stay in Closing and retry once per second; opening another client is forbidden until close succeeds.
  Stop cancels retry and queued work, closes the session and returns to Idle. Id counters survive restart
  of the same controller, making late callbacks harmless.
- The first connect uses `autoConnect=false`; recovery uses `true`. Backoff is 1/2/4/8 s, repeating 8 s.
  Eight is below the specified 10 s maximum: sleeping for 10 s alone would exhaust R8's entire budget.
  Only notifications from **all** required streams reset backoff; successful connection/CCCD callbacks
  alone cannot repeatedly reset a broken device to a fast retry loop.
- Each required periodic measurement characteristic has its own five-second silence watchdog. Optional
  battery notifications do not count. Silence enters Resubscribing and queues the subscription writes
  again. Actual notifications from every required stream must return during those writes or within
  one second after the last write. Failure, timeout or continued silence closes and reconnects.
- Every state change goes to a required `BleTransition` sink, with monotonic time, session, previous/next
  state and cause (including status, timeout and retry delay). Failed close attempts are logged as
  Closing-to-Closing events. The adapter supplies a prompt, nonthrowing, nonreentrant sensor log sink;
  persistence/export is #20. Notifications carry no payload in this policy API: the adapter also sends
  their bytes to the parsers/consumer, and calls `notification` only for nonempty measurements.

## Evidence and limits

`BleReconnectStateMachineTest` injects disconnects, GATT 133, silent stalls, missing callbacks, slow
service discovery, immediate rejection and close failure through a fake GATT. The fake rejects
concurrent requests and connects before close. Tests cover late/duplicate callbacks, queue cancellation,
independent devices, stop/restart, per-stream watchdogs and every logged setup transition. A boundary
test proves that data arriving after the resubscription grace cannot bypass timeout between ticks.

R8 timing tests measure successful recovery with virtual time: immediate dropouts, unsuccessful
resubscription and 20 seeded sensor-return offsets during capped backoff, including 1.2 s discovery.
The latter restores a notification within 9.23 s in the worst tested schedule. These are deterministic
policy guarantees, conditional on a working GATT completing setup promptly. Radio availability, Android
callback latency and a close that keeps failing cannot be bounded by core policy. Real watch R8
validation remains #22 (F1/F2), after #17/#19/#20. No field result is claimed. This controller has no
recording commands (R4); existing recording dropout tests continue to cover uninterrupted recording.
