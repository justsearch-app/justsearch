# Audit 3 — llama-server as the managed-child precedent; what a second child can reuse (read-only, lane-F-A HEAD 4229f1091)

Produced 2026-09-10 by an opus subagent under a read-only brief. Paths relative to the worktree root. Every `file:line` is at `4229f1091`.

## 1. The llama-server child lifecycle end to end

**Owner split.** `InferenceLifecycleManager` (ILM) orchestrates and holds the transition lock; `LlamaServerOps` owns the process; `OnlineModeOps` owns model traffic; `ServerPropsOps`/`TokenEndpointOps` own `/props` and `/tokenize`; `ModeStateMachine` is a pure tracker (`ModeStateMachine.java:9-11`).

**Policy.** `BrainSupervisionPolicy.defaults()` — `maxCrashes=3`, `crashRecoveryDelayMs=5000`, `consecutiveFailuresBeforeRestart=3`, `periodicHealthIntervalMs=30_000`, `healthCheckTimeoutMs=120_000` (`:38-47`, `:69-85`); asserted against `governance/supervision-contract.v1.json` (`:9-11`). Local timeouts `LlamaServerOps.java:112-117`.

**Start** (`startLlamaServer`, `:321-429`): `closeUnregisteredChild()` (`:322-323`); clear launched-window record (`:326-328`); `gpuLayers` forced 0 under policy (`:331-335`); registered `LLAMA_SERVER` record → `adoptManagedServerIfPresent` (`:338-347`); else `adoptExistingServerIfPresent()` (`:348`); plan the ladder (`:361-370`, `:868-893`); `buildLaunchCommand` → `launchManagedLlamaServer` (`:372`, `:428`).

**Argv** `buildLaunchCommand` (`:440-491`): `<exe> -m <model>`, `--mmproj`, `--cache-ram 0` (vdu), `--jinja`, `--reasoning-format deepseek --reasoning-budget N`, **`--host 127.0.0.1`** (`:476-478`), `--metrics` (`:480-483`), `--port` (`:485-486`), `-c <rung> -ngl <layers>` (`:501-503`), `-np <slots> -kvu -ctk -ctv -fa on -fit off` (`:527-531`; `-fit off` rationale `:518-525`).

**Environment.** `pb.directory(serverExeDir)` (`:1133-1137`); PATH prefixed (`:1139-1167`); stdout+stderr to `logs/llama-server.log` rotated 3 generations (`:1175-1185`, `:1218-1253`, `RETAINED_LOG_GENERATIONS=3` `:110`).

**Port is not ephemeral.** `InferenceConfig.fromEnvironment` picks `8081` or `8082` if `serverPort <= 0` (`InferenceConfig.java:123-132`); builder default `8080` (`:580`). True ephemeral only on `detachExternalServer` (`InferenceLifecycleManager.java:941`, `:1018-1023`).

**Health wait** (`awaitServerHealth`, `:660-731`): 500 ms poll to 120 s; exit `-1073741515` → `MISSING_DLL` (`:682-696`); other death → `PROCESS_EXITED`; first 200 → `ThinkingSupport.SUPPORTED`, `logServerProperties()` (`:702-711`); timeout → `HEALTH_CHECK_TIMEOUT`.

**Crash parsing** `diagnoseServerFailure` (`:1755-1790+`): last 16 KiB, matches `unknown model architecture`, `error loading model`/`failed to load model`, `CUDA error`/`cudaMalloc failed`.

**Two step-down ladders** (`waitForServerHealth`, `:643-658`): `relaunchWithoutReasoningBudget` (`:739-777`), once, on `PROCESS_EXITED` with a matching rejection; `relaunchAtLowerContextRung` (`:792-847`), on `PROCESS_EXITED` only (timeout deliberately not a trigger `:783-786`), false for adopted/override/exhausted.

**Zombie protection is `ProcessHandle`-based.** `stopLlamaServerAndConfirm` (`:551-615`): destroy → 5 s → destroyForcibly → 5 s; returns false if alive; `stopLlamaServer()` throws `IllegalStateException("Managed llama-server survived terminal cleanup")` (`:545-549`); record retained (`:601-604`). The only `taskkill` is `RuntimeActivationService.stopSelfTestServer` (`:1418`).

