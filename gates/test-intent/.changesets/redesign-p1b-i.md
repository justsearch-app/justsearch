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
is captured lazily at the first publication and compared with the mount table immediately before
each source's writes. Changed or unreadable topology triggers one re-observation; failed evidence
is unknown. Parent, active lexical projection and chunks of one source use the same envelope.
With unchanged topology, UUID inventory and device resolution are independent of source count;
mount reads are bounded by capture plus one comparison per source. No per-source lease or
directory observation is added. A topology change between that comparison and the same thread's
Lucene write remains undetectable without a kernel mount lock; the next admission re-observes.
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
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceStoragePublicationTest.java"
      ],
      "class": "New",
      "behaviour": "P1b-5/6: controlled mountinfo and UUID inventories yield effective mount plus UUID:type:filesystem-root identity, distinguishing bind roots. Missing, ambiguous, malformed or unreadable evidence yields unknown and never ancestor storage. UUID inventory and device resolution do not grow with source count while topology is unchanged; mount reads are bounded by batch capture plus one comparison per source, with no per-source identity lease or directory observation. Mount replacement, removal, unreadability and failed UUID re-observation before the first source and between sources commit refreshed evidence or unknown, never stale known. Real JobBatchWriter publication checks inspect committed target/active parents and every chunk, requiring one coherent envelope per source, including later sources after re-observation. Device evidence case table rows 1-7 run through the real extractor/writer and committed target/active parents and chunks: matching non-anonymous devices are known; contradictory devices are unknown; anonymous btrfs uses its block source; anonymous non-block sources are unknown; non-anonymous non-block sources use the unique mount-table UUID; unreadable sources and duplicate UUID aliases are unknown. Portable observation produces not_observed.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request, Decision: self-consistent device evidence",
          "quote": "for a non-anonymous mount-table device, a source path resolving to a different block device is contradictory evidence and gives unknown, never the other device's UUID; anonymous devices (btrfs) keep resolving through the source block device; every other existing case is unchanged."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "P1b-5 Linux batch observation: effective mount and UUID+type+filesystem-root key from controlled mountinfo/UUID fixtures; bind-mounted subtrees of one UUID get different keys; UUID-less, unreadable, malformed and equal-depth stacked mounts give unknown, never the ancestor's storage."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request, Decision: freshness at each source's publication",
          "quote": "Immediately before each source's writes (parent, active lexical projection, chunks), re-read the mount table once and compare it with the batch snapshot. Unchanged: use the snapshot. Changed or unreadable: re-observe once (a fresh snapshot, including the UUID inventory) and use it for this and the later sources of the batch; if re-observation fails, unknown. Every write of one source uses the same envelope."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-i request",
          "quote": "Windows and macOS = not_observed"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1b-i-storage-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceStoragePublicationTest.java#selfConsistentDeviceEvidenceDeterminesCommittedStorage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#effectiveMountAndBindSubtreesHaveIndependentKeys",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#uncertainEffectiveMountNeverFallsBackToAncestor",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#uuidAndDeviceDiscoveryDoNotGrowWithUnchangedSourceCount",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#changedOrUnreadableTopologyBeforeAndBetweenSourcesCannotReturnStaleKnown",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#ambiguousAndMalformedUuidAliasesCannotIdentifyADevice",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#nonLinuxObservationDoesNotInventStorage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceStoragePublicationTest.java#topologyChangesNeverCommitStaleKnownStorage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceStoragePublicationTest.java#unchangedTopologyKeepsDiscoveryIndependentOfSourceCount"
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

Run serially through js-gradle-slot. Manual before experiments use shared clones under $TMPDIR,
with separate build folders. Run the evidence command from a temporary shared candidate clone
with the candidate's changed files copied in: its internal before checkout then writes only that
temporary clone's Git metadata, leaving the source repository untouched. Copy the produced
evidence back. The new storage entry uses the new evidence file; accepted entries 1 and 3 retain
their original evidence.

