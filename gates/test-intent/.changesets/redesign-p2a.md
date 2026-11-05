---
schema: test-intent.v1
task: redesign-p2a
author-role: builder
author-session: redesign-p2a-builder
---

# Test-intent entries

P2a only, based on a42d2cbed2cc46990f7872bef8715cd07e26eb80. RE6 is the live retention fix;
M7 and M28 are independent schema deliveries; P12 is an unused approval primitive.
Boundary hooks return today's approval verdict (allow) and are deliberately not consumed until
P2b+c; existing containment, generation and publication-ownership checks remain authoritative.
The recoverability/version register advances with both schema constants; it also lists v26/v27 queue and v6 operations as readable legacy stores. Existing acceptance paths do not populate the new bindings. No Resume, interruption transition,
listing, UI, permission enforcement, retry-budget or overlap activation is included.

Operations active binding JSON is explicitly versioned and binds its id/predecessor, parent key,
frozen plan hash and interruption revision. Origin restart fields bind the same id and plan hash
and the predecessor origin. Separate origin-only and retained-import evaluator entry points avoid
using missing active state as an origin-only fallback. Session proof is private to one authority
instance and asks its accepted runner owner whether it remains live. A recorded interruption
refuses evaluation even if grants have since been recreated. The primitive issues no execution permit.

Retention reads exact child membership from the operation store before taking the queue lock;
all cleanup predicates protect the same snapshot. Corrupt membership fails cleanup rather than
releasing evidence. After the parent terminates, ordinary retention releases the history.
RE6 uses real coordinator/SQLite stores and a fixed advanced retention clock with controlled
record ageing; its base assertion is failed recovery after the completed child's aged receipt is pruned. No sleeps or shared app data.
M7/M28 exercise transactional DDL and failure injection. P12 has no main counterpart and no
fail-before claim. The expiry test runs real operation/job retention before loading only origins.

