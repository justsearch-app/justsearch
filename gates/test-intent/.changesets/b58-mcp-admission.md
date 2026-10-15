---
schema: test-intent.v1
task: b58
author-role: builder
author-session: b58-builder
---

# MCP admission and deferred restart checks

Added checks exercise expired query assistance, HTTP and native MCP status during occupied
search/chat admission, and watcher reads during deferred successor publication. Existing capacity,
freeze, native ownership, recovery and retirement expectations remain. The HTTP fixture supplies
valid initialize instructions so its existing session test exercises admission. Independent
acceptance belongs to the verifier or orchestrator.

Evidence for b58-mcp-admission.json was regenerated on b599d4d7e (phase B). EngineAdmissionTransportTest
and McpProtocolHandlerTest now name EngineAdmissionException.Reason.INFERENCE_BUSY, which 7655b8a20
lacks, so they did not compile there. The compat before-patch
gates/test-intent/evidence/b58-mcp-admission-compat.patch adds only that constant, plus the minimal
case arms that exhaustive switches over Reason need to compile: ConversationEngine, ApiErrorHandler
and RequestEngineWork, and the before-state test ApiErrorHandlerResolveTest, which is not one of the
checks. Each arm throws IllegalStateException("compat: unreachable before-state reason"). Nothing in
the before-state produces this reason, so no before-state behaviour changes. With the patch, all
five checks fail before by assertion.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineModeOpsTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerDeferredRetirementTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/EngineAdmissionTransportTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpProtocolHandlerTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java"
      ],
      "class": "New",
      "behaviour": "Expired or cancelled completions waiting for chat actually exit and release their owner before chat exits. Status remains available when search and chat exhaust client work admission; simultaneous status reads retain typed bounded back-pressure. Native MCP status uses independent observation admission. Deferred publication lets a watcher finish its serving read before taking publication locks, and transfers same-queue admission without draining an accepted root scan. Native generation cutovers still drain that scan.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Fix it at the cause so that a scripted agent within the documented client concurrency gets answers (semantic or keyword with a reason) within its deadline, and a client beyond it gets a typed, documented refusal; an MCP status read is never refused because searches are in flight.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task b58 request'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Reproduce the cold restart on a large corpus and find the cause of the watcher handoff timeout and the repeated encoder-owner recovery.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task b58 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineModeOpsTest.java#expiredAndCancelledCompletionsReleaseOwnershipWhileChatStillHoldsItsTurn",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerDeferredRetirementTest.java#deferredHandoffDrainsWatcherBeforeTakingServingPublicationLocks",
        "modules/ui/src/test/java/io/justsearch/ui/api/EngineAdmissionTransportTest.java#statusHasItsOwnBoundedAdmissionWhileSearchAndChatAreInFlight",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpProtocolHandlerTest.java#nativeStatusIsAdmittedWhileSearchAndChatFillClientBudget",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#deferredWriterHandoffDoesNotWaitForAnAdmittedQueueScan"
      ],
      "evidence": "gates/test-intent/evidence/b58-mcp-admission.json",
      "repaired": true,
      "conflictSearch": "Existing admission, cancellation and mutation-fence ownership rules were inspected. Actual task exit remains the release boundary. Context/aggregate capacity refusal, observer overlap refusal, freezing and closing remain bounded and typed. Existing preservation assertions are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-mcp-20261012-independent",
  "verdict": "accept",
  "digest": "sha256:83bc2730ac21d92d74bd454eb962d326a95a01eea312eac8003876de287d79f5"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:37d1855663c5c97b468e35796c84c499150927409649ecec47454d004b4a1b97",
  "note": "Reviewed the independent observation admission and deferred publication obligations; current evidence/patch/test source match the independent 75917a073 reruns. All five listed checks passed on the candidate and have matching genuine before assertions at 7655b8a20."
}
```
