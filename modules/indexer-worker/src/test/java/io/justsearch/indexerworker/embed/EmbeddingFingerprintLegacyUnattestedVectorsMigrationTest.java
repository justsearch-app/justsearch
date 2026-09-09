package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.ops.EmbeddingRecoveryOps;
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
 * Tempdoc 730 Increment 3 (A4 migration): regression test for the on-disk corruption signature
 * reproduced by the live-validation run {@code g-20260714-134648} (§THEORIZE A) — dense vectors
 * already exist (a doc's {@code embedding_status} is {@code COMPLETED}), the SPLADE fingerprint is
 * stamped (unconditional supplier), but the embedding fingerprint is ABSENT from commit userData
 * (the pre-fix state-gated supplier withheld it).
 *
 * <p>The migration must NOT back-stamp the current fingerprint over these vectors — that would
 * fabricate provenance for vectors we cannot prove came from the current model. It must instead
 * drive a REAL re-embed. Since the embedding backfill only ever picks up {@code PENDING} documents
 * ({@code EmbeddingBackfillOps}), that means the COMPLETED doc has to be re-marked PENDING first;
 * transitioning to REBUILDING without re-marking would leave {@code pending == 0} already true,
 * certify instantly, re-embed nothing, and stamp anyway. {@link
 * EmbeddingRecoveryOps#rescueBlockedLegacyIndex} is the one place that orders this correctly, so
 * this test drives that seam rather than the controller directly.
 *
 * <p>Harness idioms (two-phase late-binding overlay, {@code IndexSchema.fromCatalog(...).atPath
 * (dir).open()}, {@code commitAndTrack}, {@code latestCommitUserDataBestEffort}) are copied from
 * {@code EmbeddingFingerprintProductionWiringDurabilityTest} per that class's own template role
 * named in tempdoc 730's DERISK section.
 */
class EmbeddingFingerprintLegacyUnattestedVectorsMigrationTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final String FP = "unattested-migration-embed-fp-sha256";
  private static final String SPLADE_FP = "unattested-migration-splade-fp-sha256";
  private static final CommitMetadataValidator PERMISSIVE = metadata -> {};

  /**
   * A dense vector to accompany every {@code embedding_status=COMPLETED} write. The write-time
   * contract (tempdoc 798) rejects a COMPLETED that carries no vector, and the "unattested vectors"
   * this test probes are real vectors with unknowable provenance — not absent ones.
   */
  private static final float[] VEC = vec();

  private static final org.slf4j.Logger LOG =
      LoggerFactory.getLogger(EmbeddingFingerprintLegacyUnattestedVectorsMigrationTest.class);

  @AfterEach
  void clearFingerprint() {
    EmbeddingFingerprint.invalidate();
  }

  @Test
  @DisplayName(
      "Positive: on-disk signature (dense vectors attested via embedding_status=COMPLETED, "
          + "SPLADE fp stamped, embedding fp ABSENT -- mirrors g-20260714-134648) is detected on "
          + "reopen; the rescue re-marks the unattested doc PENDING and does NOT certify until a "
          + "real re-embed drains it, then earns a legitimate COMPATIBLE stamp")
  void legacyUnattestedVectorsSignatureAutoRescuesToCompatible() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-unattested-vectors-migration");

    // Build 1: seed the corrupted generation directly -- SPLADE fp stamped unconditionally, the
    // embedding fp NEVER offered (Optional::empty throughout), mirroring the asymmetric-stamping-
    // gate ratchet's on-disk signature exactly. The doc is marked embedding_status=COMPLETED,
    // i.e. it already carries a (dense-vector) embedding -- the "vectors exist" evidence.
    Supplier<CommitMetadataSource> spladeOnly =
        EmbeddingMetadataOverlay.createSupplier(Optional::empty, () -> Optional.of(SPLADE_FP));
    try (var r1 = openRuntime(dir, spladeOnly)) {
      r1.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      "d1",
                      SchemaFields.DOC_UID,
                      "d1#0",
                      SchemaFields.VECTOR,
                      VEC,
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_COMPLETED)));
      r1.commitOps().commitAndTrack();
    }

    // Build 2 ("worker restart"): reopen with the production-wired two-phase overlay -- identical
    // in shape to KnowledgeServer.java:485-486 + the embedding-supplier set-site.
    AtomicReference<Supplier<Optional<String>>> fpSupplierRef =
        new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> productionWiredOverlay =
        EmbeddingMetadataOverlay.createSupplier(
            () -> fpSupplierRef.get().get(), () -> Optional.of(SPLADE_FP));

    try (var r2 = openRuntime(dir, productionWiredOverlay)) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort,
              () -> docCountOrThrow(r2),
              () -> completedEmbeddingsOrThrow(r2));
      // Production wiring, post-A1-revert: KnowledgeServer.java:1022-1023.
      fpSupplierRef.set(ecc::fingerprintToStamp);
      ecc.refresh();

      // Sanity: this is the exact on-disk signature from §THEORIZE A -- SPLADE fp present,
      // embedding fp absent, docCount > 0 => BLOCKED_LEGACY.
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          ecc.state(),
          "sanity: the corrupted generation from Build 1 must open as BLOCKED_LEGACY");
      assertEquals("LEGACY_INDEX_NO_FINGERPRINT", ecc.reasonCode());
      assertEquals(
          1,
          countByStatus(r2, SchemaFields.EMBEDDING_STATUS_COMPLETED),
          "sanity: the doc must carry the 'vectors exist' evidence");
      assertEquals(
          0,
          countByStatus(r2, SchemaFields.EMBEDDING_STATUS_PENDING),
          "sanity: nothing is PENDING yet -- the trap a rescue that skips the re-mark falls into,"
              + " since certification keys on pending==0");

      // The production rescue seam -- the same call KnowledgeServer delegates to.
      var outcome = EmbeddingRecoveryOps.rescueBlockedLegacyIndex(ecc, r2, 1000, LOG);
      assertEquals(
          1,
          outcome.reMarkedPending(),
          "the rescue must re-mark the unattested COMPLETED doc PENDING so the backfill re-embeds"
              + " it -- back-stamping it instead would fabricate provenance");
      assertTrue(outcome.rebuildStarted(), "the rescue must start REBUILDING");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      assertEquals(
          "legacy_no_fingerprint",
          ecc.lastAutoRescueReason(),
          "operators must be able to tell an auto-rescue apart from a user-initiated reindex");
      r2.commitOps().commitAndTrack();
      r2.commitOps().maybeRefreshBlocking();

      // Observable effect: real re-embed work is queued, read from the index -- not asserted from
      // the rescue's return value alone.
      assertEquals(
          1,
          countByStatus(r2, SchemaFields.EMBEDDING_STATUS_PENDING),
          "the previously-COMPLETED doc must now be PENDING -- real work the backfill will pick up");

      // The invariant: while that re-embed is outstanding, certification must NOT fire and no
      // fingerprint may be stamped. The pending count is READ FROM THE INDEX, not hardcoded --
      // hardcoding zeros here is what let the pre-resolution version of this test pass while the
      // implementation re-embedded nothing.
      assertFalse(
          ecc.checkRebuildCompletion(0, countByStatus(r2, SchemaFields.EMBEDDING_STATUS_PENDING)),
          "must not certify while the re-embed is still pending -- certifying now would stamp a"
              + " fingerprint over vectors nobody re-embedded under the current model");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      assertTrue(
          ecc.fingerprintToStamp().isEmpty(),
          "no fingerprint may be offered for stamping before the re-embed completes");

      // Now the REAL producer's data flow: the backfill picks up the PENDING doc, embeds it, and
      // marks it COMPLETED. (The pre-resolution version instead hand-wrote a COMPLETED doc that was
      // never PENDING -- a seed the backfill could never have produced, which is exactly why it
      // passed against an implementation that re-embedded nothing.)
      simulateBackfillEmbedAllPending(r2);
      r2.commitOps().commitAndTrack();
      r2.commitOps().maybeRefreshBlocking();
      assertEquals(
          0,
          countByStatus(r2, SchemaFields.EMBEDDING_STATUS_PENDING),
          "sanity: the backfill drained the re-embed queue");

      boolean completedRebuild =
          ecc.checkRebuildCompletion(0, countByStatus(r2, SchemaFields.EMBEDDING_STATUS_PENDING));
      assertTrue(completedRebuild, "a real re-embed having drained pending==0 must certify");
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());

      // The completion-guarantee commit -- the same commitAndTrack + onFingerprintStamped pair
      // EmbeddingProviderLifecycle.tryFinalizeRebuild() issues -- is what actually earns and
      // persists the stamp. This is NOT a back-stamp of the pre-existing (Build 1) vectors: the
      // fingerprint now describes the vectors this rebuild just wrote.
      r2.commitOps().commitAndTrack();
      ecc.onFingerprintStamped();
    }

    // Build 3 ("second restart"): the migration must be self-terminating -- the generation now
    // resolves COMPATIBLE with a legitimately-earned fingerprint, not a fabricated one.
    Supplier<CommitMetadataSource> noStamp =
        EmbeddingMetadataOverlay.createSupplier(Optional::empty);
    try (var r3 = openRuntime(dir, noStamp)) {
      var freshEcc =
          new EmbeddingCompatibilityController(
              r3::latestCommitUserDataBestEffort, () -> r3.indexCountOps().docCount());
      freshEcc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          freshEcc.state(),
          "the migrated generation must survive reopen as COMPATIBLE, not re-flag BLOCKED_LEGACY");
      assertEquals(FP, freshEcc.storedFingerprint());
    }
  }

  @Test
  @DisplayName(
      "Negative: a properly-stamped generation (embedding fp already persisted and matching the "
          + "current model) must NOT trigger the auto-rescue")
  void properlyStampedGenerationDoesNotTriggerAutoRescue() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-unattested-vectors-negative-stamped");

    Supplier<CommitMetadataSource> stamped =
        EmbeddingMetadataOverlay.createSupplier(() -> Optional.of(FP), () -> Optional.of(SPLADE_FP));
    try (var r1 = openRuntime(dir, stamped)) {
      r1.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      "d1",
                      SchemaFields.DOC_UID,
                      "d1#0",
                      SchemaFields.VECTOR,
                      VEC,
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_COMPLETED)));
      r1.commitOps().commitAndTrack();
    }

    try (var r2 = openRuntime(dir, stamped)) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort,
              () -> docCountOrThrow(r2),
              () -> completedEmbeddingsOrThrow(r2));
      ecc.refresh();

      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          ecc.state(),
          "sanity: a properly-stamped generation must resolve COMPATIBLE, never BLOCKED_LEGACY");

      var outcome = EmbeddingRecoveryOps.rescueBlockedLegacyIndex(ecc, r2, 1000, LOG);
      assertEquals(0, outcome.reMarkedPending(), "a healthy generation must not be re-marked");
      assertFalse(outcome.rebuildStarted());
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());
      assertEquals(
          1,
          countByStatus(r2, SchemaFields.EMBEDDING_STATUS_COMPLETED),
          "the healthy generation's COMPLETED doc must be left alone");
    }
  }

  @Test
  @DisplayName(
      "Negative: an all-pending legacy generation is rescued by the same seam -- nothing to "
          + "re-mark, and certification still waits for the genuinely-pending doc to be embedded")
  void allPendingGenerationIsRescuedByTheSameSeam() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-unattested-vectors-negative-allpending");

    Supplier<CommitMetadataSource> noStamp =
        EmbeddingMetadataOverlay.createSupplier(Optional::empty);
    try (var r1 = openRuntime(dir, noStamp)) {
      r1.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      "d1",
                      SchemaFields.DOC_UID,
                      "d1#0",
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_PENDING)));
      r1.commitOps().commitAndTrack();
    }

    try (var r2 = openRuntime(dir, noStamp)) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort,
              () -> docCountOrThrow(r2),
              () -> completedEmbeddingsOrThrow(r2));
      ecc.refresh();
      assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, ecc.state());
      assertEquals(
          0,
          countByStatus(r2, SchemaFields.EMBEDDING_STATUS_COMPLETED),
          "sanity: nothing has been embedded yet");

      // The same seam handles this distribution: there is simply nothing to re-mark, because the
      // work the backfill needs is already queued. The rescue still transitions.
      var outcome = EmbeddingRecoveryOps.rescueBlockedLegacyIndex(ecc, r2, 1000, LOG);
      assertEquals(
          0, outcome.reMarkedPending(), "nothing is COMPLETED/FAILED, so nothing needs re-marking");
      assertTrue(outcome.rebuildStarted());
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      assertEquals("legacy_no_fingerprint", ecc.lastAutoRescueReason());

      // The doc was already PENDING, so certification must still wait for it -- the invariant does
      // not depend on the rescue having re-marked anything, only on real work existing.
      assertFalse(
          ecc.checkRebuildCompletion(0, countByStatus(r2, SchemaFields.EMBEDDING_STATUS_PENDING)),
          "must not certify while the genuinely-pending doc is unembedded");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());

      simulateBackfillEmbedAllPending(r2);
      r2.commitOps().commitAndTrack();
      r2.commitOps().maybeRefreshBlocking();
      assertTrue(
          ecc.checkRebuildCompletion(0, countByStatus(r2, SchemaFields.EMBEDDING_STATUS_PENDING)),
          "once the backfill embedded it, certification proceeds");
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());
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

  private static int countByStatus(
      io.justsearch.adapters.lucene.runtime.RunningRuntime r, String status) {
    return r.indexCountOps().countByField(SchemaFields.EMBEDDING_STATUS, status);
  }

  /**
   * The production doc-count supplier shape (tempdoc 819): PROPAGATES a reader failure instead of
   * swallowing it to 0, because 0 is what {@code refresh()} reads as "new empty index, safe to
   * stamp".
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
   * at least one COMPLETED embedding, read live from the index — so these tests exercise the real
   * evidence path rather than asserting it away.
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
