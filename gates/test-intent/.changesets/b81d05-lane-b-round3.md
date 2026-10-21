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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-r5",
  "verdict": "accept",
  "digest": "sha256:1fdb2deb0d06e9876db09249eeb0693691dc9514d9323a014ecda99a53cf832a",
  "note": "Accepted under narrowed scope. The missing-root-sync replay-uncertainty latch and follow-on rebuild HTTP 500 move to migration recovery; the shared help-document drop moves to follow-up. Verification limits remain in tmp/verify-r5/scenario-matrix.json."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-848-5ead93951",
  "verdict": "accept",
  "digest": "sha256:77d57eba5b3c394f8275729bf58f4198d20ff02fc9f48072b2e729c961703b9c",
  "note": "Round7: 60/60 producer cases, 10 outcome failures on recorded before-states, and reload/restart gap probes verified; lint passes; JVM run has only the predeclared long-path socket exception. Round6 product passes carried forward; scoped exclusions retained."
}
```
