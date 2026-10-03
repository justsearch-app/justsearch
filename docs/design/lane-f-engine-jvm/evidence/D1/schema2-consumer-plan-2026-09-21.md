# D1-2 schema-2 consumer migration plan

Read-only audit against revision `b4d01c1cc88c237a0a13d614f82bcb0c82b67803` on Windows 11 / PowerShell, 2026-09-21. No build, test, schema generation, frontend gate, supervisor harness, or live stack was run for this audit.

## Contract boundary

Schema 2 must keep two different state vocabularies:

- The **overall lifecycle** remains the existing six-value `LifecycleState` (`STARTING`, `READY`, `DEGRADED`, `ERROR`, `STOPPING`, `STOPPED`) declared in `contracts/wire/status.proto:101-109`. It remains the type of `lifecycle.state`; there is no second aggregate policy.
- Each **engine component** uses the registry's six states (`ABSENT`, `STARTING`, `READY`, `RELOADING`, `FAILED`, `UNAVAILABLE`) from `modules/core/src/main/java/io/justsearch/core/component/ComponentState.java:4-11`. Schema 2 exposes these unchanged in the fixed `api`, `index`, `encoders`, and `generative` slots.

The governing D1 contract is `docs/design/lane-f-engine-jvm/stages/D1.md:220-251`: the readiness envelope gains `engineComponents`, the ten existing dimensions and three composites remain diagnostics, the lifecycle snapshot moves to schema 2, and both hosts consume only `readiness.engineComponents.index.state` for essential readiness. The owner sequence in `readiness-plan-2026-09-21.md:45-59` requires one registry snapshot after worker sampling/index publication, followed by both readiness and lifecycle projections from that same snapshot. `LifecycleProjection` remains the sole overall lifecycle derivation.

## Current authorities and required migration

