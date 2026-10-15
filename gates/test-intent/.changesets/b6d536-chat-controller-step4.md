---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5 (Spec A step 4, §4.2, §7 and C9). A classifier
(`LlamaServerExitClassifier`) reads a llama-server exit from its exit code and launch log; a pure
policy (`ChatCrashPolicy`) decides from that class. Same-configuration retry loops are gone: a
start that fails is never retried as it was, and a server that crashes after READY earns one
restart only after ten monotonic minutes of READY. RESOURCE steps the derived window down one
sticky rung, and an override fails with resource_exhausted. The periodic health probe runs at a
fixed delay. The component monitor no longer admits automatic generative attempts (the Head's
generative action is operator-only), so the harness barrier that held its first automatic attempt,
the installed exhaustion fixture and their store-register row are removed. The supervision contract's brain row declares this policy,
with a gate in SupervisionContractTest. This changeset covers only this slice's files.

Evidence regenerated on b599d4d7e. The fail-before of b6d536-chat-controller-step4-repaired.json
runs on 7edc726dd, the parent of d3fe74ef2, which added these checks. It applies
`gates/test-intent/evidence/b6d536-chat-controller-step4-repaired-compat.patch`. The patch makes two
changes. It sets the HealthyFakeLlamaServer test fixture to its d3fe74ef2 version, which does not
use the later ProcessTerminator. It also gives the later
`stopLlamaServer(LlamaServerTestAccess.TEST_STOP)` call a form that works before: TEST_STOP is an
opaque token, and a new `LlamaServerOps.stopLlamaServer(Object)` overload calls the existing
`stopLlamaServer()`. That is what the check called at d3fe74ef2. No production code at 7edc726dd
calls the overload. All nine checks fail there by assertion.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartFailureTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "U9, U10 (STARTING) and I5 against a real launch: LlamaServerOps launches HealthyFakeLlamaServer as a separate process on the GPU-derived ladder (32768, 16384, ...), and the fake records each argv in launches.txt. theSteppedDownRungIsWhereTheNextStartOfThatConfigurationBegins (T6, sticky): with --max-ctx 16384 the first start steps down once; after a stop, the second start of the same configuration launches at 16384 directly (launches 32768, 16384, 16384; reason stepped-from:32768). anOverrideThatDoesNotFitFailsWithoutAStepDown (T7, P4): with justsearch.context.size 32768 set as an override, the RESOURCE exit fails after exactly one launch, PROCESS_EXITED, failed code RESOURCE_EXHAUSTED. anUnknownArchitectureFailsAfterExactlyOneLaunch (I5, T9): the real b8571 line 'error loading model architecture: unknown model architecture: 'qwen35'' fails after exactly one launch with MODEL_LOAD_FAILED, and the message names the architecture. anUnclassifiedExitWhileStartingFailsAtOnce (T10): an unclassified exit 1 fails after one launch with START_FAILED. On 7edc726dd the second start began at 32768 again, the architecture and unclassified exits walked the ladder, and no failure carried a code (failedCode is looked up reflectively). Fail-before: 7edc726dd with b6d536-chat-controller-step4-repaired-compat.patch, by assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 7 rows T6, T7, T9 and T10 as the builder implements them in step 4: only a RESOURCE exit steps the derived window down, by one rung, and the reached rung is where the next start of that configuration begins in this Engine; an override is never reduced and fails with resource_exhausted; a CONFIG exit (unknown architecture, missing DLL, unloadable model) and an unclassified exit fail the start at once with their code, with no ladder walk and no same-configuration retry.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step4-repaired.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartFailureTest.java#theSteppedDownRungIsWhereTheNextStartOfThatConfigurationBegins",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartFailureTest.java#anOverrideThatDoesNotFitFailsWithoutAStepDown",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartFailureTest.java#anUnknownArchitectureFailsAfterExactlyOneLaunch",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartFailureTest.java#anUnclassifiedExitWhileStartingFailsAtOnce"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartFailureTest.java"
      ],
      "class": "New",
      "behaviour": "I4 (preservation, reconciliation F15), aWindowThatDoesNotFitStepsDownOneRungAndServes: the fake refuses any -c above 16384 with the real llama-server allocation failure ('alloc_buffer: allocating 16604.75 MiB on device 0: cudaMalloc failed: out of memory') and exit 1. The start launches 32768, then exactly one step-down to 16384; the window is rung 16384 with reason stepped-from:32768, and the server there is alive. It passed on 7edc726dd as well in the original record. The evidence regenerated on b599d4d7e has no fail-before run: the current HealthyFakeLlamaServer uses ProcessTerminator, which 7edc726dd lacks, so the check no longer compiles there. It pins that a real fit failure still costs one rung, not inference, now that only RESOURCE steps down.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 7 row T6 with reconciliation F15 as the builder implements it in step 4: an allocation failure at the top rung of a derived window is classified RESOURCE and relaunched one rung lower, once, within the same start.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step4-preserved.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerStartFailureTest.java#aWindowThatDoesNotFitStepsDownOneRungAndServes"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashRestartTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "U10 (READY) and T27 on the real seam: an InferenceLifecycleManager on the CPU ladder (8192, 4096) runs HealthyFakeLlamaServer as a separate process, with no component monitor present; the test advances the launcher's monotonic clock (its monotonicNanos field, set reflectively) and ends the process the way a crash ends it (crashLatest kills only a descendant of this JVM). aCrashAfterTenMinutesOfReadyRelaunchesOnceWithTheSameConfiguration (T28 then T29): after 11 minutes of READY a crash relaunches once at the same -c and the manager is online again; after 11 more minutes a second crash gives no relaunch (2 launches), the manager is offline and the latched chat failed code is CRASH_LOOP. aCrashWithinTenMinutesOfReadyFailsAsACrashLoopWithoutRelaunch: at 9 min 59 s there is no relaunch and the code is CRASH_LOOP. anOutOfMemoryWhileServingStepsDownOneRungAndServesThere (T27): the fake prints 'CUDA error: out of memory' and the ggml-cuda.cu line and exits 1 while serving; the manager relaunches at 4096 (stepped-from:8192) and is online. On 7edc726dd the manager relaunched nothing on its own (it left that to the component monitor) and had no failed code. Fail-before: 7edc726dd with b6d536-chat-controller-step4-repaired-compat.patch, by assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 7 rows T27 to T30 and T34 as the builder implements them in step 4: the lifecycle manager decides the follow-up of a managed crash or hang itself and submits it as a RECOVER step to the chat controller; a RESOURCE exit steps down one rung whatever the READY time; any other exit earns one same-configuration restart only after ten minutes of READY on the monotonic clock, at most once per configuration until activation, a configuration change or an operator retry clears the latch; otherwise chat fails with crash_loop.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step4-repaired.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashRestartTest.java#aCrashAfterTenMinutesOfReadyRelaunchesOnceWithTheSameConfiguration",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashRestartTest.java#aCrashWithinTenMinutesOfReadyFailsAsACrashLoopWithoutRelaunch",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashRestartTest.java#anOutOfMemoryWhileServingStepsDownOneRungAndServesThere"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/GenerativeRecoveryAdmissionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Spec A section 4.2 and O6, theMonitorStartsNoGenerativeAttemptAndEachOperatorRetryIsOne: the generative action is the one HeadAssembly builds (generativeRecoveryAction over a mocked InferenceLifecycleManager), bound to a real KnowledgeServerHealthMonitor with a FAILED generative component (component budget 2). Four ticks a minute apart start no attempt and the reason is not component_recovery_exhausted; then three operator requests are each ACCEPTED and each make exactly one attempt (3 in total, past the old budget of two), with no escalation. On 7edc726dd the ticks started two automatic attempts. Fail-before: 7edc726dd with b6d536-chat-controller-step4-repaired-compat.patch, by assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 4.2 and ruling O6 as the builder implements them in step 4: the Head wraps the generative recovery action as operator-only, so the component monitor's ticks never admit a generative attempt and keep no budget for it, while each operator retry through the recover endpoint is admitted as one attempt regardless of earlier attempts.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step4-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/GenerativeRecoveryAdmissionTest.java#theMonitorStartsNoGenerativeAttemptAndEachOperatorRetryIsOne"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/supervision/SupervisionContractTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Spec A C9 gate, brainRowDeclaresTheChatCrashPolicy (a new method; the class's other checks are unchanged): the brain row of governance/supervision-contract.v1.json declares stabilityWindowMs, sameConfigRestartsPerStableWindow, startingSameConfigRetries and periodicHealthScheduling equal to BrainSupervisionPolicy's constants (read reflectively); no clean-exit, hang, oom or partial-boot text still promises the monitor retry loop ('two same-applied', 'up to two attempts', 'monitor budget', 'component budget', 'common component monitor'); and each of those four modes names the chat controller's policy. On 7edc726dd the policy record declared none of the constants and the row promised the monitor's two-attempt budget. Fail-before: 7edc726dd with b6d536-chat-controller-step4-repaired-compat.patch, by assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A C9 as the builder implements it in step 4: the supervision contract's brain row is rewritten to the section 7 policy (classifier, RESOURCE step-down, one restart after a ten-minute READY window, no same-configuration retry while starting, fixed-delay health, monotonic timers), its numbers are declared in BrainSupervisionPolicy and the contract test fails on drift or on a return of the monitor retry wording.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step4-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/supervision/SupervisionContractTest.java#brainRowDeclaresTheChatCrashPolicy"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerExitClassifierTest.java"
      ],
      "class": "New",
      "behaviour": "U8 against the new LlamaServerExitClassifier (no before-state). everyFixtureLandsInItsSpecClassWithItsEvidenceLine: a table of exit code plus launch log maps each row to its class, failed code and evidence line. RESOURCE: the real tempdoc-883 CUDA OOM log under exit 0xC0000409 and under 127, cudaMalloc, gallocr 'failed to allocate', a CPU alloc_buffer failure, 'failed to create context', a weights OOM that also says 'error loading model', and Windows exit 0xC0000017 without text. DEVICE_SUSPECT: unspecified launch failure, launch timed out, device lost, no CUDA-capable device, cudaErrorDevicesUnavailable. CONFIG: the real 'unknown model architecture: 'qwen35'' line (model_load_failed), the DLL-not-found exit (missing_dll), a corrupt tensor, 'failed to load model', 'Address already in use' (start_failed). PORT_LOST: the real bind-failure lines. OTHER: a bare exit 1, 0xC0000409 with no text, a reasoning-flag rejection, and -1 with no log, each with no evidence line. theTableCoversEveryClass. aWindowsLogWithCarriageReturnsGivesTheSameEvidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 7.1 with ruling F17 as the builder implements it in step 4: one classifier reads the exit code and the launch log in a fixed order (lost bind, missing DLL, unknown architecture, device loss, allocation failure, Windows no-memory status, unloadable model, port in use, otherwise OTHER); a CUDA out-of-memory is RESOURCE, never device loss; the evidence is the first matching log line.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step4-unit.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerExitClassifierTest.java#everyFixtureLandsInItsSpecClassWithItsEvidenceLine",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerExitClassifierTest.java#theTableCoversEveryClass",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerExitClassifierTest.java#aWindowsLogWithCarriageReturnsGivesTheSameEvidence"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashPolicyTest.java"
      ],
      "class": "New",
      "behaviour": "U9 and U10 as a decision table against the new ChatCrashPolicy (no before-state). While starting: RESOURCE steps down with a rung left (T6) and fails resource_exhausted without one or under an override (T7); OTHER fails start_failed even with rungs left (T10); CONFIG fails with its own code (model_load_failed, missing_dll) (T9); DEVICE_SUSPECT fails start_failed; a lost bind restarts once at the same rung and then fails (CH3). While READY: OTHER under ten minutes (including 9:59.999 and zero) is crash_loop (T29); at ten minutes one same-configuration restart (T28); once spent, crash_loop even after five hours; RESOURCE steps down whatever the READY time and even after the restart is spent, and fails resource_exhausted without a lower rung (T27); DEVICE_SUSPECT is crash_loop. The stability window is ten minutes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 7 rows T6 to T10 and T27 to T29 with ruling CH3 as the builder implements them in step 4: one pure policy maps a classified exit, the READY time, the restart latch and whether a lower rung exists to step down, restart once at the same configuration, or fail with a code; a suspect device is never restarted in place.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step4-unit.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashPolicyTest.java#startingResourceStepsDownWhileARungIsLeftAndFailsLoudOtherwise",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashPolicyTest.java#startingNonResourceExitsFailAtOnceWithTheirCode",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashPolicyTest.java#aLostBindIsRelaunchedOnceAndNeverStepsDown",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashPolicyTest.java#aReadyCrashRestartsOnceOnlyAfterTheStabilityWindow",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashPolicyTest.java#aReadyResourceExitStepsDownAndAnOverrideFails",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatCrashPolicyTest.java#theStabilityWindowIsTheDeclaredTenMinutes"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The shared llama-server stand-in gains opt-in failure behaviour and observation; install(dir) and every existing caller behave as before. install(dir, behaviour...) writes behaviour.txt: --max-ctx N exits 1 with the real alloc_buffer/cudaMalloc out-of-memory line when -c exceeds N; --log-line L with --exit-code C prints the lines and exits; --oom-on-signal makes a serving fake print a CUDA out-of-memory and exit 1 when signalOutOfMemory(dir) is called. Every launch appends its argv to launches.txt (launches(dir)); crashLatest(dir) kills the newest recorded pid only if it is a descendant of this JVM. endAll(dir) still ends and awaits every recorded server, and then also waits (at most five seconds, failing with the file named) until no file in the test directory that holds dir is still held: on Windows the launcher shell and the fake's handles on the redirected llama-server.log outlived their reported exit by 9 to 19 ms, so the @TempDir could not be deleted (pinned by HealthyFakeLlamaServerTest). No assertion in an existing test changes."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The fixture's teardown contract, afterEndAllNoLaunchedServerRunsAndItsLogCanBeDeleted: three times, the fake is launched as LlamaServerOps launches it (stdout and stderr appended to logs/llama-server.log in the test directory) and stopped as stopLlamaServer stops it (end the launched process and wait for it); after HealthyFakeLlamaServer.endAll the recorded server is not alive and the log can be moved at once (the access @TempDir deletion needs). On 7edc726dd's fixture the second round's log was still held after endAll (the same 'being used by another process' that failed @TempDir cleanup in LlamaServerStartFailureTest and ChatCrashRestartTest).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "As the builder implements step 4's process-launching tests: every fake llama-server a test launches is stopped and awaited by the fixture, including the release of what it held, before the test directory is deleted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step4-fixture.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServerTest.java#afterEndAllNoLaunchedServerRunsAndItsLogCanBeDeleted"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/ComponentRecoveryMonitorTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "generativeHarnessBarrierHoldsFailedAttemptZeroAndTheSharedSlot, interruptedGenerativeHarnessBarrierLeavesFailedAttemptZeroAndClearsSlot and ordinaryAutomaticGenerativeRecoveryDoesNotCreateHarnessBarrier. All three pinned only the installed-harness barrier the monitor held before its first automatic generative attempt (JUSTSEARCH_GENERATIVE_RECOVERY_BARRIER, runtime/generative-recovery-1-*). The monitor no longer makes automatic generative attempts (the Head's generative action is operator-only, pinned by GenerativeRecoveryAdmissionTest), so the barrier could never be reached and is removed with its environment plumbing and KnowledgeServerBootstrap.dataDirForHarness. The generic budget, escalation and barrier-free checks in this class are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 4.2 as the builder implements it in step 4: with no automatic generative attempt left in the component monitor, the harness barrier that held that attempt is unreachable and is removed rather than kept as dead harness code; the installed scenario observes the absence of an automatic attempt instead.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerHealthMonitor.java#awaitGenerativeRecoveryBarrier",
            "modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java#dataDirForHarness"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for generative recovery, recovery budget, same-configuration retry, restart and harness barrier; only ADR-0047 speaks to llama-server relaunch, the one-rung context step-down on a launch abort, which the new policy keeps as the RESOURCE step-down)"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/ComponentRecoveryMonitorTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "KnowledgeServerHealthMonitor lost its environment parameter (it fed only the removed barrier): the package-private constructors are now (..., recoveryPolicy) and (..., recoveryPolicy, ownerClockNanos). The helper monitor(components, bootstrap, ownerClockNanos) drops SystemAccess::rawEnvVar; monitor(components, bootstrap, policy, now) now calls the public five-argument constructor directly, and the five-argument helper that took an environment function (used only by the removed barrier tests) is gone. Every remaining test builds the same monitor with the same policy, clocks and bindings as before; no assertion changes."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexReadinessPublicationTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Only the recoveryMonitor helper changes: it looked up the package-private KnowledgeServerHealthMonitor constructor reflectively with the environment Function parameter this step removed (so both tests using it failed with NoSuchMethodException, not an assertion). It now looks up (EngineExecutorRegistry, KnowledgeServerBootstrap, long, LongSupplier, BootRecoveryPolicy, LongSupplier) and passes the same executors, server, 10_000 ms poll interval, wall clock, BootRecoveryPolicy.defaults() and deterministic owner clock as before; the always-null environment function it passed (read only by the removed barrier) and the unused Function import are dropped. Inputs, preconditions, ticks, observations, fault injection and every assertion are unchanged."
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineSupervisedRecoveryE2ETest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "realGenerativeChildSpendsTwoAttemptsWithoutEscalatingOptionalFailure (pinned the monitor's two-attempt generative budget ending in 503 WORKER_RECOVERY_EXHAUSTED; operator retries are no longer budgeted, O6) and, in realGenerativeChildRecoversOnTheSecondSameConfigurationAttempt, the held and busy assertions (FAILED with 0 attempts held at the barrier, and 429 ADMISSION_ENGINE_LIMIT for a manual recover during the held automatic attempt), which pinned the removed barrier and the automatic attempt it held. The method's remaining assertions are unchanged: attempt 1 fails executable_not_found, the operator retry is accepted, attempt 2 is READY with a verified replacement child on the same runtime and model that answers a real chat, and the supervisor restart count stays 0. The component-recovery scenario now reaches attempt 1 by an operator retry and, before it, requires a 25 s window (over two monitor polls) in which the row stays FAILED with 0 attempts and no new llama-server child appears; the scenario fails before printing its pass marker otherwise. Not run here: these are installed @Tag(ai) tests that start the app.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 2-4) end to end within the agreed scenarios.",
          "choice": "Spec A section 4.2 and ruling O6 as the builder implements them in step 4: the component monitor admits no automatic generative attempt and keeps no generative budget, each operator retry is one attempt, so the installed exhaustion fixture and the barrier that held the automatic attempt are retired and the success fixture proves the absence of an automatic attempt instead.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerHealthMonitor.java#awaitGenerativeRecoveryBarrier"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for generative recovery, recovery budget, same-configuration retry, restart and harness barrier; only ADR-0047 speaks to llama-server relaunch, the one-rung context step-down on a launch abort, which the new policy keeps as the RESOURCE step-down)"
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "accept",
  "digest": "sha256:b47b75448ef8eee5f21e76eefcdc30313683c4b877218754c832c96a0032bc6a",
  "note": "Re-reviewed crash policy, start failures and restored before-compatible fixture; candidate and declared before outcomes match with creation-time-only fixture safety instrumentation, without changing production repair logic or test oracles."
}
```
