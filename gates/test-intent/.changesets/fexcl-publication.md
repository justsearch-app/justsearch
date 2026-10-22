---
schema: test-intent.v1
task: F-excl
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries

The exclusion scenarios and publication ordering are from the bounded F-excl assignment.
Independent acceptance is left to the verifier.

Round 5 uses PR base `d05fb4bbac5cb97d8ea58245e6d11e3d11fdce87` (merge-base with
`origin/main`). Preservation checks execute on both that base and the candidate; their
base-side evidence is `gates/test-intent/evidence/fexcl-round5-preservation-base.json`.
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
          "kind": "owner-words",
          "location": "task F-excl request X1",
          "quote": "An exclusion saved after an enrichment pass read the policy and before it publishes means the pass publishes nothing for the excluded document."
        },
        {
          "kind": "owner-words",
          "location": "task F-excl request X2",
          "quote": "The same holds for every enrichment publication path, not only combined enrichment."
        },
        {
          "kind": "owner-words",
          "location": "task F-excl request X3",
          "quote": "Documents that are not excluded publish exactly as today."
        },
        {
          "kind": "owner-words",
          "location": "task F-excl request X1",
          "quote": "The document then goes through the normal exclusion handling, the same as a document excluded before the pass started."
        },
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 8",
          "quote": "Add a case where the path field differs from the id."
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
      "behaviour": "Preservation against the PR base: A save between the VDU exclusion check and publication publishes its snapshot promptly; explicit Apply waits for that publication and then removes the document and its chunks. This covers successful VDU output and retry-limit error enrichment.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 4",
          "quote": "Keep the deadlock fix: no config lock held across the fence or any slow wait, and a non-blocking snapshot read of the policy at publication, inside the fence."
        },
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 4",
          "quote": "Ordering now comes from Apply. Show that Apply's removal of excluded documents goes through the same fence (or a stronger order), so it runs after any publication that read the old snapshot and removes that publication's enrichment."
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
      "behaviour": "Preservation against the PR base: VDU result and claim calls, a loop writer holding the existing fair file fence, and a competing settings save all finish.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 8",
          "quote": "Where a check is a preservation check that passes on main (for example \"all threads finish\"), classify it accordingly instead."
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
      "behaviour": "A real Engine batch publication is paused after reading the old policy. An exclusion save completes without waiting for the writer. Explicit Apply uses the production ExcludesService/Engine deletion port, waits behind publication, and removes the just-published vector, entities, VDU enrichment, parent/chunks and search result.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 4",
          "quote": "Add a check: a save lands between the snapshot read and the publication, then Apply runs, and the excluded document ends with no enrichment and out of search."
        },
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 4",
          "quote": "Decision: keep explicit Apply, and Preview stays non-destructive."
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
      "behaviour": "Publication policy reads do not queue behind a settings writer; this repairs the blocking read present at the PR base.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 3",
          "quote": "The deadlock check fails (hangs, bounded by a timeout in the test) on 226be391d and passes after."
        },
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 3",
          "quote": "At publication, inside the fence, read the exclusion policy from the current immutable config snapshot, through a read that can't block behind a queued writer."
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
          "kind": "owner-words",
          "location": "task F-excl user turn 8",
          "quote": "EngineTestHarness must make the Engine's roots, operations and any other resolveDataDir() user land in the harness's own data folder, never under user.home."
        },
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 8",
          "quote": "set it for the harness's lifetime and restore it on close."
        },
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 8",
          "quote": "two harnesses in one JVM at once must not see each other's folder."
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
      "behaviour": "Preservation against the PR base: failed harness startup leaves the caller's data-directory and user-home properties unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task F-excl user turn 8",
          "quote": "set it for the harness's lifetime and restore it on close."
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
