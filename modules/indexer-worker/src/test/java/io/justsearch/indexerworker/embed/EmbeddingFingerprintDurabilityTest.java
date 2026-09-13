package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 598 reopen (B-1): the durability regression test §38.2 omitted.
 *
 * <p>The 593 §H / reopen B-1 symptom was "a rebuilt embedding generation reverts to BLOCKED_LEGACY
 * after a restart." This test proves the underlying property end-to-end at the Lucene-commit level:
 * a commit that stamps {@code embedding_model_sha256} via {@link EmbeddingMetadataOverlay} SURVIVES a
 * close→reopen of the index, so a fresh {@link EmbeddingCompatibilityController} reading the reopened
 * commit's user-data resolves to {@code COMPATIBLE} (NOT BLOCKED_LEGACY). The negative control proves
 * the test actually distinguishes the durable case: a commit WITHOUT the stamp reverts to
 * BLOCKED_LEGACY on reopen.
 *
 * <p>This is the runnable test the reopen demanded (audit-driven-fixes-need-test): a static audit of
 * the cutover's verify-promote gating is a hypothesis; this exercises the survives-restart property.
 */
class EmbeddingFingerprintDurabilityTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final String FP = "embed-fp-durable-sha256";
  private static final CommitMetadataValidator PERMISSIVE = metadata -> {};

  @AfterEach
  void clearFingerprint() {
    EmbeddingFingerprint.invalidate();
  }

  @Test
  @DisplayName("A stamped embedding fingerprint survives close→reopen → ECC resolves COMPATIBLE (B-1 durability)")
  void stampedFingerprintSurvivesReopenAsCompatible() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-durable");

    // Build 1: index a doc and commit with the embedding fingerprint stamped (the rebuild's green).
    Supplier<CommitMetadataSource> stamping =
        EmbeddingMetadataOverlay.createSupplier(() -> Optional.of(FP));
    try (var r1 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), stamping, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      r1.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(SchemaFields.DOC_ID, "d1", SchemaFields.DOC_UID, "d1#0")));
      r1.commitOps().commitAndTrack();
    }

    // Reopen the SAME directory (simulate a worker restart) and read the served commit's metadata,
    // exactly as production wires the ECC (storedMetadataSupplier = ingestLifecycle::latestCommitUserDataBestEffort).
    try (var r2 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), stamping, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
      ecc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          ecc.state(),
          "stamped fingerprint must survive reopen → COMPATIBLE, not revert to BLOCKED_LEGACY");
    }
  }

  @Test
  @DisplayName("Negative control: an UNSTAMPED commit reverts to BLOCKED_LEGACY on reopen (proves the test bites)")
  void unstampedCommitRevertsToBlockedLegacyOnReopen() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-unstamped");

    // Build 1: commit WITHOUT the overlay → no embedding_model_sha256 stamped (a legacy generation).
    Supplier<CommitMetadataSource> noStamp =
        EmbeddingMetadataOverlay.createSupplier(Optional::empty);
    try (var r1 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      r1.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(SchemaFields.DOC_ID, "d1", SchemaFields.DOC_UID, "d1#0")));
      r1.commitOps().commitAndTrack();
    }

    try (var r2 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
      ecc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          ecc.state(),
          "an index with docs but no stamped fingerprint is BLOCKED_LEGACY after reopen");
    }
  }
}
