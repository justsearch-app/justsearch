---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b40-batch1-root-20261009-builder
---

# Batch-1 root repairs

The orchestrator's b40 root-repair brief (agent wording under the owner's end-to-end delegation, cited below as delegated choices) requested current-state Health projection and declared chunk completeness without splitting unchanged content. Existing Health wire bodies and recovery occurrences remain the contract. Parent declarations reuse chunk_total and chunk_parent_content_sha256, preserving the physical catalog and its index fingerprint. Older parents take the old completeness check once per parent in a runtime and bypass unchanged admission until a successful rewrite installs the declaration; a failed rewrite remains eligible for another attempt. No acceptance record is supplied by the builder. Live verification belongs to the separate verifier.

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
      "behaviour": "The real unchanged admission path must invoke no ChunkSplitter static entry point and accept a declared complete set. The canonical writer declares total and revision for both chunked and zero-child parents. Legacy parents bypass unchanged admission, do not re-split on later checks in the same runtime, and become unchanged after a declared rewrite. Those three checks fail by assertion on 04368bfca. A separate preservation measurement asserts exactly 5,000 children and 1,000 small files, warms each workload, and records ten and five timed scans respectively on both revisions; timing has no invented performance threshold.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "make the completeness check a count and ordinal check against that record, with no re-split", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b40 root-repair builder brief, 2026-10-09, item 2'; the text does not appear in the owner's turns of session 886833dd"}],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/b6d536-batch1-root.json",
      "newChecks": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java#unchangedAdmissionNeverInvokesTheSplitter", "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java#canonicalWriterDeclaresBothChunkedAndZeroChunkParents", "modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkUnchangedAdmissionTest.java#legacyFallbackRunsOnceAndRequiresARewriteUntilTheRecordExists"]
    },
    {
      "items": ["modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"],
      "class": "New",
      "behaviour": "Existing missing, duplicate ordinal, revision and total oracles retain their inputs and assertions; complete fixture parents now carry the writer's declaration. Added cases require a complete legacy parent to bypass unchanged admission and require a parent content RMW, even without a caller-supplied digest, to invalidate the old declaration. This is fresh candidate execution; the splitter and writer regression assertion proof is in ChunkUnchangedAdmissionTest.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "A parent written by an older version without the record falls back to the 04368bfca check once and is then treated as not unchanged", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: b40 root-repair builder brief, 2026-10-09, item 2'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-batch1-root-admission.json",
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
  "digest": "sha256:db3fe675ae352ff7ff7a4b81394a9ad8294284f30dbb9adb05e47340aad42947",
  "note": "Current-state and unchanged-splitter assertions pass and fail on their real parent, but the mapping-exception new check remains candidate-only without assertion fail-before; the record also names a superseded legacy method and obsolete forced-rewrite behavior. Own fresh Health-stream subscriber probe observes a failure while the component is READY. See verify-batch2/report.md and logs/own-subscriber.log."
}
```
