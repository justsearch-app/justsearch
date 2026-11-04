---
schema: test-intent.v1
task: redesign-c4
author-role: builder
author-session: 01a1a255-4b83-7ae0-9e36-ae9e7bb62711
---

# Test-intent entries

The orchestrator/verifier produces execution evidence and acceptance after committing the candidate.
The builder refreshes execution evidence for the audit repair; acceptance records remain the verifier's responsibility.

Repair run-evidence arguments:

```text
--out gates/test-intent/evidence/redesign-c4-repair.json
--before dfd154ff65035e0676a2822f6ea687bf3a010473
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#firstMaintenanceAttachesCapturedReceiptWithoutSpendingClaim
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#firstMaintenanceRetainsEarlierEpochMemberAndReceiptAfterReopen
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#firstMaintenanceRetainsSameBytesForegroundReaddWithoutReceipt
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#pendingCapturedAttachmentAndReceiptIdentitySurviveReopen
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#rejectedCapturedReceiptRollsBackAndCanBeRetriedAfterReopen
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#foregroundMutationAroundCapturedAttachmentKeepsExistingMaintenancePrecedence
--check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecordedIngestionTest.java#firstMaintenanceDuringRecordedMigrationExtractsEachCapturedFileOnce
--overlay modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExplicitIngestRecords.java
```

No --before-patch is required. Both changed test files are transplanted by --check; the shared fixture helper ExplicitIngestRecords (which records the captured root's volume, as an explicit ingest of that root does) is overlaid.
The unchanged run-evidence producer runs under `js-gradle-slot`; a temporary execution adapter
uses shared clones under `$TMPDIR` for its before-state checkout and cleanup. Test selection,
assertion classification and evidence generation are unchanged.
The foreground matrix's attach-first cases fail before; its mutation-first cases preserve baseline behavior.
The existing resume fixture re-observes the original admission through `beginCapturedWalk` and
`enqueueRecordedEntries`, which advances its membership to the resumed epoch. The retained
earlier-epoch check seeds `walk_seen_epoch=1` on a member of a COMPLETE epoch-2 captured walk,
before leaving it PENDING or claiming it. A real-path probe confirmed that open captures fence
claims and an unseen epoch-1 member is retired as DONE/SOURCE_REMOVED at COMPLETE epoch 2.
The new same-bytes foreground matrix uses public `enqueueEntries` without generation buffering,
covering PENDING and PROCESSING, each with and without force. Both checks compare the entire
retained row, validate the single receipt against that row, and reopen to check persistence.
The preservation matrix still checks unseen-member retirement and ordinary later admission.

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
      "behaviour": "First maintenance attaches one missing generation receipt to equivalent PENDING/PROCESSING captured work without modifying any job or walk fact. Claims, owed force, recorded-force authority and retained provenance survive; repeated attachment and reopen retain receipt identity; a rejected receipt rolls back and a retry attaches to the recovered row. Retained earlier-epoch PENDING/PROCESSING members attach without a row change. Same-bytes public foreground re-adds without receipts retain their newer revision, user/HTTP provenance, force bit and claim in both states, with and without force; their receipts copy that row and survive reopen. Other foreground mutations replace or remove the old work and keep existing maintenance precedence. A running recorded migration publishes the original revisions with one extraction per file.",
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
        },
        {
          "kind": "owner-words",
          "quote": "A captured-plan member admitted at enumeration epoch 1 that is still PENDING or PROCESSING when the walk closes COMPLETE at a later epoch (walk_seen_epoch < enumeration_epoch).",
          "location": "task redesign-c4 audit repair request"
        },
        {
          "kind": "owner-words",
          "quote": "After capture, a foreground re-add through the public enqueueEntries (provenance user/HTTP, same bytes, root and collection; not buffered for the generation), once left PENDING and once claimed (PROCESSING), each with and without force.",
          "location": "task redesign-c4 audit repair request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c4-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#firstMaintenanceAttachesCapturedReceiptWithoutSpendingClaim",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#firstMaintenanceRetainsEarlierEpochMemberAndReceiptAfterReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RediscoveryAdmissionTest.java#firstMaintenanceRetainsSameBytesForegroundReaddWithoutReceipt",
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
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExplicitIngestRecords.java"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Test fixture helper. Adds recordRoot, which records the volume a root is on in the Worker's volume record under indexBase, as an explicit ingest of that root does, so the C4 end-to-end fixture leaves the same volume evidence a real recorded ingest leaves. Without it admission refuses the captured files wherever the run cannot establish the root's volume.",
        "callersAndStateOwners": "Only test code calls this helper; the new method is called only by KnowledgeServerRecordedIngestionTest#firstMaintenanceDuringRecordedMigrationExtractsEachCapturedFileOnce. It writes to the test's own temporary indexBase through RootVolumeLedger.",
        "indirectBehaviours": "The existing recordFileFolder method and its callers are unchanged in inputs, behaviour and assertions; only its Javadoc now also mentions roots.",
        "evidence": "gates/test-intent/evidence/redesign-c4-repair.json: the end-to-end check that uses the helper fails before by its duplicate-extraction assertion and passes after; the full indexer-worker suite, which runs every existing caller of recordFileFolder, is run by the verifier on both trees.",
        "unresolvedRisks": "None known: the helper adds a record only for the root the test names, under the test's temporary directory."
      }
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a36b-8535-7ec0-ad68-74b1c9177e1e",
  "verdict": "accept",
  "digest": "sha256:9c1331fc7e59dbd5c22017e9ca02e88a3d0274710cf20fbd18352d5285efa1f2",
  "note": "Independent Codex verifier-2 on 24b3c7f470: 15 repair cases fail before by assertion and pass after; 26 nonqualifying and 5 matching-receipt cases pass on both trees; Incidental fixture helper reviewed; overall accept."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a1a3e4-cf62-78a3-af31-43d0c57758a5",
  "verdict": "reject",
  "digest": "sha256:9c1331fc7e59dbd5c22017e9ca02e88a3d0274710cf20fbd18352d5285efa1f2",
  "note": "Audit (Codex reviewer): entry 1 rejected - committed tests lack a retained earlier-epoch member (walk_seen_epoch < enumeration_epoch) and a same-bytes foreground re-add without a receipt (pending and claimed); entries 2 and 3 accept."
}
```
