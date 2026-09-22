# D1-5 candidate context implementation plan

Date: 2026-09-22  
Baseline: `215af7809` (preflight/offline checkpoint); accepted by the root after source review  
Environment: Windows, PowerShell; read-only source inspection; no build, test, Git, or stack command run  
Scope: the minimum API and ownership change needed to start, validate, retain, recover, and roll back a llama-server using captured `InferenceConfig` plus `ResolvedConfig` before manager configuration publication. Settings publication, the manager's modes, and its nine executors are outside this design.

## Decision

Use explicit immutable context at the start boundary, then retain that exact context in one immutable per-server owner snapshot for all asynchronous work. Explicit parameters alone are insufficient because periodic health, exit monitoring, delayed recovery, props interpretation, and health-time relaunches outlive the initiating call. A mutable pending-config supplier is also wrong because the incumbent A and candidate B can both have callbacks in flight, and the supplier cannot say which process a callback belongs to.

The context is a projection, not a configuration authority:

```java
record LlamaServerConfigContext(
    InferenceConfig inference,
    ResolvedConfig resolved) {
  LlamaServerConfigContext {
    Objects.requireNonNull(inference, "inference");
    Objects.requireNonNull(resolved, "resolved");
  }
}
```

Both inputs are immutable records: `InferenceConfig` validates its scalar/path shape in its compact constructor (`InferenceConfig.java:41-77`), while `ResolvedConfig` rejects null components and copies its resolutions map (`ResolvedConfig.java:47-90`). The context must not offer a `captureGlobal` helper. The owner supplies the exact resolved snapshot: D1 reconfigure supplies candidate B; legacy startup alone may capture the current global at its compatibility boundary.

Add these package-local types inside `LlamaServerOps` to keep the operational vocabulary local and avoid a new general registry:

```java
enum AdoptionPolicy {
  LEGACY_ALLOW_EXTERNAL,
  REQUIRE_MANAGED_CONFIG_WITNESS
}

enum StartDisposition {
  LAUNCHED_MANAGED,
  ADOPTED_MANAGED,
  ADOPTED_EXTERNAL
}

record StartRequest(
    LlamaServerConfigContext context,
    AdoptionPolicy adoptionPolicy) {
  /* non-null compact constructor */
  int effectiveGpuLayers() {
    return context.resolved().ai().gpuAccelerationAllowed()
        ? context.inference().gpuLayers()
        : 0;
  }
}

record StartResult(
    LlamaServerConfigContext context,
    AdoptionPolicy adoptionPolicy,
    StartDisposition disposition,
    String declaredConfigHash) {
  // managed dispositions require a nonblank hash;
  // external requires null hash and LEGACY_ALLOW_EXTERNAL.
}
```

`StartResult` is the logical start/ownership token. A retry intentionally retains it because its configuration, policy, and declared hash do not change. Physical process identity is a second, internal immutable snapshot:

```java
record ActiveServer(StartResult start) {}
```

`LlamaServerOps` retains `private volatile ActiveServer activeServer`; every adoption, launch, or health-time relaunch installs a fresh immutable object. Code compares `ActiveServer` identity (`activeServer == expectedProcess`), not record equality. This rejects a late health/props/exit response from the pre-retry process even though both processes share one `StartResult`. The existing `process`, `adoptedManagedHandle`, and managed-child id continue to own OS resources and supply the actual handle where needed; `ActiveServer` owns only configuration/proof association and callback identity. No numeric counter, duplicate generation authority, registry, or mode state machine is added.

The GPU policy is already captured by the canonical resolved snapshot: `ResolvedConfig.Ai.gpuAccelerationAllowed` is the projection of `policy.gpu_acceleration_enabled` (`ResolvedConfig.java:183-186`; `ResolvedConfigBuilder.java:1243`). `StartRequest.effectiveGpuLayers()` derives the effective value exclusively from those two immutable captured inputs. Remove the launch-time system-property reread at `LlamaServerOps.java:1733-1735`. Do not store another mutable policy flag. Declared hash, argv, VRAM planning, diagnostics, and post-health witness checks all use this same derived value, so a property/global change during health cannot change the witness.