Repair evidence (every selected method must fail by assertion before, and pass after):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-p1b-i-storage-repair.json \
  --before a07eb770d626ed707748b9ce0d42ddda29816290 \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java#normalPublicationCarriesEnvelopeToTargetActiveAndEveryChunk \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java#regeneratedChunksCopyTheExistingParentsCompleteEnvelope \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceEnvelopePublicationTest.java#unreadableParentEnvelopeCannotDeleteOrPublishReplacementChunks \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java#importsResolveOnlyTheirOperationAndNormalizedRootAfterReopen \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SourceOriginLookupTest.java#watchedAdmissionsUseDeclaredRootAndMissingRecordsStayAbsent \
  --check modules/indexing/src/test/java/io/justsearch/indexing/SourceEnvelopeUnknownOriginTest.java#versionedEnvelopeWithoutOriginIsUnknownOriginRatherThanLegacy \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#effectiveMountAndBindSubtreesHaveIndependentKeys \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#uncertainEffectiveMountNeverFallsBackToAncestor \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#uuidAndDeviceDiscoveryDoNotGrowWithUnchangedSourceCount \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#changedOrUnreadableTopologyBeforeAndBetweenSourcesCannotReturnStaleKnown \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#ambiguousAndMalformedUuidAliasesCannotIdentifyADevice \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/StorageObservationSnapshotTest.java#nonLinuxObservationDoesNotInventStorage \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceStoragePublicationTest.java#selfConsistentDeviceEvidenceDeterminesCommittedStorage \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceStoragePublicationTest.java#topologyChangesNeverCommitStaleKnownStorage \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceStoragePublicationTest.java#unchangedTopologyKeepsDiscoveryIndependentOfSourceCount \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopePreservationTest.java#malformedEnvelopeNeverDecodesKnown
```

The added topology check was also overlaid onto pre-repair HEAD 68ed4f26b1 in the manual
shared before clone. Its four between-source variants fail the stale-known assertion; its four
before-first-source variants pass. The repair evidence command above uses the P1a base, where
committed rows lack the envelope and fail that assertion instead.

The device-evidence publication check covers case-table rows 1-7 through the real source-device
helper, extractor and writer, inspecting committed target/active parents and chunks. Overlay it
onto pre-repair HEAD 4fcfb0381d and the P1a base in separate shared clones, then run:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:worker-services:test \
  --tests '*SourceStoragePublicationTest.selfConsistentDeviceEvidenceDeterminesCommittedStorage' \
  -PskipWebBuild=true --console=plain
```

Row 2 must fail on pre-repair HEAD because the committed key is aaaa-1111:ext4:/ rather than
absent, and on P1a because the committed envelope is absent. Rows 1, 3 and 5-7 preserve their
pre-repair outcomes. Row 4 additionally proves that an anonymous non-block source cannot use
an inventory entry for its anonymous mount-table device.

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

Required broader checks (one invocation at a time, in the background):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:indexing:test :modules:worker-core:test :modules:indexer-worker:test \
  :modules:worker-services:test :modules:adapters-lucene:test :modules:app-engine:test \
  -PskipWebBuild=true --continue --console=plain
node scripts/dev/java-prepush.mjs --unreferenced
```

The selected chunk checks cover exact content/offsets, split boundaries, declarations and lexical
preservation. The broader before-clone and module runs also execute the chunk collection-inheritance
and SPLADE checks; their custom display names are unsuitable for evidence-runner method identifiers. The catalog preservation check covers stored text, lexical postings,
collection scope and enrichment/RMW/reopen. The compatibility check exercises a populated index,
fixed query results and fingerprint commit ordering. The shortcut check counts per-source content
reads; storage checks compare UUID/device discovery for one and many sources, bound mount
reads, and forbid per-source identity leases and directory observation.
The orchestrator owns dev-stack publication/restart and isolated bind-mount checks.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a6b3-08d4-7c83-b329-73ecf837833e",
  "verdict": "accept",
  "digest": "sha256:d8b36b4d1e1da4e8155d18531fd33f49f20d988d271041e869b3214484fdff8d",
  "note": "Codex verifier-3 on 93e2d01479: entries 1, 2, 3 accept (New). P1b-1, origin unknown, P1b-6 and preservation pass; earlier stale-storage and contradictory-device defects reproduce on old revisions and pass now. One variation (absent absolute source path, ext4) gives unknown; settled as unreadable evidence by the orchestrator's narrowing decision."
}
```