**Registration is part of launch** (`startManagedProcessAndMonitor`, `:1255-1298`): `ManagedChild.fromProcess(started, Kind.LLAMA_SERVER, "http://127.0.0.1:"+port, modelPath, declaredConfigHash, realizedArgvHash(command))`, `childRegistry.register(child)`; `rollbackFailedRegistration` (`:1300-1324`) force-kills, retains handle, JVM shutdown hook, refuses relaunch (`closeUnregisteredChild`, `:1337-1346`). Crash monitor on `llamaExit` executor unregisters on exit (`:1283-1297`).

**Registered executors (C1).** `InferenceExecutorRegistrations.java:46-51`: `inference.llama-health` (SCHEDULED 1), `inference.llama-recovery` (SCHEDULED 1), `inference.llama-exit` (PLATFORM 1); opened `LlamaServerOps.java:300-313`. `ManagedChildReconciler` registers `head.child-reconciliation.http` (`:59-91`).

**Periodic health** (`:1456-1557`): 30 s fixed-rate; 3 failures → `goOfflineFromExternalFailure` or `managedCrashHandler`; `HealthCode` by substring heuristic (`:1541-1549`).

**Crash recovery** (`:1564-1617`): always emit `PROCESS_DIED` (`:1568-1582`); at 3 crashes terminal OFFLINE; else restart at 0 ms then 5000 ms.

**Adoption of a registered managed server** (`adoptManagedServerIfPresent`, `:987-1040`): dead PID → remove; `IdentityMatch.UNKNOWN` → skip; `MISMATCH` → remove record; valid = `declaredConfigHash` equal **and** `/health` 200 **and** `/props` llama-shaped; invalid → terminate; valid → second liveness+identity re-check (`:1016-1026`); on success `adoptedManagedHandle=live`, thinking `UNKNOWN`, health scheduled, exit monitor (`:1042-1058`).

**External (BYO) adoption** (`adoptExistingServerIfPresent`, `:949-985`): `/health` 1 s; policy `disallowExternalInferenceServers`; `/props` must satisfy `looksLikeLlamaServerProps` (`ServerPropsOps.java:405-412`) unless `allow_health_only_adoption` (`:966-981`).

**`applyConfig`** (`InferenceLifecycleManager.java:684-858`): refuses while TRANSITIONING; refuses restart on external (`:732-747`); `APPLY_ONLY` swaps field; restart inside `TransitionRunner` with rollback `applyConfigRollback` (`:865-919`).

**`detachExternalServer`** (`:925-1016`): fresh ephemeral port, preserves `chatProfileId` (`:966-968`).

**Close** (`:1471-1499`): `closeUnregisteredChild()`, `stopLlamaServer()` only if `stopServerOnClose`, else "left for adoption".

## 2. Child-ownership machinery: generic vs llama-shaped

**Generic today:** `ManagedChild` record (`app-api/.../runtime/ManagedChild.java:14-23`) keyed on a `Kind` enum; `ManagedChildRegistry` (`:8-13`); `MutableManagedChildRegistry` persist-then-set (`:61-66`), single-shot `installWriter` (`:30-33`); `RuntimeManifestPublisher` sole writer (`:144-145`, `:675-680`); manifest schema v2 (`RuntimeManifest.java:113`, `children` `:108-109`); `publicProjection()` nulls `children` and `shutdownHandoff` (`:324-347`); identity capture `ManagedChild.fromProcess` (`:49-72`) throws on missing evidence; `identityOf` tri-state `MATCH/MISMATCH/UNKNOWN` (`:83-98`), start instant within 1000 ms + normalized executable; `ManagedChildReconciler.reconcile` (`:96-123`): dead → drop; `UNKNOWN` → untouched; `MISMATCH` → drop record; else terminate and drop; `terminateMatched` (`:150-162`); ownership seed `publishOwnershipSeed()` (`:399-420`) from `HeadlessApp.java:58`, `readPredecessorChildren` (`:162-178`); shutdown handoff `markShutdownPending` (`:683-692`), `completeShutdown` (`:695-733`) deletes manifest only when terminal, graceful, `children.isEmpty()`. Registration-rollback pattern duplicated per owner (`LlamaServerOps:1300-1324`, `PersistentExtractionSandbox:570-580`).