## Concrete methods

The new strict path uses:

```java
StartResult startLlamaServer(StartRequest request)
    throws IOException, ModeTransitionException;

void waitForServerHealth(StartResult expected)
    throws ModeTransitionException;

Optional<StartResult> activeStartResult();
```

Internally, health and props helpers capture the physical generation:

```java
ActiveServer requireActive(StartResult expectedStart);
HealthProbe probeHealth(ActiveServer expectedProcess, Duration timeout);
PropsProbe probeServerProps(ActiveServer expectedProcess, Duration timeout);
```

Each probe checks `activeServer == expectedProcess` before I/O and again after the response. A replaced physical owner returns a stale/non-success probe and publishes no observation.

The manager now passes explicit requests for startup, apply, detach and its package test
entry. Its legacy public constructors lazily capture the existing global only if no
resolved snapshot was supplied; normal bootstrap supplies its captured snapshot.
Thus server no-argument wrappers/config suppliers have no production purpose once
all internal callers migrate. Retire them and adapt existing tests to explicit
LEGACY requests instead of preserving dead production helpers. External adoption
behavior remains represented by the explicit policy. This supersedes the initial
compatibility-wrapper proposal after tracing the actual caller graph.

The manager's new entry receives the already-resolved candidate rather than reconstructing it. Because the D1 component caller is in `app-services`, it cannot name the package-local context. Keep that type internal and expose only existing public input types:

```java
public ConfigApplyResult applyResolvedConfig(
    InferenceConfig candidate,
    ResolvedConfig candidateResolved,
    RestartPolicy policy)
    throws ModeTransitionException;
```

`InferenceLifecycleManager` constructs the package-local `LlamaServerConfigContext` immediately on entry and uses `TransitionReason.CONFIG_APPLY`. Existing `applyConfig(InferenceConfig, ...)` overloads remain legacy wrappers until their callers migrate. The concrete external path is the D1 generative component adapter in `app-services` calling this public method with the candidate `InferenceConfig` and the same prepared `ResolvedConfig` snapshot used by the settings transaction; no public duplicate context type is introduced. Before stopping an ONLINE incumbent the manager captures the full owner, not merely its config:

```java
StartResult incumbent =
    serverOps.activeStartResult().orElseThrow(/* broken ONLINE ownership invariant */);
```

Rollback starts from `incumbent.context()` and uses the **transaction's** adoption policy. The new reconfigure entry is strict for both B and restored A; it refuses an external incumbent before destructive work. A legacy apply transaction uses legacy policy for B and rollback. It must not rebuild A from `ConfigStore.global()`, which may describe desired B or a newer snapshot by then. Separately, asynchronous background recovery is not a new transaction and retains the installed owner's policy. This keeps strict proof end-to-end without silently converting generic legacy recovery to strict behavior.

## Start, adoption, and health flow

`startLlamaServer(StartRequest request)` uses only `request.context()` for executable, model, port, context-window planning, slots/KV/thinking, declared hash, argv, log path, runtime-bin path, registration URI/model metadata, and diagnostics. The current mixed reads are visible at:

- manager supplier for launch config at `LlamaServerOps.java:330-335`;
- global resolved config for hash/argv at `:336-354`;
- supplier reads for DLL diagnostics at `:674-695`, external port diagnostics at `:949-979` and `:1084-1088`, executable/working directory at `:1104-1129`, child metadata at `:1255-1272`, and HTTP ports at `:1373-1403`;
- global reads for logs/runtime bin at `:1819-1839`.

Those helpers take `LlamaServerConfigContext` or the `StartResult` token explicitly. Static pure command construction remains unchanged.

The adoption order is:

