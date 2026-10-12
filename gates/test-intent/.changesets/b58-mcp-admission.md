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
          "kind": "owner-words",
          "location": "task b58 request",
          "quote": "Fix it at the cause so that a scripted agent within the documented client concurrency gets answers (semantic or keyword with a reason) within its deadline, and a client beyond it gets a typed, documented refusal; an MCP status read is never refused because searches are in flight."
        },
        {
          "kind": "owner-words",
          "location": "task b58 request",
          "quote": "Reproduce the cold restart on a large corpus and find the cause of the watcher handoff timeout and the repeated encoder-owner recovery."
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