The orchestrator owns evidence generation and independent acceptance. No evidence JSON is authored
by this builder. Evidence references below are the exact destinations for the commands below.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "RE6: completed children of an open import retain jobs, ledger, walk progress and exact sealed receipts through aged cleanup and both-store restart; B drains without another walk; terminal parent releases evidence. The deterministic clock seam leaves existing fixture policy unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-p2a",
          "scenario": "RE6",
          "quote": "While a parent import is open, its completed children's queue evidence (jobs, ledger, walk progress, receipts) is exempt from retention cleanup",
          "location": "task redesign-p2a request",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "selected test methods must not carry @DisplayName and parameterized methods must resolve by method name."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "while enumeration is open a rewalk may discover new files inside approved folders; once closed, recovery drains recorded members without new discovery."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/redesign-p2a-repair.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#openImportKeepsCompletedChildEvidenceThroughRetentionAndRestart"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/ImportApprovalMigrationTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ImportStoreMixedUpgradeTest.java"
      ],
      "class": "New",
      "behaviour": "M7: fresh and v6 operations stores gain versioned active-binding storage and independent interruption revision/reason without interpreting legacy approval; injected rollback preserves DDL, rows and version.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-p2a",
          "scenario": "M7",
          "quote": "Each changed store (operations, queue) gets its own transactional, versioned migration with rollback and mixed-upgrade recovery; older binaries refuse the newer stores before writing; old rows stay unversioned and never gain durable permission by repair",
          "location": "task redesign-p2a request",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "The retained parent import operation owns one active approval binding (operations store, versioned): {binding version, predecessor binding, the exact frozen plan hash, interruption revision}."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/redesign-p2a-repair.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/ImportApprovalMigrationTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ImportStoreMixedUpgradeTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ImportOriginBindingMigrationTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ImportStoreMixedUpgradeTest.java"
      ],
      "class": "New",
      "behaviour": "M28: fresh and v27 queue stores gain version/binding/plan/predecessor fields; legacy evidence stays unversioned; injected v28 rollback preserves origin and job rows and schema.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-p2a",
          "scenario": "M28",
          "quote": "Each changed store (operations, queue) gets its own transactional, versioned migration with rollback and mixed-upgrade recovery; older binaries refuse the newer stores before writing; old rows stay unversioned and never gain durable permission by repair",
          "location": "task redesign-p2a request",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "The retained parent import operation owns one active approval binding (operations store, versioned): {binding version, predecessor binding, the exact frozen plan hash, interruption revision}."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/redesign-p2a-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ImportOriginBindingMigrationTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/OriginApprovalEvaluatorTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/StrictSourceOriginLoadingTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/ImportApprovalStatePersistenceTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OriginApprovalExpiryTest.java"
      ],
      "class": "New",
      "behaviour": "P12: strict origin/state loading and point-in-time evidence evaluation, independent of legitimate completed-history expiry; exact selected grant, structural gate, source tier, live accepted ownership, active parent/plan/revision and interruption latch. Missing, malformed, legacy, declared, cross-owner and ordinary PreparedContinuation evidence cannot authorize. No main primitive exists, so no fail-before is claimed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-p2a",
          "scenario": "P12",
          "quote": "After legitimate completed-history expiry, origin-only evaluation still validates exact durable evidence and rejects session/legacy evidence",
          "location": "task redesign-p2a request",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "The retained parent import operation owns one active approval binding (operations store, versioned): {binding version, predecessor binding, the exact frozen plan hash, interruption revision}."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "strict loading, the existing IntentGateEvaluator and exact selected-grant check, live-session proof, missing/legacy/malformed never authorize"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "Work authorization reads only the active binding; publication ownership (P1: deepest watched owner) is separate and never authorizes or blocks the import."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "Once an import is interrupted by revocation or structural denial, only a fresh Resume restarts it; recreating the same grant tuple does not silently re-authorize it (grant identity today has no issuance id)."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p2a-primitive.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/OriginApprovalEvaluatorTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/StrictSourceOriginLoadingTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/ImportApprovalStatePersistenceTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OriginApprovalExpiryTest.java"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPendingTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPolicyTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationPreparationStoreTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreStartupContentionTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java"
      ],
      "class": "New",
      "behaviour": "R-A: Operations stores finish creation, migration, quarantine recovery and contention retry at literal schema version 7; existing rows, sequence, history fences, rollback and quarantine assertions stay intact.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "operations v6 -> v7"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/redesign-p2a-version-expectations.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPendingTest.java#frozenV4MigrationRollsBackAndOnlyFutureTransitionsAcquireDeliveryObligation",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPolicyTest.java#v3MigrationIsAtomicPreservesPreparedDataAndDoesNotInventHistoricalVisibility",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationPreparationStoreTest.java#v2MigrationPreservesSequenceAndRollbackRestoresOldSchema",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreStartupContentionTest.java#wrappedBusyAtSchemaCommitRollsBackAndRetriesWithoutQuarantine",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java#RF55V5SemanticMigrationAdvancesVersionAndPreservesExistingRowsAndSequence",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java#freshStoreHasIndependentVersionAndSingletonFenceAndReopens",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java#interruptedQuarantineCannotResetTheHistoryFence"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java"
      ],
      "class": "New",
      "behaviour": "R-I: Queue stores finish creation and supported-version migration at literal schema version 28; the explicit target-version pin is 28, and existing admissions, counts, job rows and origin lookup assertions stay intact.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "queue v27 -> v28"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/redesign-p2a-version-expectations.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java#everySupportedVersionMigratesToV24WithoutChangingSavedRows",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java#unreleasedQueueV23GainsMainAdmissionFenceWithoutLosingCounts",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java#releasedVersionUpgradesToV27KeepingAdmissionCount",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java#migratesV11ToCurrentPreservingQueueAndIdentityRowsAndRefusesTheNextVersion",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java#importsResolveOnlyTheirOperationAndNormalizedRootAfterReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java#freshAndV26QueuesHaveDurableOriginStorage"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/AdmissionSourceWitnessTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "signature",
        "instrumentation"
      ],
      "whatMoved": "The v5 operations fixture removes only the new v7 columns before rewinding, so it remains an actual v5 shape. AdmissionSourceWitnessTest passes the new nullable operation-key context to the hashing observer; it also supports the old private signature for before-state preservation. Its exact witness/component and before/after-mutation assertions are unchanged. The interrupted-quarantine parameterized case name now starts with its method name so the evidence selector resolves; only runner metadata changes. Schema-version expectations are accounted for separately in the New entries under decisions R-A and R-I.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-p2a",
          "scenario": "M7",
          "quote": "Each changed store (operations, queue) gets its own transactional, versioned migration with rollback and mixed-upgrade recovery; older binaries refuse the newer stores before writing; old rows stay unversioned and never gain durable permission by repair",
          "location": "task redesign-p2a request",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p2a request",
          "quote": "P2a hooks are behaviour-neutral (they return today's verdict and change nothing)"
        }
      ]
    }
  ]
}
```

## Verification commands

Run one at a time, in the background. Whole-class selectors cover parameterized methods whose
custom case names cannot be selected reliably. The selected coordinator methods have no @DisplayName. The watched-reindex parameterized case now prefixes its case name with the method name; only runner metadata changes.

Repair (assertion fail-before required):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-p2a-repair.json \
  --before a42d2cbed2cc46990f7872bef8715cd07e26eb80 \
  --check modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#openImportKeepsCompletedChildEvidenceThroughRetentionAndRestart \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/ImportApprovalMigrationTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ImportOriginBindingMigrationTest.java \
  --check modules/app-engine/src/test/java/io/justsearch/app/engine/ImportStoreMixedUpgradeTest.java
```

