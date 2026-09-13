package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.lucene.index.IndexWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 730 §DERISK: {@link EmbeddingFingerprintDurabilityTest} proves commit-userData
 * durability through an UNCONDITIONAL fingerprint supplier ({@code
 * EmbeddingMetadataOverlay.createSupplier(() -> Optional.of(FP))}) — it never touches the
 * production wiring at {@code KnowledgeServer.java}, which is the actual defect surface named in
 * §THEORIZE A. It passes precisely because it avoids the bug ({@code unreachable-seed-green}).
 *
 * <p>This test drives the REAL production supplier through a two-phase late-binding wiring
 * identical in shape to {@code KnowledgeServer.java:485-486} + the embedding-supplier set-site:
 * the overlay opens with a default {@code Optional::empty} supplier, and is rebound only after
 * the {@link EmbeddingCompatibilityController} is constructed from that same runtime.
 *
 * <p><b>Tempdoc 730 A1 was review-refuted and reverted.</b> A1 (as originally implemented) swapped
 * the production supplier from {@code ecc::fingerprintToStamp} to the unconditional {@link
 * EmbeddingFingerprint#get()}, reasoning that the stamp is "a fact about the write" independent of
 * ECC state. Adversarial review proved that wrong: a forced reindex is in-place/incremental
 * ({@code JobBatchExtractor.java:193-212} — no wipe), so an interrupted BLOCKED_MISMATCH/
 * BLOCKED_LEGACY -> REBUILDING run holds a genuinely MIXED index (old-model vectors alongside
 * new-model vectors). Stamping unconditionally means an ordinary commit mid-rebuild persists the
 * NEW model's fingerprint over that mixed index; restart then resolves COMPATIBLE and silently
 * serves the mixture — the old gate correctly kept that state BLOCKED. The production wiring is
 * back to {@code ecc::fingerprintToStamp} (state-gated on {@code state() == COMPATIBLE ||
 * (REBUILDING && rebuildCompleted)}).
 *
 * <p>The REAL ratchet hole A1 was reacting to is different from what A1 fixed: a rebuild
 * completion (or fresh-COMPATIBLE) with no SUBSEQUENT commit afterward leaves the fingerprint
 * unpersisted, and the next restart re-flags BLOCKED_LEGACY even though the rebuild genuinely
 * completed. That hole is closed at the completion-guarantee call sites instead — see (a) below —
 * not by weakening the stamp gate.
 *
 * <p>This class now covers, per the tempdoc 730 review:
 * <ul>
 *   <li>(a) the ratchet reproduction — a completed rebuild persists across close→reopen only when
 *       the completion-guarantee commit fires, and reverts to BLOCKED_LEGACY when it doesn't (the
 *       hole {@code IndexingLoop.finalizeShutdownCommit()} now closes — see that method's own
 *       regression test, {@code IndexingLoopTest#finalizeShutdownCommitFiresRebuildStampWithNoIndexedSinceCommit},
 *       for the direct disable/red → restore/green proof against the actual production wiring
 *       change, since this ECC-level test proves the *persistence mechanism* the wiring guarantee
 *       relies on, not the wiring call-site itself, which lives in a different module)</li>
 *   <li>(b) the mixed-provenance guard — an ordinary commit landing mid-REBUILDING (before
 *       completion) must still leave the index BLOCKED on reopen, pinning the A1 revert</li>
 *   <li>(c) the negative control — no fingerprint ever offered stays BLOCKED_LEGACY</li>
 * </ul>
 */
class EmbeddingFingerprintProductionWiringDurabilityTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final String FP = "prod-wiring-embed-fp-sha256";
  private static final String SIBLING_FP = "prod-wiring-sibling-fp-sha256";
  private static final CommitMetadataValidator PERMISSIVE = metadata -> {};

  @AfterEach
  void clearFingerprint() {
    EmbeddingFingerprint.invalidate();
  }

  /**
   * Simulates a hard kill (taskkill, no graceful shutdown): releases the underlying Lucene
   * write-lock via {@link IndexWriter#rollback()} directly, bypassing {@code RuntimeSession.close()}
   * entirely — so no extra close-time commit runs and, critically, {@code CleanShutdownMarker} is
   * NEVER written (that only happens inside {@code RuntimeSession.close()} after a clean {@code
   * writer().close()}). Reflection is required because the raw {@code IndexWriter} is only reachable
   * via package-private internals of {@code io.justsearch.adapters.lucene.runtime}, which this test
   * (in a different module/package) has no visibility into — mirrors what a real process death does
   * (drops the OS file handle/lock with no application-level shutdown code running at all).
   */
  private static void abandonWithoutGracefulClose(
      io.justsearch.adapters.lucene.runtime.RunningRuntime runtime) throws Exception {
    Method sessionMethod = runtime.getClass().getDeclaredMethod("session");
    sessionMethod.setAccessible(true);
    Object session = sessionMethod.invoke(runtime);
    Field snapshotField = session.getClass().getDeclaredField("snapshot");
    snapshotField.setAccessible(true);
    Object snapshot = snapshotField.get(session);
    Method writerMethod = snapshot.getClass().getMethod("writer");
    writerMethod.setAccessible(true);
    IndexWriter writer = (IndexWriter) writerMethod.invoke(snapshot);
    writer.rollback();
  }

  // ---- (a) ratchet reproduction ----

  @Test
  @DisplayName(
      "(a, positive) Ratchet reproduction: rebuild reaches completion and the "
          + "completion-guarantee commit fires (mirroring EmbeddingProviderLifecycle."
          + "tryFinalizeRebuild()'s own commitAndTrack + onFingerprintStamped pair) with no other "
          + "commit afterward — close->reopen must be COMPATIBLE, not revert to BLOCKED_LEGACY")
  void rebuildCompletionWithGuaranteeCommitPersistsOnReopen() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-ratchet-positive");

    Supplier<CommitMetadataSource> noStamp = EmbeddingMetadataOverlay.createSupplier(Optional::empty);
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

    // Two-phase late-binding, identical in shape to KnowledgeServer.java:485-486 + the
    // embedding-supplier set-site: the overlay must be constructable (and openable) before the
    // ECC exists, since the ECC's storedMetadataSupplier reads back from this same runtime. The
    // AtomicReference defaults to Optional::empty so `.open()`'s own initial read/write of commit
    // metadata cannot NPE, and is rebound to the real `ecc::fingerprintToStamp` only once the ECC
    // is constructed from this runtime, below.
    AtomicReference<Supplier<Optional<String>>> fpSupplierRef = new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> productionWiredOverlay =
        EmbeddingMetadataOverlay.createSupplier(
            () -> fpSupplierRef.get().get(), () -> Optional.of(SIBLING_FP));

    try (var r2 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), productionWiredOverlay, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
      // Production wiring, post-A1-revert: KnowledgeServer.java:1022-1023.
      fpSupplierRef.set(ecc::fingerprintToStamp);
      ecc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          ecc.state(),
          "sanity: the legacy generation from Build 1 must open as BLOCKED_LEGACY");

      ecc.onForcedReindexRequested();
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());

      r2.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(SchemaFields.DOC_ID, "d2", SchemaFields.DOC_UID, "d2#0")));

      // Completion is observed (queue drained, all embeddings done) — exactly the condition
      // EmbeddingProviderLifecycle.tryFinalizeRebuild() checks via checkRebuildCompletion(0, 0).
      // Tempdoc 819 defect B: "all embeddings done" now has to be STATED, not inferred from
      // pending==0 (failed docs are not pending either). These durability tests model a rebuild
      // that genuinely produced vectors; the zero-success case is covered by
      // EmbeddingCompatibilityControllerTest#checkRebuildCompletionRefusesWhenEveryEmbeddingFailed.
      ecc.noteSuccessfulEmbeddingObserved();
      boolean completed = ecc.checkRebuildCompletion(0, 0);
      assertTrue(completed, "sanity: queue==0 && pending==0 must report completion");
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());

      // The completion-guarantee commit: the SAME commitAndTrack + onFingerprintStamped pair
      // tryFinalizeRebuild() issues, and the ONLY thing that happens after completion in this
      // scenario — modelling a worker that stops right after, before any further loop iteration.
      // Tempdoc 730 review item 2 / IndexingLoop.finalizeShutdownCommit() is what guarantees this
      // call actually fires even at shutdown with indexedSinceCommit == 0 (see that method's own
      // test for the wiring-level proof).
      r2.commitOps().commitAndTrack();
      ecc.onFingerprintStamped();
    }

    try (var r3 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      var freshEcc =
          new EmbeddingCompatibilityController(
              r3::latestCommitUserDataBestEffort, () -> r3.indexCountOps().docCount());
      freshEcc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          freshEcc.state(),
          "the completion-guarantee commit must survive reopen as COMPATIBLE");
      assertEquals(FP, freshEcc.storedFingerprint());
    }
  }

  @Test
  @DisplayName(
      "(a, negative) Ratchet reproduction: rebuild reaches completion IN MEMORY but closes with "
          + "NO commit at all afterward — close->reopen must revert to BLOCKED_LEGACY. This IS "
          + "the tempdoc 730 review item-2 hole absent the completion-guarantee fix; proves the "
          + "positive test's COMPATIBLE assertion is meaningful, not a tautology of ECC.refresh()")
  void rebuildCompletionWithoutAnyGuaranteeCommitRevertsToBlockedLegacyOnReopen() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-ratchet-negative");

    Supplier<CommitMetadataSource> noStamp = EmbeddingMetadataOverlay.createSupplier(Optional::empty);
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

    // Two-phase late-binding, identical in shape to KnowledgeServer.java:485-486 + the
    // embedding-supplier set-site: the overlay must be constructable (and openable) before the
    // ECC exists, since the ECC's storedMetadataSupplier reads back from this same runtime. The
    // AtomicReference defaults to Optional::empty so `.open()`'s own initial read/write of commit
    // metadata cannot NPE, and is rebound to the real `ecc::fingerprintToStamp` only once the ECC
    // is constructed from this runtime, below.
    AtomicReference<Supplier<Optional<String>>> fpSupplierRef = new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> productionWiredOverlay =
        EmbeddingMetadataOverlay.createSupplier(
            () -> fpSupplierRef.get().get(), () -> Optional.of(SIBLING_FP));

    try (var r2 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), productionWiredOverlay, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
      // Production wiring, post-A1-revert: KnowledgeServer.java:1022-1023.
      fpSupplierRef.set(ecc::fingerprintToStamp);
      ecc.refresh();
      ecc.onForcedReindexRequested();

      r2.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(SchemaFields.DOC_ID, "d2", SchemaFields.DOC_UID, "d2#0")));

      ecc.noteSuccessfulEmbeddingObserved(); // tempdoc 819 B — see the (a, positive) test
      boolean completed = ecc.checkRebuildCompletion(0, 0);
      assertTrue(completed, "sanity: queue==0 && pending==0 must report completion");
      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          ecc.state(),
          "sanity: completion flips the in-memory state even without a persisting commit — this "
              + "is exactly the trap: in-memory COMPATIBLE is not itself a commit");

      // No commit follows. r2 closes here with the completion never persisted — the worker
      // "restarted" the instant after completion, before any commit (guarantee or ordinary) had
      // a chance to run.
    }

    try (var r3 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      var freshEcc =
          new EmbeddingCompatibilityController(
              r3::latestCommitUserDataBestEffort, () -> r3.indexCountOps().docCount());
      freshEcc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          freshEcc.state(),
          "without a persisting commit, the completion never survived — reopen must NOT be "
              + "COMPATIBLE (this is the ratchet hole IndexingLoop.finalizeShutdownCommit() now "
              + "closes by guaranteeing the completion commit at shutdown, not just on the next "
              + "idle/batch iteration)");
    }
  }

  // ---- (d) live falsification: DEFERRED (production restart) boot ----

  @Test
  @DisplayName(
      "(d) Live falsification: boot the PRODUCTION way (DEFERRED read-only-first open, matching "
          + "KnowledgeServer.java:505-510's useDeferredWriter=true restart path) after a completed "
          + "rebuild's guarantee commit landed durably on disk — reopen must resolve COMPATIBLE with "
          + "the stored fingerprint, not BLOCKED_LEGACY. Every other reopen in this class (and in "
          + "EmbeddingFingerprintDurabilityTest) uses IndexSchema.atPath().open() (read-write) for the "
          + "reopen — production restarts always take openDeferred() once segments exist on disk")
  void productionDeferredBootReadsStoredFingerprintAfterGuaranteeCommit() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-deferred-boot");

    Supplier<CommitMetadataSource> noStamp = EmbeddingMetadataOverlay.createSupplier(Optional::empty);
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

    AtomicReference<Supplier<Optional<String>>> fpSupplierRef = new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> productionWiredOverlay =
        EmbeddingMetadataOverlay.createSupplier(
            () -> fpSupplierRef.get().get(), () -> Optional.of(SIBLING_FP));

    var r2 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), productionWiredOverlay, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open();
    var ecc =
        new EmbeddingCompatibilityController(
            r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
    fpSupplierRef.set(ecc::fingerprintToStamp);
    ecc.refresh();
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, ecc.state());

    ecc.onForcedReindexRequested();
    r2.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(SchemaFields.DOC_ID, "d2", SchemaFields.DOC_UID, "d2#0")));
    ecc.noteSuccessfulEmbeddingObserved(); // tempdoc 819 B — see the (a, positive) test
    boolean completed = ecc.checkRebuildCompletion(0, 0);
    assertTrue(completed, "sanity: queue==0 && pending==0 must report completion");
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());

    // The completion-guarantee commit — the same commitAndTrack + onFingerprintStamped pair
    // tryFinalizeRebuild() / finalizeShutdownCommit() issues. This is what the live orchestrator
    // probe observed as "storedFp present, /api/status COMPATIBLE" before the hard-kill: the
    // stamp is genuinely durable on disk (fsynced) at this point.
    r2.commitOps().commitAndTrack();
    ecc.onFingerprintStamped();

    // HARD KILL: no further writes follow, and — critically — no graceful RuntimeSession.close()
    // runs either. taskkill drops the process with no JVM shutdown hook, so CleanShutdownMarker is
    // never written. Model that exactly instead of try-with-resources (which would call close()
    // and mask the unclean-shutdown precondition production actually restarts from).
    abandonWithoutGracefulClose(r2);

    // PRODUCTION restart shape: KnowledgeServer.java:505 `hasLuceneSegments(activeIndexPath)` is
    // true (segments exist on disk from r1/r2 above), so boot takes the DEFERRED (read-only-first)
    // path (`builder.openDeferred()`) — never the plain read-write `.open()` this test class (and
    // EmbeddingFingerprintDurabilityTest) otherwise uses for its reopen.
    try (var r3 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .openDeferred()) {
      var freshEcc =
          new EmbeddingCompatibilityController(
              r3::latestCommitUserDataBestEffort, () -> r3.indexCountOps().docCount());
      freshEcc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          freshEcc.state(),
          "DEFERRED (production restart) boot must see the same guarantee-commit fingerprint a "
              + "read-write reopen sees");
      assertEquals(FP, freshEcc.storedFingerprint());
    }
  }

  // ---- (b) mixed-provenance guard (pins the A1 revert) ----

  @Test
  @DisplayName(
      "(b) Mixed-provenance guard: an ordinary commit that lands mid-REBUILDING (BEFORE "
          + "checkRebuildCompletion marks it done) must NOT stamp the embedding fingerprint — "
          + "close->reopen must resolve BLOCKED_LEGACY, not COMPATIBLE. Pins the A1 revert: a "
          + "forced reindex is in-place/incremental (no wipe), so an index closed mid-rebuild "
          + "genuinely holds a MIXED old/new-model vector set, and the old model's docs would be "
          + "silently served as dense-compatible if this resolved COMPATIBLE "
          + "(tempdoc 730 review MAJOR finding)")
  void ordinaryCommitDuringInFlightRebuildStaysBlockedOnReopen() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-mixed-provenance");

    Supplier<CommitMetadataSource> noStamp = EmbeddingMetadataOverlay.createSupplier(Optional::empty);
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

    // Production wiring post-revert: ecc::fingerprintToStamp, state-gated.
    // Two-phase late-binding, identical in shape to KnowledgeServer.java:485-486 + the
    // embedding-supplier set-site: the overlay must be constructable (and openable) before the
    // ECC exists, since the ECC's storedMetadataSupplier reads back from this same runtime. The
    // AtomicReference defaults to Optional::empty so `.open()`'s own initial read/write of commit
    // metadata cannot NPE, and is rebound to the real `ecc::fingerprintToStamp` only once the ECC
    // is constructed from this runtime, below.
    AtomicReference<Supplier<Optional<String>>> fpSupplierRef = new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> productionWiredOverlay =
        EmbeddingMetadataOverlay.createSupplier(
            () -> fpSupplierRef.get().get(), () -> Optional.of(SIBLING_FP));

    try (var r2 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), productionWiredOverlay, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
      // Production wiring, post-A1-revert: KnowledgeServer.java:1022-1023.
      fpSupplierRef.set(ecc::fingerprintToStamp);
      ecc.refresh();
      assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, ecc.state());

      ecc.onForcedReindexRequested();
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());

      // The rebuild embeds a document (in-place, old doc d1's stale vector is still on disk
      // per JobBatchExtractor's no-wipe forced-reindex semantics)...
      r2.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(SchemaFields.DOC_ID, "d2", SchemaFields.DOC_UID, "d2#0")));

      // ...and an ORDINARY commit lands (the indexing loop's buffer/time-triggered commit)
      // BEFORE checkRebuildCompletion() has had a chance to observe queue==0/pending==0 and
      // flip state to COMPATIBLE. The rebuild is genuinely still IN FLIGHT — d1 and d2 are a
      // mixed old/new-model vector set at this instant. checkRebuildCompletion() is deliberately
      // NEVER called in this test.
      r2.commitOps().commitAndTrack();
      assertEquals(
          EmbeddingCompatibilityController.State.REBUILDING,
          ecc.state(),
          "sanity: the ordinary mid-rebuild commit must not itself flip state to COMPATIBLE");
    }

    try (var r3 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      var freshEcc =
          new EmbeddingCompatibilityController(
              r3::latestCommitUserDataBestEffort, () -> r3.indexCountOps().docCount());
      freshEcc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          freshEcc.state(),
          "a commit landing mid-rebuild must leave the index BLOCKED on reopen — the mixed "
              + "old/new-model vector set must not be silently served as dense-compatible. Under "
              + "A1's unconditional EmbeddingFingerprint::get supplier this incorrectly resolved "
              + "COMPATIBLE (the review-refuted mixed-provenance over-claim)");
    }
  }

  // ---- (c) negative control ----

  @Test
  @DisplayName(
      "(c) Negative control: without ANY fingerprint ever offered to the overlay, the same "
          + "BLOCKED_LEGACY -> REBUILDING -> commit -> reopen lifecycle correctly stays "
          + "BLOCKED_LEGACY (proves the harness bites on the real hole)")
  void rebuildWithoutAnyFingerprintOfferedStaysBlockedLegacyOnReopen() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-prod-wiring-negative");

    Supplier<CommitMetadataSource> noStamp = EmbeddingMetadataOverlay.createSupplier(Optional::empty);
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
      ecc.onForcedReindexRequested();

      r2.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(SchemaFields.DOC_ID, "d2", SchemaFields.DOC_UID, "d2#0")));
      // No fingerprint ever offered — `noStamp` is Optional::empty throughout.
      r2.commitOps().commitAndTrack();
    }

    try (var r3 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      var freshEcc =
          new EmbeddingCompatibilityController(
              r3::latestCommitUserDataBestEffort, () -> r3.indexCountOps().docCount());
      freshEcc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          freshEcc.state(),
          "with no fingerprint ever offered to the overlay, the index correctly stays "
              + "BLOCKED_LEGACY — proving the positive tests' COMPATIBLE assertions are "
              + "meaningful");
    }
  }
}
