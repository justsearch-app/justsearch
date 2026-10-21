---
schema: test-intent.v1
task: F-excl
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries

The exclusion scenarios and publication ordering are from the bounded F-excl assignment.
Independent acceptance is left to the verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Every dense, chunk, sparse, NER, combined, late-chunking and BGE-M3 success, failure and fallback publication drops excluded work without changing its pending stages, retries, text or artifacts; unaffected items publish and removing the exclusion makes pending work runnable.",
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
        }
      ],
      "evidence": "gates/test-intent/evidence/fexcl-publication.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#exclusionSavedDuringInferencePublishesNothing",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#excludedInferenceFailureCannotSpendRetries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#excludedFallbackCannotPublish"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduExclusionPublicationFenceTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A save between the VDU exclusion check and publication publishes its snapshot promptly; explicit Apply waits for that publication and then removes the document and its chunks. This covers successful VDU output and retry-limit error enrichment.",
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
      "evidence": "gates/test-intent/evidence/fexcl-round2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduExclusionPublicationFenceTest.java#explicitApplyFollowsInFlightVduPublication"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduPolicySaveDeadlockTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "VDU result and claim calls, a loop writer holding the existing fair file fence, and a competing settings save all finish. Publication policy reads do not queue behind a settings writer.",
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
      "evidence": "gates/test-intent/evidence/fexcl-round2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduPolicySaveDeadlockTest.java#vduSaveAndLoopWriterAllFinish",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduPolicySaveDeadlockTest.java#publicationPolicyReadDoesNotQueueBehindSavingWriter"
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
      "evidence": "gates/test-intent/evidence/fexcl-round2.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExclusionApplyPublicationTest.java#explicitApplyRemovesEnrichmentPublishedAfterAnExclusionSave"
      ]
    }
  ]
}
```

## Acceptance records
