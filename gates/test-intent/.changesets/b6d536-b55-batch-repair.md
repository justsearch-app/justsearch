---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b55-batch-repair
---

# Batch repair regression checks

The owner requires a subscriber attaching inside successful mapper recovery to see current Health,
constant child work for unchanged admission, and bounded migration work for the first legacy scan.
Existing mapping-failure and completeness expectations are preserved. Independent acceptance is
left to the orchestrator/verifier; the builder does not supply an acceptance record.

```json
{
  "entries": [
    {
      "items": ["modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeSubscriberRecoveryTest.java"],
      "class": "New",
      "behaviour": "A subscriber attaching inside successful reentrant mapper recovery sees no failure while the component is READY, in either its attachment snapshot or subsequent delta. The existing throwing-mapper assertion is unchanged.",
      "sources": [{"kind": "owner-words", "location": "task t-20261005-b6d536 user turn 2026-10-10", "quote": "A subscriber never observes a condition inconsistent with the component's state at the moment it observes it."}],
      "newChecks": ["modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeSubscriberRecoveryTest.java#subscriberAttachingInsideSuccessfulRecoveryNeverSeesAStaleFailure"],
      "evidence": "gates/test-intent/evidence/b6d536-b55-batch-repair.json",
      "repaired": true,
      "conflictSearch": "The R1 brief strengthens current-state recovery. Existing CapabilityHealthBridge current-state, mapping-failure, replay, classified-cause and recovery-occurrence tests retain their expectations. No conflicting rule found."
    },
    {
      "items": ["modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionCostTest.java"],
      "class": "New",
      "behaviour": "Unchanged checks read zero child and parent stored rows after reader preparation, independent of a file's 5,000 children. A first scan of 32 complete legacy parents invokes no splitter and uses at most one writer-reader open, while installing every durable declaration. Migration preserves completed sparse postings and their doc-values-only status and retry count, avoiding re-encoding unchanged content.",
      "sources": [{"kind": "owner-words", "location": "task t-20261005-b6d536 user turn 2026-10-10", "quote": "The unchanged check's cost must not grow with the file's chunk count, and the first post-upgrade scan must not do per-chunk work for complete legacy parents beyond a bounded one-time cost you measure and report; incomplete parents are still detected and completed"}],
      "newChecks": ["modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionCostTest.java#repeatedUnchangedAdmissionReadsNoChildren", "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionCostTest.java#firstLegacyScanDoesNotSplitOrOpenAWriterReaderPerParent", "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionCostTest.java#legacyMigrationPreservesCompletedSparseFeatures"],
      "evidence": "gates/test-intent/evidence/b6d536-b55-batch-repair.json",
      "repaired": true,
      "conflictSearch": "The R3/R4 brief adds work budgets. ChunkCompletenessAdmissionTest, LegacyChunkBackfillTest and RecoverableChunkCompletenessTest retain incomplete-set rejection and replay completion. No conflicting rule found."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch3b-20261010-independent",
  "verdict": "reject",
  "digest": "sha256:09f25b323949a3174afb8d4f7a7591bfe0dec2318923073183bd78825d40cf5e",
  "note": "Four new checks fail by intended assertions on exact 2f42b4d50 Gradle classes and pass on 7655b8a20. Repair outcome rejected: independently reproduced O8 shows READY with inference Health condition present at subscriber attachment during concurrent recovery until an earlier mapper releases the projection lock. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-batch3b/logs/candidate-O8.log and previous-full-O8.log. Live reproduction, qualified timing/heap and full suite campaign remain pending; no claim of their acceptance."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch3b-20261010-independent",
  "verdict": "reject",
  "digest": "sha256:09f25b323949a3174afb8d4f7a7591bfe0dec2318923073183bd78825d40cf5e",
  "note": "Independent rejection: R1 O8/O10 public Health attachment sees stable READY with failure condition present; implied R2 O9 durably missing child before reader refresh still admits unchanged; PMD adds nine violations. Four new checks fail by intended assertions on exact 2f42b4d50 and pass on 7655b8a20. Candidate standard full suites6597 found,zero failures,seven unavailable; architecture3/3 both. Live Health recovery and5000-chunk interrupted replay pass. Real old-index upgrade preserves all1690 documents and every dense/sparse/status/retry/content artifact; incomplete-file repair preserves1685 unaffected documents. Qualified R3,100000-chunk heap and upgrade timing remain unverified because startup-identity rule prevents stopping three late-recorded hung daemons without explicit exception. Deterministic full-stack race boundaries remain unverified. Report F:/agent-sandbox/design-b6d536/codex/verify-batch3/report.md."
}
```
