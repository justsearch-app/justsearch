# D1 process and launch apply-scope audit

Source audit at `6c95d7989`. This is reader evidence and a candidate classification for
the D1-3 register; it does not implement the register or claim reconfiguration proof.
Apply-scope precedence, shared dependency handling, and the remaining full-union
obligations are owned by
[`apply-register-plan-2026-09-21.md`](apply-register-plan-2026-09-21.md).

The audit is limited to process roots, launcher inputs, API binding, process resource
policy, telemetry, and boot-only policy. A setting is not hot merely because one reader
consults an environment variable late: every long-lived owner derived from that setting
must be able to change coherently. Conversely, a launch-supplied value can still belong
to a component when that component is its only physical owner and reconstruction applies
the captured value.

## Candidate classifications with reader evidence

| Canonical key and declaration | Candidate scope | Primary reader evidence |
| --- | --- | --- |
| `justsearch.data.dir` — `EnvRegistry.DATA_DIR` (`EnvRegistry.java:45`) | `restart-required` | `LauncherEnvironment.java:139-175` resolves the directory before constructing the global `ConfigStore`, telemetry, instance lock, and operation store. Later `PlatformPaths.resolveDataDir` reads at `PlatformPaths.java:67-91` cannot move those owners. |
| `justsearch.config` — `EnvRegistry.CONFIG_PATH` (`EnvRegistry.java:57`) | `restart-required` | `LauncherEnvironment.java:139-161` installs the profile path before constructing the resolved snapshot and global store. Repointing only later YAML reads would split configuration authority. |
| `justsearch.home` — `EnvRegistry.HOME` (`EnvRegistry.java:289`) | `restart-required` | `PlatformPaths.java:150-161` resolves AI home, while long-lived owners capture derived paths; `RuntimeActivationService.java:435-436` captures its home and status path. A late change would split persistent stores. |
| `justsearch.repo.root` — `EnvRegistry.REPO_ROOT` (`EnvRegistry.java:284`) | `restart-required` | `RepoRootLocator.java:29-45` owns discovery. The captured path feeds index composition at `IndexConfigurationProjection.java:37-42` and also feeds generative/install discovery, so no single component owns a coherent move. |
| `justsearch.mcp.host.config` — `EnvRegistry.MCP_HOST_CONFIG` (`EnvRegistry.java:60`) | `restart-required` | `HeadAssembly.java:700-704` resolves and parses it while constructing the process MCP-host service. There is no live replacement owner. |
| `justsearch.prod` — `EnvRegistry.PROD_MODE` (`EnvRegistry.java:72`) | `restart-required` | Boot uses it for fake-capability admission (`InfraPhase.java:71-75`), session-token minting (`HeadlessApp.java:472-486`), and API security construction (`LocalApiServer.java:299-323`). Rebuilding only the API would not revisit all three decisions. |
| `egress.block_all` — `EnvRegistry.EGRESS_BLOCK_ALL` (`EnvRegistry.java:75`) | `restart-required` | `SmokeDriver.java:63-120` consumes the resolved launch profile. No running-engine apply owner exists. |
| `justsearch.mode` — `EnvRegistry.MODE` (`EnvRegistry.java:100`) | `restart-required` | The declaration assigns this value to the launcher. `HeadlessApp.java:1214-1220` reads it once for manifest intent, and install planning uses the same launch intent. |
| `justsearch.lite.mode` — `EnvRegistry.LITE_MODE` (`EnvRegistry.java:567`) | `restart-required` | `InferenceDecision.java:29-64` uses it to decide whether the `InferenceLifecycleManager` exists. The current process cannot hot-create a manager omitted during Head assembly. |
| `justsearch.ui.settings.mode` — `EnvRegistry.UI_SETTINGS_MODE` (`EnvRegistry.java:476`) | `restart-required` | `HeadlessApp.java:690-693` resolves the persistence mode before constructing the process settings store; resolution is at `UiSettingsStore.java:435-461`. |
| `justsearch.ui.settings.readOnly` — `EnvRegistry.UI_SETTINGS_READONLY` (`EnvRegistry.java:1204`) | `restart-required` | This is the second input to the same boot-only persistence decision at `UiSettingsStore.java:455-461`. |
| `justsearch.head.tracing_level` — `EnvRegistry.HEAD_TRACING_LEVEL` (`EnvRegistry.java:945`) | `restart-required` | `HeadlessApp.java:416-437` conditionally installs the process-global tracing SDK during the infra phase and retains it for process lifetime. |
| `justsearch.index.tracing_level` — `EnvRegistry.INDEX_TRACING_LEVEL` (`EnvRegistry.java:935`) | `restart-required` with current readers | `KnowledgeServer.java:570-596` attempts index tracing installation, but `EncoderOrtRunSpans.java:30-35` and `NativeSessionHandle.java:62-74` cache the gate in static-final class state. Index reconstruction cannot reload those classes, and the Head may already own the JVM-global SDK. |
| `justsearch.engine.admission.aggregate_limit` — `EnvRegistry.ENGINE_ADMISSION_AGGREGATE_LIMIT` (`EnvRegistry.java:1405-1408`) | `restart-required` | `EngineResourcePolicy.java:23-51` reads the override once. `EngineRoot.java:62-67` constructs admission, executors, and retained-state policy from that result. |
| `justsearch.app.version` — `EnvRegistry.APP_VERSION` (`EnvRegistry.java:1220`) | `restart-required` | The desktop shell injects running-build identity. `HeadlessApp.java:495-499` supplies it to upgrade reconciliation; changing it live would misstate the launched binary. |
| `justsearch.head.stamp` — `EnvRegistry.HEAD_BUILD_STAMP` (`EnvRegistry.java:983`) | `restart-required` | The declaration identifies this as the launcher-injected Head distribution stamp (`EnvRegistry.java:977-983`). `RuntimeManifestPublisher.java:446` projects it as running-process evidence. |
| `justsearch.api.port` — `EnvRegistry.API_PORT` (`EnvRegistry.java:63`) | `component:api` | `LocalApiServer.java:299-365` owns desired port policy, actual bind/fallback, applied version, and READY evidence. The selected ephemeral numeric port is runtime evidence, not a stable configured value. |
| `justsearch.telemetry.flushMs` — `EnvRegistry.TELEMETRY_FLUSH_MS` (`EnvRegistry.java:66`) | `component:index` | Worker configuration captures it and `IndexConfigurationProjection.java:35-50` includes the effective value in the index owner's applied projection. Reconstructing the index telemetry owner can apply it. |
| `justsearch.dev.hotreload` — `EnvRegistry.DEV_HOTRELOAD` (`EnvRegistry.java:962`) | `component:index` | `IndexConfigurationProjection.java:49` captures it for index composition. `EnvRegistry.java:949-962` documents the in-process `KnowledgeServer`/`DevReloadManager` owner. |
| `justsearch.power.force_energy_state` — `EnvRegistry.POWER_FORCE_ENERGY_STATE` (`EnvRegistry.java:994`) | `hot` | `EnergyStatePoller.java:154-168` re-reads the override on every host poll. |
| `justsearch.build.stamp` — `EnvRegistry.BUILD_STAMP` (`EnvRegistry.java:975`) | `hot` | `DevReloadManager.java:223-240` intentionally updates the property after a successful HotSwapPush so subsequent status reports carry the new runtime evidence. |

