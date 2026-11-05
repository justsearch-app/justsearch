---
schema: test-intent.v1
task: redesign-p1b-ii
author-role: builder
author-session: builder-redesign-p1b-ii-20261104
---

# Test-intent entries

P1b-ii implements P1b-2, P1b-3, P1b-4, P1b-7 and P1b-8 on base
7588c01d3822c1d58358c22e28b8f4c52f6ef632 (P1b-i #1133). Queue schema remains v27.
No origin rows are changed: the coordinator supplies the existing child operation's parent key
at lookup. Unchanged publications commit before their skipped freshness receipt, keep content_hash
absent, preserve a deferred ordinary-force obligation, and clear retry_after as the former fast
path did. Current watched declarations come from the existing in-memory WatchedRootsState snapshot at
the coordinator attachment, without per-source declaration-file stats. Standalone queue fixtures
retain the existing declared-collection supplier.
A move retains the former import only inside its root and outside its excluded partitions;
otherwise a containing watch wins or the destination is its own exact-file owner (origin absent).

The checks inspect committed Lucene rows and actual SQLite queue/origin/operation records.
The gated check enters OperationExecutorImpl and RecordedIngestionCoordinator with distinct keys,
then leaves the claim unacknowledged and reopens the databases to replay it. Its grant-containment
provider is bound to the approved isolated fixture root independently of watched declarations;
otherwise the fixture would either acquire a watched owner (W7) or refuse recovery for scope,
neither of which exercises an import's parent/child origin lookup. P2 policy evaluation is unchanged. Initial missing origin
stays unknown; recovery can restore the accepted immutable declaration from retained gate evidence.
The new repair checks use no sleeps, shared dev stack, mount namespace, GPU or external service. Storage publication
fixtures are deterministic. Move storage injects controlled destination observation at the capture boundary and must replace
the old storage key coherently on parent/chunks; Linux evidence cases remain in the
controlled P1b-i checks. Actual Windows/macOS execution remains unverified locally.

Superseded publication already rejects an older issued revision after a second queue session
publishes its successor on the base, so that case
is preservation (including the unchanged path, which has no publication on the base). It is listed
in the preservation block, not claimed as a new repair. Other P1b-3 ownership changes are repairs.
Preservation includes source-wide batch rewrites of legacy chunk sparse postings. Fan-out rows
not covered by the explicit batch sparse prefetch reread their own postings before rewriting.
Preservation reuses P1a catalog/compatibility/content/lexical checks, source-origin migration and
lookup checks, P1b-i normal and active publication/regeneration/enrichment/reopen checks, per-source
freshness, device-evidence, origin-unknown and portable not_observed checks. Queue future-schema
refusal and retrieval/chunk/collection behavior also remain covered by the requested module suites.
Downgrade remains named rather than certified, as in the request. No backfill or sweep is added.

The nested active-projection mock check remains in IndexingLoopTest; the runnable evidence uses
the stronger top-level committed-row projection check because the evidence runner does not select
Gradle nested XML reports by method.

The complete source-probe preservation check includes extraction and the real writer freshness
guard, not only extraction: captures/basic/ctime remain 3/5/3 on both base and candidate,
with one content hash. Source-owner fixtures use the real stale-source resolver.
The unchanged-source probe still verifies one source-content hash and logs its capture/basic/ctime
probe counts for comparison with the base: captures/basic/ctime are 3/5/3 before and 2/4/2 after,
with other probes 3 in both and one content hash in both. Only the handoff and acknowledgement
assertions change.
The unstable-shortcut fixture visits the writer for metadata-only results to reach that same
final guard; its mutation, defer, no-parser and retry-publication assertions remain intact.
The writer's final freshness check replaces the extractor's former pre-acknowledgement check,
so it does not add another per-file freshness observation. Storage retains P1b-i's batch capture
and per-source topology comparison.

The builder leaves execution evidence and independent acceptance to the orchestrator/verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SourceOwnerPublicationTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "P1b-2/3/4/7/8: unchanged admissions publish source-wide provenance without parsing; current deepest watched declarations override imports, and later imports replace approval binding. Moves retain document identity, refresh destination storage, retain import ownership only inside its accepted partition, and preserve independently backed children. Single and batch metadata patches propagate to inheriting chunks. An injected IndexWriter failure after the parent fails the queue publication; source evidence reads unknown origin during disagreement and the next admission converges. A real OperationExecutorImpl gate and RecordedIngestionCoordinator produce distinct parent/child keys, publish the parent origin to committed parent/chunk rows, recover and replay after reopen; missing origin initially remains unknown.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-2 Unchanged admission changes owner without extraction (JobBatchExtractor ~334 early return): re-import unchanged legacy content under another root; parser never called; source-wide origin replaced after reopen."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-3 Ownership precedence and supersession (W7): deepest containing watched root wins over imports; a later explicit import replaces an earlier import owner; owner and approval binding change together; a paused older claim released after its successor never restores the stale owner."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-4 Moves (V6): within an import, outside it (exact file), into another watched root, into a nested watched root inside the former origin; identity preserved, destination storage re-observed; independently backed children keep their own envelope."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-7 Source-wide patch: origin/storage change on a parent and its inheriting chunks in one fenced batch; injected failure after the parent fails that source's publication and the next admission converges it; disagreement reads as the more restrictive state."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-8 Gated import origin. An import admitted through the real recorded-ingestion path (operation gate, then its ingest-child walk operation whose identity names the parent operation key) publishes the parent import's origin_id on the parent and every chunk, also after a reopen and for a replay of that walk."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1b-ii.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SourceOwnerPublicationTest.java#unchangedLegacyReadmissionChangesEveryOwnerAfterReopenWithoutExtraction",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SourceOwnerPublicationTest.java#deepestWatchedOwnerOverridesImportAndLaterImportReplacesEarlierApproval",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SourceOwnerPublicationTest.java#deepestWatchedDeclarationWinsEvenForFreshImportPublication",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SourceOwnerPublicationTest.java#movePreservesIdentityAndReobservesDestinationEnvelopeWithIndependentChildren",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SourceOwnerPublicationTest.java#metadataPatchUpdatesTheWholeSourceAndClearsUnknownEvidence",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SourceOwnerPublicationTest.java#failureAfterParentFailsPublicationReadsRestrictivelyAndNextAdmissionConverges",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#gatedImportPublishesItsParentOriginOnAllRowsIncludingReopenedReplay"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java"
      ],
      "class": "New",
      "behaviour": "Unchanged content still skips parsing, including same/default collection and the inert pre-P3 force key, but now yields a metadata-only publication and defers UNCHANGED acknowledgement until the writer commits. Existing force and source-identity scenarios remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-2 Unchanged admission changes owner without extraction (JobBatchExtractor ~334 early return): re-import unchanged legacy content under another root; parser never called; source-wide origin replaced after reopen."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-7 Source-wide patch: origin/storage change on a parent and its inheriting chunks in one fenced batch; injected failure after the parent fails that source's publication and the next admission converges it; disagreement reads as the more restrictive state."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1b-ii.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java#unforcedPathTakesTheUnchangedBranch",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java#sameCollectionTagStillTakesTheUnchangedBranch",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java#blankClaimCollectionMatchesIndexedDefaultCollection",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java#preP3KeyForTheSameShapeSilentlyFailsToForce"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneLatePublicationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The existing rename/prune latch and injected post-move failure wrap IndexingCoordinator.updateDocumentPaths(old,new,envelope) after the worker now supplies destination provenance. Inputs, latch ordering, failure injection and every assertion remain unchanged."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionFidelityIndexingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingDocumentOpsScalarIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "Existing extraction-fidelity, quality-score and queued-restart fixtures keep their content, chunk, policy, quality and preservation assertions. An unchanged extractor result is now a metadata-only publication, not an empty result or an immediate acknowledgement. Fixtures with a writer send that result through it before the commit journal drains. Historical and forced sources still require an extraction artifact.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-2 Unchanged admission changes owner without extraction (JobBatchExtractor ~334 early return): re-import unchanged legacy content under another root; parser never called; source-wide origin replaced after reopen."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1b-ii.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#newlyIndexedAsciiFirstUtf8SurvivesRestart",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionFidelityIndexingTest.java#ordinaryIngestionRepairsHistoricalExtractionAndStaysCurrentAfterReopening",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionFidelityIndexingTest.java#missingSourceEvidenceRepairsUnchangedContent",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingDocumentOpsScalarIntegrationTest.java#changedFileReplacesScoreAndForcedUnchangedLegacyFileAcquiresOne"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceWitnessCertificationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java"
      ],
      "class": "New",
      "behaviour": "Complete legacy chunks still avoid parsing, splitting and embedding; unchanged source witnesses still hash exactly once and preserve the source-call instrumentation; unchanged active lexical documents retain their vector publication and receive only an envelope metadata patch. UNCHANGED acknowledgements now belong to the writer commit journal. All existing vector, chunk-boundary and hash assertions remain.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-2 Unchanged admission changes owner without extraction (JobBatchExtractor ~334 early return): re-import unchanged legacy content under another root; parser never called; source-wide origin replaced after reopen."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-p1b-ii request",
          "quote": "P1b-1 Envelope on every normal publication: watched, import and exact-file admissions, short and chunked files, active lexical projection during a candidate build, chunk regeneration through the existing-parent path; chunks keep their own copy when the parent is removed."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-p1b-ii.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LegacyChunkBackfillTest.java#completeLegacyScanBackfillsWithoutExtractionOrEmbeddingAndSurvivesReopen",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceWitnessCertificationTest.java#unmodifiedShortcutReadsContentOnlyOnce",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SourceOwnerPublicationTest.java#unchangedReadmissionUpdatesTheServingLexicalProjectionWithoutParsing"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing process helper still reports actual extractions. It counts non-null extraction artifacts, excluding metadata-only publications, and still writes every publication and drains the same commit journal. All inputs, failures, assertions, ordering and restart boundaries are unchanged."
    }
  ]
}
```

Moves through an in-root directory alias reuse the real parent already resolved by the
existing destination presence proof. This adds no per-source filesystem observation; the alias
variant checks destination storage at the committed parent and chunk boundary. Unreadable
ancestor evidence publishes unknown storage.

## Verification commands

B1 repair: the first two OrdinaryScanOriginTest checks pass on base 7588c01d38 and fail
by assertion on pre-repair HEAD 76da02586c (UUIDv4 lookup throws; no committed parent/chunks).
The store-failure guard applies to P1b-ii's resolver: it passes on pre-repair HEAD and must
also pass after repair. The base has no resolver, so that guard is not a base preservation check.
All three must pass after repair. The tests use isolated SQLite/Lucene resources; the real
ScanRoot entry point and production extractor/writer run, with only the sandbox's missing
root-volume witness controlled and a parser adapter reading the actual fixture file.

Use separate shared clones inside $TMPDIR, with the same OrdinaryScanOriginTest.java copied
into each test tree. Each checkout uses its own default build directories. Run the following
from each of the base, pre-repair and repaired trees, in the background, one at a time:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:app-engine:test \
  --tests '*OrdinaryScanOriginTest.missingOrdinaryV4ScanOperationStaysUnknownInsteadOfThrowing' \
  --tests '*OrdinaryScanOriginTest.actualOrdinaryScanPublishesParentAndChunksWithUnknownOrigin' \
  -PskipWebBuild=true
```

