# D1-6 typed desired API-port design

Date: 2026-09-22  
Inspected revision: `1d89a465fa0d793ff8de0f2ebab79c35be8508b9` in the shared dirty
`codex/lane-f-pr1` worktree on Windows.  
Execution: read-only design pass. No source was edited and no Gradle, frontend,
installed-process, or stack check was run.

## Decision

Add one explicit nullable desired setting, `apiPort`, to the persisted
`UiSettings` object and as a trailing top-level member of `SettingsV2`. Do not
place it under `ui` or `llm`, and do not introduce a canonical-key/value bag.
This is the smallest typed extension of the existing accepted settings owner:
`SettingsV2` already carries top-level non-UI process-affecting input
(`indexPaths`) and uses `null` to mean “absent from this partial patch”
([SettingsV2.java:11-25](../../../../../modules/app-api/src/main/java/io/justsearch/app/api/settings/SettingsV2.java)).
The governing decision explicitly requires a typed persisted API-port mapping
and says the ephemeral bound port is runtime evidence
([reconfigure-owner-decisions-2026-09-22.md:6-15](../../../../../docs/design/lane-f-engine-jvm/evidence/D1/reconfigure-owner-decisions-2026-09-22.md)).

Use these meanings:

| Persisted `UiSettings.apiPort` | Meaning |
|---|---|
| `null` / missing | no user-owned value; normal default, environment, and JVM-property resolution applies |
| `0` | explicitly request an ephemeral listener on the next process incarnation |
| `1..65535` | explicitly request that fixed listener port on the next process incarnation |

Reject values outside `0..65535`; do not clamp a public settings request. The
resolver currently clamps ports as a defensive configuration boundary
([ResolvedConfigBuilder.java:898-906](../../../../../modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java)),
but silently rewriting a durable user request would make its restart receipt
false. A `null` patch member remains “preserve”, as it does for every current v2
field. Reset-to-defaults is the existing way to remove a stored override.

This is additive and needs no `UiSettingsStore` format bump. The store
serializes the whole POJO, accepts legacy envelopes 0–3, and reserves migration
steps for structural/default semantic changes
([UiSettingsStore.java:41-70](../../../../../modules/app-services/src/main/java/io/justsearch/app/services/settings/UiSettingsStore.java),
[UiSettingsStore.java:229-277](../../../../../modules/app-services/src/main/java/io/justsearch/app/services/settings/UiSettingsStore.java)).
An older file simply deserializes the new nullable field as `null`; a current
file may serialize it as null or an integer. Keep the envelope at schema 4.

## One data path

1. **Persisted owner.** Add `Integer apiPort`, Jackson getter/setter, and a
   `configuredApiPort()` accessor to
   `modules/app-api/.../UiSettings.java`. The setter accepts null and validates
   the integer range. `UiSettingsStore` remains the sole file owner at
   `$JUSTSEARCH_HOME/ui/settings.json`
   ([UiSettingsStore.java:398-416](../../../../../modules/app-services/src/main/java/io/justsearch/app/services/settings/UiSettingsStore.java)).

2. **Public typed contract.** Append `Integer apiPort` to `SettingsV2`; retain
   the four-argument compatibility constructor and `empty()` with null. Map it
   in `SettingsV2Projection.toSettingsV2`, `SettingsPatch.normalize`, and
   `SettingsPatch.merge`. The current projection and merge seams are
   [SettingsV2Projection.java:15-34](../../../../../modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsV2Projection.java)
   and [SettingsPatch.java:18-88](../../../../../modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsPatch.java).
   Include `apiPort` in the prepared success payload in `HeadlessApp` and its
   direct test/integration fixtures; that payload is currently hand-built from
   only `ui`, `llm`, `indexPaths`, and `settingsMode`
   ([HeadlessApp.java:1081-1087](../../../../../modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java)).

3. **Canonical resolution.** In
   `ConfigStoreRebuilder.contributeUiSettings`, contribute
   `EnvRegistry.API_PORT.configKey()` only when `configuredApiPort()` is
   non-null. Initial boot already loads settings before building the first
   `ConfigStore` and calls this exact mapping before the API is assembled
   ([HeadlessApp.java:700-752](../../../../../modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java)).
   Runtime settings preparation uses the same mapper
   ([ConfigStoreRebuilder.java:55-82](../../../../../modules/app-services/src/main/java/io/justsearch/app/services/config/ConfigStoreRebuilder.java)).

