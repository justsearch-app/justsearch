---
schema: test-intent.v1
task: redesign-c4
author-role: builder
author-session: 01a1a255-4b83-7ae0-9e36-ae9e7bb62711
---

# Test-intent entries

The orchestrator/verifier produces execution evidence and acceptance after committing the candidate.
The builder writes neither evidence nor acceptance records.

Repair run-evidence arguments:

```text
--out gates/test-intent/evidence/redesign-c4-repair.json
--before dfd154ff65035e0676a2822f6ea687bf3a010473
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#firstMaintenanceAttachesCapturedReceiptWithoutSpendingClaim
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#pendingCapturedAttachmentAndReceiptIdentitySurviveReopen
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#rejectedCapturedReceiptRollsBackAndCanBeRetriedAfterReopen
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#foregroundMutationAroundCapturedAttachmentKeepsExistingMaintenancePrecedence
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java#firstMaintenanceDuringRecordedMigrationExtractsEachCapturedFileOnce
```

No --before-patch or --overlay is required: both changed test files are transplanted by --check.
The foreground matrix's attach-first cases fail before; its mutation-first cases preserve baseline behavior.
The resume fixture re-observes the original admission through `beginCapturedWalk` and
`enqueueRecordedEntries`, which advances its membership to the resumed epoch. The preservation
matrix separately checks that an unseen earlier-epoch member closes as DONE/SOURCE_REMOVED
without a receipt; subsequent maintenance still uses ordinary admission.

Preservation run-evidence arguments (no fail-before requirement):

```text
--out gates/test-intent/evidence/redesign-c4-preservation.json
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#maintenanceWithoutCapturedProofKeepsExistingAdmission
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#identicalCandidateEnumerationRetainsRevisionAndReplayWitness
```

P2-P5 also use the unchanged CapturedWalkPlanTest, SwitchBufferVersionTest,
MigrationCollectionOwnershipTest, MigrationEnumerationCompletenessTest and recorded-authority checks
in the acceptance module suite. The shared dev-stack check is reserved for the orchestrator.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java"
      ],
      "class": "New",
      "behaviour": "First maintenance attaches one missing generation receipt to equivalent PENDING/PROCESSING captured work without modifying any job or walk fact. Claims, owed force, recorded-force authority and retained provenance survive; repeated attachment and reopen retain receipt identity; a rejected receipt rolls back and a retry attaches to the recovered row. Foreground mutations replace or remove the old work and keep existing maintenance precedence. A running recorded migration publishes the original revisions with one extraction per file.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Attachment preserves the whole retained row: revision, state, retry history, force bit, attribution, scan and revision identity of an issued PROCESSING claim. The receipt copies the retained row's provenance, not the incoming one.",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "C4-2 Pending and repeated attachment. Before the claim, and twice in a row, with a reopen between: one receipt, unchanged job, no second receipt revision or order change.",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "The case is restated: an earlier-epoch member that is still retained as PENDING or PROCESSING after the resume (the resumed walk saw it again, or it was claimed before the interruption) qualifies; an unseen earlier-epoch member is retired as today and gets no receipt.",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "C4-3 One extraction across first maintenance. In a running migration (real lifecycle wiring, KnowledgeServerRecordedIngestionTest): hold a claimed extraction, trigger maintenance, release; the original publication succeeds and each file is extracted once.",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "P5 Atomicity: a SQLite trigger rejecting the second receipt rolls back the whole batch; rows and claims survive; reopen shows either no receipt or the complete one (pattern SwitchBufferVersionTest ~267).",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "P5 uses single-receipt rejection (the public maintenance API takes one file) unless a real batch route exists.",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "a row changed by a newer foreground mutation does not qualify (its state, hashes or membership no longer match), so no receipt is attached, and admission then proceeds as today",
          "location": "task redesign-c4 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c4-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#firstMaintenanceAttachesCapturedReceiptWithoutSpendingClaim",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#pendingCapturedAttachmentAndReceiptIdentitySurviveReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#rejectedCapturedReceiptRollsBackAndCanBeRetriedAfterReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#foregroundMutationAroundCapturedAttachmentKeepsExistingMaintenancePrecedence",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java#firstMaintenanceDuringRecordedMigrationExtractsEachCapturedFileOnce"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "Matching receipts preserve jobs and receipt versions across reopen and maintenance. Rows without the exact captured target, active membership, both equal current hashes, exact collection and normalized root retain current admission/refusal behavior. Incoming force, terminal rows and recovery revisions do not attach. An unseen member of a resumed capture is retired as DONE/SOURCE_REMOVED without a receipt, and later maintenance follows existing admission.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Only attachment changes. Everything that does not qualify keeps today's maintenance admission exactly, including watcher events entering through the maintenance API, explicit deletions followed by recreation, and genuinely newer bytes.",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "A retained row qualifies only through the existing evidence: target generation equals 'g-' || jobs.scan_id (IndexGenerationManager ~1130), the walk row for that scan_id is a captured plan with enumeration_outcome COMPLETE, a manifest hash and no seal; walk_seen_epoch is between 1 and the walk's enumeration_epoch (earlier-epoch members of a captured manifest qualify); state is PENDING or PROCESSING; planned and admitted source hashes both equal the current validated hash; collection and normalized ingestion root equal exactly (null is not a wildcard).",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "P1 Existing matching receipts keep every job fact and receipt identity across reopen (RediscoveryAdmissionTest ~164).",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "DONE, FAILED and RETRY_EXHAUSTED rows never qualify (today's behaviour), including DONE H1->H2 followed by the source reverting to H1.",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "denied recovery and retirement-recovery revisions keep today's outcomes.",
          "location": "task redesign-c4 request"
        },
        {
          "kind": "owner-words",
          "quote": "The case is restated: an earlier-epoch member that is still retained as PENDING or PROCESSING after the resume (the resumed walk saw it again, or it was claimed before the interruption) qualifies; an unseen earlier-epoch member is retired as today and gets no receipt.",
          "location": "task redesign-c4 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c4-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#maintenanceWithoutCapturedProofKeepsExistingAdmission",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#identicalCandidateEnumerationRetainsRevisionAndReplayWitness"
      ]
    }
  ]
}
```

## Acceptance records