1. Compute the expected declared hash from the request context and `request.effectiveGpuLayers()`. `ManagedLlamaConfigIdentity` includes both inference values and resolved AI inputs (`ManagedLlamaConfigIdentity.java:18-32`). Never recompute effective layers from the system property after health.
2. Attempt registered managed adoption using that hash. On success, install one `StartResult(... ADOPTED_MANAGED, expectedHash)` before scheduling health/exit callbacks and return it. Existing adoption already requires hash equality, health, llama props, and a second process identity check (`LlamaServerOps.java:987-1037`).
3. Probe an unregistered/external server at the request port. For `LEGACY_ALLOW_EXTERNAL`, retain today's policy checks and install `ADOPTED_EXTERNAL` with no hash. For `REQUIRE_MANAGED_CONFIG_WITNESS`, fail with the existing external-conflict/policy-shaped exception; do not adopt it and do not report candidate B applied.
4. Otherwise launch managed B. Create `LAUNCHED_MANAGED` with the expected hash immediately after process creation/registration succeeds, install a fresh `ActiveServer(result)`, and then schedule callbacks. Registration already records the declared and realized argv hashes (`:1255-1280`). If registration rolls back but the process survives, do not install an active server result; the existing retained unregistered-process handle remains the cleanup authority (`:1300-1344`).
5. `waitForServerHealth(expected)` resolves the current `ActiveServer` for that logical start, then probes its captured port. Reasoning-budget and context-rung relaunches keep the same `StartResult` context/hash but install a fresh immutable physical owner and continue health against it. A response associated with the replaced object is ignored. Current retries already reuse `currentDeclaredConfigHash` (`:739-776`, `:792-846`), but the hash and executable/log paths must instead come from `expected`.
6. A successful strict start result is itself the managed witness: disposition is managed and its nonblank hash equals the hash recomputed from its captured context/effective GPU policy. The manager assigns durable B only after this health completion.

## Long-lived callback ownership

Retaining the immutable snapshot is necessary at these boundaries:

- **Props.** Remove the configuration supplier from `ServerPropsOps`. Its update becomes `updateFromPropsBestEffort(JsonNode root, StartResult expectedOwner)`, and all expected-build, thinking, and model comparisons use `expectedOwner.context()`. `LlamaServerOps` captures an `ActiveServer` before the HTTP request, checks it before and after I/O, and invokes the update under the same small ownership lock used to replace `activeServer`; it checks the physical object again immediately before and after the synchronous update. Thus stale A cannot publish as B, and a stale pre-retry response cannot publish into the same logical token's new process. This removes the global thinking read at `ServerPropsOps.java:176-185` and supplier reads at `:154-160` and `:331-338`. Requested context tokens remain the existing realized-rung supplier because rung step-down is runtime state, not configuration.
- **Periodic health.** Change scheduling to `schedulePeriodicHealthCheck(ActiveServer owner)` and schedule `() -> runPeriodicHealthCheck(owner)`. The callback exits unless `activeServer == owner`, then probes that physical owner's port with the before/after guard. The current task is unbound and reads the mutable supplier (`LlamaServerOps.java:1456-1495`).
- **Exit monitor.** Capture the exact `Process`/`ProcessHandle` and `ActiveServer`. Unregister that child as today, but invoke recovery only when the handle is still owned and `activeServer == owner`. The current launched-process callback captures the process but calls context-free recovery (`:1282-1297`); adopted managed monitoring similarly needs the physical token (`:1042-1057`).
- **Recovery.** `handleServerCrash(ActiveServer owner)` and `scheduleRecoveryTask(ActiveServer owner, long delay)` exit if ownership changed. Recovery captures `owner.start().context()` and `owner.start().adoptionPolicy()` before confirmed stop. Thus legacy managed ownership preserves legacy external adoption, while a server installed by strict reconfigure remains strict after a crash. Current recovery calls context-free no-argument start/health (`:1564-1616`), which can read a newer manager/global combination.
- **Diagnostics/retries.** Health, props, launch-log reads, failure diagnosis, DLL path, runtime bin, and external diagnostics use the owner passed to the synchronous operation or retained for the active process. They never recapture current global state.

