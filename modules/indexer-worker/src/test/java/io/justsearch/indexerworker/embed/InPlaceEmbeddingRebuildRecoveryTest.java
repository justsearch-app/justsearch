/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.EmbeddingProviderLifecycle;
import io.justsearch.indexerworker.loop.ops.EmbeddingRecoveryOps;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 726 T1 — end-to-end regression for the in-place embedding backfill recovery path, with NO
 * mocked ECC / IndexCountOps / JobQueue / persistence.
 *
 * <p>Reproduces the 0.2.0 sandbox finding A.1: a populated index whose embedding fingerprint was
 * never stamped lands {@code BLOCKED_LEGACY} after a restart, and — with a real job queue holding a
 * job in {@code PROCESSING} (the exact hang shape) — must still recover: refresh → BLOCKED_LEGACY →
 * recovery re-marks the unknown-provenance vectors PENDING and enters REBUILDING → backfill drives
 * pending to 0 → certification (queue-independent, two confirming reads) stamps the fingerprint →
 * close/reopen → a fresh ECC reads the stored fingerprint → COMPATIBLE and {@code
 * allowQueryEmbeddings()==true}.
 *
 * <p>The only thing not real is the embedding compute itself (the vectors are simulated by marking
 * the re-marked docs COMPLETED, as the backfill would). ECC, counts, the SQLite job queue, and the
 * Lucene commit/overlay persistence are all real.
 */
