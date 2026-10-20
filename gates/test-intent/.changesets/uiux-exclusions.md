---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: 01a15819-2a8c-73e1-84af-4f19e90308d0
---

# Test-intent entries

WP3.1 only. S31.n maps to EXn and N31.n to ENn. The EX4 sources include the adopted
pass-2 limit amendment; finding 8 belongs to WP3.2 (AX3). Preservation checks in the Deliver scenario
retain already working subcases; the other EX4 entry carries the fail-before repairs.
Editor checks exercise existing API-refusal presentation; real Windows desktop and live-stack
checks are not claimed. No acceptance record is authored here.

D1-D5 below are agent-adopted orchestrator decisions under the user's delegation, as given in
the builder assignment; they are not new owner quotations. D1 and D3 restore regressions against
47e8d1e79. D2 changes the old recorded-glob refusal expectation under AX1 and compares with f9bd104d2.

Agent-adopted D4: "if the counting code is in this change, files excluded by the user's list are
counted under the user-exclusion reason, not the temporary/system one (if no such reason exists on
f9bd104d2, leave it and say so)." No user-exclusion reason exists on f9bd104d2: its reason-code list
only has SKIPPED_TEMP_OR_SYSTEM for this case. This conditional leaves that classification unchanged.

Agent-adopted D5: "check whether JustSearch's own settings UI ever sends the exclusion list back
unchanged with another setting. If it does and that save fails while invalid entries are saved,
make a save that sends the saved list unchanged succeed (newly added invalid entries are still
refused). If only foreign clients can hit it, leave it and say so." Own UI writes are narrow patches:
SettingsSurface dispatches APPEARANCE_FLOW's literal individual-field effects, Brain writes llm,
adaptation writes highContrast and uiMode writes mode. Library skips a POST for unchanged invalid
saved patterns. A whole-object echo is only a foreign-client case; its refusal is left unchanged.
No frontend edit is required by D4/D5.

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
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LegacyExclusionUpgradeTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java",
        "modules/app-services/src/test/resources/io/justsearch/app/services/settings/legacy-exclusions-f9bd104d2.json"
      ],
      "class": "New",
      "behaviour": "EN1/AX1/AX2: a settings list actually written by f9bd104d2 survives upgrade and restart; malformed entries match literal names, valid entries retain the current class/alternative meaning, replay can recompile captured lists, and Preview/Apply preserve exact saved labels and file membership. The fixture is portable, immutable before-version output.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AX1",
          "quote": "an invalid saved entry is matched as a literal name, never stops indexing, the other entries keep working, Preview/editor show the saved list and the editor warns about each such entry",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AX2",
          "quote": "the new meaning of [ ] and { } applies to old lists and the editor's pattern help says so",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-ax-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LegacyExclusionUpgradeTest.java#EN1AX1ListWrittenByBeforeVersionKeepsLiteralAndValidMatchesAfterRestart",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LegacyExclusionUpgradeTest.java#AX1EditorProjectionNamesEveryInvalidEntryWithoutReplacingSavedList",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LegacyExclusionUpgradeTest.java#AX1PreviewApplyKeepTheSavedListAndLiteralMembership",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsTest.java#AX1QueuedWorkUsesCurrentPolicyAndEN1BuiltInSkipsRemain",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java#AX1AllScanModesUseSavedPolicyAndEN2KeepOtherFiles",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java#AX1CandidateReconcileHonoursUserExclusions",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerUserExclusionsAdmissionTest.java#AX1LiveCreatesChangesAndMovesUseSavedPolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#AX1ExplicitIngestOfWatchedPathCannotBypassPolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java#AX1RecordedEnumerationReplayedAfterRestartUsesCurrentPolicy"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts"
      ],
      "class": "New",
      "behaviour": "AX1: the editor retains the saved list, names every invalid entry with a literal-name warning, and Preview/Apply can use that unchanged saved list without trying to save invalid syntax. The fail-before schema overlay only admits the new diagnostic response field to isolate editor behavior from parser compatibility.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AX1",
          "quote": "an invalid saved entry is matched as a literal name, never stops indexing, the other entries keep working, Preview/editor show the saved list and the editor warns about each such entry",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-ax-editor-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts#AX1 editor preserves the saved list and names every literal fallback warning",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts#AX1 unchanged legacy list can be previewed and applied without replacing the saved policy"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts"
      ],
      "class": "New",
      "behaviour": "AX2: editor help explains listed characters, ranges, negated character classes and brace alternatives, explicitly including lists saved by earlier versions.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AX2",
          "quote": "the new meaning of [ ] and { } applies to old lists and the editor's pattern help says so",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-ax-editor-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts#AX2 pattern help explains character classes and alternatives for previously saved lists"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/MalformedOperatorExclusionOverrideTest.java"
      ],
      "class": "New",
      "behaviour": "EN1/D1 (agent-adopted under the user's delegation): malformed live operator JSON produces an empty exclusion policy, rather than the GUI list or an exception, restoring f9bd104d2. Repeated reads of the unchanged policy emit one warning. The legacy optional JSON list parser is preserved; recorded JSON-shape validation remains strict. The test was first run in ExclusionLimitTest before being moved unchanged into this standalone file to retain the existing ExclusionLimitTest evidence.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "EN1",
          "quote": "Built-in skips (`IngestionSkipPolicy`), existing pattern syntax (a bare name matches at any depth), environment and `-D` overrides of the GUI list (`ExcludesServiceImpl.java:28-31`).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "D1",
          "quote": "a malformed operator override behaves exactly as on f9bd104d2 (find what that is and keep it), and is logged once as a warning; it never stops indexing. A check reproduces R1 and fails on 47e8d1e79.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-d1-d3-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/MalformedOperatorExclusionOverrideTest.java#D1MalformedOperatorOverrideIsIgnoredAndWarnsOnce"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsV2ProjectionTest.java"
      ],
      "class": "New",
      "behaviour": "D3 (agent-adopted choice under the user's delegation): omit invalidExcludePatterns when no entry is invalid, represented as null in the Java projection. This restores the existing full-record expectation and adds an explicit null assertion. NON_EMPTY already omits the empty collection on the wire, the schema permits omission/null, and the editor defaults an omitted field to []. Nonempty diagnostics remain covered by the existing AX1 checks.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "D3",
          "quote": "decide whether the projected invalidExcludePatterns should be [] or absent when there are none, keep it consistent with the schema and the UI, and enter the projection test change with its reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-d1-d3-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsV2ProjectionTest.java#projectsEveryStoredFieldWithoutTrimmingPaths",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsV2ProjectionTest.java#preservesUnsetAndAutomaticValuesWithoutInventingAnIndexPath"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsRecordedTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AX1",
          "quote": "an invalid saved entry is matched as a literal name and never stops indexing, on every path including replay of recorded work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "D2",
          "quote": "AX1 covers replay, so a recorded scan whose excludes hold an invalid entry is no longer refused; it matches that entry literally. Rewrite that test to AX1's expectation and enter it as a changed check citing AX1 (it must fail on f9bd104d2 for the reason under test, or be classified as the docs require).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "Decision records searched for a conflict: none. Under the agent-adopted D2 decision and AX1, the old malformedRecordedGlobRefusesBeforeAnyQueueOrLedgerEffect expectation is retired: invalid saved glob entries in recorded scans match literally instead of refusing replay. JSON-shape and recorded admission ownership/cancellation guards remain."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsRecordedTest.java"
      ],
      "class": "New",
      "behaviour": "AX1/D2 (agent-adopted under the user's delegation): recorded scans exclude invalid entries as literal names at the root and nested levels, keep valid entries working, and admit unrelated files with their frozen key, epoch and collection. This is the intentional replacement of the old refusal expectation: it already passes on unchanged 47e8d1e79, so its assertion fail-before is against the real base f9bd104d2, not claimed as a new failure on 47e8d1e79.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AX1",
          "quote": "an invalid saved entry is matched as a literal name and never stops indexing, on every path including replay of recorded work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "D2",
          "quote": "AX1 covers replay, so a recorded scan whose excludes hold an invalid entry is no longer refused; it matches that entry literally. Rewrite that test to AX1's expectation and enter it as a changed check citing AX1 (it must fail on f9bd104d2 for the reason under test, or be classified as the docs require).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-exclusions-recorded-ax1.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsRecordedTest.java#AX1MalformedRecordedGlobMatchesLiteralNameAndKeepsOtherEntriesWorking"
      ],
      "repaired": true
    }
  ]
}
```

## Review history

- 2026-10-20: Reworked after verifier rejection 8cf789758. Removed finding-8 claims from both EX4 entries (AX3); added before-version-written upgrade data and EN1/AX1 admission, replay, Preview/Apply and editor checks, plus AX2 pattern help. Existing rejection record retained below. Builder session agent-session-2xmru_je.

- 2026-10-20: Reworked after verifier rejection 47e8d1e79 under agent-adopted D1-D5: restore malformed operator override fallback with a once-per-policy warning (EN1 evidence); retain omitted empty diagnostics and the original projection oracle; replace recorded invalid-glob refusal under AX1 with real-base fail-before evidence; document the D4/D5 conditional no-change findings. Both rejection records retained byte-identical. Builder session 01a15819-2a8c-73e1-84af-4f19e90308d0.

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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-p5qzauy2",
  "verdict": "reject",
  "digest": "sha256:695071faadbd875846fdd20fcea90847447eb6c3988aa4b7ac9dc70da31b0d6b",
  "note": "Evidence re-run reproduces: 19 repaired checks fail on f9bd104d2 (assertion) and pass; 9 AX checks fail on 8cf789758 (assertion), not the base; 3 editor AX/AX2 checks fail on f9bd104d2; 10 preserved pass before and after. Reject: (1) two pre-existing tests are red with no entry: SettingsV2ProjectionTest.projectsEveryStoredFieldWithoutTrimmingPaths (invalidExcludePatterns [] vs null) and WorkerScanOpsRecordedTest.malformedRecordedGlobRefusesBeforeAnyQueueOrLedgerEffect; both pass on f9bd104d2. (2) EN1 names operator overrides as preserved, but no check covers a malformed JUSTSEARCH_UI_EXCLUDE_PATTERNS; live, the candidate then indexes nothing (boot walk, watcher, ingest fail), f9bd104d2 indexes normally."
}
```
