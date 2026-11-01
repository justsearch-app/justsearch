package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddingCompatibilityControllerTest {

  @TempDir Path tempDir;

  private ConfigStore prevStore;

  @BeforeEach
  void setUpConfigStore() {
    prevStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void tearDown() {
    EmbeddingFingerprint.invalidate();
    TestResolvedConfigHelper.restoreGlobal(prevStore);
  }

  @Test
  void successfulCommitReadbackClearsAnAbsentStampWithoutDiscardingRepairEvidence() {
    EmbeddingFingerprint.setForTesting("current");
    var persisted = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>(
        Map.of(EmbeddingCompatibilityController.COMMIT_META_KEY, "current"));
    var controller = new EmbeddingCompatibilityController(persisted::get, () -> 1L, () -> 0);
    controller.refresh();
    persisted.set(Map.of());
    controller.refreshStoredFingerprintAfterCommit();
    assertNull(controller.storedFingerprint());
    assertEquals(java.util.Optional.of("current"), controller.fingerprintToStamp());
  }

  @Test
  void preparingMetadataDoesNotClaimDurableFingerprint() {
    EmbeddingFingerprint.setForTesting("current");
    var persisted = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>(Map.of());
    var controller = new EmbeddingCompatibilityController(persisted::get, () -> 0L, () -> 1);
    controller.refresh();
    controller.noteSuccessfulEmbeddingObserved();
    assertEquals(java.util.Optional.of("current"), controller.fingerprintToStamp());
    assertNull(controller.storedFingerprint(), "preparation is not a successful commit");
    assertEquals(java.util.Optional.of("current"), controller.fingerprintToStamp());
    persisted.set(Map.of(EmbeddingCompatibilityController.COMMIT_META_KEY, "current"));
    controller.refreshStoredFingerprintAfterCommit();
    assertEquals("current", controller.storedFingerprint());
  }

  @Test
  void maybeAutoStartRebuildForBlockedLegacyTransitionsToRebuilding() throws Exception {
    // Inject a fake fingerprint so the controller sees embedding as "available" without
    // needing a real model file on disk. Without this, EmbeddingFingerprint.get() returns
    // empty and the controller goes to UNAVAILABLE instead of BLOCKED_LEGACY.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            Map::of,
            () -> 5L);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
    assertEquals("LEGACY_INDEX_NO_FINGERPRINT", controller.reasonCode());

    boolean started = controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertTrue(started);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    assertEquals("REBUILD_IN_PROGRESS", controller.reasonCode());
  }

  @Test
  void maybeAutoStartRebuildForBlockedLegacyFiresRegardlessOfDistribution() throws Exception {
    // Tempdoc 726 T3 (pins F3 at the controller level): a fully-embedded-but-unstamped index
    // (documents COMPLETED, none PENDING) is exactly what the pre-fix in-place backfill path leaves
    // after a restart. Recovery must STILL auto-start REBUILDING — the former all-pending-only guard
    // (completed==0 && failed==0 && pending==docCount) refused, stranding it in BLOCKED_LEGACY.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());

    // docCount>0 but NOT all-pending (the recovery caller re-marks COMPLETED/FAILED docs PENDING
    // before/around this; the controller only needs to know there are documents to (re-)embed).
    boolean started = controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertTrue(started);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
  }

  @Test
  void maybeAutoStartRebuildForBlockedLegacyGuardsEmptyIndex() throws Exception {
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());

    // No documents → nothing to rebuild → stays BLOCKED_LEGACY.
    assertFalse(controller.maybeAutoStartRebuildForBlockedLegacy(0L));
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
  }

  @Test
  void refreshWhileRebuildingIsIgnored() throws Exception {
    // 734: refresh() re-derives state unconditionally from the fingerprint suppliers, but the
    // stored fingerprint isn't stamped until certification — so a mid-rebuild refresh() call
    // would re-derive BLOCKED_LEGACY (storedFp still null/stale) and silently clobber the
    // in-flight rebuild. Guard: refresh() while REBUILDING is a no-op.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    assertEquals("REBUILD_IN_PROGRESS", controller.reasonCode());

    controller.refresh();

    assertEquals(
        EmbeddingCompatibilityController.State.REBUILDING,
        controller.state(),
        "refresh() must not clobber an in-flight rebuild");
    assertEquals(
        "REBUILD_IN_PROGRESS",
        controller.reasonCode(),
        "reason must stay REBUILD_IN_PROGRESS, not re-derive BLOCKED_LEGACY's reason");
  }

  @Test
  void checkRebuildCompletionCertifiesOnPendingZeroEvenWhenJobQueueNonEmpty() throws Exception {
    // Tempdoc 726 T2 (pins F1): a job stuck in PROCESSING — or any ongoing unrelated ingestion —
    // keeps the global queueDepth > 0. Certification must NOT depend on it: pending_embedding==0
    // alone certifies. On the OLD code (`queueDepth==0 && pendingEmbeddingCount==0`) this call with
    // queueDepth=7 returned false and REBUILDING persisted forever (the sandbox hang, reproduced 3x).
    //
    // Tempdoc 819 defect B NARROWS this test rather than deleting it. The queue-depth-doesn't-gate
    // intent above is intact and is what this still pins; what it must no longer assert is that
    // pending==0 is SUFFICIENT on its own — it isn't (failed docs are not pending, so an
    // all-failed rebuild satisfies it). The successful-embedding precondition is now supplied
    // explicitly, so the assertion below distinguishes "certified because the queue didn't gate"
    // from "certified because pending==0 was taken as proof of success".
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L, () -> 4 /* completed */);
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());

    boolean completed = controller.checkRebuildCompletion(7L /* non-empty job queue */, 0);

    assertTrue(completed);
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals("fake-sha256-for-test", controller.fingerprintToStamp().orElse(null));
  }

  @Test
  void refreshResolvesCompatibleOnAGenuinelyEmptyIndex() throws Exception {
    // Tempdoc 819 defect A coverage gap: EVERY other test in this class constructs the controller
    // with a non-zero docCount, so the empty-index fast path — the one a fresh install must take —
    // had no test at all. It shipped structurally unreachable in production (the Head's bundled
    // help batch committed before the controller existed) and nothing here noticed.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 0L, () -> 0);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals("NEW_INDEX_NO_FINGERPRINT", controller.reasonCode());
    // #470 D2: emptiness alone earns NOTHING. The old empty-index permit was the only mechanism
    // able to grant an unearned durable attestation — a document-less commit (delete/reset RPCs,
    // the background commit timer racing the write path's revocation) could spend it before the
    // first write, permanently stamping a fingerprint no embedding ever earned. So a fresh empty
    // index is COMPATIBLE (writes may proceed) but the stamp is withheld until real evidence.
    assertTrue(
        controller.fingerprintToStamp().isEmpty(),
        "a fresh empty index must NOT stamp on emptiness alone — the stamp is earned by the first"
            + " successful embedding, never granted vacuously");
  }

  @Test
  void firstSuccessfulEmbeddingEarnsTheStampOnAFreshIndex() throws Exception {
    // The positive half of #470 D2: once an embedding really succeeds, the attestation is earned
    // on its own merits and the very next commit may stamp.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 0L, () -> 0);
    controller.refresh();
    assertTrue(
        controller.fingerprintToStamp().isEmpty(), "precondition: no evidence, stamp withheld");

    controller.noteSuccessfulEmbeddingObserved();

    assertEquals("fake-sha256-for-test", controller.fingerprintToStamp().orElse(null));
  }

  @Test
  void anAlreadyStampedIndexKeepsStampingWithoutFreshEvidence() throws Exception {
    // Non-regression for the FINGERPRINT_MATCH boot: a healthy stamped index keeps indexing
    // documents forever, often with zero embedding runs in a given boot. If the stamp were
    // withheld there, the next commit would strip the fingerprint and the following boot would
    // re-derive BLOCKED_LEGACY and re-embed the whole corpus — strictly worse than the defect
    // being fixed. The on-disk attestation for THIS model is itself the earned evidence.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            () -> Map.of(
                EmbeddingCompatibilityController.COMMIT_META_KEY, "fake-sha256-for-test"),
            () -> 5L,
            () -> 0 /* no embedding runs this boot */);
    controller.refresh();
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals("FINGERPRINT_MATCH", controller.reasonCode());

    assertEquals(
        "fake-sha256-for-test",
        controller.fingerprintToStamp().orElse(null),
        "the attestation is already on disk for THIS model — later commits must keep offering it,"
            + " or the next commit would strip the fingerprint");
  }

  @Test
  void refreshFailsClosedWhenTheDocCountCannotBeRead() throws Exception {
    // Tempdoc 819 defect B: IndexCountOps.docCount() swallows IOException to 0, and 0 is exactly
    // the value refresh() reads as "new empty index — safe to stamp". A supplier that throws must
    // therefore resolve BLOCKED_LEGACY, never the fast path.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            Map::of,
            () -> {
              throw new java.io.UncheckedIOException(new java.io.IOException("reader gone"));
            },
            () -> 0);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
    assertEquals("LEGACY_INDEX_NO_FINGERPRINT", controller.reasonCode());
    assertTrue(controller.fingerprintToStamp().isEmpty());
  }

  @Test
  void checkRebuildCompletionRefusesWhenEveryEmbeddingFailed() throws Exception {
    // Tempdoc 819 defect B, reproduced live: embeddingDocCount=5 completed=0 pending=0 failed=5
    // coverage=0% certified COMPATIBLE and stamped the fingerprint over an index with zero vectors,
    // permanently closing the BLOCKED_LEGACY recovery path. Failed documents are not pending, so
    // pending==0 is the absence of outstanding work — not evidence of success.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L, () -> 0 /* none completed */);
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());

    boolean completed = controller.checkRebuildCompletion(0L, 0 /* nothing pending: all FAILED */);

    assertFalse(completed);
    assertEquals(
        EmbeddingCompatibilityController.State.REBUILDING,
        controller.state(),
        "a rebuild that produced nothing must not flip COMPATIBLE");
    assertEquals(
        EmbeddingCompatibilityController.REBUILD_FAILED_NO_VECTORS, controller.reasonCode());
    assertTrue(
        controller.fingerprintToStamp().isEmpty(),
        "and the attestation must not reach commit metadata either");
  }

  @Test
  void checkRebuildCompletionStopsReAttemptingAfterARefusal() throws Exception {
    // The refusal is terminal for the boot: refresh() is called exactly once per boot and the
    // backfill only picks up PENDING documents, so nothing within this process can change the
    // outcome. Re-attempting would re-log the ERROR on every loop tick.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            Map::of,
            () -> 5L,
            () -> {
              reads.incrementAndGet();
              return 0;
            });
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertFalse(controller.checkRebuildCompletion(0L, 0));
    assertFalse(controller.checkRebuildCompletion(0L, 0));
    assertFalse(controller.checkRebuildCompletion(0L, 0));

    assertEquals(1, reads.get(), "the index must be consulted once, not once per tick");
  }

  @Test
  void checkRebuildCompletionStillCertifiesThePoisonPillDistribution() throws Exception {
    // Tempdoc 813's "settled numerator" case, which the 819 narrowing must NOT break: some
    // documents embedded successfully, some are permanently FAILED. That rebuild genuinely
    // produced current-model vectors, so the attestation IS earned — "at least one success", not
    // "no failures", is the condition.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L, () -> 3 /* 3 ok, 2 FAILED */);
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertTrue(controller.checkRebuildCompletion(0L, 0));
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals("REBUILD_COMPLETED", controller.reasonCode());
    assertEquals("fake-sha256-for-test", controller.fingerprintToStamp().orElse(null));
  }

  @Test
  void checkRebuildCompletionRefusesButStaysRetryableWhenTheCountCannotBeRead() throws Exception {
    // A read failure is NOT the same fact as "zero completed": it must refuse (fail closed) without
    // burning the terminal refusal, so the next loop tick can try again once the reader recovers.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    java.util.concurrent.atomic.AtomicBoolean broken =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            Map::of,
            () -> 5L,
            () -> {
              if (broken.get()) {
                throw new java.io.UncheckedIOException(new java.io.IOException("reader gone"));
              }
              return 2;
            });
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertFalse(controller.checkRebuildCompletion(0L, 0), "unreadable count must not certify");
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    assertEquals(
        "REBUILD_IN_PROGRESS",
        controller.reasonCode(),
        "an unreadable count is not the terminal zero-vector verdict");

    broken.set(false);
    assertTrue(controller.checkRebuildCompletion(0L, 0), "the retry must be able to succeed");
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
  }

  @Test
  void anAlreadyStampedIndexKeepsOfferingItsFingerprintWithoutFreshEmbeddings() throws Exception {
    // Tempdoc 819 defect B guard-rail: the evidence gate governs the FIRST persistence of an
    // attestation, never the preservation of one already earned. A healthy stamped index that
    // simply restarts embeds nothing new — if fingerprintToStamp() demanded fresh evidence there,
    // the next timer commit would strip the fingerprint and the following boot would re-derive
    // BLOCKED_LEGACY and re-embed the whole corpus.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            () -> Map.of(
                EmbeddingCompatibilityController.COMMIT_META_KEY, "fake-sha256-for-test"),
            () -> 5L,
            () -> 0 /* no embedding runs this boot */);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals("fake-sha256-for-test", controller.fingerprintToStamp().orElse(null));
  }

  @Test
  void waivedEvidenceCertifiesWithoutConsultingTheIndex() throws Exception {
    // Tempdoc 819: the corruption-recovery rebuild waives the evidence requirement — the green is
    // rebuilt from source so its vectors are current-model by construction, and refusing there
    // would strand the user on the EMPTY blue the adapter recovered.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            Map::of,
            () -> 5L,
            () -> {
              throw new AssertionError("waived evidence must not consult the index");
            });
    controller.refresh();
    controller.permitStampWithoutEmbeddingEvidence("corrupt_index_rebuild");
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertTrue(controller.checkRebuildCompletion(0L, 0));
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals("fake-sha256-for-test", controller.fingerprintToStamp().orElse(null));
  }

  @Test
  void autoRescueTagsTheDiagnosticReasonForOperators() throws Exception {
    // Tempdoc 730 A4 telemetry: the corruption signature — completed > 0 (dense vectors already
    // exist) but no embedding fingerprint was ever persisted — must fire the rescue AND record a
    // diagnostic tag naming why it fired. That the rescue fires on this distribution is covered by
    // maybeAutoStartRebuildForBlockedLegacyFiresRegardlessOfDistribution above; what this pins is
    // the operator-facing tag, which is the surviving half of main's unattested-vectors test.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
    assertEquals("LEGACY_INDEX_NO_FINGERPRINT", controller.reasonCode());

    boolean started = controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertTrue(started);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    // The shared, contract-stable reason for query-time degradation messaging is unchanged — only
    // the diagnostic-only lastAutoRescueReason() tag names the auto-rescue path.
    assertEquals("REBUILD_IN_PROGRESS", controller.reasonCode());
    assertEquals("legacy_no_fingerprint", controller.lastAutoRescueReason());
  }

  @Test
  void autoRescueDoesNotFireOnProperlyStampedGeneration() throws Exception {
    // Negative control: a generation whose embedding fingerprint IS already persisted and
    // matches the current model must resolve COMPATIBLE at refresh() — the auto-rescue must be a
    // no-op since state() is never BLOCKED_LEGACY in the first place.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            () -> Map.of(
                EmbeddingCompatibilityController.COMMIT_META_KEY, "fake-sha256-for-test"),
            () -> 5L);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());

    assertFalse(controller.maybeAutoStartRebuildForBlockedLegacy(5L));
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertNull(controller.lastAutoRescueReason());
  }

  @Test
  void checkRebuildCompletionFinalizesRebuildAndEnablesFingerprintStamp() throws Exception {
    // Tempdoc 598 review Fix E: the migration cutover calls checkRebuildCompletion BEFORE the green
    // COMPLETE commit. On a fully-drained green (queue=0, pending=0) it must flip COMPATIBLE so
    // fingerprintToStamp() returns the fp — i.e. the COMPLETE commit deterministically stamps
    // embedding_model_sha256 (no race with the indexing-loop thread).
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L, () -> 5 /* all embedded */);
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    // While REBUILDING-not-yet-complete, there is nothing to stamp.
    assertTrue(controller.fingerprintToStamp().isEmpty());

    boolean completed = controller.checkRebuildCompletion(0L, 0);

    assertTrue(completed);
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals("fake-sha256-for-test", controller.fingerprintToStamp().orElse(null));
  }

  @Test
  void checkRebuildCompletionDoesNotFinalizeWhenEmbeddingsStillPending() throws Exception {
    // Fix E guard: a green that is NOT fully embedded must not flip COMPATIBLE, so its COMPLETE
    // commit lacks the fingerprint and the cutover verification correctly blocks promotion.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    boolean completed = controller.checkRebuildCompletion(0L, 3); // 3 docs still pending embeddings

    assertFalse(completed);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    assertTrue(controller.fingerprintToStamp().isEmpty());
  }
}
