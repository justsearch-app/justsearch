---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b42-legacy-backfill-20261009-builder
---

# Legacy upgrade admission

Complete legacy parents now keep unchanged admission and acquire declarations from their stored content and verified children. The one-time parent RMW preserves existing vectors, runs under the mutation lock, and checks the current writer snapshot before publishing. It never rewrites children, extracts the source or encodes embeddings. Incomplete sets still reach extraction and the canonical writer repairs them. This supersedes the b40 expectation that even a complete legacy set must be rewritten. The separate verifier owns acceptance; no builder acceptance is supplied.

```json
{
  "entries": [
    {
      "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java"],
      "class": "New",
      "behaviour": "A complete legacy parent takes the real scan's UNCHANGED outcome, invokes no extractor, emits no job for embedding, preserves real parent/child vectors and child documents, gains count/digest declarations, and never calls the splitter again, including after index reopening. Zero-child and missing-content parents gain zero declarations. Both regression checks fail by assertion on 65daf680a. The candidate preservation run also verifies incomplete legacy admission reaches extraction and canonical replacement restores completeness.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "when a legacy parent passes the existing legacy completeness check, it is admitted as unchanged and its declaration is recorded from data already stored", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b42 builder brief, 2026-10-09, item 1'; the text does not appear in the owner's turns of session 886833dd"}],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b6d536-batch1-legacy-backfill.json",
      "newChecks": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java#completeLegacyScanBackfillsWithoutExtractionOrEmbeddingAndSurvivesReopen", "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java#legacyZeroChildrenAndMissingContentGainDurableZeroDeclarations"]
    },
    {
      "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java"],
      "class": "New",
      "behaviour": "The legacy expectation changes from compulsory rewrite to unchanged admission with immediate backfill and no repeated splitter call. Declared admission, canonical chunked/zero-child declarations and the 5,000-child/1,000-small-file timing workloads are preserved. Fresh candidate execution includes every case.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "After backfill the legacy check never runs again for that parent, across restarts.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b42 builder brief, 2026-10-09, item 1'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-batch1-legacy-preservation.json",
      "newChecks": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java"]
    },
    {
      "items": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"],
      "class": "New",
      "behaviour": "Complete legacy admission is accepted. Missing/duplicate/wrong-total/wrong-revision/content-change rejection remains. New race checks prove a stale admission snapshot cannot backfill children deleted meanwhile or overwrite a changed parent. Those package-private backfill interfaces are new, so race checks are candidate-only; fail-before uses only shared scan/admission interfaces in LegacyChunkBackfillTest.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "an incomplete legacy set is still not unchanged and is rewritten", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b42 builder brief, 2026-10-09, item 1'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-batch1-legacy-preservation.json",
      "newChecks": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch2-20261010-independent",
  "verdict": "reject",
  "digest": "sha256:853b59219280da25aa575dfa5fa8d43d080aef08ad70a960fc1aef176951751e",
  "note": "The two shared-interface legacy regression checks fail on 65daf680a and pass on freshly built candidate artifacts; incomplete preservation passes. The newly added race checks call new backfill interfaces and have no real-parent assertion fail-before. Required real old-build upgrade counts and comparable quiet scan times are still unverified. See verify-batch2/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "reject",
  "digest": "sha256:89edea8272447f7cf7935e2453cf72f181c93c80bc6ec2e397d76c22f7b704c2",
  "note": "Entry 2 attaches candidate-only b6d536-batch1-legacy-preservation.json without explaining the missing before run. Entry 3's absent package-private race APIs concern different checks; the existing legacy isUnmodified oracle does not use them."
}
```