4. **Precedence.** Preserve the existing chain: JVM property/command-line
   `-Djustsearch.api.port` at 500, `JUSTSEARCH_API_PORT` at 400, persisted
   settings at 300, and programmatic default at 100. The current resolver has
   no YAML contribution for this key
   ([ResolvedConfigBuilder.java:52-73](../../../../../modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java),
   [ResolvedConfigBuilder.java:202-210](../../../../../modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java)).
   Therefore a dev-runner `--api-port` remains an operator override: it is
   forwarded as `JUSTSEARCH_API_PORT` on every incarnation
   ([dev-runner.cjs:2073-2089](../../../../../scripts/dev/dev-runner.cjs)). The installed
   Tauri launcher does not inject API_PORT; its packaged command supplies other
   JVM properties and only `JUSTSEARCH_HOME` at this boundary
   ([lib.rs:797-847](../../../../../modules/shell/src-tauri/src/lib.rs)). The D1-6 installed
   acceptance must use that no-port-override shape; running it through the
   ordinary dev-runner `--api-port` path would correctly mask the desired
   setting and test the wrong precedence.

5. **Configured policy versus bound endpoint.** Change
   `LocalApiServer.resolveConfiguredPort()` to return the resolved integer,
   including zero, rather than converting zero to null. Binding still passes 0
   to Javalin, but the API component desired/applied configuration digest then
   records the selected policy value `0`
   ([LocalApiServer.java:325-365](../../../../../modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java),
   [LocalApiServer.java:1079-1084](../../../../../modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java)).
   Keep the resulting positive `app.port()` solely in runtime evidence: stdout
   and the runtime manifest publish the actual bound port
   ([HeadlessApp.java:503-507](../../../../../modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java),
   [RuntimeManifestPublisher.java:435-484](../../../../../modules/ui/src/main/java/io/justsearch/ui/runtime/RuntimeManifestPublisher.java)).
   The shell already changes its binding when a new manifest incarnation
   appears ([binding.rs:77-102](../../../../../modules/shell/src-tauri/src/binding.rs)). Do
   not write the positive ephemeral result back to `UiSettings` or hash it as
   desired configuration.

6. **Diagnostics.** Replace
   `EffectiveConfigController.keyJustsearchApiPortConfigured`'s independent
   sysprop/env parse with the installed `ConfigStore` resolution; it currently
   omits settings/default even though the controller already exposes the
   full ordinal trace
   ([EffectiveConfigController.java:139-145](../../../../../modules/ui/src/main/java/io/justsearch/ui/api/EffectiveConfigController.java),
   [EffectiveConfigController.java:191-213](../../../../../modules/ui/src/main/java/io/justsearch/ui/api/EffectiveConfigController.java),
   [EffectiveConfigController.java:220-242](../../../../../modules/ui/src/main/java/io/justsearch/ui/api/EffectiveConfigController.java)).
   Retain `process.apiPort` as the separate actual bound value.

The apply register already classifies `justsearch.api.port` as
`restart-required`
([config-apply.v1.json:402-408](../../../../../governance/config-apply.v1.json)), and the API
component already declares it as its sole dependency
([LocalApiServer.java:1061-1068](../../../../../modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java)).
No register, EnvRegistry, YAML key, or generic override representation is
needed. D1-4 must persist this candidate through the existing witnessed
settings transaction, complete the operation with `restartScheduled`, and only
then invoke the existing requested-restart action. D1-6 requires the ordered
RESTART close and exit 4 ([D1.md:386-400](../../../../../docs/design/lane-f-engine-jvm/stages/D1.md));
the shutdown sequence selects exit 4 only after a clean ordered close
([EngineShutdownSequence.java:188-195](../../../../../modules/app-engine/src/main/java/io/justsearch/app/engine/EngineShutdownSequence.java)),
and the supervisor treats that requested restart as free
([supervisor.rs:314-331](../../../../../modules/shell/src-tauri/src/supervisor.rs)).

## Wire, frontend, and documentation work

- Regenerate `SSOT/schemas/settings-v2.v1.json` from the changed app-api
  record, then sync
  `modules/ui/src/main/resources/SSOT/schemas/settings-v2.v1.json`. This is an
  additive v2 API change, so retain the existing filename/version. The schema
  generator already owns `SettingsV2`
  ([WireRecordSchemaGenTest.java:182](../../../../../modules/app-api/src/test/java/io/justsearch/app/api/schema/WireRecordSchemaGenTest.java)).
- Regenerate `modules/ui-web/src/api/generated/schema-types/settings-v2.ts`
  from that schema. The target already exists
  ([gen-wire-schema-types.mjs:171-177](../../../../../scripts/codegen/gen-wire-schema-types.mjs));
  do not hand-edit the generated type or Zod schema.
