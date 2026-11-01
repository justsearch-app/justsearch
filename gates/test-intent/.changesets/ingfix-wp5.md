---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: wp5-ti2-builder
---

# Test-intent entries

The CLI inventory against 09c246ae15 contains 29 flagged files. Fixture adaptations keep their oracles under the new publication rules. The isolated new processor test and the publication test (new relative to the base, moved between modules within this branch) are exempt as isolated new test sources. Five named checks pass on the candidate and fail by assertion on the before-state (gates/test-intent/evidence/ingfix-wp5.json); the end-to-end check needs the real vision model and has its own script-produced evidence (gates/test-intent/evidence/ingfix-wp5-e2e.json). This record is not an acceptance. Supplementary preservation cases in resultAcknowledgementCoversParentAndReplacementChunks are SUCCESS_TEXT, SUCCESS_EMPTY and REJECTED_SUSPECT_TEXT; in vcn1NonReplacingResultsPreserveCountsChunksAndBaselineDescriptors they are empty, rejected and blank. All six parameter cases pass on both states; they are supplementary cases within the selected methods, rather than separate newChecks ids.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/VduDeferredRetryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor invocations and verifications to the eligibility overload, and successful result fixtures to source-hash and complete-coverage publication. Split success and failure signature verification while keeping admitted-context, lifecycle, activity and cancellation assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening. C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C10: Complete, accepted transcription must still replace content and regenerate coherent chunks, counts, hashes, preview, language and downstream enrichment work; check both short-to-long and long-to-short replacement after reopen. S-D76: Complete VDU for a 51-page PDF and require the unique page-51 baseline token retained.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor mocks to the cooperative process overload; route added result/attempt arguments to the same fake-index oracle and acknowledge successful owned releases. Existing assertions, injected failures and admission/cancellation expectations are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold. C16: Recognized generation-unavailable failures and orderly cancellation must retain their retry refunds; startup recovery must retain its existing consumed-attempt behavior; include cancellation on the final available attempt. C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor mocks to the cooperative process overload; route added result/attempt arguments to the same fake-index oracle and acknowledge successful owned releases. Existing assertions, injected failures and admission/cancellation expectations are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold. C16: Recognized generation-unavailable failures and orderly cancellation must retain their retry refunds; startup recovery must retain its existing consumed-attempt behavior; include cancellation on the final available attempt. C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduAttemptTestSupport.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor mocks to the cooperative process overload; route added result/attempt arguments to the same fake-index oracle and acknowledge successful owned releases. Existing assertions, injected failures and admission/cancellation expectations are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold. C16: Recognized generation-unavailable failures and orderly cancellation must retain their retry refunds; startup recovery must retain its existing consumed-attempt behavior; include cancellation on the final available attempt. C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C10: Complete, accepted transcription must still replace content and regenerate coherent chunks, counts, hashes, preview, language and downstream enrichment work; check both short-to-long and long-to-short replacement after reopen. S-D76: Complete VDU for a 51-page PDF and require the unique page-51 baseline token retained.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchAcknowledgedOutcomeTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor mocks to the cooperative process overload; route added result/attempt arguments to the same fake-index oracle and acknowledge successful owned releases. Existing assertions, injected failures and admission/cancellation expectations are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold. C16: Recognized generation-unavailable failures and orderly cancellation must retain their retry refunds; startup recovery must retain its existing consumed-attempt behavior; include cancellation on the final available attempt. C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorAbstentionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor mocks to the cooperative process overload; route added result/attempt arguments to the same fake-index oracle and acknowledge successful owned releases. Existing assertions, injected failures and admission/cancellation expectations are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold. C16: Recognized generation-unavailable failures and orderly cancellation must retain their retry refunds; startup recovery must retain its existing consumed-attempt behavior; include cancellation on the final available attempt. C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorModeScopingTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor mocks to the cooperative process overload; route added result/attempt arguments to the same fake-index oracle and acknowledge successful owned releases. Existing assertions, injected failures and admission/cancellation expectations are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold. C16: Recognized generation-unavailable failures and orderly cancellation must retain their retry refunds; startup recovery must retain its existing consumed-attempt behavior; include cancellation on the final available attempt. C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor mocks to the cooperative process overload; route added result/attempt arguments to the same fake-index oracle and acknowledge successful owned releases. Existing assertions, injected failures and admission/cancellation expectations are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold. C16: Recognized generation-unavailable failures and orderly cancellation must retain their retry refunds; startup recovery must retain its existing consumed-attempt behavior; include cancellation on the final available attempt. C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduChatHoldTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor mocks to the cooperative process overload; route added result/attempt arguments to the same fake-index oracle and acknowledge successful owned releases. Existing assertions, injected failures and admission/cancellation expectations are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold. C16: Recognized generation-unavailable failures and orderly cancellation must retain their retry refunds; startup recovery must retain its existing consumed-attempt behavior; include cancellation on the final available attempt. C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduDeferralDuringChatDrainTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor mocks to the cooperative process overload; route added result/attempt arguments to the same fake-index oracle and acknowledge successful owned releases. Existing assertions, injected failures and admission/cancellation expectations are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold. C16: Recognized generation-unavailable failures and orderly cancellation must retain their retry refunds; startup recovery must retain its existing consumed-attempt behavior; include cancellation on the final available attempt. C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/VduOpsAttemptOwnershipTest.java"
      ],
      "class": "New",
      "behaviour": "A refused owned release retains the exact attempt for recovery; the new check uses a false release acknowledgement.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C17: A retry-state/result write refusal must not be counted as committed progress or followed by an unrelated failure write; inject write failures and confirm recovery cannot reset a newer attempt.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp5.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/VduOpsAttemptOwnershipTest.java#refusedReleaseRetainsAttemptForOwnedRecovery"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/LegacyVduSourceRefreshTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/VduGenerationBoundaryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceChunkRegenerationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceVduHardeningTest.java"
      ],
      "class": "New",
      "behaviour": "Legacy FAILED and explicit FAILED results remain PENDING and selectable until the configured retry allowance is exhausted, preserve baseline content on every attempt, and retain failure enrichment. Both changed checks are moved from nested classes to top-level methods with the same fixtures, helpers and assertions; only their display annotations become comments. Other nested tests remain nested. Source-hash additions adapt valid publication fixtures to S-D19/C3.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D66: Fail the first attempt transiently, make the second viable and require another ordinary pass to attempt it. C15: Repeated failures must consume the configured allowance, become terminal at exhaustion and leave other documents processable; check actual processor-call counts and pending selection across passes.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp5.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceVduHardeningTest.java#ordinaryFailuresRemainPendingUntilAllowanceExhausted",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceVduHardeningTest.java#failedDoesNotOverwriteContent"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/system-tests/src/systemTest/java/io/justsearch/systemtests/vdu/VduBatchProcessorE2ETest.java"
      ],
      "class": "New",
      "behaviour": "The invalid-image scenario retries ordinary failures through MAX_RETRIES before requiring terminal removal from pending. The test helper adapts to admitted work, explicit attempts, source hashes and complete-coverage publication without changing the other scenario expectations. Method display annotation becomes a comment for selection. Execution is unresolved: the task and AI tag are opt-in, and setup requires an external vision server; no pass or defect reproduction is claimed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D66: Fail the first attempt transiently, make the second viable and require another ordinary pass to attempt it. C15: Repeated failures must consume the configured allowance, become terminal at exhaustion and leave other documents processable; check actual processor-call counts and pending selection across passes.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C10: Complete, accepted transcription must still replace content and regenerate coherent chunks, counts, hashes, preview, language and downstream enrichment work; check both short-to-long and long-to-short replacement after reopen. S-D76: Complete VDU for a 51-page PDF and require the unique page-51 baseline token retained.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp5-e2e.json",
      "newChecks": [
        "modules/system-tests/src/systemTest/java/io/justsearch/systemtests/vdu/VduBatchProcessorE2ETest.java#invalidImageReachesFailed"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OfflineProcessingOperationLifecycleIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor invocations and verifications to the eligibility overload, and successful result fixtures to source-hash and complete-coverage publication. Split success and failure signature verification while keeping admitted-context, lifecycle, activity and cancellation assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening. C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C10: Complete, accepted transcription must still replace content and regenerate coherent chunks, counts, hashes, preview, language and downstream enrichment work; check both short-to-long and long-to-short replacement after reopen. S-D76: Complete VDU for a 51-page PDF and require the unique page-51 baseline token retained.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt processor invocations and verifications to the eligibility overload, and successful result fixtures to source-hash and complete-coverage publication. Split success and failure signature verification while keeping admitted-context, lifecycle, activity and cancellation assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening. C18: Losing activity/energy/bulk eligibility after page one must prevent page two; losing it after the final page must prevent an agreement probe or metadata request; observe actual request counts. C20: Explicit processing must retain its existing admission behavior; terminal cancellation must still interrupt an active vision, probe or metadata request, clean temporary images and release the VDU hold.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C10: Complete, accepted transcription must still replace content and regenerate coherent chunks, counts, hashes, preview, language and downstream enrichment work; check both short-to-long and long-to-short replacement after reopen. S-D76: Complete VDU for a 51-page PDF and require the unique page-51 baseline token retained.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCancellationRetryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCrashRecoveryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduExclusionPublicationFenceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java"
      ],
      "class": "New",
      "behaviour": "An ordinary FAILED acknowledgement durably commits PENDING while allowance remains, alongside the existing success, empty and rejected commit assertions. Hash-bearing fixtures adapt valid publications to S-D19/C3. Naming-only parameterized display change permits selection by method.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D66: Fail the first attempt transiently, make the second viable and require another ordinary pass to attempt it. C15: Repeated failures must consume the configured allowance, become terminal at exhaustion and leave other documents processable; check actual processor-call counts and pending selection across passes.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp5.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java#resultAcknowledgementCoversParentAndReplacementChunks"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduPolicySaveDeadlockTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterCoherenceTest.java"
      ],
      "class": "New",
      "behaviour": "An ordinary failed nonreplacement preserves pending-fallback provenance while allowance remains, including after reopen; other nonreplacement outcomes retain their existing assertions. Source hashes and the max-retries argument adapt fixtures to S-D19/C3 and C15. Naming-only parameterized display change permits selection by method.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D66: Fail the first attempt transiently, make the second viable and require another ordinary pass to attempt it. C15: Repeated failures must consume the configured allowance, become terminal at exhaustion and leave other documents processable; check actual processor-call counts and pending selection across passes.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp5.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterCoherenceTest.java#vcn1NonReplacingResultsPreserveCountsChunksAndBaselineDescriptors"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C15: Repeated failures must consume the configured allowance, become terminal at exhaustion and leave other documents processable; check actual processor-call counts and pending selection across passes.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionRaceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerVduExclusionClaimTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Adapt valid publication fixtures to include the claimed source hash; supply the explicit retry limit where the writer signature requires it. Existing assertions, fault injection, outcomes and publication fences are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D19: Change disk bytes after claiming A without updating the index and require VDU publication refusal. C3: Existing publication fences: newer indexed content, recreated document identity, deletion, exclusion, another owner or another attempt must still defeat an old result; exercise success, empty and failure updates and inspect parent/chunks after reopening.",
          "label": "agent choice"
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
  "session": "01a19582-1022-7853-8b54-41423cbdf92e",
  "verdict": "accept",
  "digest": "sha256:1ba760ca960a5dd369a2c5dbaf29273078c9a0d8f2b83e84b431b72271a1c76e",
  "note": "Verifier verifier-1 (WP5, candidate 2e7eae37d3) accepted entries 1-29 and rejected only entry 30, prescribing its removal (isolated new test source, exempt). Entry 30 removed exactly as prescribed and the summary prose updated to match; entries 1-29 unchanged. All 65 paired VDU cases pass after; 50 fail before by assertion."
}
```
