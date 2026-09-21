# D1-2 host readiness sampling audit

Source audit at `b4d01c1cc`. This note narrows D1-2 to the two supervised host
consumers and their shared conformance proof. It does not change readiness or
supervision policy. The target contract remains
[`readiness-plan-2026-09-21.md`](readiness-plan-2026-09-21.md) and
[`D1.md` D1-2](../../stages/D1.md#d1-2--readiness-as-projections-of-the-registry-hosts-read-one-field).

## Current owners and data flow

| Boundary | Current behavior | Primary evidence |
| --- | --- | --- |
| Status sample | `buildStatusMap` uses the cached Worker sample on ordinary reads, recomputes its age against the current clock, and declares it stale on failure or after the bounded sample age. It does not issue an extra RPC merely because a host polls `/api/status`. | `StatusLifecycleHandler.java:503-510,529-550` |
| Existing envelope | The Worker-contact result is projected into the readiness envelope; the ten diagnostic dimensions and three composites are built from it. The current wire is schema 1 and has no `engineComponents` field. | `StatusLifecycleHandler.java:555-573,1395-1432`; `ReadinessEnvelopeView.java:11-24` |
| Existing index diagnostic | `INDEX_SERVING` can be `DEGRADED` while `indexHealthy` is true because compatibility, embedding and throughput remain diagnostic degradations. Therefore its diagnostic state cannot replace the essential host gate. | `StatusLifecycleHandler.java:1558-1608` |
| Health/liveness split | `/api/health` returns the lifecycle snapshot and maps READY/DEGRADED to 200 and other lifecycle states to 503. Both hosts deliberately accept any valid HTTP status from this route as liveness; essential readiness is the separate `/api/status` read. D1-2 must preserve that split. | `StatusLifecycleHandler.java:1139-1158`; `scripts/dev/dev-runner.cjs:1305-1319,2721-2727`; `modules/shell/src-tauri/src/engine_probe.rs:7-44,57-62` |
| Dev host predicate | `essentialStatusReady` currently requires Head READY, `indexAvailable`, Worker `indexHealthy`, and a non-stale `indexServing` observation. Optional AI and the diagnostic `indexServing.state` do not gate it. | `scripts/dev/dev-runner.cjs:1372-1378`; focused truth table at `scripts/dev/test-dev-runner-supervisor.mjs:529-543` |
| Dev host stability clock | Each successful poll starts or continues `essentialReadySince` using `performance.now()`; any false predicate or failed health probe clears it. Only a continuous interval of `stabilityWindowMs` resets a nonzero restart budget. | `scripts/dev/dev-runner.cjs:2708-2740` |
| Rust host predicate | `engine_probe::essential_status` implements the same four-input conjunction over `/api/status`; malformed or absent fields fail closed. Production and the conformance binary both call this probe through the current Engine binding. | `modules/shell/src-tauri/src/engine_probe.rs:61-79`; unit table at `:123-145`; production adapter at `modules/shell/src-tauri/src/lib.rs:1170-1179`; conformance adapter at `modules/shell/src-tauri/src/bin/supervisor_conformance.rs:151-158` |
| Rust host stability clock | The common supervisor loop owns `ready_at`, sets it from the actuator's monotonic `now_ms`, clears it on readiness or health loss, and resets the budget only after one continuous window. | `modules/shell/src-tauri/src/supervisor.rs:742-760`; actuator contract at `:524-534` |
| Shared cross-host proof | One scenario holds the budget while essential readiness is false, interrupts a partial ready interval, and resets it only after a fresh full interval. Both adapters invoke the same scenario. | `scripts/supervisor-conformance/essential-stability.mjs:1-31`; dev adapter `scripts/supervisor-conformance/adapters/dev-runner.mjs:194-196`; Tauri adapter `scripts/supervisor-conformance/adapters/tauri.mjs:110-119` |
| Policy authority | The product stability window is 300000 ms and is counted from `ready`; harness overrides are accepted only under the explicit harness flag. | `governance/supervision-contract.v1.json:166-177`; `scripts/dev/lib/engine-supervisor.cjs:55-100`; Rust policy projection `modules/shell/src-tauri/src/supervisor.rs:183-201` |

The four current inputs are not deleted as facts. D1 moves their conjunction into
the `index` component publisher. A cached `/api/status` read must still recompute
sample age and may demote an earlier registry READY observation when the sampler
wedges. Optional-AI, compatibility, embedding and throughput diagnoses remain in
the existing dimensions even when the essential `index` component is READY.

## Minimal migration

1. `StatusLifecycleHandler` first obtains or ages the Worker sample exactly as it
   does today. It publishes `index=READY` only when all four current inputs hold:
   API/Head serving, `indexAvailable`, Worker `indexHealthy`, and non-stale Worker
   contact. A failed conjunction demotes a prior sampled READY to `UNAVAILABLE`
   without overwriting physical `ABSENT`, `STARTING`, `RELOADING`, or `FAILED`.
   Capture one registry snapshot after that observation and project it into
   `readiness.engineComponents`. Do not feed this sampler publication back into a
   new sampler pass.
2. The envelope exposes the registry `index` state and its state-start instant.
   The host gate becomes exactly `index.state === "READY"`; missing envelope,
   missing component, unknown state, and invalid/missing state-start evidence fail
   closed. Hosts no longer independently inspect `components.head`,
   `indexAvailable`, `worker.core.indexHealthy`, or `indexServing.stale`.
3. Treat the state-start value as a READY-epoch token. The dev runner retains its
   monotonic `performance.now()` timer and resets `essentialReadySince` when the
   predicate is false **or the token changes**. The Rust probe returns an optional
   READY epoch rather than a Boolean; the common supervisor retains its monotonic
   `ready_at` and clears/restarts it when the epoch changes. This preserves the
   existing same-host monotonic elapsed-time proof while detecting a READY to
   READY epoch replacement between polls.
4. Update the fake Engine to emit only the new `engineComponents.index` state and
   state-start evidence for this gate. Its control file must cause a new epoch on
   each false-to-true transition. Keep `essential-stability.mjs` unchanged in
   policy: half-window readiness, a loss, then another half-window must not reset
   the budget; only the new complete interval may do so.
5. Update the supervision register's partial-boot detection text and guard to name
   `readiness.engineComponents.index.state`, as D1-2 already requires
   (`D1.md:239-250`). This is a consumer/schema migration, not a new readiness
   policy or a second component authority.

The registry already records both an `Instant stateSince` and an in-process
monotonic origin (`EngineComponentSnapshot.java:14-31`), and the concrete registry
changes both on a state transition (`DefaultEngineComponentRegistry.java:210-223`).
The JVM monotonic value must not cross the process boundary: it has no meaning in a
Node or Rust clock domain. The hosts use the wire instant only to identify the
state epoch and measure the stability interval with their own monotonic clocks.

## Regression and conformance obligations

- Status projection: every false input in the existing four-way conjunction must
  independently prevent `index=READY`; a cached sample must age from READY to
  UNAVAILABLE without another Worker RPC; physical STARTING/RELOADING/FAILED/ABSENT
  must not be promoted or overwritten by stale cached evidence. Preserve the
  current staleness tests at `WorkerStatusSamplerTest.java:184-237` and
  `StatusReadinessStalenessTest.java:161-246`.
- Envelope/schema: require all four component entries, exact index state and
  state-start wire shape, schema bump, and equality with the schema-2 lifecycle
  projection. Existing owners are `StatusRecordSchemaTest`,
  `LifecycleContractTest`, and `LifecycleSnapshotTapTest`.
- Dev host: replace the four-field fixture in
  `test-dev-runner-supervisor.mjs:529-543` with READY, each non-READY state,
  absent/malformed component, missing/malformed epoch, and same-state/new-epoch
  cases. Prove a new epoch restarts the local stability window.
- Rust host: mirror that table in `engine_probe.rs:123-145`, including fail-closed
  JSON parsing and epoch changes. Exercise the changed actuator return shape in
  the common supervisor-loop tests, rather than testing only the JSON helper.
- Cross-host: run the existing continuous-essential-stability scenario against
  both real supervisor adapters. It is the proof that a 503-responsive process
  cannot reset its budget, optional AI remains irrelevant, and readiness loss
  interrupts the window.

Required commands after implementation, from the repository root unless noted:

```text
./gradlew.bat :modules:app-api:test --tests io.justsearch.app.api.status.StatusRecordSchemaTest
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.observability.health.LifecycleSnapshotTapTest
./gradlew.bat :modules:ui:test --tests io.justsearch.ui.api.LifecycleContractTest --tests io.justsearch.ui.api.StatusReadinessStalenessTest --tests io.justsearch.ui.api.WorkerStatusSamplerTest
node scripts/dev/test-dev-runner-supervisor.mjs
node scripts/dev/run-dev-runner-tests.mjs
node scripts/supervisor-conformance/run.mjs --self-test
node scripts/supervisor-conformance/run.mjs --adapter dev-runner
cd modules/shell/src-tauri && cargo test --locked engine_probe && cargo build --bin supervisor-conformance --locked
node scripts/supervisor-conformance/run.mjs --adapter tauri
node scripts/ci/regen-all.mjs --check
node scripts/ci/run-ui-web-gates.mjs
```

The adapter commands are executed proof only when the real dev-runner and compiled
Rust conformance binary both run the shared scenario; a successful self-test or
Rust build alone is insufficient.

## Design inconsistency to settle before implementation

D1 currently names two different wire spellings for the same evidence:
`EngineComponentView.stateSince` in the readiness envelope (`D1.md:224-226`) and
`state_since` in the schema-2 lifecycle (`D1.md:229-231`). The requested host path
is in the envelope, so the host code cannot safely guess which spelling ships.
Choose and schema-test the envelope spelling before changing both consumers; the
lifecycle projection may retain its established snake-case convention.

There is also a clock-domain boundary hidden by the phrase “state_since clock.”
Computing the five-minute stability window directly from a JVM wall-clock string
would replace the existing host-monotonic proofs and become sensitive to clock
adjustments. The epoch-token approach above uses `stateSince` to prove continuity
and retains the current monotonic policy clock. If D1 instead intends wall-clock
elapsed time from the wire value, that is a supervision-policy change and needs an
explicit decision rather than an incidental parser rewrite.

No JavaScript, Java, Rust, schema, register, or runtime behavior is changed by this
audit.
