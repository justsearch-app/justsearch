---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-round5-20261020
---

# A withdrawn root no longer wedges a refused candidate

The builder does not supply acceptance. The refusal route now hands a buffered file delete whose
root or source cannot be proved to the surviving generation's root reconciliation, instead of
retaining Green. Ordinary replay and promoted-pointer recovery keep the exact receipt as before.

WatcherDeleteSafetyTest is new on this branch (an isolated new test source), so it needs no entry
here; its new refusal check is still listed with its evidence below. Its existing
`watcherReceiptRetainsUnavailableRootAcrossEveryReplayRoute` drops only its refusal case, which
asserted the outcome the owner's round-5 decision replaced (the refusal drain failing and keeping
Green while the root is missing); its ordinary and promoted cases are unchanged. The branch's first
changeset's evidence (b81d05-lane-b.json) was regenerated with its original command for that edit.

```json
{
  "entries": [{
    "items": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java"],
    "class": "New",
    "repaired": true,
    "behaviour": "A refused recorded candidate whose journal holds a WATCHER or legacy DELETE for a file under a root that was renamed away during SWITCHING rolls back cleanly: the real KnowledgeServer refusal reconciliation abandons and retires the building generation, returns the migration to IDLE on the source generation, closes the candidate producer, requests the restart, and leaves no candidate journal rows. Nothing is deleted on the source while the root is uncertain: the file and its sibling keep their rows. When the root returns, the source generation's own root reconciliation (syncDirectory with confirmed absence) removes the deleted file's rows and keeps the sibling. Real files, SQLite receipts and committed Lucene parent/chunk rows back the replay check; the KnowledgeServer check uses a real SQLite journal and generation manager with mocked runtimes.",
    "sources": [{
      "kind": "owner-words",
      "quote": "A buffered delete that cannot be applied because its root is uncertain makes the candidate refuse and roll back cleanly, as on the baseline",
      "location": "task t-20261019-b81d05 request"
    }, {
      "kind": "owner-words",
      "quote": "The delete itself is neither applied nor lost. It stays an obligation on the surviving generation, or is left to that root's reconciliation when the root is back. Once the root is back and reconciled, the deleted file is gone from search.",
      "location": "task t-20261019-b81d05 request"
    }, {
      "kind": "owner-words",
      "quote": "Never delete on uncertainty.",
      "location": "task t-20261019-b81d05 request"
    }],
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-round5.json",
    "newChecks": [
      "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#refusalLeavesUnprovedDeleteToSurvivingRootReconciliation",
      "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java#refusalWithWithdrawnRootAbandonsCandidateAndKeepsSourceRows"
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
  "digest": "sha256:9ed588b9f97ab01dd2eedad087763cf7a2770bd848698a7731080684319c5fc8",
  "note": "Accepted under narrowed scope. The missing-root-sync replay-uncertainty latch and follow-on rebuild HTTP 500 move to migration recovery; the shared help-document drop moves to follow-up. Verification limits remain in tmp/verify-r5/scenario-matrix.json."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-848-5ead93951",
  "verdict": "accept",
  "digest": "sha256:dab573b4d46b7f6727bdf7644f3d9cf2f3a1ca38c12dc1bc2e084a3e05d6e311",
  "note": "Round7: 60/60 producer cases, 10 outcome failures on recorded before-states, and reload/restart gap probes verified; lint passes; JVM run has only the predeclared long-path socket exception. Round6 product passes carried forward; scoped exclusions retained."
}
```