**Llama-shaped:**
- `ManagedChild.Kind` closed two-value enum `LLAMA_SERVER`, `EXTRACTION` (`:25-28`).
- `ManagedChildReconciler` hardcodes `child.kind() == Kind.LLAMA_SERVER && declaredLlamaConfigHash.equals(...) && healthyLlama(endpoint)` (`:116-121`); `healthyLlama` (`:125-142`) requires `http://127.0.0.1:` prefix and llama `/props` shape. No per-kind probe seam.
- Reconciler takes one hash `declaredLlamaConfigHash` (`:34-38`), computed in `HeadlessApp.java:1003-1018` via `ManagedLlamaConfigIdentity.declaredHash`.
- Config-identity hashing is not shared: `ManagedLlamaConfigIdentity` bound to `InferenceConfig`; extraction pool duplicates SHA-256/NUL as private `hashArgv` (`PersistentExtractionSandbox.java:601-610`).
- `ShutdownRequest.Reason.stopsGenerativeBackend()` (`app-engine/.../ShutdownRequest.java:80-85`): `QUIT, UPGRADE -> true`, `RESTART, HANG -> false`; wired at `HeadlessApp.java:1436` → `HeadAssembly.java:311` → `ILM.setStopServerOnClose` (`:1451-1469`). No per-child stop-on-reason policy.
- Supervisor terminal kill ignores `kind`: Rust `ManagedChildRecord { pid, started_at, executable }` (`lib.rs:984-990`); dev-runner reads the same (`dev-runner.cjs:1005-1037`). Inherited for free; no ordering or reason-awareness.
- Windows Job Object containment parser-only: `WindowsParserContainment.install()` (`worker-services/.../extract/WindowsParserContainment.java:25-37`), called from `ExtractionSandboxChild.initializeProcessBoundary` (`:152-153`), self-assigned in the child, `KILL_ON_JOB_CLOSE` (`:65`), handle never closed (`:20-24`), package-private, 64-bit only (`:61-63`). llama-server has no containment.
- Parent-watch child-side: `ExtractionSandboxChild` polls `ProcessHandle.of(parentPid)` every 500 ms, `Runtime.halt(0)` on death (`:158-176`), `--parent-pid=` (`PersistentExtractionSandbox.java:493`); `public` for the chaos harness (`:144-147`).

**What `encoder-host` would add:** a third `Kind` + schema test; per-kind liveness/identity probe seam + second declared hash; a shared hash helper in `app-api`; a per-kind stop-on-reason predicate; its own containment if it spawns native descendants; nothing supervisor-side.

## 3. Protocol facts

**Engine → llama-server:** one `HttpClient` (`InferenceLifecycleManager.java:192`) on `inference.http` BACKGROUND executor (`InferenceExecutorRegistrations.java:34-35`); separate clients in `LlamaServerOps` and `ManagedChildReconciler` (`:80-81`). URI `InferenceHttpHelpers.serverUri` = **`http://localhost:<port><path>`** (`:42-44`) while registration/probe use `http://127.0.0.1:` (`LlamaServerOps.java:1268`, `ManagedChildReconciler.java:126`) — a v4/v6 inconsistency. Endpoints: `/health` (`:80`, `:1373-1385`), `/props` (`:81`, `:1391-1404`), `/v1/chat/completions` (`OnlineModeOps.java:46`), `/tokenize`, `/apply-template` (`TokenEndpointOps.java:29-30`). `/metrics` enabled, never scraped (`InferenceMetricCatalog.java:35-38`). Bodies are OpenAI JSON (`OnlineModeOps.java:917-575`, `:960-975`). `HTTP_TIMEOUT = 2 min` (`:47`) bounds headers only → `StreamIdleWatchdog` (`:13-19`). SSE `consumeStreamBody` (`:688-713`). **Cancellation asymmetric:** `StreamWorkOwner` binds cancellation to interrupt + body close (`:60-68`, `:47-53`), constructed at one site `OnlineModeOps.java:875` inside `streamChatWithTools` (`:974`); `streamChat` family (`:444-687`) takes no `EngineWorkHandle`; `OnlineAiServiceImpl.java:431-433` refuses work-less streams; ILM overloads `:1092-1119` untraced.

