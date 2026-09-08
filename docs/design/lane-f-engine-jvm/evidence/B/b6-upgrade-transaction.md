# B6 upgrade shutdown transaction evidence — 2026-09-08

This record covers the controller-owned preparation/nonce transaction and its production request
file wiring. It does not cover first-claim publication, accepted-instance retention, schema and
named exit changes, or Rust/Node supervisor observation; those remain the coordinated protocol
batch in `design.md` section 0.

## Connected behavior

- `UpgradeController` owns OPEN/PERSISTING/ACKNOWLEDGED and the only preparation/nonce pair. Its
  verifier is installed on `UpgradeShutdownBridge` before the API routes can use the controller.
  Its PREPARING reservation spans the complete freeze/cancellation/nonce/Worker/response
  transaction. Prepare, cancel and commit return conflict while it is held, and `finally` releases
  it after failure. One latch pauses after the real lease cancellation snapshot is obtained but
  before it returns, and a second pauses the Worker prepare call; together they pin both ends of
  the reservation. A completed repeated prepare preserves the live capability.
- `HeadlessApp` installs the synchronous production writer after strict predecessor cleanup and
  before API exposure. Persistence completes before response bytes are written; acknowledgement
  follows a successful flush. Persistence or response failure restores OPEN.
- `ShutdownRequestWatcher` asks the verifier before generic expiry handling. PERSISTING returns
  DEFER and retains the file without consuming the one-shot guard. An exact ACKNOWLEDGED request
  with the live frozen preparation returns ACCEPT_COMMITTED, preserving response-before-dispatch
  even when the original force deadline elapsed during the flush. Plain ACCEPT remains expiry
  checked; missing, null and mismatched verification refuses dispatch.
- Production factory tests cover a controller-issued capability, direct wrong/missing nonce files,
  persistence failure, request-before-watcher startup, expired acknowledged dispatch and receipt
  identifiers. The controller transaction test blocks persistence and flush separately and proves
  verifier responsiveness, deferral, rollback and dispatch only after flush.

## Falsification evidence

Two deliberate defects were restored and rejected by the focused regressions:

| Deliberate defect | Raw failing output | Failing assertion |
|---|---|---|
| Nonce comparison removed from the controller verifier | `tmp/lane-f-takeover/b6-nonce-verifier-falsification.txt` | direct invalid nonce request dispatched (`HeadlessAppUpgradeShutdownWiringTest`, line 206 at that revision) |
| PERSISTING returned ACCEPT instead of DEFER | `tmp/lane-f-takeover/b6-controller-defer-falsification.txt` | dispatch occurred before the blocked response flush (`UpgradeControllerTransactionTest`, line 75 at that revision) |
| PREPARING guards removed from prepare/cancel/commit | `tmp/lane-f-takeover/b6-preparation-reservation-falsification.txt` | a competing prepare did not return conflict while Worker prepare was blocked (`UpgradeControllerTransactionTest`, line 85 at that revision) |

Moving generic expiry ahead of DEFER and removing the committed-expiry exception produced two
failures: the blocked-flush test found the request deleted, and the production-factory test found
no receipt. Raw output is `tmp/lane-f-takeover/b6-deadline-order-falsification.txt`.

## Verification

The complete affected-module run used:

```powershell
.\gradlew.bat --console=plain :modules:app-engine:test :modules:ui:test
```

It completed in 7m7s with `BUILD SUCCESSFUL`: app-engine and UI test tasks executed, while 90
other tasks were up-to-date. Raw output is
`tmp/lane-f-takeover/b6-full-modules-green.txt`. Before any later filtered run, the JUnit XML was
copied to `tmp/lane-f-takeover/b6-full-module-xml/`: 180 XML files, 1158 tests, zero failures,
zero errors and one skipped, timestamped 2026-09-08T07:24:51.016Z through
2026-09-08T07:31:52.461Z. The parser summary is
`tmp/lane-f-takeover/b6-full-modules-summary.txt`.

The pre-deadline-correction focused transaction run used:

```powershell
.\gradlew.bat --console=plain :modules:app-engine:spotlessApply :modules:ui:spotlessApply :modules:app-engine:test --tests '*ShutdownRequestWatcherTest.nullAcceptanceDecisionIsRefused' :modules:ui:test --tests '*UpgradeControllerTransactionTest' --tests '*HeadlessAppUpgradeShutdownWiringTest' --tests '*UpgradeLifecycleContractTest'
```

It completed in 21s with `BUILD SUCCESSFUL`; app-engine was up-to-date and UI executed. Raw output
is `tmp/lane-f-takeover/b6-transaction-final-green.txt`.

After restoring the deadline-safe ordering, the focused command was:

```powershell
.\gradlew.bat --console=plain :modules:app-engine:test --tests '*ShutdownRequestWatcherTest.expiredRequestIsDiscarded' :modules:ui:test --tests '*UpgradeControllerTransactionTest.watcherDefersDuringBlockedFlushAndDispatchesOnlyAfterAcknowledgement' --tests '*HeadlessAppUpgradeShutdownWiringTest.productionFactoriesCarryUpgradeRequestToReceipt'
```

It completed in 16s with `BUILD SUCCESSFUL`: three selected tests passed, including ordinary
expired-request clearing. Raw output is `tmp/lane-f-takeover/b6-deadline-order-green.txt`.

Removing the PREPARING checks from the three competing paths made the Worker-latch regression fail
in 13s; raw output is
`tmp/lane-f-takeover/b6-preparation-reservation-falsification.txt`. Restored source and both
reservation-boundary regressions are included in the final focused transaction run below.

The final focused command after both review corrections was:

```powershell
.\gradlew.bat --console=plain :modules:ui:spotlessApply :modules:app-engine:test --tests '*ShutdownRequestWatcherTest' :modules:ui:test --tests '*UpgradeControllerTransactionTest' --tests '*HeadlessAppUpgradeShutdownWiringTest' --tests '*UpgradeLifecycleContractTest'
```

It completed in 21s with `BUILD SUCCESSFUL`; UI executed and app-engine was up-to-date from the
immediately preceding focused run. Raw output is
`tmp/lane-f-takeover/b6-transaction-final-with-prepare-boundaries.txt`. The resulting reports
contain 33 selected tests: app-engine has 1 XML/10 tests and UI has 4 XML/23 tests, with zero
failures, errors or skips. Their timestamps span 2026-09-08T07:49:31.506Z through
2026-09-08T07:56:15.909Z. These filtered reports do not replace the saved 180 XML/1158-test
affected-module snapshot above, which predates the deadline and preparation-reservation reviews.