- Extend `AppSettings` with `apiPort?: number` and extend the generated-derived
  `SettingsPatch`/`patchSchema` picks so a typed frontend caller can submit it
  ([domains/settings.ts:44-50](../../../../../modules/ui-web/src/api/domains/settings.ts),
  [settingsAttempt.ts:3-10](../../../../../modules/ui-web/src/api/settingsAttempt.ts)). A
  visible SettingsSurface control is not required for the D1-6 named API
  acceptance; the existing browser-mode UI-port recommendation is separately
  recorded at [ui-user-readiness.md:325-332](../../../../../docs/reference/ui-user-readiness.md).
- Refresh the Java-produced `settings-v2-live.json` fixture and its Java/TS
  contract assertions
  ([SettingsV2ContractTest.java:34-57](../../../../../modules/ui/src/test/java/io/justsearch/ui/api/SettingsV2ContractTest.java),
  [settings-v2.test.ts:13-35](../../../../../modules/ui-web/src/api/generated/schema-types/settings-v2.test.ts)).
  The existing contract-surface catalog row already names both real generated
  consumers, so it needs no new row
  ([contract-surfaces.v1.json:192-197](../../../../../governance/contract-surfaces.v1.json)).
- Update canonical configuration text to say settings ordinal 300 can own the
  desired API port and that the manifest reports the actual endpoint:
  `docs/explanation/06-configuration-ssot.md` §Settings, the API_PORT precedence
  row in `docs/reference/configuration/runtime-config-ownership-matrix.md`,
  `docs/reference/api-contract-map.md` §Settings API, and
  `docs/explanation/07-ui-host-architecture.md`. The environment-variable
  catalog remains correct (`0` means ephemeral)
  ([environment-variables.md:40-43](../../../../../docs/reference/configuration/environment-variables.md)).
  `SSOT/schemas/config/app-config.schema.json` is unchanged because this is a
  settings-file field, not a new application-YAML key.

Regeneration order is the repository-prescribed sequence
([common-workflows.md:125-139](../../../../../docs/reference/contributing/common-workflows.md)):

```text
./gradlew.bat :modules:app-api:updateSchemas
./gradlew.bat :modules:ui:syncSsotSchemas
node scripts/codegen/gen-wire-schema-types.mjs
./gradlew.bat :modules:ui:test --tests io.justsearch.ui.api.SettingsV2ContractTest
npm --prefix modules/ui-web run typecheck
npm --prefix modules/ui-web run test:unit:run
```

Run the docs/config/contract governance checks required by the changed canonical
documents after regeneration; no normalized config-key count changes because
`justsearch.api.port` is already declared.

## Required regression set

1. **POJO/store compatibility:** a schema-4 and a legacy envelope omitting
   `apiPort` load with null and preserve existing behavior; explicit 0 and a
   fixed port round-trip; reset removes the override; -1/65536 are refused
   without replacing file bytes or advancing the witness.
2. **Patch/projection:** GET projects null/0/fixed distinctly; omission and null
   preserve the incumbent; a fixed update returns the same field in the
   completed receipt; replay identity includes the normalized field.
3. **Resolution:** settings beats YAML/default; env and JVM property each beat
   settings; source attribution is `settings.json` at ordinal 300. Initial boot
   and runtime rebuild produce the same `ResolvedConfig.Ports.apiPort`.
4. **API observation:** configured 0 binds a positive ephemeral port while the
   component's applied digest is computed from 0; a fixed free port binds that
   exact port; effective-config reports canonical desired provenance separately
   from `process.apiPort`.
5. **Installed requested restart (non-vacuous D1-6 acceptance):** launch the
   packaged/installed supervisor with no API-port JVM/env override, read the
   current manifest/token/witness, submit a witnessed `SettingsV2.apiPort`
   change to a reserved free fixed port, and assert the durable settings row is
   `COMPLETE` with `restartScheduled=true` before replacement. Assert the old
   incarnation exits through clean requested exit 4 without spending restart
   budget, the manifest changes incarnation, the old listener closes, the new
   listener answers health on the desired port with its new boot token, GET
   settings returns the desired port, and API component desired/applied versions
   agree. A companion `apiPort=0` case asserts the successor binds a positive
   port while GET settings remains 0. This cannot be replaced by a unit test of
   `localRestartAction` or by a dev-runner launch that exports
   `JUSTSEARCH_API_PORT`, because neither proves the successor consumed the
   persisted setting.

## Proof still missing

This pass only establishes the typed field and ownership plan. It does not prove
the D1-4 dispatcher, row completion ordering, installed exit/restart, successor
binding, schema regeneration, or frontend acceptance. The current explicit-port
bind fallback also deliberately records a null applied policy after falling back
to ephemeral ([LocalApiServer.java:340-362](../../../../../modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java));
the fixed-port installed test must reserve a genuinely free port rather than
mistaking that fallback for successful application.
