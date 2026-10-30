---
schema: test-intent.v1
task: t-20261024-b68264
author-role: builder
author-session: 01a186a4-55fe-7962-9d1b-d74902810f35
---

# Test-intent entries

Sources and checks for the bounded admission and reconciliation implementation.
Execution evidence and independent acceptance are supplied after the candidate is committed.
The isolated LegacyCollectionRecoveryTest adds preservation checks for frozen NULL defaults,
missing plan coverage, accepted deletion order and explicit/reserved labels. Existing released-journal
checks retain their original raw-path namespace/fencing assertions; raw paths contain no stored
collection field and are handled by their existing replay authority.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionAdmissionTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no leak across a requested scope",
        "reproduction": "On fda69956df a mixed hintless batch stores null for P/C; a default child readmission retains obsolete-parent."
      },
      "reproduction": "On fda69956df a mixed hintless batch stores null for P/C; a default child readmission retains obsolete-parent.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "C1/C1b/C3: hintless mixed file admissions inherit each deepest persisted declared folder, including exact default clearing; outside paths keep null and explicit/reserved labels remain authoritative."
        }
      ],
      "evidence": "gates/test-intent/evidence/b68264-collection-ownership.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionAdmissionTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionReconciliationTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "On fda69956df unchanged bytes with missing or obsolete labels are skipped by reconciliation, leaving documents vulnerable to deletion under an obsolete collection."
      },
      "reproduction": "On fda69956df unchanged bytes with missing or obsolete labels are skipped by reconciliation, leaving documents vulnerable to deletion under an obsolete collection.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "C5/C3: non-forced ordinary and candidate reconciliation admit unchanged files with missing or obsolete owner labels, including declared default roots; matching and reserved labels stay skipped."
        }
      ],
      "evidence": "gates/test-intent/evidence/b68264-collection-ownership.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionReconciliationTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "C5/C3: retire the expectation that non-forced candidate reconciliation skips an unchanged file whose stored collection is missing although its owning declared folder is research. Unchanged bytes do not exempt a missing or obsolete owner label from reconciliation. The replacement matching-label fixture is a changed precondition, not an input re-encoding."
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions/ for collection-label reconciliation; the supplied C5/C3 delegated settlement requires readmission of missing or obsolete labels."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "New",
      "behaviour": "Changed precondition in forceReadmitsAnExistingServingPathToTheScopedCandidateJournal: the existing regular-file fixture now stores collection research, matching watch(). Under this matching-label precondition the ordinary candidate scan adds zero files and does not enqueue; force still admits exactly one file to the building-generation journal. This replaces the missing-label skip scenario; it does not preserve its whole oracle. Size, mtime, root identity, queue observations and force assertions remain as written. The matching-label preservation expectation is independently exercised for both ordinary and candidate scans by DeclaredCollectionReconciliationTest.currentAndReservedLabelsStaySkipped (lines 39-44, 73-82); its executed current case is in the cited evidence. This New entry records the changed precondition, not repaired matching-label behaviour; the separate reconciliation Defect pin above records missing/obsolete-label assertion failures before the fix.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "C5/C3: scans re-admit unchanged files whose stored collection differs from the owning declared folder; a file with unchanged bytes and its current declared research collection stays skipped without force and is readmitted with force. Matching-label preservation and missing-label repair are separate scenarios."
        }
      ],
      "evidence": "gates/test-intent/evidence/b68264-collection-ownership.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionReconciliationTest.java"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineHintlessCollectionMigrationTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no leak across a requested scope",
        "reproduction": "On fda69956df the Engine writes NULL for a hintless child, keeps P on default-child readmission, and retains NULL legacy receipts after restarting a migration with a later C deletion."
      },
      "reproduction": "Four baseline assertions fail: C is NULL, default is P, and both legacy-recovery cases retain a NULL receipt.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "C2/C1b/C7: real Engine pause and publication barriers prove hintless C parent/chunk labels, collection deletion, exact default clearing, and restart/cutover recovery of pre-fix NULL jobs and receipts. Under the R1 settlement, removed declarations drop a legacy admission only with later generation-eligible covering scoped deletion evidence; unknown ownership alone preserves it."
        }
      ],
      "evidence": "gates/test-intent/evidence/b68264-collection-ownership.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineHintlessCollectionMigrationTest.java"
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
  "session": "verifier-b68264-codex-r2",
  "verdict": "accept",
  "digest": "sha256:c3b1721e23d22bb2548ff7807fdda84375f3ca2a4d5ab823436197b39e07d933",
  "note": "Collection checks pass after merge; activation stall and final index/queue state match baseline; all full-suite failures reproduce before."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-b68264-codex-r3",
  "verdict": "accept",
  "digest": "sha256:f6e805563a893d0d3c3ff06623c882882dc67ad90fdf0d1f74c9ef440442c770",
  "note": "R1 restart/cutover, deletion boundaries, snapshot refresh, C1-C7 and merge checks pass; all 96 full-suite failures match baseline."
}
```