| Surface | Current authority and evidence | Smallest coherent schema-2 change |
| --- | --- | --- |
| Registry observation | `EngineComponentSnapshot.Component` already carries state, reason, `stateSince`, applied/desired versions, compose evidence, recovery attempts and evidence (`modules/core/src/main/java/io/justsearch/core/component/EngineComponentSnapshot.java:8-34`). | Project this immutable snapshot; do not read capabilities, manifests or health fields again. |
| Health record | `LifecycleSnapshotV1` fixes `SCHEMA_VERSION = 1` and exposes old `head/worker/inference` components (`modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleSnapshotV1.java:13-31,56-71`). | Introduce an honestly named schema-2 record with overall `LifecycleState` plus four component records containing `ComponentState`, `reason_code`, and `state_since`. |
| Health JSON Schema | `modules/ui/src/main/resources/SSOT/schemas/lifecycle-snapshot.v1.json:1-53` fixes version 1 and old slots. `LifecycleContractTest` validates both routes against it (`modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java:45-49,66-95,101-130,136-166`). | Add/update the schema-2 artifact and migrate both `/api/health` and the status subset assertions together. Keep `state_since` in every component slot. |
| Status Java record | `StatusResponse` embeds `LifecycleSnapshotV1.Lifecycle` and `.Components` (`modules/app-api/src/main/java/io/justsearch/app/api/status/StatusResponse.java:20-27`). | Point these fields at the schema-2 types; bump `schema_version` to 2 in production and fixtures. |
| Status proto | `StatusResponse` fields 3/4 reference `Lifecycle`/`Components` (`contracts/wire/status.proto:20-25`); `Components` currently has head/worker/inference and `Component.state` incorrectly shares the overall enum (`:80-109`). | Preserve `LifecycleState` for the aggregate. Add a distinct component enum and four-slot component message. Update the status field without reusing or widening `LifecycleState`. `StatusWireContractConformanceTest` recursively pins Java names/types to proto (`modules/app-api/src/test/java/io/justsearch/app/api/status/StatusWireContractConformanceTest.java:35-60`). |
| Readiness envelope | `ReadinessEnvelopeView` currently contains only diagnostic `components` and `composites` (`modules/app-api/src/main/java/io/justsearch/app/api/status/ReadinessEnvelopeView.java:12-22`). | Bump its schema version and add immutable `engineComponents: Map<String, EngineComponentView>`. Retain both existing maps because health taps and the frontend still consume them. |
| Status JSON Schema/fixture | `StatusRecordSchemaTest` pins `status-response.schema.json` (`modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java:78-80`) and rewrites schemas/fixtures only in update mode (`:571-611,723-738`). The fixture is `modules/ui-web/src/api/__fixtures__/status-response-live.json`. | Update the Java sample to schema 2, regenerate the status schema and shared fixture, then regenerate TypeScript/Zod from that schema. Do not hand-edit generated TypeScript. |
| TypeScript generation | `scripts/codegen/gen-wire-schema-types.mjs:36-51` maps the status schema to `modules/ui-web/src/api/generated/schema-types/status-response.ts`; `scripts/ci/regen-all.mjs:84-85` owns the regen entry. Current generated components are old head/worker/inference slots (`status-response.ts:88-92,436-440`). | Regenerate after the Java/schema change so component-state types cannot be confused with `LifecycleStateNullable`. |
| Backend derivation | `StatusLifecycleHandler` builds a lifecycle snapshot before sampling (`modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java:510-564`), independently recomputes old slots (`:1285-1355`), and `/api/health` calls that path separately (`:1139-1143`). Readiness schema 1 is assembled at `:1395-1432`; worker/index dimensions read the old worker slot at `:1545-1563`. | Observe worker, publish the exact index readiness conjunction, capture one registry snapshot, then derive the lifecycle snapshot and readiness envelope from it. Delete `computeLifecycleSnapshot`. Health and status must share this projection path rather than take separate mutable reads. Derive `WORKER_CONTROL_PLANE` and `INDEX_SERVING` from the captured index component as required by D1. |
| Overall lifecycle/manifest | `LifecycleProjection.derive` currently recomputes from `WorkerCapability` and `InferenceCapability` (`modules/app-services/src/main/java/io/justsearch/app/services/lifecycle/LifecycleProjection.java:10-54`). The status handler prefers the manifest, then falls back to this second derivation (`StatusLifecycleHandler.java:1338-1352`). | Change the one projection to accept the registry snapshot and produce the aggregate used by both manifest and lifecycle schema. Remove the status fallback derivation. The aggregate table is already settled in `readiness-plan-2026-09-21.md`; this migration must not add another table. |
| Diagnostic health tap | `LifecycleSnapshotTap` reconciles `ReadinessEnvelopeView.components()` by readiness dimension (`modules/app-services/src/main/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTap.java:380-388`); its fixture constructs the current envelope at `LifecycleSnapshotTapTest.java:63-67`. | Keep diagnostic dimensions intact and update constructors/fixtures for the additive engine-component map. The tap does not become a component-lifecycle authority. |

## Consumers

- `StatusDeck.connectionTone()` is the only direct frontend reader of the old lifecycle slots found in `modules/ui-web/src`: it reads `status.components.head.state` and `.worker.state` after the independent `snapshotLive` reachability check (`modules/ui-web/src/shell-v0/components/StatusDeck.ts:403-425`). Migrate its retained-snapshot classification to schema-2 `api` and `index` component states and update `StatusDeck.test.ts:93` and its fixtures. Keep reachability precedence unchanged.
- `aiStateStore.computeReadiness()` reads `status.readiness.composites`, not the old lifecycle component slots (`modules/ui-web/src/shell-v0/state/aiStateStore.ts:795-798`). `verdict.ts` and `readinessNotice.ts` consume the resulting `ReadinessView` (`verdict.ts:224-266`; `readinessNotice.ts:5-9,705-713`). Because D1 retains composites, these consumers need schema-2 fixture/regression coverage, not a new component-to-verdict aggregate.
- `ApiExplorerView` reads diagnostic readiness dimensions plus legacy aliases (`modules/ui-web/src/shell-v0/views/ApiExplorerView.ts:95-109`). It can remain on those diagnostics for this migration; moving it to engine component states would change capability semantics beyond D1-2.
- `modules/ui-web/src/api/lifecycleState.ts:1-26` names the overall wire enum. Keep it for `lifecycle.state`; introduce/use the generated component-state type for the four component slots instead of adding component values to this constant.
- The dev host currently reconstructs the index conjunction from four fields (`scripts/dev/dev-runner.cjs:1372-1377`), and Tauri does the same (`modules/shell/src-tauri/src/engine_probe.rs:61-78`). Replace both with an exact test for `readiness.engineComponents.index.state === "READY"`; preserve their existing stability clock. Update the supervision contract field at `governance/supervision-contract.v1.json:239-247` and both adapter fixtures/tests.

