---
schema: test-intent.v1
task: redesign-p1b-i
author-role: builder
author-session: builder-redesign-p1b-i-20261104
---

# Test-intent entries

P1b-i implements P1b-1, P1b-5 and P1b-6. Owner changes, unchanged re-admission,
precedence, moves and source-wide convergence remain P1b-ii. Help files, agent history
and pre-upgrade roots carry no origin_id until P3. No new origin records are fabricated.

Linux fixtures control mountinfo, UUID inventories and source-device resolution. The snapshot
is captured lazily at the first publication and validated once before use. Every publication
in that batch uses the same immutable evidence; a detected topology change invalidates the
batch's storage evidence. No per-source filesystem calls or directory leases are added.
Windows/macOS dispatch is not_observed; actual Windows execution requires CI.

Independent execution evidence and acceptance are intentionally not written by this builder.
The repair checks compile against P1a without a compatibility patch. Reflection exposes absent
production APIs as assertion failures on P1a; publication assertions also inspect committed rows.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java",
        "modules/indexing/src/test/java/io/justsearch/indexing/SourceEnvelopeUnknownOriginTest.java"
      ],
      "class": "New",
      "behaviour": "P1b-1: normal watched/import/exact-file and pre-upgrade admissions publish versioned origin/storage on target and active lexical parents and all declared chunks, surviving enrichment, reopen and parent removal. Existing-parent regeneration copies all five stored keywords from one throwing read; unreadable evidence prevents chunk replacement. Import lookups bind operation plus normalized root; watched lookups bind declared root; both compare the queue's normalized keys, including Windows case folding; absent declarations create no records. Missing origin decodes independently of observed storage.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "P1b-1 Envelope on every normal publication: watched, import and exact-file admissions, short and chunked files, active lexical projection during a candidate build, chunk regeneration through the existing-parent path; chunks keep their own copy when the parent is removed."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "Publication writes origin_id only when the admission resolves to an existing source_origin record. Otherwise the envelope is still written (version, storage as observed) with origin_id absent; it decodes as \"origin unknown\", distinct from legacy (no envelope) and from known."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "lookup only: by the admission's operation key and normalized root for imports, by declared root for watched admissions; no schema change, no new table or index, queue schema stays v27"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "Never cut validation at trust boundaries, error handling that prevents data loss, security or accessibility."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1b-i.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java#normalPublicationCarriesEnvelopeToTargetActiveAndEveryChunk",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java#regeneratedChunksCopyTheExistingParentsCompleteEnvelope",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java#unreadableParentEnvelopeCannotDeleteOrPublishReplacementChunks",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java#importsResolveOnlyTheirOperationAndNormalizedRootAfterReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java#watchedAdmissionsUseDeclaredRootAndMissingRecordsStayAbsent",
        "modules/indexing/src/test/java/io/justsearch/indexing/SourceEnvelopeUnknownOriginTest.java#versionedEnvelopeWithoutOriginIsUnknownOriginRatherThanLegacy"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java"
      ],
      "class": "New",
      "behaviour": "P1b-5/6: controlled mountinfo and UUID inventories yield effective mount plus UUID:type:filesystem-root identity, distinguishing bind roots. Missing, ambiguous, malformed or unreadable evidence yields unknown and never ancestor storage. One batch takes two mount-table reads (snapshot and publication validation), one UUID inventory, and one block-device lookup per mount, regardless of source count. Detected topology changes make the batch unknown. Portable observation produces not_observed.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "P1b-5 Linux batch observation: effective mount and UUID+type+filesystem-root key from controlled mountinfo/UUID fixtures; bind-mounted subtrees of one UUID get different keys; UUID-less, unreadable, malformed and equal-depth stacked mounts give unknown, never the ancestor's storage."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "P1b-6 One snapshot per batch: mount-table reads for a batch of one file and of many files are equal; no per-source lease or UUID discovery; a topology change between observation and publication yields refreshed coherent evidence or unknown, never stale known."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "Windows and macOS = not_observed"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1b-i.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#effectiveMountAndBindSubtreesHaveIndependentKeys",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#uncertainEffectiveMountNeverFallsBackToAncestor",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#mountReadsAndUuidDiscoveryAreConstantForTheWholeBatch",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#changedOrUnreadableTopologyBeforePublicationCannotPublishStaleKnown",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#ambiguousAndMalformedUuidAliasesCannotIdentifyADevice",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#nonLinuxObservationDoesNotInventStorage"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java"
      ],
      "class": "New",
      "behaviour": "The amended decoder check distinguishes absent origin from legacy while retaining rejection of malformed origin IDs and malformed storage. Its prior assumption that missing origin_id forbids known storage is superseded by the settled admission decision; every other malformed-field case remains.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "Publication writes origin_id only when the admission resolves to an existing source_origin record. Otherwise the envelope is still written (version, storage as observed) with origin_id absent; it decodes as \"origin unknown\", distinct from legacy (no envelope) and from known."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1b-i.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#malformedEnvelopeNeverDecodesKnown"
      ],
      "repaired": true
    }
  ]
}
```

## Verification commands

Run serially through js-gradle-slot. The evidence runner handles its own before-state checkout;
the builder's manual before experiment uses a shared clone in $TMPDIR/before, with its own build folders.

Repair evidence (every selected method must fail by assertion before, and pass after):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-p1b-i.json \
  --before a07eb770d626ed707748b9ce0d42ddda29816290 \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java#normalPublicationCarriesEnvelopeToTargetActiveAndEveryChunk \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java#regeneratedChunksCopyTheExistingParentsCompleteEnvelope \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java#unreadableParentEnvelopeCannotDeleteOrPublishReplacementChunks \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java#importsResolveOnlyTheirOperationAndNormalizedRootAfterReopen \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java#watchedAdmissionsUseDeclaredRootAndMissingRecordsStayAbsent \
  --check modules/indexing/src/test/java/io/justsearch/indexing/SourceEnvelopeUnknownOriginTest.java#versionedEnvelopeWithoutOriginIsUnknownOriginRatherThanLegacy \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#effectiveMountAndBindSubtreesHaveIndependentKeys \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#uncertainEffectiveMountNeverFallsBackToAncestor \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#mountReadsAndUuidDiscoveryAreConstantForTheWholeBatch \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#changedOrUnreadableTopologyBeforePublicationCannotPublishStaleKnown \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#ambiguousAndMalformedUuidAliasesCannotIdentifyADevice \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#nonLinuxObservationDoesNotInventStorage \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#malformedEnvelopeNeverDecodesKnown
```