Version expectations (decisions R-A: "operations v6 -> v7" and R-I: "queue v27 -> v28",
location "task redesign-p2a request"; assertion fail-before required):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-p2a-version-expectations.json \
  --before a42d2cbed2cc46990f7872bef8715cd07e26eb80 \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPendingTest.java#frozenV4MigrationRollsBackAndOnlyFutureTransitionsAcquireDeliveryObligation \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryPolicyTest.java#v3MigrationIsAtomicPreservesPreparedDataAndDoesNotInventHistoricalVisibility \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationPreparationStoreTest.java#v2MigrationPreservesSequenceAndRollbackRestoresOldSchema \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreStartupContentionTest.java#wrappedBusyAtSchemaCommitRollsBackAndRetriesWithoutQuarantine \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java#RF55V5SemanticMigrationAdvancesVersionAndPreservesExistingRowsAndSequence \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java#freshStoreHasIndependentVersionAndSingletonFenceAndReopens \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java#interruptedQuarantineCannotResetTheHistoryFence \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java#everySupportedVersionMigratesToV24WithoutChangingSavedRows \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java#unreleasedQueueV23GainsMainAdmissionFenceWithoutLosingCounts \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java#releasedVersionUpgradesToV27KeepingAdmissionCount \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java#migratesV11ToCurrentPreservingQueueAndIdentityRowsAndRefusesTheNextVersion \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java#importsResolveOnlyTheirOperationAndNormalizedRootAfterReopen \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java#freshAndV26QueuesHaveDurableOriginStorage
```

The Windows file-lock version expectation in SqliteOperationStoreStartupContentionTest remains
platform-conditional; Linux runs do not verify it. The selected cross-platform contention check
covers the same final schema version. Every selected method has no @DisplayName, and parameterized
case names resolve by method name.

Primitive (new API, no fail-before):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-p2a-primitive.json \
  --check modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/OriginApprovalEvaluatorTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/StrictSourceOriginLoadingTest.java \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/ImportApprovalStatePersistenceTest.java \
  --check modules/app-engine/src/test/java/io/justsearch/app/engine/OriginApprovalExpiryTest.java
```

Preservation (also run the corresponding Gradle selection in an isolated shared clone of the base).
The whole-class selections for SourceOriginLookupTest, SourceOriginMigrationTest and
SqliteOperationStoreTest also include the changed version expectations, covered by the New entries
above. Their other assertions remain preservation checks; the base preservation run uses its
original version expectations, while the separate version run above overlays the new expectations:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-p2a-preservation.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/AdmissionSourceWitnessTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RecordedWalkReceiptValidationTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RecordedWalkIssuedClaimsTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RecordedWalkSealTest.java \
  --check modules/app-services/src/test/java/io/justsearch/app/services/registry/executor/RecordedIngestRecoveryDecisionTest.java \
  --check modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTest.java \
  --check modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#dispatchesAcceptedWatchedReindexThroughMembershipFencedProducer \
  --check modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#gatedImportPublishesItsParentOriginOnAllRowsIncludingReopenedReplay
```

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:indexer-worker:test :modules:app-observability:test :modules:app-services:test :modules:app-engine:test :modules:app-api:test :modules:worker-services:test -PskipWebBuild=true --continue
node scripts/governance/run.mjs --gate test-intent --mode gate
env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced
```

The installed application, UI and activation scenarios belong to P2b+c. Local checks touch only
owned temporary SQLite files and build/test directories, plus the shared Gradle dependency cache
and its admission slots. They do not start the dev stack, use a GPU, or touch ~/.justsearch.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "d39abd9d-eeb9-425d-bc72-6e89fca78097",
  "verdict": "accept",
  "digest": "sha256:37eec189dd0238aeca5884cacc9dd534e92bef1b40f9a1d0315fe3c5e84bf7b9",
  "note": "Claude acceptor on 28f5ad3faf: all seven entries accept (RE6, M7, M28 repaired; P12 primitive; version pins 6->7 and 27->28 New with fail-before; Adaptation); evidence by run-evidence validated. Behaviour: Claude verifier 23db5bbe on 799fdb505c (R-E, migrations, rollback, mixed upgrade, older-binary refusal, evaluator, inert hooks; 13,844 cases 0 failures)."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a1abe4-27ae-7891-92e8-a52087334874",
  "verdict": "accept",
  "digest": "sha256:37eec189dd0238aeca5884cacc9dd534e92bef1b40f9a1d0315fe3c5e84bf7b9",
  "note": "Codex auditor on 9603811766 (PR #1150): all seven entries accept; four evidence files validate; no must-fix."
}
```
