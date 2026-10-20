---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-round3-20261020
---

# Decode exact DELETE identities in candidate gap evidence

```json
{
  "entries": [{
    "items": ["modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedBulkIngestionCoordinatorTest.java"],
    "class": "New",
    "repaired": true,
    "behaviour": "The real KnowledgeServer candidate journal snapshot decodes versioned WATCHER and EXPLICIT DELETE paths before hashing the captured unit and reading Lucene. A completed deletion covers the actual DELETED_OR_MISSING captured unit, so the real recorded coordinator settles SUCCESS without user gap acceptance. A still-indexed document instead yields DELETE_NOT_APPLIED for the decoded unit and never false coverage. Legacy raw path receipts preserve their existing coverage and settlement behavior. Real SQLite and Lucene back the snapshot and the existing recorded-operation harness; its physical generation observations remain the existing harness seam. No existing test expectation or production gap decision is weakened.",
    "sources": [{
      "kind": "owner-words",
      "quote": "Add a check that fails before your fix (on 40f3af9b7) by assertion: a buffered versioned DELETE during a rebuild yields no gap for the deleted file, and the build settles without user acceptance. Use the real candidateJournalSnapshot path, not a mock.",
      "location": "task t-20261019-b81d05 request"
    }, {
      "kind": "owner-words",
      "quote": "Fix the gap reader to decode the versioned payload, through one shared decoder.",
      "location": "task t-20261019-b81d05 request"
    }, {
      "kind": "owner-words",
      "quote": "Make sure legacy (path-only) payloads still decode, per I10.",
      "location": "task t-20261019-b81d05 request"
    }],
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-round3.json",
    "newChecks": [
      "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedBulkIngestionCoordinatorTest.java#bufferedDeleteCoversCapturedMissingFileAndSettlesWithoutAcceptance",
      "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedBulkIngestionCoordinatorTest.java#versionedDeleteStillIndexedCannotCertifyCandidateCoverage"
    ]
  }]
}
```

## Acceptance records
