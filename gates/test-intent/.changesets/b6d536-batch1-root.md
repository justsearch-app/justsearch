---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b40-batch1-root-20261009-builder
---

# Batch-1 root repairs

The orchestrator's b40 root-repair brief (agent wording under the owner's end-to-end delegation, cited below as delegated choices) requested current-state Health projection and declared chunk completeness without splitting unchanged content. Existing Health wire bodies and recovery occurrences remain the contract. Parent declarations reuse chunk_total and chunk_parent_content_sha256, preserving the physical catalog and its index fingerprint. Older parents take the old completeness check once per parent in a runtime and bypass unchanged admission until a successful rewrite installs the declaration; a failed rewrite remains eligible for another attempt. No acceptance record is supplied by the builder. Live verification belongs to the separate verifier.

Evidence for b6d536-batch1-root.json was regenerated on b599d4d7e (phase B). CapabilityHealthBridgeCurrentStateTest now builds HealthEventChangeRegistry with an ExecutorService, which 04368bfca lacks, so it did not compile there. The compat before-patch gates/test-intent/evidence/b6d536-batch1-root-compat.patch adds only that constructor. It ignores the executor and calls the existing no-argument constructor, and no production code in that state calls it. With it, the check fails before by assertion. The two ChunkUnchangedAdmissionTest checks also fail before by assertion. The third check entry 3 named, legacyFallbackRunsOnceAndRequiresARewriteUntilTheRecordExists, no longer exists: 24a3fc682 retired its behaviour, and entry 4 records it as Obsolete.

Phase B verifier repair (integration base 181363e86):

- **Entry 5's wording is corrected.** The old text said a complete legacy parent bypasses unchanged
  admission. `legacyCompleteParentBackfillsTheDeclarationWithoutRewritingChildren` asserts the
  opposite: `isUnmodified` is true. Since 24a3fc682, a complete legacy parent keeps unchanged
  admission while its declaration is backfilled. That also supersedes the preamble's sentence above
  about older parents bypassing unchanged admission until a rewrite, and the delegated choice cited
  in entries 5 and 6, which describes the 65daf680a behaviour that 24a3fc682 retired.
- **Entry 5 now names its five cases.** `b6d536-batch1-root-admission.json` runs them with a
  before-state run at 04368bfca, this changeset's before-ref. The compat before-patch
  `gates/test-intent/evidence/b6d536-batch1-root-admission-compat.patch` lets the file compile
  there. It adds only `ChunkSetCompleteness.Declaration` and an
  `IndexingCoordinator.backfillChunkDeclaration` that throws. No production code at 04368bfca calls
  either, and none of the five selected cases reaches them.
- **Results at 04368bfca:** `committedChildDeletionCannotBeAdmittedBeforeTheSearchReaderRefreshes`
  fails by assertion. Its O9 behaviour came later, with fe62ed231. The other four pass there:
  - the missing, duplicate-ordinal, revision and total oracles already held at 04368bfca;
  - the parent-content RMW invalidation already held there;
  - a complete legacy parent already kept unchanged admission through the old check.

  None of these four is claimed as a repair.
- **New entry 6** holds the two backfill cases, in
  `b6d536-batch1-root-admission-backfill.json`. Before-state not reproducible: both call
  `IndexingCoordinator.backfillChunkDeclaration` and assert its return value. 24a3fc682 adds that
  method after 04368bfca, so any compat stub of it would itself decide both cases. Both pass on the
  candidate.