**Engine → extraction child:** 4-byte big-endian signed length + UTF-8 JSON (`SandboxFrames.java:23-75`), `MAX_FRAME_BYTES = 64 MiB` (`:26`); child redirects `System.out` to stderr (`ExtractionSandboxChild.java:46-51`); fresh UUID `requestId` per request (`PersistentExtractionSandbox.java:303`, `:307-308`), `decode` rejects mismatched schema or id (`:384-390`); refusal after frame write retires the child (`:319-332`); response budget vs frame ceiling (`:200-257`); timeout kills first then cancels (`:336-344`); pool `Slot[]` + free queue, `DEFAULT_MAX_REQUESTS_PER_CHILD = 500` (`:60`).

**Comparison:**

| | loopback HTTP | stdio frames |
|---|---|---|
| encoding | JSON via `ObjectMapper` (`OnlineModeOps.java:575`) | JSON payload in byte frame |
| binary tensors | no helper (`InferenceHttpHelpers.java:44-51`) but nothing forbids octet-stream | framing byte-oriented; smaller change |
| size ceiling | none | 64 MiB per frame |
| concurrency | multiplexed; `onlineRequestLock` serializes model calls (`:913`) | one in-flight per child |
| correlation | implicit | UUID echo validated |
| cancellation | close body / idle watchdog | kill child; no per-request cancel |
| survives Engine restart | yes (adoption) | no (parent-watch) |

Sizing: 8×768 fp32 = 24 576 B raw, ~70-90 KB JSON; 50×512 ints ~150 KB JSON. JSON float encoding dominates; neither has a binary path; only HTTP supports warm survival.

## 4. Egress and trust

- `ArchUnitEgressTest` (`modules/ai-backend/src/test/.../ArchUnitEgressTest.java:9-27`) scope `io.justsearch.aibackend` only; llama traffic outside scope (gap, not exception). No other egress rule.
- **No egress factory.** Clients at `InferenceLifecycleManager.java:192`, `RuntimeActivationService.java:430`, `OpenAiCompatController.java:105,108`, `ManagedChildReconciler.java:81`, `ExternalLlamaServerClient.java:54`. Two ephemeral-port pickers (`InferenceLifecycleManager.java:1018-1023`, `RuntimeActivationService.java:1680-1687` with `18080` fallback).
- Loopback: `LocalApiServer.java:605` `app.start("127.0.0.1", bindPort)`; Host allowlist `ApiSecurityFilters.java:251-266`, `:744`, `LOOPBACK_HOSTS` (`:40`); MCP Origin `:296-320`, `:353-384`.
- Per-boot token (ADR-0046): 32 `SecureRandom` bytes base64url (`LocalApiServer.java:974-978`), prod only (`HeadlessApp.java:470`), header `X-JustSearch-Session` (`:69`), required on mutations + `RunRoutes` (`ApiSecurityFilters.java:481-489`), fail-closed (`:135`), stored `head.sessionToken` (`RuntimeManifestPublisher.java:422-456`), stripped by `publicProjection()` (`RuntimeManifest.java:155-157`), read by shell (`binding.rs:116`) or `GET /api/mcp/token`.
- **llama-server is unauthenticated**: no `--api-key`/`Authorization` anywhere in `app-inference`; only `--host 127.0.0.1`. Port not in the manifest (`EffectiveConfigController.java:248-262`, `OnlineAiRuntimeIntrospection.java:28`).
- ADR-0049 pre-authorizes the lane (`:70`, `:164-165`). `GpuProbeAccessTest` (`:33-56`) pins `GpuDriverApiProbe` to `io.justsearch.ort..` + `app.services.gpu..` and `NvmlService` to `io.justsearch.gpu..` — would need editing to move probing behind the host.

## 5. Config identity and catalogue

