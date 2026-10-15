---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5, the gaps the coordinator named in Spec A steps 5-6. Two
behaviours steps 5-6 change are now checked from outside at the real process seam, against the
d3fe74ef2 API: an activation stops the main chat server before its candidate launches, and an
offline pass with embeddings pending leaves chat running. Install acquisition's freeze (Spec A row
11) is checked where AiInstallService takes it, as preservation. RuntimeActivationService now
publishes its terminal state after the self-test session closes and after the spec nudge, the
d3fe74ef2 order (it had published "completed" before the session closed). The seam fixture waits
until Windows releases what the ended servers held before the test directory is deleted. This
changeset covers only these files.

Records phase B (batch 3) re-ran both evidence files on b599d4d7e because their check files changed
after the first runs. The checks now build RuntimeReconciler with the lease-free constructor that
5e4f4e698 left, and ManagedChatSeam can pass ChildControl to
BootstrapInferenceFactory.createInferenceManager, so they no longer compile on d3fe74ef2 with the
ManagedChatSeam overlay alone.

- `b6d536-chat-controller-step56-seam.json` keeps its before ref d3fe74ef2, the parent of the lane
  commit 0b7b9df44 that introduced the behaviour. In place of the overlay, the fail-before applies
  the compat before-patch `gates/test-intent/evidence/b6d536-chat-controller-step56-seam-compat.patch`.
  It adds one production constructor: RuntimeReconciler without the lease, delegating to that
  state's own Engine constructor with a fresh RuntimeGpuLease, as ServicePhase builds it. It also
  carries a before-API variant of ManagedChatSeam: the candidate's file without its ChildControl
  branch, which the named checks never use, so no assertion changes. No d3fe74ef2 code calls the
  constructor. Both checks pass on the change and again fail on d3fe74ef2 by the same assertions as
  the first record: the main server alive at the candidate's launch, and chat stopped for the
  embedding hand-off.
