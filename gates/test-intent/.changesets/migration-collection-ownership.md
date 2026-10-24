---
schema: test-intent.v1
task: migration-collection
author-role: builder
author-session: codex-migration-collection-builder
---

# Test-intent entries

The owner resolved the M2 fixture conflict on 2026-10-24. Independent acceptance remains the verifier's.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/MigrationEnumerationCompletenessTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Owner-authorized fixture corrections: resumedEnumerationCannotResurrectAcceptedCollectionDelete declares its root as books, keeping the deletion/non-resurrection oracle. collectionDeleteWithoutSourceIdentityBlocksBaselineAdmission supplies an absent root authority through the public EnqueueContext(null, ...) caller for the unknown-identity refusal, then admits a declared null/default root despite the unrelated books deletion. This counterpart changes the former null-as-unknown expectation exactly as the owner directed; it does not introduce a second meaning for a declared default root. Maintenance enqueueAndBufferFilesForGeneration still treats null as unspecified and retains its collection-preserving contract, covered by the new standalone ownership test.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "make the root itself declare \"books\" (FileSource(file, \"books\")), so an accepted DELETE_COLLECTION books still keeps the resumed baseline from recreating the file. Add the counterpart: a file under a default-collection root isn't suppressed by deleting \"books\".",
          "location": "task migration-collection user turn 3"
        },
        {
          "kind": "owner-words",
          "quote": "keep the refusal for genuinely unknown identity wherever a caller can still supply one (show which). For a declared default root, identity is known: assert admission under the default collection.",
          "location": "task migration-collection user turn 3"
        },
        {
          "kind": "owner-words",
          "quote": "Record both as adaptations in the test-intent changeset, with this decision as the reason.",
          "location": "task migration-collection user turn 3"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/HelpMigrationReviewRegressionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The watched-default-over-configured-books check and stale literal default seed are retained. The owner explicitly corrected the two resulting job/receipt expectations from the stale literal default to null. IngestCollectionPolicy.DEFAULT_COLLECTION is a display bucket, not a real collection term; a declared null root authoritatively requires no collection field. Migration must replace the stale maintenance label rather than inherit it.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Approved: apply tmp/third-test-adaptation.patch. A literal \"default\" label is the stale value here: IngestCollectionPolicy.DEFAULT_COLLECTION says the default bucket is not a real collection term, and documents without a collection carry no collection field, so migration producing null is the correct result. Keep the watched-default-over-configured-books check. Record it in the changeset as an adaptation, with that reason.",
          "location": "task migration-collection user turn 4"
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
  "session": "codex-verifier-migration-collection-1",
  "verdict": "accept",
  "digest": "sha256:3a96633a66bc018c03da3d77c6918d60a1a5701886abf2858db3b6d3981a537c",
  "note": "Agreed M1-M9 pass on candidate 24de22f923; new ownership checks fail on 08200f4a5, M3 and all three adapted protections pass on both. Both full module test tasks pass and real Engine migration/cutover, labels and filtered searches pass. R1 is pre-existing and explicitly excluded by the orchestrator scope decision; no other defect found. Evidence: tmp/verification/report.md."
}
```