Focused P1b-ii and P1b-i provenance and preservation checks, including the recorded-operation
failure guard (whole classes also select nested/parameterized cases):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  :modules:adapters-lucene:test \
  --tests 'io.justsearch.adapters.lucene.runtime.SourceEnvelopePreservationTest' \
  --tests 'io.justsearch.adapters.lucene.runtime.SourceEnvelopeSparsePreservationTest' \
  :modules:app-engine:test \
  --tests 'io.justsearch.app.engine.EngineForgetMovePublicationTest' \
  --tests 'io.justsearch.app.engine.OrdinaryScanOriginTest' \
  --tests 'io.justsearch.app.engine.RecordedIngestionCoordinatorTest' \
  :modules:indexer-worker:test \
  --tests 'io.justsearch.indexerworker.loop.AsciiFirstUtf8QueuedRestartTest' \
  --tests 'io.justsearch.indexerworker.loop.RestoredMtimeQueuedRestartTest' \
  --tests 'io.justsearch.indexerworker.loop.SourceOwnerPublicationTest' \
  --tests 'io.justsearch.indexerworker.queue.JobQueueMigrationTest' \
  --tests 'io.justsearch.indexerworker.queue.SourceOriginLookupTest' \
  --tests 'io.justsearch.indexerworker.queue.SourceOriginMigrationTest' \
  --tests 'io.justsearch.indexerworker.server.Release030ReplayRetentionTest' \
  --tests 'io.justsearch.indexerworker.services.IndexedSourceMaintenanceTest' \
  --tests 'io.justsearch.indexerworker.services.PruneLatePublicationTest' \
  :modules:indexing:test \
  --tests 'io.justsearch.indexing.SourceEnvelopeUnknownOriginTest' \
  :modules:worker-services:test \
  --tests 'io.justsearch.indexerworker.loop.ExtractionFidelityIndexingTest' \
  --tests 'io.justsearch.indexerworker.loop.IndexingDocumentOpsScalarIntegrationTest' \
  --tests 'io.justsearch.indexerworker.loop.IndexingLoopTest' \
  --tests 'io.justsearch.indexerworker.loop.JobBatchExtractorForcedPathTest' \
  --tests 'io.justsearch.indexerworker.loop.LegacyChunkBackfillTest' \
  --tests 'io.justsearch.indexerworker.loop.SourceEnvelopePublicationTest' \
  --tests 'io.justsearch.indexerworker.loop.SourceStoragePublicationTest' \
  --tests 'io.justsearch.indexerworker.loop.SourceWitnessCertificationTest' \
  --tests 'io.justsearch.indexerworker.rag.ChunkDocumentWriterTest' \
  --tests 'io.justsearch.indexerworker.services.StorageObservationSnapshotTest' \
  -PskipWebBuild=true --continue