- `b6d536-chat-controller-step56-install-freeze.json` holds a preservation check only, so it was
  re-run without a before-state and passes on the change.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflinePassChatSeamTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Seen from outside, through the slow-start fake llama-server, which runs as a separate process and records every launch, and the lifecycle manager the composition root builds, with a RuntimeReconciler following it (followHolds where it exists). Both build their production service by reflection over its public constructors, so they run unchanged on d3fe74ef2. theMainServerIsStoppedBeforeTheCandidateLaunchesAndLoadsOnceAfter (U11, I6): chat online on model.gguf; a RuntimeActivationService activation of a cuda12 variant whose self-test launches the fake as the candidate (candidate.gguf) on its own port and waits for /health; at the candidate's launch no launched server is alive; the activation completes with the candidate healthy; chat is online again; a sampler that keeps each launched process's handle never sees two servers alive; the launches are exactly model, candidate, model. aPassWithEmbeddingsPendingLeavesChatRunning (O2): chat online; an OfflineCoordinator pass with 0 VDU and 12 embeddings pending completes; a 2 ms sampler never sees the manager offline during the pass or the following 1.2 s, the only launch is the boot's model.gguf and chat is online. On d3fe74ef2 the main server was alive at the candidate's launch ('U11: the main server is stopped before the candidate launches ==> expected: <false> but was: <true>') and the pass stopped chat for the embedding hand-off ('chat stays up through the pass ==> expected: <0> but was: <1643>').",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S19",
          "quote": "The activation self-test never loads a second chat model beside the running one when that would not fit.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A steps 5 and 6 as the builder implements them, seen at the process seam: an activation stops the main chat server before its candidate launches, the two are never alive together, and the main server loads once afterwards; an offline pass with embeddings pending never stops chat (O2).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step56-seam.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestSeamTest.java#theMainServerIsStoppedBeforeTheCandidateLaunchesAndLoadsOnceAfter",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflinePassChatSeamTest.java#aPassWithEmbeddingsPendingLeavesChatRunning"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallAcquisitionFreezeSeamTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on d3fe74ef2 and after (recorded in the evidence's fail-before run). theAcquisitionWindowFreezesConvergenceAndTheEngineFollowsTheSpecOnceAfter (Spec A row 11): chat off on the real seam; AiInstallService.startInstall runs its real install thread into a test AI home with a present runtime and is held at the first stage's free-space check, before any byte moves. There the PROCEDURE axis reads INSTALL_ACQUISITION; turning chat on in the spec and nudging the reconciler launches nothing and leaves chat offline for 3.5 s (past the manager's 2 s pre-launch wait); the check then cancels the run and reports no space. After the run ends chat comes online, the reconciler is quiescent and the launches are exactly one model.gguf. The run's BITS orphan sweep would remove this Windows user's unclaimed 'JustSearch AI' transfers machine-wide; the check reports a non-Windows os.name from the start of the run until the free-space check, which the sweep precedes, so the sweep is a no-op, and restores it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A step 6 row 11 as the builder implements it: install acquisition takes one convergence freeze on the chat controller instead of a reconciler procedure; it shows as the INSTALL_ACQUISITION procedure, spec writes inside it are not acted on, and the engine follows the spec once when it ends, as before.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step56-install-freeze.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallAcquisitionFreezeSeamTest.java#theAcquisitionWindowFreezesConvergenceAndTheEngineFollowsTheSpecOnceAfter"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ManagedChatSeam.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestSessionSeamTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "ManagedChatSeam becomes public (with fakeHome()) so the seam checks in the ai.runtime, ai.install and vdu packages can use it, and close() waits, after endAll, until the server logs (data/logs), the fake's home and the self-test directory can be renamed, at most 10 s. ChatActivationSeamTest and ChatControllerLifecycleSeamTest failed @TempDir cleanup on a locked llama-server.log in 2 of 3 runs at d3fe74ef2: at that moment every recorded launch had exited, the JVM had no other descendant, and the Restart Manager, queried in-process, named no holder, yet the log and the fake's working directory stayed in use for 1 to 8 ms. No process or handle is left alive by production code or the fixture; the wait covers the release. SelfTestSessionSeamTest's candidate runs with its working directory and log in that self-test directory instead of the test root. No assertion changes."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StreamingFakeLlamaServer.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "endAll, after ending and awaiting every recorded server as before, waits (at most 10 s, failing with the path named) until every path under the test directory that holds the fake's home can be renamed. ProxyStreamAcrossReconfigureTest failed @TempDir cleanup on a held data/logs/llama-server.log in the full ui suite, the same short hold after the last exit as in the chat seam fixture. No assertion changes in ProxyStreamAcrossReconfigureTest or KnowledgeSearchDuringChatDrainTest."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The recording FakeActuator's launch checks the terminal fence and marks the child live in one synchronized step, and terminalStop sets the fence and clears the child in the same monitor, as LlamaServerProcessOwnership does. Before, a launch that had passed the fence check could mark the child live after terminalStop cleared it, so 'the terminal fence refuses a launch after quit' failed in about 1 run in 8. No assertion or input changes."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "accept",
  "digest": "sha256:3a85330ec451e677dd2b72bcf90bcbac647d7a10259fdbc117ff0046f3b54f72",
  "note": "Reviewed current fixtures, evidence and corrected sources; selected current candidate checks pass. Today's independent reruns exactly match both declared repaired-seam before assertions and the shared API-absence explanation covers the separate candidate-only cases."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4b-20261015",
  "verdict": "accept",
  "digest": "sha256:5f74bc3911ffdbca803fc60026a77e3dd21d254400f4c20529a2810a7bbb5318",
  "note": "Entries and evidence are unchanged since 9f1f6257b; the shared controller file only gained the independently covered planner check, and its current whole class passes. Read review and source/patch comparisons confirm the retained seam assertions still match the independent d3fe74ef2 replay."
}
```