Explicit parameters are preferable until ownership is claimed; the retained snapshot is preferable afterward. Adoption probes occur before an active owner exists, so they take `StartRequest.context()` explicitly. Once adoption or registration succeeds, configuration follows `StartResult` and callback validity follows the current `ActiveServer` physical token.

## Stop, rollback, and close

`stopLlamaServerAndConfirm` first captures and cancels callbacks associated with the current physical token, then terminates the exact managed handle. Clear `activeServer` only after managed termination is confirmed, or immediately when detaching an external server because there is no owned process. If termination fails, retain both the OS handle and physical/context token: `stopLlamaServer()` already throws and deliberately retains ownership when the process survives (`LlamaServerOps.java:545-614`). Because that path has already cancelled health/crash monitoring and cleared realized context, a surviving handle does **not** prove A is still a healthy incumbent. Strict apply must refuse to start B, retain A as the configuration and cleanup authority, and report `LEFT_OFFLINE` unless it explicitly re-establishes A health and monitoring. The first implementation need not add such recovery.

Candidate failure handling is:

1. retain the pre-stop incumbent A context;
2. stop/confirm B using B's owner token;
3. start A with `new StartRequest(incumbent.context(), transactionAdoptionPolicy)` and health-check its returned token; strict reconfigure uses strict for both candidate and rollback, while legacy apply uses legacy for both;
4. publish rolled-back ONLINE only after A's witness and health succeed;
5. otherwise use the already-added explicit OFFLINE failure restoration.

Do not repeat a VRAM admission gate for A. The captured context describes the actual serving incumbent; start/health is the recovery test.

`shutdown()` still cancels health/recovery/exit tasks in its current executor order (`LlamaServerOps.java:1694-1711`). Manager close remains responsible for the configured stop policy before shutdown. If stop-on-close is false, the registered managed child survives for next-boot adoption; discarding the in-memory context with the closing owner is correct because the next process reconstructs and verifies the declared hash from its own captured startup context. The nine registrations and reverse close order remain unchanged (`InferenceExecutorRegistrations.java:13-22`, `:66-84`).

## Bounded implementation split

1. **`LlamaServerConfigContext.java` (new):** package-local immutable pair with null validation only.
2. **`LlamaServerOps.java`:** nested policy/disposition/request/result plus physical `ActiveServer`, explicit start/health APIs, context-bound helpers/callbacks/recovery, strict adoption branch, canonical resolved GPU policy, compatibility no-argument wrappers, and removal of launch-path global/supplier reads after context capture.
3. **`ServerPropsOps.java`:** consume active owner context for build/thinking/model diagnostics; no global lookup.
4. **`InferenceLifecycleManager.java`:** new context-bearing apply entry, capture actual incumbent before stop, strict candidate and rollback requests, publish manager config only after candidate health/witness, and typed apply result. Do not alter modes/executors.
5. **Tests:** extend `ManagedLlamaAdoptionTest`, `ServerPropsOpsTest`, crash/recovery tests, and `InferenceLifecycleManagerApplyConfigTest`; add a focused candidate-context test only if those owners cannot express the concurrency assertions.

This is one coherent change after the already-separated preflight slice. Moving only the manager assignment or only the initial launch reads would leave retries/background work attached to a different configuration.

## Acceptance and refutation checks

