---
status: active
---

# Composition substrate slots — named-consumer contract v1

**Status**: active (first cut 2026-05-21)
**Owning tempdoc**: 541 (composition-substrate-completion)
**Cadence**: manual quarterly audit until tempdoc 531 (`consumer-drift` gate kind on tempdoc 530's discipline-gate kernel) ships an automated CI-tier check. See `docs/reference/contributing/composition-substrate-retraction.md`.

## What this contract is

Each Phase Output field exposed by the head-process composition substrate (per tempdoc 519 §31) is a **named substrate slot** under the C-018 discipline ("substrate-without-consumer fails review"). This document is the authoritative enumeration of every slot, its named production consumer(s), and its verdict per the tempdoc 527 audit format:

- **healthy** — ≥ 2 named consumers, OR 1 consumer that is inherent to the substrate's design and explicitly justified;
- **fragility signal (per 527 §7)** — exactly 1 named consumer; calibration risk, not failure; eligible for grace period under tempdoc 531's `consumer-drift` semantics;
- **C-018-unnamed-pending** — 0 named consumers; ship-or-retract protocol applies (see retraction doc).

🟡 indicates an indirect consumer — the slot flows through a `Supplier<T>` wrapper or constructor injection where direct grep is unreliable. Such verdicts are documented with the proximate constructor argument and the downstream consumer.

## Slot table

Source-of-truth file paths reference the post-§31-merge repository state (HEAD ≥ commit `ffae40b9b`).

### Worker-dependent slots (`ServicePhase.Output.workers().*`)

| Slot | Named production consumer(s) | Verdict |
|---|---|---|
| `workers().search()` | `KnowledgeSearchController` (`modules/ui/.../api/KnowledgeSearchController.java`); agent `SearchTool` (`modules/app-agent/.../tools/SearchTool.java`) | healthy (≥ 2) |
| `workers().indexing()` | `IndexingController` (via `Supplier<IndexingService>` constructor arg — `modules/ui/.../api/IndexingController.java`); agent `IngestTool` (`modules/app-agent/.../tools/IngestTool.java`); `SyncOps` (`modules/app-services/.../worker/SyncOps.java`) | healthy (≥ 3) |
| `workers().documents()` | `PreviewController`, `ChunkInfoController` (both via `Supplier<DocumentService>` constructor arg — `LocalApiServer.java` lines ~494, ~497) | healthy (≥ 2) 🟡 indirect |
| `workers().excludes()` | `IndexingController.handleApplyExcludes` (via injected `ExcludesService` constructor arg — `IndexingController.java:33,40,178,181`) | fragility signal (1) 🟡 indirect |

### Inference slots (`ServicePhase.Output.inference().*`)

| Slot | Named production consumer(s) | Verdict |
|---|---|---|
| `inference().onlineAi()` | `InferenceHandlers` (5 callsites in `InferenceHandlers.java`); `BrainRuntimeServiceImpl` (constructor parameter); `OfflineCoordinatorBuilder.build` (3rd argument) | healthy (≥ 3) |
| `inference().brainRuntime()` | `BrainRuntimeController`-side caller (operation handler `ReloadInferenceHandler`, `SwitchInferenceModeHandler`, `TriggerOfflineProcessingHandler` via `OperationHandlerRegistrations.registerWorker`); `LocalApiServer` controller wiring | healthy (≥ 2) |
| `inference().runtimeVariant()` | `ActivateRuntimeVariantHandler`, `DeactivateRuntimeVariantHandler` (operation handlers); `LocalApiServer` controller wiring | healthy (≥ 2) |
| `inference().packImport()` | `PreflightAiPackHandler`, `ImportAiPackHandler` (operation handlers); `LocalApiServer` controller wiring | healthy (≥ 2) |
| `inference().brainInstall()` | `StartAiInstallHandler`, `CancelAiInstallHandler`, `RepairAiInstallHandler` (operation handlers); `LocalApiServer` controller wiring | healthy (≥ 3) |

### Core slots (`ServicePhase.Output.core().*`)

| Slot | Named production consumer(s) | Verdict |
|---|---|---|
| `core().settings()` | `ResetSettingsHandler` via the service supplier registered by HeadAssembly | fragility signal (1 consumer); directly composed, no controller callback |
| `core().policy()` | `CreateUserPolicyHandler`, `AllowlistAddDigestHandler` (operation handlers); `PolicyController` (HTTP) | healthy (≥ 3) |
| `core().diagnostics()` | `ExportDiagnosticsHandler` (operation handler); `DiagnosticsController` (HTTP) | healthy (≥ 2) |
| `core().agent()` | `AgentController` (HTTP); held via `ServiceGraph` for downstream HTTP routes | healthy (≥ 1 confirmed, likely ≥ 2 once orchestration is fully traced) 🟡 indirect |

### Helper slots (`ServicePhase.Output.{aiInstallHelper,aiPackImportHelper,runtimeActivationHelper,packAllowlistService}()`)

| Slot | Named production consumer | Verdict |
|---|---|---|
| `aiInstallHelper()` | `AiInstallController` (constructor arg — `LocalApiServer.java:644`); `BrainInstallServiceImpl` constructor (in `ServicePhase` itself, internal composition) | fragility signal (1 external + 1 internal — eligible for grace) |
| `aiPackImportHelper()` | `AiPackController` (constructor arg — `LocalApiServer.java:705`); `PackImportServiceImpl` constructor (internal composition) | fragility signal (1 external + 1 internal — eligible for grace) |
| `runtimeActivationHelper()` | `AiRuntimeController` (constructor arg — `LocalApiServer.java:695`); `RuntimeVariantServiceImpl` constructor (internal composition) | fragility signal (1 external + 1 internal — eligible for grace) |
| `packAllowlistService()` | `AiPackImportService` constructor (used as the allowlist source within the helper itself — `LocalApiServer.java:704`) | fragility signal (1 — used through the helper, no direct external consumer) |

### Infra slot (`ServicePhase.Output.gpuCapabilitiesService()`)

| Slot | Named production consumer(s) | Verdict |
|---|---|---|
| `gpuCapabilitiesService()` | `LocalApiServer.statusLifecycleHandler` (confirmed direct read); `InferenceHandlers` (`GpuCapabilitiesService` constructor param at line 44; used at lines 140, 292); `OfflineCoordinatorBuilder.build` via `VduBatchProcessor` constructor; `AiInstallService` helper (`enterprisePolicy` orchestration) | healthy (≥ 3) |

### Late-binding holders (`BootstrapLateBindings`)

Settings reset is directly composed from UiSettingsStore and OperationAttemptRunner in
ServicePhase. Its former controller callback was removed; only diagnostic SPI bindings remain.

| Slot | Named production consumer | Verdict |
|---|---|---|
| `debugStateProvider` | `DiagnosticsServiceImpl` (deferred Supplier wrapper) | **inherent** |
| `statusSnapshotProvider` | `DiagnosticsServiceImpl` (deferred Supplier wrapper) | **inherent** |

### Substrate-graph slots (`SubstratePhase.Output.*` — internal)

| Slot | Named production consumer | Verdict |
|---|---|---|
| `substrateOut.healthOut.lifecycleSnapshotTap` | `StatusLifecycleHandler` (`LocalApiServer.java:596`) | fragility signal (1) — inherent to status-deck contract; not eligible for retraction |
| `substrateOut.operationOut.capabilitiesChangeRegistry` | `InfraRoutes.handleInfraCapabilities` (via `HeadAssembly.capabilitiesChangeRegistry()` accessor — `HeadAssembly.java:553`, `InfraRoutes.java:66`) | fragility signal (1) — inherent to `/infra/capabilities` endpoint contract; not eligible for retraction |

### Endpoint slots

| Slot | Named production consumer | Verdict |
|---|---|---|
| `GET /api/boot/phases` (shipped 2026-05-21 in tempdoc 541) | `<jf-boot-phases-panel>` Lit element — `modules/ui-web/src/shell-v0/components/BootPhasesPanel.ts`. Registered via Shell.ts side-effect import (`Shell.ts:47`); mounted in `LogSurface.ts` under the BOOT_TRACE filter chip. Backend: `BootRoutes.register` in `modules/ui/src/main/java/io/justsearch/ui/api/routes/BootRoutes.java`. | fragility signal (1) — single FE consumer; ships in lockstep with the endpoint. Eligible for retraction if the panel is ever unmounted from every surface. |

## Audit history

| Date | Auditor | Outcome | Changes |
|---|---|---|---|
| 2026-05-21 | tempdoc 541 P2 (initial slot enumeration) | first cut — 13 slots enumerated; 4 indirect-cardinality slots resolved via deeper grep; helper-slot cardinalities clarified (1 external + 1 internal-composition each). `EffectiveConfigController` removed from `gpuCapabilitiesService` consumer list (not actually a consumer). | Initial document. |
| 2026-05-21 | tempdoc 541 fix-pass F.2 (real grep-verified audit) | All 13 slots audited against branch `worktree-541-composition-substrate` HEAD. Sample evidence verified live (commands below). **No retractions, no verdict changes.** The proposed `GET /api/boot/phases` endpoint shipped in fix-pass Tier 3 with its named FE consumer (`BootPhasesPanel.ts` registered via `Shell.ts:47` side-effect import, mounted in `LogSurface.ts` under the BOOT_TRACE filter chip). Endpoint row should be re-classified from "C-018-unnamed-pending" to "fragility signal (1)" in the v2 contract. | Verified slot consumers via grep:<br/>- `capabilitiesChangeRegistry` → `InfraRoutes.java:66-68` ✓ direct read.<br/>- `lifecycleSnapshotTap` → `LocalApiServer.java:596` ✓ direct read via `StatusLifecycleHandler:93,170,273`.<br/>- `gpuCapabilitiesService` → `InferenceHandlers.java:34-51,140` ✓ direct read.<br/>- `/api/boot/phases` → `BootPhasesPanel.ts` registered via `Shell.ts:47`, mounted in `LogSurface.ts:504` under BOOT_TRACE filter ✓ named consumer at landing. |

## How this contract is enforced

Until tempdoc 531's `consumer-drift` gate kind ships on tempdoc 530's discipline-gate kernel:
- **Manual quarterly audit** runs this enumeration against the current codebase. Each row's consumer evidence is grep-verified.
- A row whose consumer disappears moves to `C-018-unnamed-pending`. The retraction protocol in `docs/reference/contributing/composition-substrate-retraction.md` applies.
- New Phase Output fields require a new row at the same time the field is added.

After 531 lands:
- The enumeration becomes a slot declaration consumed by the `consumer-drift` gate so CI flags drift automatically.

## Glossary

- **Substrate slot** — A Phase Output field exposed by the head-process composition substrate. Each is a named typed value with declared production consumers.
- **Named consumer** — A specific source-code callsite (file + line) that reads the substrate slot. Anonymous-via-reflection / lookup-by-name consumers do not count.
- **Inherent slot** — A slot whose single-consumer cardinality is *by design*; documented in tempdoc 519 §31 or in this contract's "verdict" column. Not eligible for retraction.
- **Indirect consumer (🟡)** — Consumer that receives the slot via `Supplier<T>` wrapper or via constructor injection chain. Direct grep does not surface them; evidence is the proximate constructor argument + the downstream call.
