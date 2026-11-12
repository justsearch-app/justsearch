---
schema: test-intent.v1
task: p5-import-upkeep
author-role: builder
author-session: p5-upkeep-sx7uillm
---

# Test intent

The before-state for this assignment is 317831ee434b4b45bf37e782904128086916b6ab. No acceptance record is supplied by the builder. Detailed U/N cases and settled design rules are attributed to the orchestrator's delegation; only the quoted snapshot decision is attributed to the owner.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/IndexedSourceMaintenanceTest.java"
      ],
      "class": "New",
      "behaviour": "New maintenance cases: U7QueuedRefreshDefersWithoutOpeningOrSpendingAttemptsWhenApprovalIsLost (existing deferral records no file hash and spends no retry attempt after approval loss), U4FailedUpkeepRetainsItsRetryBudgetUntilTheMemberOrPolicyChanges (stable exhausted upkeep keeps its budget; changed bytes or extraction policy refresh through existing maintenance admission), U2U3MissingImportAncestorsRetireTheWholeFamily, U9ChildMountChangesNeverAuthorizeRetirement, U10OpenImportProducerIsNeitherRetiredNorRefreshed, U10InterruptedImportOwnerIsNeitherRetiredNorRefreshed, U4U11ChangedMembersQueueDurableOriginBearingRefreshWithoutDiscoveringNewFiles, U6U7U8N5UnavailableApprovalOrExclusionNeverQueuesRefresh, U4U11TwoSuccessiveEditsPublishNewTextAndPreserveOwnershipAcrossQueueRestart, U11RefreshNeverDeletesRowsWhenTheSourceOrStorageDisappears, U12ExclusionRemovalSkipsChangedOwnerAndCancelsAnAlreadyQueuedRefresh, U14RecreatedMemberAfterRetirementCommitIsReadmittedUnderItsOrigin, U13SingleFileImportRefreshesAndThenRetiresItsDeletedMember, U13ExternalRenameRetiresOnlyTheIndexedPathAndNeverDiscoversItsReplacement, U13AppMediatedMoveRetainsTheOriginAndItsRefreshScope, U7U8MissingImportAncestorsRetireEvenWhenRefreshApprovalIsUnavailable, N2UnmountedImportStorageKeepsRowsUntilTheSameMountReturns, N4UnknownStorageKeepsTheExistingParentWitnessRule, U11ChunkFamilyGrowthAndShrinkReplaceEveryOldMember, U11FailedUnreadableOrPlaceholderRefreshPreservesTheExistingFamily. U2/U3 cover absent ancestors and N9 mount-read cost; U4 checks actual Lucene replacement for two edits across queue reopen; U6/U7/U8 restrict refresh without restricting proven retirement; U9 covers child mount removal/replacement/overmount and a change after enumeration; U10 checks an open walk and an interrupted operation owner without sealing either; U11 checks durable origin/collection/storage plus complete family replacement, missing/storage races before and after extraction, and failed/unreadable/placeholder sources; U12 fences re-import ownership and queue cancellation; U13 covers both move kinds and a single-file import; U14 checks post-commit recreation. N1, N2, N4 and N5 preservation cases stay unchanged. The class also reruns its pre-existing preservation checks.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "One-off imports: a snapshot that stays correct: deleted files and subfolders leave search, exclusions apply, changed files are refreshed; new files are not picked up (that is watching).",
          "location": "task p5-import-upkeep request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Tests: real SQLite stores, real Lucene, real files in temp folders, real worker maintenance pass; mount topology may be injected through the existing StorageObservationSnapshot / mountinfo seams (no new framework). Reuse IndexedSourceMaintenanceTest, IndexedExcludesTest and their fixtures.",
          "delegationLocation": "task p5-import-upkeep request",
          "delegationScope": "The orchestrator-settled D1-D7 and S1-S4 rules and U/N cases in this builder assignment. Detailed cases are delegated intent, not owner-authored scenarios.",
          "choice": "Use temporary real stores and worker maintenance, a controlled existing mount observation seam, and the real extraction/publication pipeline with a file-reading test extractor. Each new case names its assigned U/N scenario; baseline-compatible reflection only exposes missing behavior rather than emulating it.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/IndexedSourceMaintenanceTest.java"
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/p5-import-upkeep.json"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/excludes/IndexedExcludesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "The four existing Apply Excludes mock enumerations now accept both the old path-only port and the new path/origin port, wrapping the same paths with an absent origin. Their inputs, faults, expectations, conditions and watched matching assertions are unchanged."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/excludes/IndexedExcludesTest.java"
      ],
      "class": "New",
      "behaviour": "U5ImportRootRelativeExclusionsRemoveOnlyMatchingMembers: inner/** matches only the indexed import descendant using its saved root; its neighbour survives and the conditional delete carries the origin used for matching; U12 changed ownership is counted as a changed_owner unchecked scope with no confirmed removal. Origin lookup must follow completion of the serving enumeration, avoiding recursive calls into the bounded Worker executor. Existing cases retain their previous oracles.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "One-off imports: a snapshot that stays correct: deleted files and subfolders leave search, exclusions apply, changed files are refreshed; new files are not picked up (that is watching).",
          "location": "task p5-import-upkeep request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "D3 Apply Excludes matches an import member with a valid origin record against its origin root, so root-relative patterns apply; members without one keep today's matching; watched matching unchanged. Before removal, revalidate under the fence that the member's current owner and the matching rule still hold.",
          "delegationLocation": "task p5-import-upkeep request",
          "delegationScope": "The orchestrator-settled D1-D7 and S1-S4 rules and U/N cases in this builder assignment. Detailed cases are delegated intent, not owner-authored scenarios.",
          "choice": "Enumerate path plus origin, resolve the origin root through the worker port and require conditional deletion. Worker maintenance class covers the real-store changed-owner fence and refresh cancellation race.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/excludes/IndexedExcludesTest.java"
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/p5-import-upkeep.json"
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OriginApprovalExpiryTest.java"
      ],
      "class": "New",
      "behaviour": "The existing history-expiry case keeps every prior oracle and additionally reads upkeep eligibility through the real attached coordinator and SQLite queue after operation/job history expiry: StructuralAuto and the exact family grant permit refresh; revocation and SESSION retain deletion eligibility but refuse refresh.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "One-off imports: a snapshot that stays correct: deleted files and subfolders leave search, exclusions apply, changed files are refreshed; new files are not picked up (that is watching).",
          "location": "task p5-import-upkeep request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "S1 Authority and eligibility (D2, D6): a read-only, fail-closed query from the worker to the app's operation owner, answering per origin_id: eligible for upkeep (no open producer: no open walk and the operation not running, interrupted or recovering; origin approval revalidates through OperationAuthority.evaluateOriginApproval), or not. Unknown, error or timeout = not eligible. It never seals, claims or modifies a producer.",
          "delegationLocation": "task p5-import-upkeep request",
          "delegationScope": "The orchestrator-settled D1-D7 and S1-S4 rules and U/N cases in this builder assignment. Detailed cases are delegated intent, not owner-authored scenarios.",
          "choice": "Exercise the owner bridge using actual expired operation and job histories, preserving the existing exact-grant, revocation and session evaluator checks.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OriginApprovalExpiryTest.java"
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/p5-import-upkeep.json"
    }
  ]
}
```