class InPlaceEmbeddingRebuildRecoveryTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final String FP = "in-place-recovery-fp-sha256";
  private static final CommitMetadataValidator PERMISSIVE = metadata -> {};

  /**
   * A dense vector to accompany every {@code embedding_status=COMPLETED} write. The write-time
   * contract (tempdoc 798) rejects a COMPLETED that carries no vector, and an embedded doc in this
   * test's fixture is meant to be genuinely embedded — the vector's *provenance* is what these
   * tests probe, not its absence.
   */
  private static final float[] VEC = vec();
  private static final org.slf4j.Logger LOG =
      LoggerFactory.getLogger(InPlaceEmbeddingRebuildRecoveryTest.class);

  @AfterEach
  void clearFingerprint() {
    EmbeddingFingerprint.invalidate();
  }

  @Test
  @DisplayName(
      "in-place backfill path: BLOCKED_LEGACY (no fingerprint) recovers to COMPATIBLE across restart"
          + " even with a job stuck in PROCESSING")
  void inPlaceBackfillRecoversDespiteNonIdleQueue() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("in-place-recovery");
    int docCount = 3;

    // Build 1: a legacy generation — parent docs marked embedding COMPLETED, committed WITHOUT a
    // stamped fingerprint (the pre-fix in-place path never certified, so nothing stamped it).
    Supplier<CommitMetadataSource> noStamp =
        EmbeddingMetadataOverlay.createSupplier(Optional::empty);
    try (var r1 = openRuntime(dir, noStamp)) {
      for (int i = 0; i < docCount; i++) {
        r1.indexingCoordinator()
            .indexSingle(
                new IndexDocument(
                    Map.of(
                        SchemaFields.DOC_ID, "d" + i,
                        SchemaFields.DOC_UID, "d" + i + "#0",
                        SchemaFields.VECTOR, VEC,
                        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED)));
      }
      r1.commitOps().commitAndTrack();
    }

    // Build 2: "restart". The overlay is late-bound to the ECC exactly as production wires it
    // (KnowledgeServer.embeddingFingerprintSupplier -> ecc::fingerprintToStamp).
    AtomicReference<Supplier<Optional<String>>> fpHolder =
        new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> stamping =
        EmbeddingMetadataOverlay.createSupplier(() -> fpHolder.get().get());

    try (var r2 = openRuntime(dir, stamping);
        SqliteJobQueue jobQueue = new SqliteJobQueue(dir.resolve("jobs.db"))) {
      jobQueue.open();
      // A real job stuck in PROCESSING keeps the global queue non-idle — the exact sandbox hang.
      Path stuck = Files.createTempFile("stuck", ".txt");
      jobQueue.enqueue(List.of(stuck));
      jobQueue.pollPending(1); // -> PROCESSING; never completed
      assertTrue(jobQueue.queueDepth() > 0, "job queue must be non-idle (job left in PROCESSING)");

      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort,
              () -> docCountOrThrow(r2),
              () -> completedEmbeddingsOrThrow(r2));
      ecc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          ecc.state(),
          "populated index with no stored fingerprint must land BLOCKED_LEGACY after restart");
      fpHolder.set(ecc::fingerprintToStamp);

      // Recovery (F3): re-mark the unknown-provenance COMPLETED docs PENDING, then enter REBUILDING.
      int remarked =
          EmbeddingRecoveryOps.remarkEmbeddedParentDocsPending(
              r2.documentFieldOps(), r2.indexingCoordinator(), 1000, LOG);
      assertEquals(docCount, remarked, "all COMPLETED docs must be re-marked PENDING for re-embed");
      // Commit so the re-marked state is durably visible to count/query reads (the real backfill
      // commits periodically). No fingerprint is stamped: fingerprintToStamp() is empty while
      // REBUILDING is not yet complete.
      r2.commitOps().commitAndTrack();
      r2.commitOps().maybeRefreshBlocking(); // make the commit visible to count/query reads
      assertTrue(
          ecc.maybeAutoStartRebuildForBlockedLegacy(r2.indexCountOps().docCount()),
          "recovery must auto-start REBUILDING regardless of completed/pending distribution");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      assertEquals(
          docCount,
          r2.indexCountOps()
              .countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING),
          "after re-mark, every parent doc is PENDING");

      // Simulate the backfill embedding all PENDING docs (marks them COMPLETED → pending reaches 0).
      simulateBackfillEmbedAllPending(r2);
      r2.commitOps().commitAndTrack();
      r2.commitOps().maybeRefreshBlocking();
      assertEquals(
          0,
          r2.indexCountOps()
              .countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING),
          "backfill drove pending to 0");

      // Finalize via the REAL lifecycle with the REAL non-idle queue + REAL counts + REAL commit.
      var lifecycle =
          new EmbeddingProviderLifecycle(
              null /* signalBus unused by tryFinalizeRebuild */,
              jobQueue,
              r2.indexCountOps(),
              r2.commitOps());
      lifecycle.setEmbeddingCompatController(ecc);

      // First finalize: pending==0 first-of-two confirming reads — must NOT certify yet.
      assertFalse(lifecycle.tryFinalizeRebuild(), "first pending==0 read must not certify (debounce)");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());

      // Second finalize: second consecutive pending==0 read — certifies + stamps despite queue>0.
      assertTrue(
          lifecycle.tryFinalizeRebuild(),
          "second confirming read must certify even though the job queue is non-idle");
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());
      assertTrue(ecc.allowQueryEmbeddings(), "COMPATIBLE must allow query embeddings");
    }

    // Build 3: second "restart" — a fresh ECC over the reopened index must read the stamped
    // fingerprint from commit user-data and resolve COMPATIBLE (durability across restart).
    try (var r3 = openRuntime(dir, EmbeddingMetadataOverlay.createSupplier(Optional::empty))) {
      var ecc3 =
          new EmbeddingCompatibilityController(
              r3::latestCommitUserDataBestEffort,
              () -> docCountOrThrow(r3),
              () -> completedEmbeddingsOrThrow(r3));
      ecc3.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          ecc3.state(),
          "the stamped fingerprint must survive reopen → COMPATIBLE, not revert to BLOCKED_LEGACY");
      assertTrue(ecc3.allowQueryEmbeddings());
    }
  }

  @Test
  @DisplayName(
      "the production rescue seam re-marks COMPLETED docs PENDING before entering REBUILDING —"
          + " a rescue that transitioned without re-marking (PR #185's"
          + " maybeAutoStartRebuildForLegacyUnattestedVectors shape) would find pending==0 already"
          + " true, certify instantly, and stamp a fingerprint over never-re-embedded vectors")
  void rescueSeamReMarksPendingBeforeCertifying_neverFabricatesProvenance() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("in-place-recovery-fabrication");
    int docCount = 3;

    // Build 1: a legacy generation — parent docs marked embedding COMPLETED, committed WITHOUT a
    // stamped fingerprint. Their vector provenance is unknowable.
    Supplier<CommitMetadataSource> noStamp =
        EmbeddingMetadataOverlay.createSupplier(Optional::empty);
    try (var r1 = openRuntime(dir, noStamp)) {
      for (int i = 0; i < docCount; i++) {
        r1.indexingCoordinator()
            .indexSingle(
                new IndexDocument(
                    Map.of(
                        SchemaFields.DOC_ID, "f" + i,
                        SchemaFields.DOC_UID, "f" + i + "#0",
                        SchemaFields.VECTOR, VEC,
                        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED)));
      }
      r1.commitOps().commitAndTrack();
    }

    // Build 2: "restart".
    AtomicReference<Supplier<Optional<String>>> fpHolder =
        new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> stamping =
        EmbeddingMetadataOverlay.createSupplier(() -> fpHolder.get().get());

    try (var r2 = openRuntime(dir, stamping);
        SqliteJobQueue jobQueue = new SqliteJobQueue(dir.resolve("jobs.db"))) {
      jobQueue.open();

      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort,
              () -> docCountOrThrow(r2),
              () -> completedEmbeddingsOrThrow(r2));
      ecc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          ecc.state(),
          "populated index with no stored fingerprint must land BLOCKED_LEGACY after restart");
      fpHolder.set(ecc::fingerprintToStamp);

      // The trap: every parent doc is already COMPLETED, not PENDING. A rescue that transitioned
      // straight to REBUILDING here would see pendingEmbeddingCount==0 and certify on the very next
      // check — stamping a fingerprint over vectors nobody re-embedded under the current model.
      assertEquals(
          0,
          r2.indexCountOps()
              .countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING),
          "pre-rescue: nothing is PENDING yet — the trap an unsound rescue would fall into");

      // The PRODUCTION seam — the same call KnowledgeServer's
      // maybeAutoStartEmbeddingRebuildForBlockedLegacyBestEffort delegates to, so this test
      // exercises the real ordering rather than re-implementing it.
      var outcome = EmbeddingRecoveryOps.rescueBlockedLegacyIndex(ecc, r2, 1000, LOG);
      assertEquals(docCount, outcome.reMarkedPending(), "the rescue re-marks every COMPLETED doc");
      assertTrue(outcome.rebuildStarted(), "the rescue transitions to REBUILDING");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      // Commit so the re-marked state is durably visible to count/query reads (the real backfill
      // commits periodically).
      r2.commitOps().commitAndTrack();
      r2.commitOps().maybeRefreshBlocking();

      // Observable effect #1: the documents are actually re-queued for real re-embedding, not just
      // a bare state flip.
      assertEquals(
          docCount,
          r2.indexCountOps()
              .countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING),
          "after the rescue, every previously-COMPLETED doc must be PENDING again — real work"
              + " queued");

      var lifecycle =
          new EmbeddingProviderLifecycle(
              null /* signalBus unused by tryFinalizeRebuild */,
              jobQueue,
              r2.indexCountOps(),
              r2.commitOps());
      lifecycle.setEmbeddingCompatController(ecc);

      // Observable effect #2 — the central invariant: while real re-embed work is outstanding, NO
      // number of certification reads may certify. Probing twice matters: tryFinalizeRebuild
      // debounces with a two-consecutive-pending==0 guard, so a single assertFalse would pass on
      // the debounce alone even for a rescue that re-marked nothing — i.e. pass for the wrong
      // reason. Two consecutive reads defeat the debounce and leave only the invariant.
      assertFalse(
          lifecycle.tryFinalizeRebuild(),
          "must not certify while re-embed work is still pending — certifying would stamp a"
              + " fingerprint over vectors nobody has re-embedded under the current model");
      assertFalse(
          lifecycle.tryFinalizeRebuild(),
          "must still not certify on a second consecutive read — with docs genuinely PENDING no"
              + " read count may certify; if this passes only via the debounce, the rescue never"
              + " re-marked and provenance is about to be fabricated");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      assertTrue(
          ecc.fingerprintToStamp().isEmpty(),
          "no fingerprint may be stamped before re-embedding actually completes");

      // Only once the backfill has actually re-embedded every re-marked doc does certification
      // (two confirming pending==0 reads) legitimately proceed and stamp the fingerprint.
      simulateBackfillEmbedAllPending(r2);
      r2.commitOps().commitAndTrack();
      r2.commitOps().maybeRefreshBlocking();
      assertFalse(
          lifecycle.tryFinalizeRebuild(), "first pending==0 read must not certify (debounce)");
      assertTrue(
          lifecycle.tryFinalizeRebuild(),
          "second confirming pending==0 read, reached only after real re-embedding, may certify");
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());
      assertTrue(
          ecc.fingerprintToStamp().isPresent(),
          "fingerprint may only be stamped after documents were actually re-marked and"
              + " re-embedded");
    }
  }

  private static float[] vec() {
    float[] v = new float[768];
    java.util.Arrays.fill(v, 0.05f);
    return v;
  }

  private io.justsearch.adapters.lucene.runtime.RunningRuntime openRuntime(
      Path dir, Supplier<CommitMetadataSource> commitMetadata) {
    return io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
            FieldCatalogDef.forTesting(768), commitMetadata, PERMISSIVE)
        .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
        .open();
  }

  /**
   * The production doc-count supplier shape (tempdoc 819): PROPAGATES a reader failure rather than
   * swallowing it to 0, since 0 is what {@code refresh()} reads as "new empty index, safe to stamp".
   */
  private static long docCountOrThrow(io.justsearch.adapters.lucene.runtime.RunningRuntime r) {
    try {
      return r.indexCountOps().docCountOrThrow();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /**
   * The production success-evidence supplier shape (tempdoc 819 defect B): certification requires
   * at least one COMPLETED embedding, read live from the index.
   */
  private static int completedEmbeddingsOrThrow(
      io.justsearch.adapters.lucene.runtime.RunningRuntime r) {
    try {
      return r.indexCountOps()
          .countByFieldOrThrow(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** Marks every currently-PENDING parent doc COMPLETED, as the embedding backfill would. */
  private static void simulateBackfillEmbedAllPending(
      io.justsearch.adapters.lucene.runtime.RunningRuntime r) {
    List<String> pending =
        r.documentFieldOps()
            .queryDocIdsByField(
                SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING, 1000);
    List<Map.Entry<String, Map<String, Object>>> updates = new ArrayList<>(pending.size());
    for (String id : pending) {
      updates.add(
          Map.entry(
              id,
              Map.of(
                  SchemaFields.VECTOR, VEC,
                  SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED)));
    }
    r.indexingCoordinator().updateDocumentsBatch(updates);
  }
}