```

The resolver has one invocation, SqliteJobQueue.sourceOriginId, used by JobBatchWriter's
publication envelope for normal and metadata-only publications. A null scan id skips it;
ordinary UUIDv4 and older opaque scan ids retain the original key for the existing origin
lookup. Canonical UUIDv7 keys still query the operation store, whose failures propagate.
Moves use movedSourceOriginId without invoking this resolver. No other resolver caller exists.

Module suites and repository gates (background, sequential):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:indexing:test :modules:worker-core:test :modules:indexer-worker:test :modules:worker-services:test :modules:adapters-lucene:test :modules:app-engine:test -PskipWebBuild=true --continue
env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced
node scripts/governance/run.mjs --gate test-intent --mode gate
```

OrdinaryScanOriginTest is an isolated new test source (exempt from test-intent entries).
Its three checks are missingOrdinaryV4ScanOperationStaysUnknownInsteadOfThrowing,
actualOrdinaryScanPublishesParentAndChunksWithUnknownOrigin, and
recordedOperationLookupFailureStillSurfaces. The B1 repair assignment supplies their
expectations: ordinary scans keep watched-root lookup or origin unknown; failures for a
recorded operation still surface. Execution evidence JSON and acceptance records are left
to the orchestrator/verifier, per the B1 assignment.

## Acceptance records