## Dynamic-reader exceptions

- `DATA_DIR`, `HOME`, `PROD_MODE`, `APP_VERSION`, and `HEAD_BUILD_STAMP` have late
  readers, but other process owners have already captured derived stores, trust state,
  or binary identity. Those late reads do not establish a coherent hot-apply path.
- `INDEX_TRACING_LEVEL` has the opposite shape: it is already projected as an index
  dependency, but its static class-initialization gates and the shared
  `GlobalOpenTelemetry` authority prevent index reconstruction from applying it fully.
  The current candidate is therefore `restart-required`, following the register plan's
  precedence. A future `component:index` target requires removing the static global reads
  and binding every gate to the captured index runtime sampler first.
- `BUILD_STAMP` is intentionally updated runtime evidence. `HEAD_BUILD_STAMP` describes
  the launched Head binary and must not be rewritten to simulate a different process.

## Unresolved target decisions

- `LITE_MODE` overlaps the generative-owner audit. Current construction evidence requires
  restart; a future component target is valid only after the generative owner can create a
  previously absent manager without rebuilding Head assembly.
- `EGRESS_BLOCK_ALL` currently has launcher/smoke readers rather than a running-engine
  owner. Its candidate scope is restart-required; the full-union reconciliation should
  decide whether the declaration remains an operator setting or is test-only legacy.
- `HOME` and `REPO_ROOT` are shared selectors. They must remain in every affected
  component dependency set even though restart precedence supplies their single register
  scope, as required by the linked apply-register plan.
- This audit does not classify legacy indexer IPC declarations, encoder/model keys,
  generative runtime inputs, or direct index fingerprint inputs; their owner audits feed
  the 291-key reconciliation separately.

No register file, runtime behavior, or verification result is produced by this evidence
document.