1. **Candidate isolation:** with manager/global A and explicit request B, start command, port probes, hash, child registration, build pin, log/runtime paths, and props diagnostics all observe B. Update global and the raw GPU system property to C during blocked B health; every B observation and effective GPU/hash decision remains from B's captured resolved snapshot.
2. **Publication fence:** manager config remains A inside B start and health callbacks, then becomes B only after managed witness plus health succeeds.
3. **Strict adoption:** a matching registered managed child produces `ADOPTED_MANAGED` and the B hash. An unregistered healthy server is not adopted under strict policy. The same fixture is adopted under the legacy policy, preserving current behavior.
4. **Retry identity:** reasoning fallback and context-rung fallback launch with B executable/port/hash/resolved flags after global changes; registered child metadata still identifies B.
5. **Background identity:** after B is active, change manager/global to C and trigger periodic health plus crash recovery. Probes and recovery use B. A stale A exit/health callback cannot clear, restart, or demote B. Block a props response from B's first process, perform a context-rung retry with the same logical start token, then release the old response; the replaced physical-owner object publishes no props into the retry.
6. **Rollback identity:** A was launched from resolved snapshot A1, global later becomes B. Candidate B fails; rollback command/hash/props use captured A1, not global B. Successful rollback reports A; failed rollback ends OFFLINE.
7. **Failed stop:** retained A process also retains A context for cleanup and B never starts, but the strict transaction reports `LEFT_OFFLINE` because monitoring/realized state was already torn down and health was not re-established.
8. **Close:** callbacks are cancelled, active context clears only after confirmed stop/detach, failed termination retains handle plus context, and stop-on-close false preserves the registered process for hash-checked next-boot adoption.
9. Existing external-server, managed-adoption, launch-flags, props, crash telemetry, log retention, inference identity, and executor-registration/close tests remain green.

Negative controls: make `probeHealth` read `config.get()` and candidate-isolation fails; let recovery call the no-argument start and background-identity fails; rebuild rollback context from global and rollback-identity fails; remove the owner-token equality guard and stale-callback replacement fails; allow external adoption in strict mode and strict-adoption fails.

## Remaining decisions and proof

The parent has settled the only product-policy question: new reconfigure requires a managed hash witness, while legacy entry points retain explicit external adoption. No broader lifecycle decision is required. Implementation must still choose the existing typed exception reason for strict external refusal without adding a new public wire code; `EXTERNAL_SERVER_CONFLICT` is the closest current contract (`InferenceLifecycleManager.java:732-746`).

No check was executed for this read-only design. The missing proof is the focused context/concurrency suite above, affected-module tests/static checks, and integrated D1-4 reconfigure evidence. Root's successful preflight/offline checks are evidence for the preceding slice only.

## Implementation ownership

Root owns manager integration, public result semantics, design/docs, all builds, stack and Git.
The bounded server worker owns LlamaServerConfigContext, LlamaServerOps and ServerPropsOps,
plus necessary existing server tests. New lifecycle/lock ordering ambiguity must return to
the root before expanding the contract. In particular, a volatile before/after check alone
does not make callback publication atomic: prove the owner-check/publication critical section
and avoid lock inversion through manager failure callbacks. No I/O or process wait belongs
in a new long-held publication critical section. Existing small model-id file
persistence may remain serialized with props publication to preserve ordering;
do not add a second writer merely to move that existing callback outside the lock.

### Manager integration decisions

- Replace the scalar manager configuration with one immutable configured snapshot
  containing InferenceConfig, the captured ResolvedConfig and adoption policy.
  Only legacy construction may temporarily lack the resolved snapshot; first
  startup captures it from the existing global compatibility owner. Bootstrap
  supplies its already-captured resolved snapshot directly. No defaults fallback.
- APPLIED means healthy witnessed replacement; CONFIGURED means validated values
  retained without starting/restarting; UNCHANGED means refusal before effects;
  ROLLED_BACK_TO_A and LEFT_OFFLINE distinguish recovery outcomes. A result carries
  retained configuration, optional managed hash, and the transition exception.
  The exception cause/suppressed chain preserves candidate and rollback failures.
  Legacy wrappers still throw; the new resolved entry returns the typed outcome.
- CONFIGURED does not certify that a running server applies those values. Request
  routing reads the active server context. Later startup uses the retained exact
  configured snapshot, so a global update cannot reinterpret an offline candidate.
- Both apply branches validate before configured publication. Restart publishes
  only after health and witness verification. Rollback never repeats VRAM admission
  for the formerly serving incumbent; its actual restart/health is the proof.
  This retires the transitional rollback-VRAM-refusal test in favor of a stronger
  regression that proves restoration is attempted without a third VRAM probe.
- VDU entry/exit retains the whole existing procedure snapshot and policy; it must
  not recapture unrelated global flags. The existing procedure stash is cleared
  only after successful exit, so failed exit can still be retried.
