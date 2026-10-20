---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: agent-session-a6k7nkou
---

# Test-intent entries

WP3.1 only. S31.n maps to EXn and N31.n to ENn. The EX4 sources include the adopted
pass-2 limit and readable-fixture amendments. Preservation checks in the Deliver scenario
retain already working subcases; the other EX4 entry carries the fail-before repairs.
Editor checks exercise existing API-refusal presentation; real Windows desktop and live-stack
checks are not claimed. No acceptance record is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsTest.java"
      ],
      "class": "New",
      "behaviour": "EN1: preservation checks for the named existing behaviour; these pass on the baseline.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EN1",
          "quote": "Built-in skips (`IngestionSkipPolicy`), existing pattern syntax (a bare name matches at any depth), environment and `-D` overrides of the GUI list (`ExcludesServiceImpl.java:28-31`).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsTest.java#EN1BuiltInSkipsAndExplicitExemptionsRemain",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EN1ResolvedOperatorOverridesPreserveBareNameMatching",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EN1PatternLimitsKeepCompleteSupportedLists"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java"
      ],
      "class": "New",
      "behaviour": "EN2: preservation checks for the named existing behaviour; these pass on the baseline.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EN2",
          "quote": "Indexing of non-excluded files on every path, including deletion cleanup from #793 (sync removes all rows of a deleted file) and #803 once merged; the folder-check cadence and its \"couldn't verify deletions\" state.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-preserved.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#EN2VerifiedDeletionStillRemovesParentAndChunks",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#EN2DeclaredNoFileProjectionKeepsEnrichmentEligibility"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "EN3: preservation checks for the named existing behaviour; these pass on the baseline.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EN3",
          "quote": "Reindex/rebuild results for a corpus without patterns; recorded plans written before the change replay.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java#EN3NoPatternsPreserveEveryScanMode"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "EX1: repaired admission/matching/current-policy behaviour in the named checks.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX1",
          "quote": "(broken today) After exclusion patterns are saved, a file that matches them does not enter the index by any path: the periodic folder check, a live file event (create, change, move or rename into the excluded location), a watcher-overflow re-check, a per-folder reconcile, an incremental or forced Reindex, a full rebuild, an MCP or agent ingest of a watched path, or the boot walk after a restart. Checked with a fixture that is not yet indexed and one that was indexed before the pattern was saved and then removed by Apply.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java#EX1AllScanModesUseSavedPolicyAndEN2KeepOtherFiles",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java#EX1CandidateReconcileHonoursUserExclusions",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java#EX1LiveCreatesChangesAndMovesUseSavedPolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#EX1ExplicitIngestOfWatchedPathCannotBypassPolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#EX1AppliedMatchesStayAbsentThroughMaintenanceScansExplicitIngestAndBootWalk",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#EX1RenameCannotPublishExistingRowsAtExcludedDestination"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsTest.java"
      ],
      "class": "New",
      "behaviour": "EX2: repaired admission/matching/current-policy behaviour in the named checks.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX2",
          "quote": "Work prepared or queued before the patterns changed cannot add a now-excluded file: jobs already in the queue, a Reindex prepared with the older list, and a recorded Reindex replayed after a restart all honour the list current when the file is processed (or drop the file); none restores a document silently.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsTest.java#EX2QueuedWorkUsesCurrentPolicyAndEN1BuiltInSkipsRemain",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#EX2RecordedEnumerationReplayedAfterRestartUsesCurrentPolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#EX2PolicySavedAfterExtractionPreventsPublication"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsTest.java"
      ],
      "class": "New",
      "behaviour": "EX3: repaired admission/matching/current-policy behaviour in the named checks.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX3",
          "quote": "Removing a pattern lets matching files be indexed again by the next folder check or Reindex, without a restart.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsTest.java#EX3RemovingPatternReadmitsWithoutRestart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "EX4: preservation checks for the named existing behaviour; these pass on the baseline.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX4",
          "quote": "On a fixed corpus, the set of files Preview reports, the set Apply removes and the set every admission path skips are the same files, for root-level and nested matches, directory patterns, both path separators, case (per platform rules) and every metacharacter the editor accepts. Counts state their unit (files or folders); numeric equality alone is not the check. Syntax the matchers do not support is refused (S32.6), never interpreted differently by two paths.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX4",
          "quote": "Exceeding input or expanded-pattern limits is explicitly refused without changing saved policy; test both limits through the editor and API.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX4",
          "quote": "Check matching parity on a readable, fully indexed fixture with unchanged files and policy. Separately verify honest Preview/Apply outcomes for unindexed, missing, unreadable and offline entries.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java#EX4RootLevelAndNestedGlobMembershipAgree",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EX4SeparatorsAndNativeCaseRuleArePreserved",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts#EX4 editor reports input limit refusal without applying or discarding the draft",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts#EX4 editor reports expanded limit refusal without applying or discarding the draft"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java"
      ],
      "class": "New",
      "behaviour": "EX4: repaired admission/matching/current-policy behaviour in the named checks.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX4",
          "quote": "On a fixed corpus, the set of files Preview reports, the set Apply removes and the set every admission path skips are the same files, for root-level and nested matches, directory patterns, both path separators, case (per platform rules) and every metacharacter the editor accepts. Counts state their unit (files or folders); numeric equality alone is not the check. Syntax the matchers do not support is refused (S32.6), never interpreted differently by two paths.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX4",
          "quote": "Exceeding input or expanded-pattern limits is explicitly refused without changing saved policy; test both limits through the editor and API.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX4",
          "quote": "Check matching parity on a readable, fully indexed fixture with unchanged files and policy. Separately verify honest Preview/Apply outcomes for unindexed, missing, unreadable and offline entries.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EX4PreviewAndApplyCountFilesAndRemoveExactMembership",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EX4InputOverflowRefusesWithoutReplacingPolicy",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EX4ExpandedOverflowRefusesWithoutReplacingPolicy",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EX4MalformedGlobIsRefusedBeforeSaving",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EX4AcceptedMetacharactersMatchExactCorpusMembership",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EX4WindowsCaseRuleIncludesUnicodeNames",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EX4DoubleStarAloneMatchesAllFiles",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java#EX4QualifiedDirectoryPatternsIncludeDescendants"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java"
      ],
      "class": "New",
      "behaviour": "EX5: repaired admission/matching/current-policy behaviour in the named checks.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EX5",
          "quote": "An excluded file that is indexed when a pattern is saved is not deleted by saving alone (deletion is Apply's job, WP3.2), and it is not re-processed or re-enriched by later checks either.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#EX5SavedPolicyRetainsIndexedTextButStopsFurtherEnrichment"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-h7b04h41",
  "verdict": "reject",
  "digest": "sha256:cb95dd5a74780e60cbf28b96244ab22b0a2731595a4fab6c3e45fce894de6fbe",
  "note": "Both EX4 entries cite the pass-2 amendment 'Separately verify honest Preview/Apply outcomes for unindexed, missing, unreadable and offline entries', but no listed check exercises unindexed, missing, unreadable or offline entries (live: Preview counts an unindexed file, omits an unreadable one Apply removes, and reports 1 of 2 roots with no unchecked-scope message). EN1 cites 'existing pattern syntax' yet no check covers a list saved before the change; live, a before-state-saved list containing '[legacy' stops boot walk, watcher and periodic check on the candidate."
}
```
