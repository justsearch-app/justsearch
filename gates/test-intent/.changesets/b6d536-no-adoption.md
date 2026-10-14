---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 4 of specs-reconciliation-v2 (rulings CH2-CH5): the Engine no
longer adopts a llama-server across Engine restarts. A verified-owned predecessor is terminated at
boot, every managed launch binds a fresh loopback port under a port-free declared hash, a lost bind
is retried once at the same context rung, and the user's own server is used only through
`justsearch.inference.external_endpoint`, reported and never stopped. This changeset covers only this
task's files; the other flagged files on the branch belong to other tasks.

Agent choice under the owner's delegation (quoted in the entries below), on the orchestrator's
ruling: the e456 E5 instrument (`scripts/jseval/lane-f/e456-live.mjs`) counts chat as ready again
only when the successor's own llama-server answers `/health` within
`CHAT_READY_AFTER_ENGINE_READY_MS` = 60 s of the new Engine reporting ready (measured cached
activation 9-16 s plus cold-disk margin). The crash path now takes that observation right after the
successor is identified, before the in-flight jobs finish, so the bound is still open; a first
observation after the bound is unmeasurable, not a pass.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Five new checks through LlamaServerOps with a fake llama-server script. (1) healthyServerOnConfiguredPortIsNotAdoptedWithoutExternalEndpoint: a healthy llama-shaped server on the configured port is not adopted when the endpoint setting is unset (disposition LAUNCHED_MANAGED, the launch's --port differs from the configured port) and still answers /health after stopLlamaServer and stopServerForTerminalShutdown. (2) explicitExternalEndpointIsUsedAndNeverStopped: with justsearch.inference.external_endpoint naming a healthy server on another port, that server is used (ADOPTED_EXTERNAL, health wait succeeds), nothing is launched or registered, and it still answers /health after both stops. (3) twoLaunchesUseDifferentFreshPortsAndTheSameDeclaredHash: two launches whose configurations differ only in the configured port bind different ports, neither the configured one, and register the same declaredConfigHash. (4) lostBindRetriesOnceOnAFreshPortAtTheSameContextRung: a launch whose output reports llama-server's bind failure is relaunched exactly once on a fresh port with the same -c, and the second loss fails the health wait as PROCESS_EXITED with both registrations released. (5) lostBindOnRecoveryRetriesOnceOnAFreshPortAtTheSameContextRung: the same through waitForServerHealthOnce, the path a recovery relaunch takes: a lost bind is relaunched once on a fresh port with the same -c.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the no-adoption change end to end within ADR-0051 decision 2 and scenario S14.",
          "choice": "Reconciliation rulings CH2-CH4 as the builder implements them: the declared configuration hash leaves out the port (two launches differing only in the configured port share it); a launch whose llama-server output reports the bind failure is retried once on a fresh port at the same context rung, never stepped down, on every managed launch including a recovery relaunch (orchestrator ruling: CH3 covers every managed launch); the explicit external server is set by justsearch.inference.external_endpoint as a loopback http URL with a port.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-no-adoption-launch.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java#healthyServerOnConfiguredPortIsNotAdoptedWithoutExternalEndpoint",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java#explicitExternalEndpointIsUsedAndNeverStopped",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java#twoLaunchesUseDifferentFreshPortsAndTheSameDeclaredHash",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java#lostBindRetriesOnceOnAFreshPortAtTheSameContextRung",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java#lostBindOnRecoveryRetriesOnceOnAFreshPortAtTheSameContextRung"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "terminalCloseRetiresDeadAdoptedChildEvenWhenExitCallbackWasCancelled, deadBetweenReconciliationAndAdoptionIsRemoved, adoptedManagedDeathRetiresOnlyItsRecord, failedMatchedTerminationBlocksActivationAndRetainsOwnership, configurationMismatchTerminatesMatchedChildBeforeActivation, adoptedManagedHangReachesManagedCrashRecovery, adoptedManagedChildThatDiesBeforeExplicitHealthCannotBeCertified, with their Fixture. Each drove LlamaServerOps.adoptManagedServerIfPresent, the adopt-or-kill path for a predecessor's registered llama-server. The two rollback checks and SleepingChild are kept unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java#adoptManagedServerIfPresent",
            "modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java#adoptedManagedHandle",
            "modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java#monitorAdoptedManagedChild"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for adopt, external llama/inference/server and server port; only ADR-0051 decision 2, added on this branch, speaks to llama-server adoption, and it retires it)"
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildReconcilerTest.java"
      ],
      "class": "New",
      "behaviour": "verifiedOwnedHealthyCompatibleLlamaPredecessorIsTerminatedAtBoot: a registered LLAMA_SERVER whose PID, start time and executable match a live process is terminated by reconcile() and its record released, even though its endpoint answers like a healthy llama-server and its declared hash equals the one the old reconciler was given. llamaRecordWithReusedPidLeavesTheLiveProcessUntouched: a LLAMA_SERVER record whose start time does not match leaves the live process running and drops the record.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-no-adoption-reconciler.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildReconcilerTest.java#verifiedOwnedHealthyCompatibleLlamaPredecessorIsTerminatedAtBoot",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildReconcilerTest.java#llamaRecordWithReusedPidLeavesTheLiveProcessUntouched"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildReconcilerTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "matchingManagedLlamaIdentityHealthPropsAndConfigAreRetainedForAdoption (pinned the retention the decision retires), matchingManagedLlamaWithDifferentAppliedConfigIsStopped (its outcome is now the rule for every matched llama-server, pinned by the new check) and closeReleasesOwnedRegistrationWithoutClosingProcessRegistry (pinned the HTTP executor the reconciler owned only to probe a predecessor for adoption).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui/src/main/java/io/justsearch/ui/runtime/ManagedChildReconciler.java#healthyLlama",
            "modules/ui/src/main/java/io/justsearch/ui/runtime/ManagedChildReconciler.java#declaredLlamaConfigHash",
            "modules/ui/src/main/java/io/justsearch/ui/runtime/ManagedChildReconciler.java#httpRegistration"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for adopt, external llama/inference/server and server port; only ADR-0051 decision 2, added on this branch, speaks to llama-server adoption, and it retires it)"
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildReconcilerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "registeredExtractionChildIsStoppedOnlyWhenAllIdentityAxesMatch, executableMismatchLeavesUnrelatedLiveProcessUntouched, reusedPidStartInstantLeavesLiveProcessUntouched and failedTerminationOfMatchedProcessRetainsOwnership construct the reconciler as new ManagedChildReconciler(registry) or new ManagedChildReconciler(registry, ignored -> false) instead of (executors, registry, null[, termination]) inside a try-with-resources: the reconciler no longer owns an executor registration or an HTTP client and is no longer AutoCloseable. Inputs, records, sleeper process, the reconcile() call and every assertion are unchanged. captureUsesTheOwnedProcessesObservedExecutable, missingObservedExecutableRefusesRegistration and the HealthyLlamaStub are unchanged; unused imports (TestEngineExecutors, spy, never, verify) are dropped."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExternalInferenceEndpointTest.java"
      ],
      "class": "New",
      "behaviour": "The explicit endpoint setting: blank means none; a loopback http URL with a port (127.0.0.1 or localhost, optional trailing slash) is accepted with that port; another host, https, no port, port 0, a path or a query is refused with a message naming the setting; a refused value fails startLlamaServer as INVALID_CONFIG; a server answering on the configured port while the setting is unset makes externalServerNote() report external_server_ignored after a start attempt, and the note clears once nothing answers there.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the no-adoption change end to end within ADR-0051 decision 2 and scenario S14.",
          "choice": "Reconciliation ruling CH4 as the builder implements it: the explicit configuration is justsearch.inference.external_endpoint (env JUSTSEARCH_INFERENCE_EXTERNAL_ENDPOINT), accepted only as a loopback http URL with a port because every request is built against the loopback host; anything else is refused with a reason, never reinterpreted. Without it, a server on the configured port is ignored with one WARN and the status note external_server_ignored.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-no-adoption-endpoint.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExternalInferenceEndpointTest.java#blankMeansJustSearchRunsItsOwnServer",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExternalInferenceEndpointTest.java#loopbackHttpUrlWithPortIsAccepted",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExternalInferenceEndpointTest.java#unusableValuesAreRefusedWithAReason",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExternalInferenceEndpointTest.java#refusedEndpointFailsTheStartAsInvalidConfiguration",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExternalInferenceEndpointTest.java#serverOnConfiguredPortWithoutEndpointIsReportedAsIgnored"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerTestAccess.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "installLogicalOwner reflects LlamaServerOps.installActive(StartResult, Process, int port) instead of installActive(StartResult, Process, ProcessHandle): the adopted-handle parameter is gone and the owner now carries the port it serves on. The helper passes the start's configured server port, which is where its callers' stub servers listen, so the installed owner probes the same address as before. crashCurrent, checkCurrentHealth and invokeCurrent are unchanged."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerExternalServerTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerPreservedChildTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFlagsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/HeadAssemblyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppShutdownWiringTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/GenerativeBackendByReasonTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "The expectations that pinned adoption, keep-alive or the fixed port, each replaced at the same seam by the New entry below. ApplyConfigTest managedFailureRejectsReplacedOwnerAndCannotMutateAfterClose: 'preserved child ownership remains available for adoption' after close with setStopServerOnClose(false). ApplyConfigTest failedDetachCleanupRetainsCandidateOwnershipAndCannotReadoptExternal: detach rewrote the configured port (retained port != configured port), and the retained policy REQUIRE_MANAGED_CONFIG_WITNESS. ExternalServerTest startLlamaServerSkipsProcessStartWhenHealthAlreadyUp and startLlamaServerCanAdoptHealthOnlyWhenExplicitlyEnabled: a healthy server on the configured port is adopted without the explicit endpoint. PreservedChildTest preservedManagedChildIsStrictlyReadoptedByNextManager: an ordered restart preserves the managed child and the next manager readopts it. ShutdownTest closeDirectiveControlsServerStop: close(false) leaves the server running; terminalStopTerminatesOwnedChildWhileUndrainedWorkHoldsLifecycleLock's [2] adoptedManagedHandle parameter. CandidateContextTest explicitCandidateOwnsGpuArgvHashAndFailureSignalAcrossGlobalChanges: disposition ADOPTED_MANAGED for a registered predecessor; strictRequestRefusesUnregisteredServerWhileLegacyRequestAdopts: the legacy request adopts the configured-port server. LaunchFlagsTest declaredConfigurationIdentity: the port changes the declared hash. HeadAssemblyTest shutdownDirectiveReachesHeldInferenceManager: setStopGenerativeBackendOnClose(false) makes the manager keep its server. HeadlessAppShutdownWiringTest everyReasonConfiguresInferenceCloseBeforeAssemblyClose and terminalWriterUsesLateBoundOrderedSequence: setStopGenerativeBackendOnClose(reason) precedes HeadAssembly.close. GenerativeBackendByReasonTest reasonsSplitExactlyAsDesignSays and eachReasonIndividually: restart and hang leave llama-server running for adoption.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java#setStopGenerativeBackendOnClose",
            "modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java#ADOPTED_MANAGED",
            "modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java#adoptedManagedHandle"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for adopt, keep-alive/stopsGenerativeBackend, external llama/inference/server and server port; only ADR-0051 decision 2, added on this branch, speaks to llama-server adoption or keep-alive, and it retires both)"
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerExternalServerTest.java"
      ],
      "class": "New",
      "behaviour": "ExternalServerTest startLlamaServerDoesNotAdoptHealthyServerOnConfiguredPortWithoutEndpoint: a healthy llama-server on the configured port with no endpoint is not adopted: the start goes on to launch its own and fails EXECUTABLE_NOT_FOUND on the fixture's missing executable, no external server is active, and the status note is external_server_ignored. healthOnlyUseNeedsTheExplicitEndpointEvenWhenEnabled: with health-only use enabled the same holds, and once justsearch.inference.external_endpoint names that server a new manager uses it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the no-adoption change end to end within ADR-0051 decision 2 and scenario S14.",
          "choice": "Orchestrator ruling on the failing pre-existing tests, under the owner's delegation: a configured port without the external endpoint is not adopted and is refused or ignored with external_server_ignored, asserted at the same seam (InferenceLifecycleManager.switchToOnlineMode / startLlamaServer).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-no-adoption-rewrites-external.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerExternalServerTest.java#startLlamaServerDoesNotAdoptHealthyServerOnConfiguredPortWithoutEndpoint",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerExternalServerTest.java#healthOnlyUseNeedsTheExplicitEndpointEvenWhenEnabled"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerPreservedChildTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFlagsTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java"
      ],
      "class": "New",
      "behaviour": "The retired expectations rewritten at the same seam, every other assertion kept. ApplyConfigTest managedFailureRejectsReplacedOwnerAndCannotMutateAfterClose: with the same setStopServerOnClose(false) request as before, close ends the managed server (no owner remains). failedDetachCleanupRetainsCandidateOwnershipAndCannotReadoptExternal: detach leaves the configured port as it was and the retained candidate carries MANAGED_DETACHED_FROM_EXTERNAL, the policy that always launches managed (the strict witness policy now refuses while an endpoint is configured); cleanup refusal, two starts, retained LAUNCHED_MANAGED and the failure chain are kept. PreservedChildTest orderedRestartEndsTheManagedChildAndTheNextManagerLaunchesItsOwn: with the same setStopServerOnClose(false) request the restart path used to make, close ends the first manager's child (a real stand-in llama-server process) and retires its row; the next manager's RESTART_ALWAYS apply launches and registers its own server (two registrations, different pid and endpoint, same declared hash) and its close ends it too. ShutdownTest closeAlwaysStopsServer: after setStopServerOnClose(false), close still stops the server. CandidateContextTest explicitCandidateOwnsGpuArgvHashAndFailureSignalAcrossGlobalChanges: the candidate is launched fresh (LAUNCHED_MANAGED) with a stand-in llama-server and a fake GPU service; GPU layers, argv, hash and recovery owner assertions kept. strictRequestRefusesTheNamedExternalServerWhileLegacyRequestUsesIt: with the endpoint naming the server, the strict request is refused EXTERNAL_SERVER_CONFLICT and the legacy request uses it; without the endpoint the legacy request does not adopt the same healthy server on the configured port (it launches its own, which fails here on an unrunnable executable, and no external server is active). LaunchFlagsTest declaredConfigurationIdentity: the configured port does not change the declared hash; every other input still does. HealthyFakeLlamaServer is the stand-in llama-server process these checks launch (serves /health and /props on the --port it is given, records its pid so the test ends it).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the no-adoption change end to end within ADR-0051 decision 2 and scenario S14.",
          "choice": "Orchestrator ruling on the failing pre-existing tests, under the owner's delegation: rewrite each to assert the new rule at the same seam (close always stops; no readoption; a configured port without the external endpoint is not adopted and is refused or ignored with external_server_ignored; the port is not in the declared hash; every shutdown reason stops the chat server), keeping every assertion that still holds. Detach keeps its own always-managed policy because CH4 makes the strict witness policy refuse while an endpoint is set.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-no-adoption-rewrites-inference-b15-refresh.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#managedFailureRejectsReplacedOwnerAndCannotMutateAfterClose",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#failedDetachCleanupRetainsCandidateOwnershipAndCannotReadoptExternal",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerPreservedChildTest.java#orderedRestartEndsTheManagedChildAndTheNextManagerLaunchesItsOwn",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java#closeAlwaysStopsServer",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java#explicitCandidateOwnsGpuArgvHashAndFailureSignalAcrossGlobalChanges",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java#strictRequestRefusesTheNamedExternalServerWhileLegacyRequestUsesIt",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFlagsTest.java#declaredConfigurationIdentity"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/HeadAssemblyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppShutdownWiringTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/GenerativeBackendByReasonTest.java"
      ],
      "class": "New",
      "behaviour": "Every shutdown reason stops the chat server. HeadAssemblyTest heldInferenceManagerAlwaysStopsItsServerOnClose: the Head's held inference manager reports that close stops its server, and HeadAssembly has no setStopGenerativeBackendOnClose directive to change it. HeadlessAppShutdownWiringTest everyReasonClosesTheAssemblyAndItsChatServer: for every reason the ordered sequence freezes admission, stops the API and then closes HeadAssembly, and never calls a keep-alive directive on it. (terminalWriterUsesLateBoundOrderedSequence only loses the keep-alive step, see the Obsolete entry; its order and exit code 1 are unchanged.) GenerativeBackendByReasonTest everyReasonStopsTheGenerativeBackend and eachReasonIndividually: quit, upgrade, restart and hang all stop it; none leaves it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the no-adoption change end to end within ADR-0051 decision 2 and scenario S14.",
          "choice": "Orchestrator ruling on the failing pre-existing tests, under the owner's delegation: rewrite each to assert that every shutdown reason stops the chat server at the same seam, keeping the shutdown order and exit code; the HeadAssembly keep-alive directive is removed, not kept as a no-op.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-no-adoption-rewrites-shutdown.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/HeadAssemblyTest.java#heldInferenceManagerAlwaysStopsItsServerOnClose",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppShutdownWiringTest.java#everyReasonClosesTheAssemblyAndItsChatServer",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/GenerativeBackendByReasonTest.java#everyReasonStopsTheGenerativeBackend",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/GenerativeBackendByReasonTest.java#eachReasonIndividually"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerExternalServerTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootInProcessPortsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The stand-in llama-server these checks talk to is reached through justsearch.inference.external_endpoint instead of by sitting on the configured port, the only way the user's own server is used now (CH4). ExternalServerTest adoptedExternalServerTransitionsOfflineAfterRepeatedHealthFailures, failedDetachReadoptsCapturedExternalOwnerAndRemainsOnline and applyConfigRestartRecordsStartupDuration_tempdoc601: useExternalEndpoint(port) installs a ConfigStore naming the stub's port before the manager is built. CandidateContextTest stalePhysicalPropsResponseCannotPublishAfterOwnerReplacement, stalePhysicalHealthFailureCannotMutateReplacementDiagnostics and stalePhysicalHealthSuccessCannotMutateReplacementDiagnostics: legacyExternal resolves its context with the endpoint set to the stub's port (resolved(...) gains an externalPort overload). EngineRootInProcessPortsTest generationKeepsIndexingPacedAfterRetrievalAndReleasesOnCancellation and generationStartedBeforeIndexCompositionIsPacedUntilProducerExit: the resolved config names the stub through the endpoint key instead of justsearch.server.port. The stub servers, their responses, every call and every assertion are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerPropsInsightsTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerCandidateContextTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Reflected private signatures and one register count. PropsInsightsTest (all four checks, through setUsingExternalServer) and CandidateContextTest armedLaunchedChildCleanExitSignalsCapturedOwnerFailure reflect LlamaServerOps.installActive(StartResult, Process, int port) instead of (StartResult, Process, ProcessHandle) and pass the configured port, where the stub listens. CandidateContextTest newOps builds LlamaServerOps with the ten-argument constructor (the eleven-argument one took the retired adoption termination hook and ignored it) and a GPU service argument, null where it was null before. ShutdownTest terminalFenceIsAppliedToTheRealProcessBuilderPath reflects startManagedProcess(ProcessBuilder, Path, List, StartResult, int port) and passes port 0; the other ShutdownTest checks drop setStopServerOnClose calls, which are no-ops now (close always stops), so each check's precondition is unchanged. ConfigApplyScopesTest packagedRegisterIsTheRepositoryRegister counts 281 register entries instead of 280: the one added entry is justsearch.inference.external_endpoint (restart-required); the byte comparison with the repository register is unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPublisherTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "LlamaServerProcessOwnershipTest terminalFenceRefusesLaunchAndManagedAdoptionBeforeCreatingAChild's adoption leg: after the terminal fence, adoptManaged(handle) throws IOException (it pinned that adoption was fenced, not that it is gone); its launch-fence assertions are kept in the New entry below. RuntimeManifestPublisherTest restartRetainsOwnershipAndFinallyCloseCannotEraseIt: a graceful restart with a registered llama-server child left hands off as ready, so a successor could adopt it; childPersistenceAndShutdownCompletionCannotDeadlockUnderForcedContention's handoff state ready with a child registered.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerProcessOwnership.java#adoptManaged"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for adopt, shutdownHandoff/handoff and manifest children; only ADR-0051 decision 2, added on this branch, speaks to llama-server adoption, and it retires it)"
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java"
      ],
      "class": "New",
      "behaviour": "terminalFenceRefusesLaunchAndOwnershipHasNoAdoptionPath: after stopTerminal, requireLaunchAllowed and launch are refused before any child is created (kept), and LlamaServerProcessOwnership has no adoptManaged(ProcessHandle): a child is owned only because this Engine launched it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the no-adoption change end to end within ADR-0051 decision 2 and scenario S14.",
          "choice": "Orchestrator ruling under the owner's delegation: remove LlamaServerProcessOwnership.adoptManaged (adoption retired by design) and rewrite the fence check to the new rule.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-no-adoption-ownership.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerProcessOwnershipTest.java#terminalFenceRefusesLaunchAndOwnershipHasNoAdoptionPath"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPublisherTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Spec A U20, manifest half. restartWithChildrenRemainingIsIncompleteAndCloseCannotEraseIt: for restart and for hang, a graceful completion with a registered LLAMA_SERVER child still present writes shutdownHandoff state incomplete, never ready, keeps the child recorded, and the later close does not delete the manifest. childPersistenceAndShutdownCompletionCannotDeadlockUnderForcedContention: the contended registration and completion still both finish, and with the child registered the handoff is incomplete. A restart or hang with no children left still hands off as ready (shutdownDispositionCoversEveryReasonAndCompletionOutcome, unchanged).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the no-adoption change end to end within ADR-0051 decision 2 and scenario S14.",
          "choice": "Orchestrator ruling under the owner's delegation: implement Spec A step 1's manifest rule in RuntimeManifestPublisher (registered children left at shutdown completion mean the handoff is incomplete for every reason) with test U20. Builder's reading: only the published state changes; a non-terminal completion with children left does not throw, so the shutdown exit path is unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-no-adoption-manifest.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPublisherTest.java#restartWithChildrenRemainingIsIncompleteAndCloseCannotEraseIt",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestPublisherTest.java#childPersistenceAndShutdownCompletionCannotDeadlockUnderForcedContention"
      ]
    },
    {
      "items": [
        "modules/app-launcher/src/test/java/io/justsearch/app/launcher/ExecutorArchitectureTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "engineHttpClientFactoriesAlwaysInjectTheirExecutor: the census floor goes from 5 to 4 HttpClient factories. The floor only keeps the scan from passing vacuously (it matched nothing would mean nothing was checked); its message named the five current Engine factories. ManagedChildReconciler's probe client, the fifth, is removed with llama-server adoption, so four remain: InferenceLifecycleManager, RuntimeActivationService and OpenAiCompatController foreground and background, each built with .executor(...). The rule itself (every Engine HttpClient factory is newBuilder with an injected executor, the named system-test harness excluded) and the violation assertion are unchanged. The evidence runs the check on the candidate and on 68547f75a, where the same floor of 4 holds over six factories.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the no-adoption change end to end within ADR-0051 decision 2 and scenario S14.",
          "choice": "Orchestrator ruling under the owner's delegation (verifier repair round 1): the floor exists only to stop a vacuous pass, so it is lowered to the four factories that remain once the reconciler's adoption probe client is gone, and all four still inject their executor.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-no-adoption-executor-floor.json"
    },
    {
      "items": [
        "scripts/jseval/lane-f/e456-instruments.test.mjs"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "'child policy never passes vacuously and any orphan/reload dominates a gap': the all-true fixture row carries llamaEndedAndChatReady instead of healthyLlamaAdopted, the field projectChildPolicies now reads for crash and restart (the E5 instrument measures that the old llama-server ended and the successor's own answers /health, instead of that it was adopted). The three assertions (undefined, false, true) are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "8aa43650-69d1-41ab-9908-8482c5b8b16f",
  "verdict": "reject",
  "digest": "sha256:f84f455cf88b01d9031d1542fbcfb1d90737ba6ccb94520d18dbaccc9c5da022",
  "note": "Regression without an entry: io.justsearch.app.launcher.ExecutorArchitectureTest#engineHttpClientFactoriesAlwaysInjectTheirExecutor passes on 68547f75a and fails on f90c16237 ('five current Engine HttpClient factories must remain covered': the candidate deletes ManagedChildReconciler's HttpClient, leaving 4 factories, floor is 5). docs/reference/testing/test-intent.md: a red pre-existing test with no acceptable entry is a regression. The listed entries themselves checked out: the 5 ManagedLlamaAdoptionTest checks, the reconciler, manifest, external-server, closeAlwaysStopsServer, preserved-child and declared-hash checks fail on the baseline by assertion for the reason under test. Live real-interface verification (S14, N1-N4, P5) was not possible: the shared dev stack is held by another agent."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "32924b48-181e-495f-a011-4939c2c2fce1",
  "verdict": "accept",
  "digest": "sha256:77d4eb7d4ae4beeaa27bcae97e9db6e8806b60a65ff3abb3dfcc4b32c7e6d784"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "9ab35ce5-d0fe-4b77-bc6b-47bb55c9efa6",
  "verdict": "accept",
  "digest": "sha256:3c4aa6b44a3a13ea6cff792bf7941ba7a43410b2fca486771b94b4e158b28db1",
  "note": "Re-review after e456-instruments.test.mjs gained tests (T24, CH9, readChildLedger, child policy ledger); no existing assertion was weakened, and the renamed child-policy test keeps its never-vacuous oracle. On b76f89ba2 the new tests fail by assertion (T24 crash true !== false, CH9) or because readChildLedger is absent; on 9b88b9e03 the 41 tests pass. compareChildLedger was also confirmed against a live Engine kill (OWNER_DIED GONE, no disagreement)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-e7b-codex-20261007",
  "verdict": "reject",
  "digest": "sha256:88e1c70042ab760c16cfac281aed09c7b153bf26be4d28223f57227e49b3519c",
  "note": "E7b changed LlamaServerLaunchFlagsTest, so the attached no-adoption-rewrites-inference evidence for declaredConfigurationIdentity has a stale file digest. The named candidate check passed in the independent full suite, but the changeset evidence and existing acceptance need refresh; current content is not supported by its attached evidence."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-e7c-codex-20261008",
  "verdict": "reject",
  "digest": "sha256:8d58df6ccd5e95fc4bc5e729a2e84b01cdc9574920bf1e4cb535fee3c201ddb8",
  "note": "Launch evidence has a stale ManagedLlamaAdoptionTest digest (1020910e... vs current a5662234e...). Current inference tests pass; changeset evidence remains invalid. See verify-e7c/no-adoption-stale.json."
}
```
