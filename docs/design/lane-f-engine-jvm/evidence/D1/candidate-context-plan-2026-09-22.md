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
record ActiveServer(StartResult start, Process process, ProcessHandle adoptedHandle) {}
```

`LlamaServerOps` retains `private volatile ActiveServer activeServer`; every adoption, launch, or health-time relaunch installs a fresh immutable object. Code compares `ActiveServer` identity (`activeServer == expectedProcess`), not record equality. This rejects a late health/props/exit response from the pre-retry process even though both processes share one `StartResult`. The snapshot carries the exact launched process or adopted handle so probes and callbacks never borrow a successor's physical handle. The existing process/adopted-handle fields and managed-child id remain the cleanup owner's references; the snapshot associates those same resources with configuration and callback identity. No numeric counter, duplicate generation authority, registry, or mode state machine is added.

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

The original design pass was read-only. Current implementation evidence is below.
The missing proof remains the focused context/concurrency suite above,
affected-module tests/static checks, and integrated D1-4 reconfigure evidence.
Root's successful preflight/offline checks are evidence for the preceding slice only.

## Implementation ownership

Root owns manager integration, public result semantics, design/docs, all builds, stack and Git.
Root also owns LlamaServerConfigContext, LlamaServerOps and ServerPropsOps after the
lifecycle review exposed coupled lock/recovery changes. The bounded test worker owns only
LlamaServerCandidateContextTest.java; the independent reviewer is read-only. New lifecycle/lock ordering ambiguity must return to
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
- Review exposed the serving-A / desired-C distinction after APPLY_ONLY. Successful
  rollback publishes actual A's captured inference and resolved context with the
  transaction policy, after health and witness verification. It must not republish
  desired C or relax a strict transaction on the next startup. Failed rollback
  retains the pretransaction desired snapshot and reports LEFT_OFFLINE; that
  snapshot is not a claim of a serving process.
- Model-ID observation carries the same captured context into existing diagnostic
  persistence. Its path uses that context's data directory, never the global
  configuration, including before candidate settings are committed.
- The explicit owner also requires migrating detach rollback: toggling an external
  flag after stopping the candidate cannot recreate a cleared physical owner.
  Detach therefore retains the actual external context, requires managed proof for
  its new-port candidate, and explicitly re-adopts/health-checks the captured
  external incumbent on failure. Failed cleanup or restoration reports OFFLINE.
  This is a required caller migration, not a separate detach state machine.
- Physical recovery must serialize with manager-driven replacement, not only
  guard props publication. The existing recovery scheduler retains delay/count
  ownership but invokes a narrow manager callback with the captured physical-owner
  predicate. Under the existing transition lock, the manager verifies that owner
  is still current and mode is ONLINE, then invokes the existing server recovery
  body. The same lock already orders apply, startup, detach and close. A queued
  callback after close observes OFFLINE and cannot restart a preserved child.
  No new executor or recovery state is introduced; server callbacks are never
  invoked under the props ownership monitor.

## Implementation verification (in progress)

Base is local docs checkpoint `1d89a465f` plus the source inventories attached to each run.
Compile2437 and compile/pmdMain2446 passed. The eleven compiler advisories predate this
change; none was suppressed. Focused/static2445 passed351 cases but did not close proof:
review refuted the recovery fixture because its second callback followed refusal before
any physical retry. Production review also required post-health monitoring, pre-arm crash
budget reset, dead final-attempt retirement and terminal strict cleanup.

Full affected-module2448 then ran354 cases, one failure, zero skips across32 suites;
Spotless/pmdMain/pmdTest passed. Its failed recovery fixture used a thread-local Mockito
constructor mock while recovery ran on the scheduler. Focused2449 exposed another invalid
fixture input: GPU launch without a GPU capability service. The corrected fixture delivers
the first scheduled owner guard, executes recovery under the test-thread interception and
uses the CPU8192→4096 ladder. It still requires a second scheduler callback, distinct real
OS children/rungs, declared and realized registry hashes, dead-row retirement and crash
count1→2. Focused2450 passes all seven candidate-context tests plus Spotless/pmdTest.

Each run's log, test XML, counts and WIP source inventory is retained under `tmp/<run-label>*`
in the assigned worktree. Labels are2448-candidate-focused,2449-candidate-recovery and
2450-candidate-recovery. ProcessBuilder interception proves production argv/registration
contracts while returning real OS children; it does not prove llama accepts those arguments.

Negative controls2451–2455 each produced exactly one intended behavioral failure;
the runner restored both production files byte-for-byte and root independently re-read
all five saved XML failure messages. Commands use
`./gradlew.bat :modules:app-inference:test --tests <class.method> --console=plain`.
The exact per-run command and source inventory are in each run's counts/sources files.

| Run / intentional mutation | Regression and observed rejection |
| --- | --- |
|2451-recovery-owner-negative: remove physical-owner predicate | `recoveryRejectsReplacedOwnersAndCannotRestartAfterPreservingChildOnClose`: NeverWantedButInvoked |
|2452-recovery-close-negative: remove ONLINE guard | same regression: TooManyActualInvocations (2 instead of1) |
|2453-recovery-lock-negative: remove manager synchronization | `queuedRecoveryWaitsForApplyThenRejectsItsReplacedPhysicalOwner`: expected TimeoutException was absent |
|2454-recovery-budget-negative: reset crashCount in installActive | `recoveryContextRelaunchFinalFailureAdvancesSameCrashEpisode`: expected2, actual1 |
|2455-clean-exit-negative: exclude exit0 | `armedLaunchedChildCleanExitQueuesCapturedRecovery`: expected callback did not arrive |

These prove guard sensitivity, not real llama compatibility. Remaining proof includes direct
helper/context and reasoning-fallback gaps, integrated/stress checks, installed standard
model query and hosted CI. The preceding215af7809 live/hosted proof cannot certify this WIP.


### Acceptance reconciliation before integrated verification

All rows remain requirements. Listed tests are proof subjects, not pass claims for
unexecuted edits.2450 proves the original seven candidate-context subjects;2448
proves the other manager/module cases at its source inventory, except its named
recovery fixture failure. New additions below need a combined restored-source run.

| Acceptance | Existing proof and remaining addition |
| --- | --- |
|1 Candidate isolation|Explicit B GPU/argv/hash under changed global C; new direct launch-path test checks B log, runtime PATH and build pin while global C differs. Installed llama proof remains.|
|2 Publication fence|Manager callback assertions retain A through B start/health, then publish B; mocked server boundary. New add/remove vision test pins active capability during APPLY_ONLY.|
|3 Strict adoption|Real HTTP plus registered OS child proves matching managed witness; unregistered server refused under strict and accepted under explicit legacy policy.|
|4 Retry identity|Real intercepted child launches prove distinct context rungs, PID/registry hashes and crash count; new reasoning rejection test adds same-rung flag removal before lower rung.|
|5 Background identity|Stale health success/failure and props cannot mutate successor; queued recovery waits for apply, rejects old owner and stops after close.2451–2455 mutations prove recovery guards/count/clean-exit sensitivity.|
|6 Rollback identity|Captured actual A restored despite global C/desired APPLY_ONLY configuration; strict policy retained; failed cleanup or rollback OFFLINE with both causes; no repeated VRAM gate for A.|
|7 Failed stop|No candidate start after incumbent stop refusal; failed candidate cleanup blocks rollback. New detach cleanup test covers retained candidate without phantom external restoration.|
|8 Close|Post-close recovery blocked, failed termination ownership retained, dead adopted row retired; new two-manager real-child/shared-registry round trip joins preservation to strict next-owner adoption.|
|9 Existing contracts|Full affected-module/static and integrated/stress runs remain required after all new additions; installed standard query and hosted CI belong to the final candidate-context revision.|

Review found one additional desired-versus-serving bug: hasVisionCapability still
read desired configuration after APPLY_ONLY, although VduProcessor uses it to admit
actual work. Run2457 proved both directions fail with the old desired-config read (false→true
and true→false). Root changed the method to servingInference; combined2459 is running.
Run2456 was only a fixture compile failure (ambiguous Mockito redirect overload),
fixed by typing the matcher explicitly; it is not behavioral evidence. This is the same captured-serving contract,
not a new design exception or reduced scope.


### Combined focused proof2459

`./gradlew.bat :modules:app-inference:test :modules:app-inference:spotlessCheck
:modules:app-inference:pmdMain :modules:app-inference:pmdTest
:modules:app-services:spotlessCheck :modules:app-services:compileJava --continue --console=plain`
passes on Windows in33s:359 tests, zero failures/errors/skips across33 suites.
Base1d89a465f plus the15-file inventory in tmp/2459-candidate-focused-sources.json;
log, complete XML, counts and skips are retained under the same label. All new subjects
in the reconciliation table executed, including both vision directions after correction.

Integrated2460 is running with
`./gradlew.bat spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist --continue --console=plain`.
This and installed/hosted proof remain unclaimed until their actual results are captured.


Independent final source/proof review after2459 found no remaining concrete defect
in this bounded slice. It traced the new path/reasoning fixture to actual launch
helpers, confirmed detach cleanup cannot pass through readoption, and verified the
shared-registry two-manager round trip excludes replacement/external adoption. The
reviewer did not run tests; root owns the2459 results. Full D1-4 integration remains
outside this slice and still required by the stage contract.


### Integrated audit correction2460–2462

Checkpoint78fca2b67 was pushed to existing draft PR727 after focused proof and independent
review. Full2460 completed in9m21s with11559 tests,2 failures,31 skips,1805 suites,
34 test tasks (27 reused). Static checks/installDist passed. Complete evidence is
`tmp/2460-candidate-integrated*`; tested code is78fca2b67.

Both failures expose residue of this migration: UnreferencedCodeTest rejects the
no-argument schedulePeriodicHealthCheck, runPeriodicHealthCheck and handleServerCrash
wrappers; SystemAccessFunnelTest rejects obsolete resolveModelStatePath and
policyGpuAccelerationEnabled allowlist entries. Root retired those exact wrappers and
entries. No audit exemption or baseline weakening was added. A narrow src/test helper
now captures the current private owner and invokes the same owner-taking callback that
production uses, failing if the fixture lacks an owner. Telemetry installs the existing
logical-owner shape rather than relying on a non-production null-owner branch; that
branch is retired. Recovery callbacks are no-ops in the telemetry fixture, so its obsolete
Windows race exclusions were removed and owned schedulers close after each test.

This matters behaviorally: direct test access must not preserve dead production wrappers
or manufacture a path that real callbacks cannot take. Focused module proof did not
substitute for the integrated dead-code/configuration audits. Independent review accepted
the correction mechanism; actual focused2462 result and a corrected integrated pass remain
pending. Hosted CI35678039813 belongs to the pre-correction78fca2b67 checkpoint.


### Handoff proof2464

After fixing a missed method reference in the test-only migration,2464 passes
`./gradlew.bat :modules:app-inference:test :modules:app-inference:spotlessCheck
:modules:app-inference:pmdMain :modules:app-inference:pmdTest :modules:app-launcher:test
:modules:dead-code-audit:test --continue --console=plain` in33s.453 cases, zero
failures/errors/skips,58 suites:359 inference cases rerun;94 audit cases reuse their
successful2462 results against unchanged correction sources. Full evidence and five-file
WIP source inventory are tmp/2464-candidate-residue-focused*.2462's overall failure was
compile-only in inference tests; it did not invalidate the two completed audit tasks.

The user requested handoff after usage limits interrupted work. Corrected full integrated,
installed standard-model and hosted proof remain required; no completion is claimed.
CI35678039813 finished with the same two failed audit classes, while other jobs passed;
its log is tmp/2465-hosted-failed.txt. Resume at branch HEAD after the audit-correction
handoff commit. See ../../handoff.md for exact continuation and authorization.

### Successor verification and hosted ordering correction (2026-09-23)

Runtime code at `22800c842` remained content-equivalent through documentation HEAD
`2e7f1e1f6`. Local generation2466 ran the full `spotlessCheck pmdAll test
-PincludeStress=true :modules:ui:installDist --continue --console=plain` command at that
HEAD: native exit0,11559 cases,zero failures/errors,31 skips,1805 suites and34 test
tasks (27 reused). `tmp/2466-candidate-corrected-integrated*` holds the complete log,
copied XML/counts/skips, the actual HEAD inventory (no Java changes in that docs commit),
and the five-file `22800c842` source inventory under the `-code` label. This closes the
local audit-residue regression, but hosted CI35793788189 exposed a separate activation
observation ordering race in `RuntimeActivationServiceTest` line226.

The hosted job ran3043 app-services cases with one failure: terminal activation status
became `failed` before its component observation became `UNAVAILABLE`. The owner now
publishes the component observation before terminal status, including when publication
throws; no test wait or audit exception was added. Focused2468 passes28 activation cases,
Spotless and PMD. A latch-based regression in the corrected test blocks component
publication and requires status to stay `running`; negative control2469 against the
exact pre-fix production bytes fails `expected running, was failed`, then those bytes
were restored to the correction. Logs, copied XML, counts and source inventory are
retained under the respective `tmp/2468-*`/`tmp/2469-*` labels.

Corrected generation2470 at commit `79c63e9b0` passes the same full command in9m17s:
native exit0,11560 cases,zero failures/errors,31 skips,1805 suites,34 test tasks
(28 reused). Complete log, XML, counts, skips and two-file source inventory are
`tmp/2470-candidate-order-integrated*`. The commit was pushed to PR727; corrected
hosted CI35796030905 is pending as this record is written. The previous hosted red
run cannot close that proof.

Installed run `191bb413-b3bd-4205-911c-a88efde74d4a` launched the fresh worktree
distribution with retained data and `standard` profile: API59268, all four health
components READY, runtime-client0.4.0 smoke passed (`tmp/2472-runtime-smoke.txt`).
The jseval tier2 single real-model query (`tmp/2473-model-query/tier2-eval.json`)
served `Qwen_Qwen3.5-9B-Q4_K_M.gguf`, answered Captain Mortimer Flux exactly,
and reported zero query or anchor errors. This is functional plumbing proof, not a
quality benchmark. Official stop reported `portsClosed:true`; quick_health then
reported ABSENT, no foreign runs and no inference orphan. The dev MCP preflight's
old Worker-dist check fails because Lane F retired that distribution; the active
dev-runner launched the fresh `ui` Engine distribution and reported buildArtifact
FRESH at `79c63e9b0`. An initial owned start against a newly resolved deeper
data path was stopped cleanly before the retained-data run.

### Integrated, installed and hosted successor evidence (2026-09-23)

The ordering correction's full local generation2475 at `358af2cb4` passed
`spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist --continue
--console=plain`: exit0,11561 cases,zero failures/errors,31 skips,1805 suites.
`tmp/2475-candidate-publication-integrated*` retains the full log, XML, counts,
skips and source inventory. Owned installed run `25140142-8163-48fd-90ea-f7096472668f`
used that exact distribution with retained data and the `standard` Qwen9B model:
all four health components were READY, runtime-client0.4.0 smoke passed
(`tmp/2477-runtime-smoke.txt`), and a real-model jseval query answered Captain
Mortimer Flux exactly with zero query/anchor errors
(`tmp/2478-model-query/tier2-eval.json`). Official stop reported
`portsClosed:true`; quick_health reported ABSENT with no foreign/orphan process.

Hosted CI35797331099 at `358af2cb4` passed every lane except Windows-native:
`WindowsParserContainmentTest`'s first cold child bootstrap took longer than its
test-only PID deadline, while the retry passed. The assertion and response clocks
were extended without weakening the containment condition; focused local2483
passed. Commit `22b76b850` was pushed. CI35799156920 passed Windows-native,
app-ui and system integration, but search-worker failed once in
`IndexGenerationVduEligibilityTest`: a strict state reader contended on the old
file and reopened during the writer's `state.json -> state.json.prev`, then
temporary missing-path gap. The retry passed. `tmp/2479-hosted-windows-native-log.txt`
and `tmp/2490-hosted-search-worker-log.txt` retain the exact hosted failures.

The narrow `ContendedFileReads` correction retries `NoSuchFileException` only
after it observed lock contention, within the existing bounded wait. An initially
missing path still fails immediately. Focused local configuration and worker
tests (2491/2492) and configuration Spotless/PMD (2499) pass. A Linux-only
regression holds an exclusive lock, rotates the path, leaves the replacement
gap open, and requires the reader to reopen the new named file. Commit
`e3b7b6d3c` is pushed. Hosted CI35800678462 completed successfully at the
exact commit: all 13 jobs passed, including search-worker, Windows-native,
app-ui, system integration, and public claims. Downloaded search-worker XML
`tmp/2506-hosted-search-worker-results` confirms all 12
`IndexGenerationVduEligibilityTest` cases passed, including the formerly
failing strict reopen case (zero failures/errors/skips); its configuration XML
confirms all seven `ContendedFileReadsTest` cases passed, including the Linux
replacement-gap regression (zero failures/errors/skips). The corrected
candidate-context slice has integrated, installed standard-model and hosted
proof. These results do not establish D1-4 composition or later stages.