Preservation evidence (run the same selected checks in the before clone and candidate; the
runner's --before mode requires failures, so the preservation evidence command has no --before):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-p1b-i-preservation.json \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#catalogKeepsEnvelopeThroughEnrichmentAndMetadataPatches \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#envelopeAdditionsUseBothExistingParityCertificationPaths \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#populatedLegacyIndexOpensAndStampsNewFingerprintOnlyAfterSuccessfulCommit \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java#supplementaryChunksAreExactAndPendingAfterFreshWriteRegenerationAndReopen \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java#canonicalZeroChunkDeclarationsQualifyFreshAndReopenedLibraries \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriterTest.java#invalidPlanIsRefusedBeforeParentAndLexicalPublication \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceWitnessCertificationTest.java#unmodifiedShortcutReadsContentOnlyOnce
```

The selected chunk checks cover exact content/offsets, split boundaries, declarations and lexical
preservation. The broader before-clone and module runs also execute the chunk collection-inheritance
and SPLADE checks; their custom display names are unsuitable for evidence-runner method identifiers. The catalog preservation check covers stored text, lexical postings,
collection scope and enrichment/RMW/reopen. The compatibility check exercises a populated index,
fixed query results and fingerprint commit ordering. The shortcut check counts per-source content
reads; the observation fixture counts batch-only mount/UUID/source-device discovery.
The orchestrator owns dev-stack publication/restart and isolated bind-mount checks.

## Acceptance records
