---
schema: test-intent.v1
task: F-excl
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries

The exclusion scenarios and publication ordering are from the orchestrator's bounded F-excl builder assignment.
Independent acceptance is left to the verifier. The delegated choices enforce the existing
exclusion setting, explicit Apply ordering and test isolation without changing an established contract.

Round 5 evidence was recorded against `d05fb4bba`
(`d05fb4bbac5cb97d8ea58245e6d11e3d11fdce87`), the PR base at that time.
Preservation checks were recorded on that base and the then-candidate; their
base-side evidence is `gates/test-intent/evidence/fexcl-round5-preservation-base.json`.
These historical records do not claim reruns against a later PR base.
The prior acceptance in commit `3ce1b8da8` is historical and does not accept these changes.
The enrichment entry uses its separately regenerated evidence after making the new
parameterized display names explicit. The initial repair batch remains evidence only
for the unchanged snapshot and Engine checks referenced by those entries.


The harness change affects these 21 callers (JUnit parallel execution is not enabled;
Gradle uses its defaults of one fork and no fork recycling):

- `EngineCorruptionRebuildTest`
- `EngineDocumentFetchAndContextTest`
- `EngineExclusionApplyPublicationTest`
- `EngineExclusionReplayPolicyTest`
- `EngineExtractionSandboxChaosTest`
- `EngineFormatCapabilityMatrixTest`
- `EngineGenerationCaptureTest`
- `EngineIndexBasePathLockTest`
- `EngineIndexingWorkflowTest`
- `EngineLinuxRootVolumeLiveTest`
- `EngineMigrationLifecycleTest`
- `EnginePruneReappearedFileTest`
- `EngineRebuildPreparationTest`
- `EngineRootVolumeSwapTest`
- `EngineSandboxFailureWorkflowTest`
- `EngineSoakTest`
- `EngineSwitchingFenceBufferingTest`
- `EngineSyncDirectoryTest`
- `EngineTestHarnessIsolationTest`
- `EngineVduMigrationReplayTest`
- `EngineVduRecoveryTest`

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Every dense, chunk, sparse, NER, combined, late-chunking and BGE-M3 success, failure and fallback publication drops excluded work without changing its pending stages, retries, text or artifacts; unaffected items publish and removing the exclusion makes pending work runnable. Parent and chunk cases also exclude by a stored source path that differs from the document id.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "A file the user excluded must not be processed or published. gates/test-intent/evidence/fexcl-round5-enrichment.json records assertion fail-before at d05fb4bba for EnrichmentExclusionPublicationTest#exclusionSavedDuringInferencePublishesNothing, #excludedInferenceFailureCannotSpendRetries, #excludedFallbackCannotPublish and #sourcePathDifferentFromDocumentIdIsExcluded: excluded work published COMPLETED enrichment or spent retries, including when the stored path differs from the id."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:20Z",
          "delegationScope": "all remaining work the user had assigned to this session, which the user later extended with \"work on everything i gave you though, dont just stop at arbitrary milestones\"; it includes fixing the indexing findings from the review swarm the user asked for (\"do that\", 2026-10-20). The user validates afterwards.",
          "choice": "An exclusion saved while an enrichment pass is in progress stops that pass from publishing or spending retries for the excluded document, including when its stored path differs from its id, while unaffected and later-unexcluded work remains runnable.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fexcl-round5-enrichment.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#exclusionSavedDuringInferencePublishesNothing",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#excludedInferenceFailureCannotSpendRetries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#excludedFallbackCannotPublish",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#sourcePathDifferentFromDocumentIdIsExcluded"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduExclusionPublicationFenceTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Preservation at the recorded base d05fb4bba: A save between the VDU exclusion check and publication publishes its snapshot promptly; explicit Apply waits for that publication and then removes the document and its chunks. This covers successful VDU output and retry-limit error enrichment.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "A file the user excluded must not remain published after explicit Apply. gates/test-intent/evidence/fexcl-round2.json records assertion fail-before at 226be391d for VduExclusionPublicationFenceTest#explicitApplyFollowsInFlightVduPublication: a settings save timed out while publication held the config lock. The round-5 evidence for this entry is preservation: both recorded-base d05fb4bba and candidate runs pass; it claims no fail-before at that base."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:20Z",
          "delegationScope": "all remaining work the user had assigned to this session, which the user later extended with \"work on everything i gave you though, dont just stop at arbitrary milestones\"; it includes fixing the indexing findings from the review swarm the user asked for (\"do that\", 2026-10-20). The user validates afterwards.",
          "choice": "A settings save can finish during VDU publication, and explicit Apply runs after that publication under the fence and removes the excluded document and its chunks.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fexcl-round5-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduExclusionPublicationFenceTest.java#explicitApplyFollowsInFlightVduPublication"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduPolicySaveDeadlockTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Preservation at the recorded base d05fb4bba: VDU result and claim calls, a loop writer holding the existing fair file fence, and a competing settings save all finish.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "Enforcing the requested exclusion scope must remain possible during publication. gates/test-intent/evidence/fexcl-round2.json records bounded assertion fail-before at 226be391d for VduPolicySaveDeadlockTest#vduSaveAndLoopWriterAllFinish: loop writer, VDU and settings-save threads failed to finish because of a lock cycle. The round-5 evidence for this entry is preservation on recorded-base d05fb4bba and candidate, both passing."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:20Z",
          "delegationScope": "all remaining work the user had assigned to this session, which the user later extended with \"work on everything i gave you though, dont just stop at arbitrary milestones\"; it includes fixing the indexing findings from the review swarm the user asked for (\"do that\", 2026-10-20). The user validates afterwards.",
          "choice": "VDU publication and claims, the loop writer and a competing settings save all finish without a lock-order deadlock.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fexcl-round5-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduPolicySaveDeadlockTest.java#vduSaveAndLoopWriterAllFinish"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExclusionApplyPublicationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Checks ordering through the real Engine: batch publication pauses after its policy hook reads the old snapshot inside the fence; an exclusion save completes, then explicit Apply waits behind publication under the same fence and removes the just-published enrichment, parent/chunks and search result. At the recorded base d05fb4bba, the hook is never consulted and the check fails waiting for it. This check does not establish that the policy answer suppresses publication; EnrichmentExclusionPublicationTest pins that behaviour.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "A file the user excluded must not remain published or searchable after explicit Apply. gates/test-intent/evidence/fexcl-round5-repairs.json records assertion fail-before at d05fb4bba for EngineExclusionApplyPublicationTest#explicitApplyRemovesEnrichmentPublishedAfterAnExclusionSave: the policy hook was never consulted, so the wait for that hook failed before the ordered save/publication/Apply exercise. This check demonstrates ordering on the candidate; EnrichmentExclusionPublicationTest pins whether the policy answer prevents publication."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:20Z",
          "delegationScope": "all remaining work the user had assigned to this session, which the user later extended with \"work on everything i gave you though, dont just stop at arbitrary milestones\"; it includes fixing the indexing findings from the review swarm the user asked for (\"do that\", 2026-10-20). The user validates afterwards.",
          "choice": "Explicit Apply runs after an in-flight Engine publication under the fence and removes the excluded document, its enrichment and its search result.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fexcl-round5-repairs.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExclusionApplyPublicationTest.java#explicitApplyRemovesEnrichmentPublishedAfterAnExclusionSave"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduPolicySaveDeadlockTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Publication policy reads do not queue behind a settings writer; this repairs the blocking read present at the recorded base d05fb4bba.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "Enforcing the requested exclusion scope must not block behind a settings writer at publication. gates/test-intent/evidence/fexcl-round5-repairs.json records assertion fail-before at d05fb4bba for VduPolicySaveDeadlockTest#publicationPolicyReadDoesNotQueueBehindSavingWriter: the policy read timed out behind the saving writer. gates/test-intent/evidence/fexcl-round2.json also records that assertion failure at 226be391d."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:20Z",
          "delegationScope": "all remaining work the user had assigned to this session, which the user later extended with \"work on everything i gave you though, dont just stop at arbitrary milestones\"; it includes fixing the indexing findings from the review swarm the user asked for (\"do that\", 2026-10-20). The user validates afterwards.",
          "choice": "The exclusion-policy snapshot read at publication completes without waiting behind a queued settings writer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fexcl-round5-repairs.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduPolicySaveDeadlockTest.java#publicationPolicyReadDoesNotQueueBehindSavingWriter"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarness.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarnessIsolationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The harness scopes platform data-directory and user-home properties to its own fixture for the full Engine lifetime, including restart, restores both after close, and persists watched folders only in its own data directory. Legacy migration never reads the real user profile. Overlapping harness startup is refused before changing process properties.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "Tests must not write outside their requested scope into the user's real profile. gates/test-intent/evidence/fexcl-round5-repairs.json records assertion fail-before at d05fb4bba for EngineTestHarnessIsolationTest#watchedRootsStayInsideHarnessDataDirectory: watched_roots.json appeared under sandboxed user.home instead of the harness data directory. The sandbox reproduced the previously observed real-profile leak without touching the real profile."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:20Z",
          "delegationScope": "all remaining work the user had assigned to this session, which the user later extended with \"work on everything i gave you though, dont just stop at arbitrary milestones\"; it includes fixing the indexing findings from the review swarm the user asked for (\"do that\", 2026-10-20). The user validates afterwards.",
          "choice": "Engine tests run with their own data folder and home and never read or write the user's real profile.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fexcl-round5-repairs.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarnessIsolationTest.java#watchedRootsStayInsideHarnessDataDirectory"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarnessIsolationTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Preservation at the recorded base d05fb4bba: failed harness startup leaves the caller's data-directory and user-home properties unchanged.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "Tests must not write outside their requested scope into the user's real profile or leave process properties redirecting later tests. The related scope leak is recorded as assertion fail-before at d05fb4bba in gates/test-intent/evidence/fexcl-round5-repairs.json for EngineTestHarnessIsolationTest#watchedRootsStayInsideHarnessDataDirectory. This entry's #failedStartupRestoresProperties is a preservation check: gates/test-intent/evidence/fexcl-round5-preservation-base.json and fexcl-round5-preservation.json record passes, not a fail-before."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:20Z",
          "delegationScope": "all remaining work the user had assigned to this session, which the user later extended with \"work on everything i gave you though, dont just stop at arbitrary milestones\"; it includes fixing the indexing findings from the review swarm the user asked for (\"do that\", 2026-10-20). The user validates afterwards.",
          "choice": "Failed Engine harness startup leaves the caller's data-folder and home properties unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fexcl-round5-preservation.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineTestHarnessIsolationTest.java#failedStartupRestoresProperties"
      ]
    }
  ]
}
```

## Acceptance records
