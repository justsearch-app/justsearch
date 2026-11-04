---
schema: test-intent.v1
task: redesign-p1a
author-role: builder
author-session: builder-redesign-p1a-20261104
---

# Test-intent entries

Evidence and acceptance records are intentionally left to the independent verifier.
The before-signatures patch adds only the watched-origin binder signature to main;
its empty body cannot persist an origin or change declaration behaviour.
The builder ran these checks in a shared before clone and observed assertion failures
for every listed repair check; no evidence JSON was written.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/WatchedSourceOriginTest.java"
      ],
      "class": "New",
      "behaviour": "P1a-1: accepted partitioned imports and watched declarations retain normalized scope and complete recorded approval evidence through reopen and history expiry, including AUTO, family and session approvals. The direct watched route retains caller-declared attribution with absent basis and operation identifiers. Fresh queues and v26 upgrade acquire the table; injected v27 migration failure rolls back table and version without losing jobs. Existing assertions remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Accepting a partitioned import with request exclusions, and a watched declaration, writes a source_origin row with kind, normalized root, partitions, exclusions and the approval inputs (authorization basis, recorded transport, source tier, producing operation id, approval class durable/session), for AUTO, family-grant and session approval.",
          "location": "task redesign-p1a request"
        },
        {
          "kind": "owner-words",
          "quote": "It survives reopen and operation/queue history expiry.",
          "location": "task redesign-p1a request"
        },
        {
          "kind": "owner-words",
          "quote": "Queue migration: fresh creation, v26 -> v27 upgrade, injected rollback leaves v26 intact (existing migration hook).",
          "location": "task redesign-p1a request"
        },
        {
          "kind": "owner-words",
          "quote": "The direct route records what is actually known and nothing invented: no authorization basis (absent, never AUTO and never the caller-supplied grant header), producing operation id absent, transport and client kind as the request context recorded them, marked as caller-declared, and approval class durable.",
          "location": "task redesign-p1a request"
        },
        {
          "kind": "owner-words",
          "quote": "A record with an absent basis must be distinguishable from an AUTO one in the stored row and in tests.",
          "location": "task redesign-p1a request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1a-origins.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java#freshAndV26QueuesHaveDurableOriginStorage",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java#failedV27MigrationRollsBackTableAndVersion",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#acceptedPartitionedImportsRetainOriginAndApprovalAfterHistoryExpiry",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/WatchedSourceOriginTest.java#acceptedWatchedDeclarationRetainsOnlyItsActualApprovalInputs"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java"
      ],
      "class": "New",
      "behaviour": "P1a-2/3: five optional stored-only envelope keywords survive enrichment and metadata RMW and reopen without lexical/content/scope changes; malformed or missing envelopes never decode as known. Both parity paths certify only the additions and refuse changed existing field/model and uncertified inputs. A populated legacy-shape index opens, keeps its original fingerprint after a rejected commit, stamps the candidate only on successful commit, and reopens with its content and identity intact.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The envelope fields (origin_id, storage_status known|unknown|not_observed, storage_mount, storage_key, envelope version) are optional stored keywords (no roles, no doc values), known to FieldMapper, so enrichment RMW and metadata patches keep them (main drops uncatalogued fields, FieldMapper ~164).",
          "location": "task redesign-p1a request"
        },
        {
          "kind": "owner-words",
          "quote": "Missing or malformed envelope decodes as legacy or unknown, never as known.",
          "location": "task redesign-p1a request"
        },
        {
          "kind": "owner-words",
          "quote": "A populated index written by main opens without rebuild through CompatibleFieldAdditions and the existing parity certification; model and retrieval identities unchanged; the new fingerprint is stamped only after a successful commit; an altered existing field, model or uncertified rendering is still refused (precedent SparsePayloadPreservationTest ~283).",
          "location": "task redesign-p1a request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1a-envelope.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#catalogKeepsEnvelopeThroughEnrichmentAndMetadataPatches",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#envelopeAdditionsUseBothExistingParityCertificationPaths",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#malformedEnvelopeNeverDecodesKnown",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#populatedLegacyIndexOpensAndStampsNewFingerprintOnlyAfterSuccessfulCommit"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleV24MigrationEvidenceTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Every supported queue layout migrates atomically to the current target, preserving rows, identity, counts and admission fences; failed migrations roll back and older binaries refuse newer queues.",
      "whySurvives": "The same target-version assertions now name v27. The saved-row, count, crash/rollback, refusal and resource predicates remain. The released-version check has a current method/display name; no test is skipped or weakened. The optional million-row evidence lane is not part of the assigned acceptance command and is not claimed executed.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "source_origin lives in the queue database (SqliteSchema version 26 -> 27, next step in SqliteQueueMigrationOps; backup before migration as today).",
          "location": "task redesign-p1a request"
        }
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/DoubleFieldIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingWindows1252ExtractionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Exclude the five P1a optional additions from both legacy-catalog fixtures, alongside their existing splade_payload exclusion. The original fixed fingerprints and all assertions remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "model and retrieval identities unchanged",
          "location": "task redesign-p1a request"
        }
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Every catalog field is accounted for in the result-schema fixture or the explicit internal-field set.",
      "whySurvives": "The five provenance envelope fields join the internal set. They are internal stored metadata and do not change result projection. Existing schema/fixture assertions remain. This catalog-reading test is rerun with :modules:app-api:test --rerun.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "If a test reads the field catalog (for example app-api StatusRecordSchemaTest INTERNAL_FIELDS), update it and run it with --rerun: Gradle's cache can hide catalog-reading tests.",
          "location": "task redesign-p1a request"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineQueuedRootOwnershipTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootRetirementTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The isolated Engine adapter fixtures supply the new origin-store collaborator. Their existing occupied executors, caller work identity/context, bounded queues, retirement fences, deadline/removal ordering, watcher observations and completion assertions remain unchanged; declaration persistence has its own real queue check.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "a source_origin record per accepted import or watched declaration",
          "location": "task redesign-p1a request"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/WatchedRootRiskBoundaryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Observe addAcceptedWatchedRoot, the gated declaration port that carries the accepted operation key. Preserve the exact call counts, collection, normalized path and context matcher, additionally requiring a string operation key; wrap verification in assertDoesNotThrow. All risk, prompt, consent and scope assertions remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The gated path records the full approval inputs from the gate (basis, transport, source tier, producing operation id, approval class).",
          "location": "task redesign-p1a request"
        }
      ]
    }
  ]
}
```

Run-evidence arguments for the verifier (run from the committed candidate):

```bash
node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-p1a-origins.json \
  --before 1194832fcf05b084727186d46066eef992fa16fa \
  --before-patch gates/test-intent/evidence/redesign-p1a-before-signatures.patch \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java#freshAndV26QueuesHaveDurableOriginStorage \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginMigrationTest.java#failedV27MigrationRollsBackTableAndVersion \
  --check modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#acceptedPartitionedImportsRetainOriginAndApprovalAfterHistoryExpiry \
  --check modules/app-engine/src/test/java/io/justsearch/app/engine/WatchedSourceOriginTest.java#acceptedWatchedDeclarationRetainsOnlyItsActualApprovalInputs
```

```bash
node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-p1a-envelope.json \
  --before 1194832fcf05b084727186d46066eef992fa16fa \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#catalogKeepsEnvelopeThroughEnrichmentAndMetadataPatches \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#envelopeAdditionsUseBothExistingParityCertificationPaths \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#malformedEnvelopeNeverDecodesKnown \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#populatedLegacyIndexOpensAndStampsNewFingerprintOnlyAfterSuccessfulCommit
```

The orchestrator performs the real-system main-index open/search/restart check.
The builder did not start the shared dev stack.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a520-6610-7870-946b-a317d50e0424",
  "verdict": "accept",
  "digest": "sha256:8e0a527eb790252ed68b053edffeffa5fd9f52bd367815877b60d8ff6c070331",
  "note": "Codex verifier-1 on 4276f76507: all 14 P1a cases fail before for the intended reason and pass after without skips; E1-E7 accept. Live upgrade check assigned to orchestrator."
}
```
