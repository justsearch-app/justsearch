---
schema: test-intent.v1
task: extraction-unicode-preview-offset
author-role: builder
author-session: uq-builder-preview-1
---

# Preview surrogate-pair offset test intent

All new checks are durable. Existing tests and expectations are unchanged. Repair and preservation
methods are listed separately. These are delegated test choices under the bounded repair brief.

Execution evidence is generated in disposable shared clones containing the exact candidate Java
sources. The producer is adapted there only to use js-gradle-slot, the owning UI class shard and
shared before clones; result parsing, assertions, content hashing and evidence serialization remain
unchanged. Evidence records the adapted producer digest, exact commands and the runner-only before
patch. Its bytes are preserved in
`gates/test-intent/evidence/extraction-unicode-preview-offset-runner.patch` (the temporary run
uses the identical `tmp/runner-scaffolding.patch`). The repository's producer is unchanged. Independent acceptance remains the acceptor's work.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceSliceFailureTest.java"
      ],
      "class": "New",
      "behaviour": "PV1/PV3: direct typed INVALID_ARGUMENT retains exception identity and the captured client is released on exceptional completion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-preview-offset request",
          "delegationScope": "Repair extraction-unicode preview/content surrogate-pair offset error classification (PV1/PV2/PV3) and prove PV-N1 through PV-N5 preservation, without changing UTF-16 units, admission, security or UI contracts.",
          "choice": "PV1/PV3: direct typed INVALID_ARGUMENT retains exception identity and the captured client is released on exceptional completion.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preview-offset-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceSliceFailureTest.java#invalidArgumentKeepsItsIdentityAndReleasesCapturedClient"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/AgentToolInvalidSliceTest.java"
      ],
      "class": "New",
      "behaviour": "PV2: direct and future-wrapped typed INVALID_ARGUMENT is INVALID_REQUEST, non-retryable; ReadDocumentTool returns the same typed failure without another fetch.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-preview-offset request",
          "delegationScope": "Repair extraction-unicode preview/content surrogate-pair offset error classification (PV1/PV2/PV3) and prove PV-N1 through PV-N5 preservation, without changing UTF-16 units, admission, security or UI contracts.",
          "choice": "PV2: direct and future-wrapped typed INVALID_ARGUMENT is INVALID_REQUEST, non-retryable; ReadDocumentTool returns the same typed failure without another fetch.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preview-offset-repair.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/AgentToolInvalidSliceTest.java#typedInvalidArgumentIsNonRetryableWithOnlyFutureWrappersRemoved",
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/AgentToolInvalidSliceTest.java#readDocumentReportsTypedInvalidOffsetWithoutRetrying"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java"
      ],
      "class": "New",
      "behaviour": "PV1/PV2/PV3: real Engine HTTP preview over a real Lucene index rejects offsets inside a surrogate pair with 400 INVALID_REQUEST, non-retryable, no content; repeated failures release captures and valid size-one paging reconstructs the text on the same Engine; the real read tool classifies the same invalid offset and accepts offset zero.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-preview-offset request",
          "delegationScope": "Repair extraction-unicode preview/content surrogate-pair offset error classification (PV1/PV2/PV3) and prove PV-N1 through PV-N5 preservation, without changing UTF-16 units, admission, security or UI contracts.",
          "choice": "PV1/PV2/PV3: real Engine HTTP preview over a real Lucene index rejects offsets inside a surrogate pair with 400 INVALID_REQUEST, non-retryable, no content; repeated failures release captures and valid size-one paging reconstructs the text on the same Engine; the real read tool classifies the same invalid offset and accepts offset zero.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preview-offset-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java#invalidOffsetsAre400AndReleaseCapturesBeforeValidPaging",
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java#readToolUsesRealEngineInvalidOffsetAndValidLeadingPair"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceSliceFailureTest.java"
      ],
      "class": "New",
      "behaviour": "PV-N3/PV-N4/PV-N5: other typed statuses, generic illegal arguments, surrogate-related messages and wrapped invalid arguments remain unavailable; captures release; lazy delegation and the selection-context offset-zero probe retain leading supplementary content. Existing RemoteDocumentServiceRefusalTest separately preserves refusal identities.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-preview-offset request",
          "delegationScope": "Repair extraction-unicode preview/content surrogate-pair offset error classification (PV1/PV2/PV3) and prove PV-N1 through PV-N5 preservation, without changing UTF-16 units, admission, security or UI contracts.",
          "choice": "PV-N3/PV-N4/PV-N5: other typed statuses, generic illegal arguments, surrogate-related messages and wrapped invalid arguments remain unavailable; captures release; lazy delegation and the selection-context offset-zero probe retain leading supplementary content. Existing RemoteDocumentServiceRefusalTest separately preserves refusal identities.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preview-offset-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceSliceFailureTest.java#otherFailuresStayWrappedAndReleaseCapturedClient",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceSliceFailureTest.java#lazyDelegationAndSelectionProbeKeepLeadingSupplementaryContent"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/AgentToolInvalidSliceTest.java"
      ],
      "class": "New",
      "behaviour": "PV-N4: a generic illegal argument mentioning surrogate pairs stays an internal failure; classification does not depend on message text.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-preview-offset request",
          "delegationScope": "Repair extraction-unicode preview/content surrogate-pair offset error classification (PV1/PV2/PV3) and prove PV-N1 through PV-N5 preservation, without changing UTF-16 units, admission, security or UI contracts.",
          "choice": "PV-N4: a generic illegal argument mentioning surrogate pairs stays an internal failure; classification does not depend on message text.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preview-offset-preservation.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/AgentToolInvalidSliceTest.java#surrogateMessageDoesNotClassifyAnInternalFailureAsInvalidRequest"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java"
      ],
      "class": "New",
      "behaviour": "PV-N1/PV-N2/PV-N3/PV-N4: UTF-16 paging, end offsets, negative/missing/malformed defaults, max defaults/caps and pair completion, missing/unknown/empty documents and metadata, real unavailable worker, controller timeout, all admission/executor refusal statuses, internal/generic/message-only worker failures retain their existing responses.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-preview-offset request",
          "delegationScope": "Repair extraction-unicode preview/content surrogate-pair offset error classification (PV1/PV2/PV3) and prove PV-N1 through PV-N5 preservation, without changing UTF-16 units, admission, security or UI contracts.",
          "choice": "PV-N1/PV-N2/PV-N3/PV-N4: UTF-16 paging, end offsets, negative/missing/malformed defaults, max defaults/caps and pair completion, missing/unknown/empty documents and metadata, real unavailable worker, controller timeout, all admission/executor refusal statuses, internal/generic/message-only worker failures retain their existing responses.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preview-offset-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java#pagingDefaultsCapsAndEndOffsetsKeepUtf16Semantics",
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java#missingUnknownAndEmptyDocumentsRetainTheirResponses",
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java#otherWorkerFailuresStay503RegardlessOfMessage",
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java#genuinelyUnavailableWorkerRemainsRetryable503",
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java#admissionAndExecutorRefusalsKeepExistingControllerStatuses",
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewSliceHttpTest.java#controllersOwnWaitTimeoutRemains504"
      ],
      "repaired": false
    }
  ]
}
```