- `ManagedLlamaConfigIdentity.declaredHash` inputs (`:20-32`): normalized exe, model, mmproj or "", port, effective gpuLayers, vduMode, contextSize, useThinking, reasoningBudget, llmSlots, llmKvType, `"llama-fixed-flags-v1"` (`:14`); SHA-256 NUL-separated (`:39-50`). `realizedArgvHash` (`:35-37`) — nothing compares it. Consumers: `LlamaServerOps.java:1004`, `ManagedChildReconciler.java:116-118`. Extraction passes `declaredConfigHash = null` (`PersistentExtractionSandbox.java:503`).
- Catalogue: `modules/configuration/src/main/resources/ai/model-registry.v2.json`, classpath (`ModelRegistryLoader.java:31-32`, `AiInstallService.java:327`); `install-contract.v2.json` (`InstallContract.java:41`). `ModelPackage` (`:56-71`), `ModelVariant` (`:17-23`: filename, precision, targetEP CPU/CUDA, sha256, sizeBytes, downloadUrl), `InstallContract`/`InstalledModel` no memory field; `VariantSelection` (`:19-24`); `ModelManifest` (`ort-common/.../ModelManifest.java:40-46, 78-86`); selection axes GPU-vs-CPU + precision (`VariantSelector.java:49-95`).
- **No declared device footprint.** `ModelPackage.minVramBytes` (`:63`) consumed only by `InstallPlanner.java:207-211` against `MINIMUM_VRAM_FOR_GGUF`. Only `arenaCapBytes` (`ModelSessionPolicy.java:111-112`) from config.

## 6. Supervisor and dev-runner view of children

- State file `<dataDir>/runtime/supervisor.v1.json` (`lib.rs:1097-1104`, `dev-runner.cjs:823-826`); registered `store-recoverability.v1.json:1463-1471`. Rust `StateRecord` (`supervisor.rs:461-488`); dev-runner adds `runId`, `readyAt` (`dev-runner.cjs:852-889`); TS mirror `supervisorState.ts:39-63`.
- **`justsearch://supervisor-state` carries no children** (`lib.rs:1261-1265`, `:1120-1135`, `:1349-1352`; `supervisorState.ts:119-128`).
- Tauri terminal: `RunEvent::Exit` → kill Engine → `cleanup_registered_children_for_terminal` (`lib.rs:1883-1889`) → `reconcile_registered_children` (`:992-1030`), `ManagedChildRecord { pid, started_at, executable }` (`:984-990`), `terminal_kill_if_identity_matches` (`:1032-1077`); recoverable death leaves children (`:1208-1210`); updater calls before installer (`updater.rs:753`).
- Dev-runner: `engine-supervisor.cjs` (`decide`, `classifyExit` `:129-146`); actuator `dev-runner.cjs:808-1064`; `cleanupRegisteredChildrenForTerminal` (`:1005-1037`) with per-child outcomes; fires on force-kill for `quit`/`upgrade` (`:2687-2690`) and on every `dev stop` (`:3118`); writes `supervisor-history.v1.jsonl` on exhausted (`:828-841`, `:891-913`) — Rust only `eprintln!` (`lib.rs:1289-1294`).
- `quick_health` (`server.mjs:2203-2522`) reads neither the supervisor file nor the child registry; `supervisorAlive` is the dev-runner's own pid (`:191`); bare inference port probe (`:2334-2344`) unattributed; `foreignRuns` kinds `'backend'|'inference'` (`process-record.cjs:375-469`).

## Contradictions
1. design.md 875-878 public projection — confirmed.
2. design.md 717-722 cancellation obligation — neither transport supplies a reusable per-request cancel.
3. design.md 894-895 extraction never adopted — confirmed.
4. design.md 837 terminal kill — Rust cannot order or differentiate kills by kind.
5. `serverUri` `localhost` vs `127.0.0.1`.
6. design.md §5 and §3.1 already specify the target contract.

## Could not establish
1. Whether ILM `streamChat` (`:1092-1119`) still has a production caller.
2. `supervision-contract.v1.json` assertions.
3. Whether the manifest schema test enumerates `Kind` values.
4. Transport throughput (structural only).
5. Whether `check-runtime-manifest-closure` needs a new entry.
6. `ContextWindowPolicy` rung values (read elsewhere: 32768/16384/8192/4096).
7. Whether supervisors need a schema bump for per-child state.