## Implementation sequence

1. Add the separate wire/component state type and schema-2 Java records; update `StatusResponse`, `status.proto`, schema conformance samples, and lifecycle schema tests.
2. Make `LifecycleProjection` consume one `EngineComponentSnapshot`. In `StatusLifecycleHandler`, finish worker observation/index publication before capturing that snapshot, then use the projection once for the manifest aggregate, health/status lifecycle fields, and readiness `engineComponents`. Preserve diagnostic dimensions/composites and their current health-tap inputs.
3. Regenerate the status JSON Schema, shared JSON fixture, Java proto projection, and generated TypeScript/Zod. Sync the lifecycle schema into the UI resources if its canonical copy changes.
4. Migrate `StatusDeck` and both host readers. Update fixtures and conformance tests in the same change so no consumer silently accepts the old slots.
5. Add the exhaustive component-combination proof required by D1: schema-2 component slots must equal the captured registry snapshot, while manifest and lifecycle aggregate results must agree. This proof should call the one production projection, with an independent expected table.

## Compatibility decisions required before implementation

1. **Versioned Java/schema naming.** Prefer a new `LifecycleSnapshotV2` and `lifecycle-snapshot.v2.json`, retaining v1 artifacts only if an external compatibility window is required. Mutating a class/file named V1 would make generated and test evidence misleading. Routes remain unchanged and emit schema 2; dual emission is not specified by D1.
2. **Proto evolution.** A distinct `EngineComponentState` is required. Root must settle whether the old `Components` message/field numbers are reserved and replaced with a new message, or whether JSON compatibility is the only supported boundary. Reassigning old field numbers 1-3 from head/worker/inference to api/index/encoders would let old protobuf clients misinterpret valid data.
3. **StatusDeck meaning.** The closest schema-2 replacement for its old “head and worker both ready” dot is `api == READY && index == READY`; reachability still wins. If the dot is intended to mean HTTP connectivity only, use `api` alone, but that is a product semantic change and should be explicit.
4. **Timestamp naming.** D1 specifies lifecycle `state_since` but readiness `EngineComponentView.stateSince`. Keep those wire names while sourcing both from the same registry `Instant`; do not expose the monotonic timestamp.
5. **Forward compatibility.** The proto comment currently allows unknown overall lifecycle enum values (`status.proto:99-100`). Root should choose the same unknown-value policy for component states and ensure generated TypeScript parsing does not collapse a future component value into an overall state.

## Required verification after implementation

Run in this order after source freeze is released and generated artifacts are intentionally updated:

```text
./gradlew.bat :modules:app-api:updateSchemas
./gradlew.bat :modules:ui:syncSsotSchemas
node scripts/codegen/gen-wire-schema-types.mjs
node scripts/ci/regen-all.mjs --check

./gradlew.bat :modules:app-api:test --tests io.justsearch.app.api.status.StatusRecordSchemaTest --tests io.justsearch.app.api.status.StatusWireContractConformanceTest
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.observability.health.LifecycleSnapshotTapTest
./gradlew.bat :modules:ui:test --tests io.justsearch.ui.api.LifecycleContractTest --tests io.justsearch.ui.api.StatusLifecycleHandlerTest

cd modules/ui-web
npm run typecheck
npm run test:unit:run
cd ../..
node scripts/ci/run-ui-web-gates.mjs

cargo build --bin supervisor-conformance --locked --manifest-path modules/shell/src-tauri/Cargo.toml
node scripts/supervisor-conformance/run.mjs --adapter dev-runner
node scripts/supervisor-conformance/run.mjs --adapter tauri
```

The final integrated boundary also requires the repository's full `./gradlew.bat test`. The audit did not establish live boot, HTTP route, generated-artifact, or host-adapter proof; all commands above are prescribed, not executed.