```json
{
  "entries": [
    {
      "items": ["modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeCurrentStateTest.java"],
      "class": "New",
      "behaviour": "Through the real registry, CapabilityHealthBridge and ConditionStore, PENDING recovery clears the quiet inference condition instead of retaining InferenceModelLoadFailed. Subscription replay reconciles a recovery published while detached; worker recovery replaces its failure with Pending and READY clears it. Both cases fail by assertion on 04368bfca.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Make the inference capability's Health condition a function of the component's current state", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b40 root-repair builder brief, 2026-10-09, item 1'; the text does not appear in the owner's turns of session 886833dd"}],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b6d536-batch1-root.json",
      "newChecks": ["modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeCurrentStateTest.java"]
    },
    {
      "items": ["modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeMappingFailureTest.java"],
      "class": "New",
      "behaviour": "A classifier injection transitions the real registry to READY and throws during mapping. The bridge re-reads current state and removes the previous failure. The injection overload is new, so this fault-injection check is candidate-only; the independent before-state assertion checks are in CapabilityHealthBridgeCurrentStateTest.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "including at subscription, replay and after any exception in the mapping", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b40 root-repair builder brief, 2026-10-09, item 1'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-batch1-root-mapping.json",
      "newChecks": ["modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeMappingFailureTest.java"]
    },
    {
      "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java"],
      "class": "New",
      "behaviour": "The real unchanged admission path must invoke no ChunkSplitter static entry point and accept a declared complete set. The canonical writer declares total and revision for both chunked and zero-child parents. Those two checks fail by assertion on 04368bfca. The legacy-parent case this entry first named is recorded as Obsolete in the next entry. A separate preservation measurement asserts exactly 5,000 children and 1,000 small files, warms each workload, and records ten and five timed scans respectively on both revisions; timing has no invented performance threshold.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "make the completeness check a count and ordinal check against that record, with no re-split", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b40 root-repair builder brief, 2026-10-09, item 2'; the text does not appear in the owner's turns of session 886833dd"}],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b6d536-batch1-root.json",
      "newChecks": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java#unchangedAdmissionNeverInvokesTheSplitter", "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java#canonicalWriterDeclaresBothChunkedAndZeroChunkParents"]
    },
    {
      "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java"],
      "class": "Obsolete",
      "retired": "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java#legacyFallbackRunsOnceAndRequiresARewriteUntilTheRecordExists",
      "reason": "24a3fc682 (fix(indexing): backfill complete legacy parents without reprocessing) retired the guarded behaviour: a complete legacy parent no longer requires a rewrite until its declaration exists, but keeps unchanged admission and gains its declaration through the parent read-modify-write path, with no extraction, child rewrite or embedding. The same commit renamed the check to legacyFallbackBackfillsAndRunsOnlyOnce with the opposite expectation; b6d536-batch1-legacy-backfill.md entry 2 records that new expectation.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "After backfill the legacy check never runs again for that parent, across restarts.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b42 builder brief, 2026-10-09, item 1'; the text does not appear in the owner's turns of session 886833dd"}],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions entries naming legacy or chunk paths, including 0050, do not cover legacy chunk admission)"
    },
    {
      "items": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"],
      "class": "New",
      "behaviour": "Existing missing, duplicate ordinal, revision and total oracles retain their inputs and assertions; complete fixture parents now carry the writer's declaration. missingChunksAreNotCertifiedByTheParentTimestamp: a matching timestamp certifies no parent with a missing chunk or a missing chunk set. equalCountsWithDuplicateOrdinalsOrWrongRevisionsAreIncomplete: equal counts with duplicate ordinals, a wrong revision or a disagreeing total are not unchanged. parentContentChangesInvalidateTheDeclarationEvenWithoutAnExplicitHashUpdate: a parent content RMW without a caller-supplied digest invalidates the old declaration. committedChildDeletionCannotBeAdmittedBeforeTheSearchReaderRefreshes: a committed child deletion is not unchanged before the reader refreshes. legacyCompleteParentBackfillsTheDeclarationWithoutRewritingChildren: a complete legacy parent (no declaration) keeps unchanged admission (isUnmodified is true) while its declaration is backfilled, without rewriting its children. The splitter and writer regression assertion proof is in ChunkUnchangedAdmissionTest.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "A parent written by an older version without the record falls back to the 04368bfca check once and is then treated as not unchanged", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b40 root-repair builder brief, 2026-10-09, item 2'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-batch1-root-admission.json",
      "newChecks": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java#missingChunksAreNotCertifiedByTheParentTimestamp","modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java#committedChildDeletionCannotBeAdmittedBeforeTheSearchReaderRefreshes","modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java#equalCountsWithDuplicateOrdinalsOrWrongRevisionsAreIncomplete","modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java#parentContentChangesInvalidateTheDeclarationEvenWithoutAnExplicitHashUpdate","modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java#legacyCompleteParentBackfillsTheDeclarationWithoutRewritingChildren"]
    },
    {
      "items": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"],
      "class": "New",
      "behaviour": "The legacy backfill writes a declaration only for the snapshot it admitted. backfillCannotCertifyChildrenDeletedAfterItsAdmissionSnapshot: when a child is deleted after the admission snapshot, backfillChunkDeclaration returns false and certifies nothing. backfillCannotOverwriteAParentChangedAfterItsAdmissionSnapshot: when the parent changed after the snapshot, the backfill returns false and the new parent content stays.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "A parent written by an older version without the record falls back to the 04368bfca check once and is then treated as not unchanged", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b40 root-repair builder brief, 2026-10-09, item 2'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-batch1-root-admission-backfill.json",
      "newChecks": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java#backfillCannotCertifyChildrenDeletedAfterItsAdmissionSnapshot","modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java#backfillCannotOverwriteAParentChangedAfterItsAdmissionSnapshot"]
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
  "digest": "sha256:db3fe675ae352ff7ff7a4b81394a9ad8294284f30dbb9adb05e47340aad42947",
  "note": "Current-state and unchanged-splitter assertions pass and fail on their real parent, but the mapping-exception new check remains candidate-only without assertion fail-before; the record also names a superseded legacy method and obsolete forced-rewrite behavior. Own fresh Health-stream subscriber probe observes a failure while the component is READY. See verify-batch2/report.md and logs/own-subscriber.log."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "accept",
  "digest": "sha256:81cdc6e1288c820141751992d722f230839c1359caedd3f5af51b6d8195538ac",
  "note": "Re-reviewed corrected admission and backfill declarations and the unreached compile-only compatibility stub; candidate and declared before checks reproduce the recorded repair and preservation outcomes."
}
```
