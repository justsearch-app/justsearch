---
schema: test-intent.v1
task: s7-deadline
author-role: builder
author-session: s7-deadline-builder-20261027
---

# Retrieval request endings

The bounded builder assignment supplies D1-D12 and P1-P10. New isolated test classes exercise
those scenarios. The existing MMR supplier-cancellation test changes its outward expectation;
its worker cancellation check, injection and real Engine delegation remain. The RAGContext
deadline test keeps its oracle and uses the knowledge port's typed failure vocabulary.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/mcp/MmrFailureInterfacesIntegrationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task s7-deadline request",
          "quote": "The existing test cancelledSupplierWithNormallyReturningProviderPreservesWorkerAndOutwardBoundaries documents the defect and is expected to change; its change is named."
        },
        {
          "kind": "owner-words",
          "location": "task s7-deadline request",
          "quote": "D5 Both RemoteDocumentService retrieval overloads with caller-chosen doc ids (rich params with docIds, legacy docIds/retrieveContextWithMeta): cancellation propagates, no fallback fetch."
        }
      ],
      "conflictSearch": "Public decision records searched for retrieval cancellation and fallback: no conflicting decision found. The explicit D5 assignment retires the old successful cancellation-to-fallback assertion. Private orchestrator records are not read under the builder contract."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGContextTest.java"
      ],
      "class": "Adaptation",
      "moved": ["input re-encoding"],
      "whatMoved": "grpcDeadlineIsRecognisedAsTimeout keeps the same deadline message, input request and RETRIEVAL_TIMEOUT assertion. Its failure fixture now supplies KnowledgeClientException DEADLINE_EXCEEDED, the actual in-process port type, instead of an untyped RuntimeException. A separate isolated test supplies the former untyped message and requires RETRIEVAL_FAILED under D10.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task s7-deadline request",
          "quote": "D10 RAGContext classifies deadlines by type/status, not by message text: a typed deadline with a neutral or null message counts; an ordinary exception whose message merely contains \"DEADLINE_EXCEEDED\" does not."
        }
      ]
    }
  ]
}
```

## Acceptance records

Independent acceptance is left to the orchestrator/verifier; the builder does not accept its own work.
