package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingFingerprint;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 730 A3: {@link EmbeddingProviderLifecycle#tryFinalizeFreshCompatibleStamp()} forces
 * the stamp-persisting commit {@link EmbeddingProviderLifecycle#tryFinalizeRebuild()} guarantees
 * only for the REBUILDING path. The fresh-index -> COMPATIBLE path (docCount == 0 at open) never
 * enters REBUILDING, so without this method it has no equivalent guarantee that some commit
 * carried the embedding fingerprint before the queue drained (§DESIGN A3).
 *
 * <p>Tempdoc 730 review item 3 hardening: the method also gates on the LIVE {@link
 * EmbeddingFingerprint#get()}, not just the ECC's cached {@code currentFingerprint()}, and backs
 * off after a failed forced commit instead of retrying every drain. {@link EmbeddingFingerprint}
 * is a process-wide static cache, so every test here pins it explicitly rather than relying on
 * whatever model (if any) happens to be discoverable in the test environment.
 */
class EmbeddingProviderLifecycleTest {

  private static final String LIVE_FP = "live-embed-fp-sha256";

  @BeforeEach
  void fakeLiveFingerprintPresent() {
    EmbeddingFingerprint.setForTesting(LIVE_FP);
  }

  @AfterEach
  void clearLiveFingerprint() {
    EmbeddingFingerprint.invalidate();
  }

  private static EmbeddingProviderLifecycle newLifecycle(
      JobQueue jobQueue, IndexCountOps indexCountOps, CommitOps commitOps) {
    return new EmbeddingProviderLifecycle(
        mock(WorkerSignalBus.class), jobQueue, indexCountOps, commitOps);
  }

  @Test
  void firesForcedCommitWhenCompatibleWithDocsAndNoStoredFingerprint() {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    when(indexCountOps.docCount()).thenReturn(5L);

    EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.COMPATIBLE);
    when(ecc.storedFingerprint()).thenReturn(null);
    when(ecc.currentFingerprint()).thenReturn("fake-sha256");
    when(ecc.reconcileStampEvidence()).thenReturn(true);

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    boolean fired = lifecycle.tryFinalizeFreshCompatibleStamp();

    assertTrue(fired, "must fire the forced commit: COMPATIBLE + docs + no stored fingerprint");
    verify(commitOps).commitAndTrack(CommitReason.INDEXING_LOOP_FRESH_STAMP);
  }

  @Test
  void doesNotFireWhenTheStampEvidenceIsNotYetEarned() {
    // Tempdoc 821 §O.1 (+ #470 D2: no empty-index permit exists), so COMPATIBLE without evidence
    // is the normal fresh-install state — and there fingerprintToStamp() returns empty, so a
    // forced commit would persist NOTHING and refire on every drain (~once/sec). Decline instead.
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    when(indexCountOps.docCount()).thenReturn(5L);

    EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.COMPATIBLE);
    when(ecc.storedFingerprint()).thenReturn(null);
    when(ecc.currentFingerprint()).thenReturn("fake-sha256");
    when(ecc.reconcileStampEvidence()).thenReturn(false);

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(
        lifecycle.tryFinalizeFreshCompatibleStamp(),
        "no evidence yet — the commit would stamp nothing, so it must not be forced");
    verifyNoInteractions(commitOps);
  }

  @Test
  void doesNotFireWhenLiveFingerprintAbsentEvenIfCachedCurrentFingerprintPresent() {
    // Tempdoc 730 review item 3: the ECC's cached currentFingerprint() can go stale (the model
    // was released after the ECC last refreshed) while still reporting non-null. Gating on the
    // cache alone would incorrectly treat this as safe to stamp; the live check must catch it.
    EmbeddingFingerprint.setForTesting(null);

    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    when(indexCountOps.docCount()).thenReturn(5L);

    EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.COMPATIBLE);
    when(ecc.storedFingerprint()).thenReturn(null);
    when(ecc.currentFingerprint()).thenReturn("stale-cached-sha256"); // cache still non-null

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(
        lifecycle.tryFinalizeFreshCompatibleStamp(),
        "the cached currentFingerprint() alone must not be enough — the live fingerprint is gone");
    verifyNoInteractions(commitOps);
  }

  @Test
  void backsOffAfterFailedCommitInsteadOfRetryingEveryDrain() {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    when(indexCountOps.docCount()).thenReturn(5L);
    doThrow(new RuntimeException("simulated persistently-failing commit"))
        .when(commitOps)
        .commitAndTrack(CommitReason.INDEXING_LOOP_FRESH_STAMP);

    EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.COMPATIBLE);
    when(ecc.storedFingerprint()).thenReturn(null);
    when(ecc.currentFingerprint()).thenReturn("fake-sha256");
    when(ecc.reconcileStampEvidence()).thenReturn(true);

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(lifecycle.tryFinalizeFreshCompatibleStamp(), "first attempt fails");
    assertFalse(
        lifecycle.tryFinalizeFreshCompatibleStamp(),
        "must not retry immediately after a failure (would hammer a persistently-failing commit"
            + " every idle drain, ~once/sec)");
    assertFalse(lifecycle.tryFinalizeFreshCompatibleStamp(), "still within the backoff window");

    verify(commitOps, times(1)).commitAndTrack(CommitReason.INDEXING_LOOP_FRESH_STAMP);
  }

  @Test
  void backoffResetsWhenStateLeavesCompatible() {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    when(indexCountOps.docCount()).thenReturn(5L);
    doThrow(new RuntimeException("simulated persistently-failing commit"))
        .doNothing()
        .when(commitOps)
        .commitAndTrack(CommitReason.INDEXING_LOOP_FRESH_STAMP);

    EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
    when(ecc.storedFingerprint()).thenReturn(null);
    when(ecc.currentFingerprint()).thenReturn("fake-sha256");
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.COMPATIBLE);
    when(ecc.reconcileStampEvidence()).thenReturn(true);

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(lifecycle.tryFinalizeFreshCompatibleStamp(), "first attempt fails, backoff set");
    assertFalse(
        lifecycle.tryFinalizeFreshCompatibleStamp(), "still backing off while state is COMPATIBLE");

    // ECC transitions away from COMPATIBLE (e.g. a new forced reindex starts) and back — a
    // genuinely new need to stamp must not be stuck behind the stale backoff window.
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.REBUILDING);
    assertFalse(lifecycle.tryFinalizeFreshCompatibleStamp(), "REBUILDING is not this method's state");

    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.COMPATIBLE);
    assertTrue(
        lifecycle.tryFinalizeFreshCompatibleStamp(),
        "back in COMPATIBLE after a state change must retry immediately, not wait out the backoff");

    verify(commitOps, times(2)).commitAndTrack(CommitReason.INDEXING_LOOP_FRESH_STAMP);
  }

  @Test
  void doesNotFireWhenStoredFingerprintAlreadyPersisted() {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    when(indexCountOps.docCount()).thenReturn(5L);

    EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.COMPATIBLE);
    when(ecc.storedFingerprint()).thenReturn("already-stamped-sha256");

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(
        lifecycle.tryFinalizeFreshCompatibleStamp(),
        "must not fire again once the stamp is already persisted");
    verifyNoInteractions(commitOps);
  }

  @Test
  void doesNotFireOutsideCompatibleState() {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);

    EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.REBUILDING);

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(
        lifecycle.tryFinalizeFreshCompatibleStamp(),
        "REBUILDING is tryFinalizeRebuild()'s territory, not this method's");
    verifyNoInteractions(commitOps);
  }

  @Test
  void doesNotFireWhenModelNotYetProducingFingerprint() {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    when(indexCountOps.docCount()).thenReturn(5L);

    EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.COMPATIBLE);
    when(ecc.storedFingerprint()).thenReturn(null);
    when(ecc.currentFingerprint()).thenReturn(null);

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(
        lifecycle.tryFinalizeFreshCompatibleStamp(),
        "no live fingerprint to stamp yet — retry on a later drain");
    verifyNoInteractions(commitOps);
  }

  @Test
  void doesNotFireWithZeroDocs() {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    when(indexCountOps.docCount()).thenReturn(0L);

    EmbeddingCompatibilityController ecc = mock(EmbeddingCompatibilityController.class);
    when(ecc.state()).thenReturn(EmbeddingCompatibilityController.State.COMPATIBLE);
    when(ecc.storedFingerprint()).thenReturn(null);
    when(ecc.currentFingerprint()).thenReturn("fake-sha256");

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(
        lifecycle.tryFinalizeFreshCompatibleStamp(),
        "an empty index has nothing to stamp for yet");
    verifyNoInteractions(commitOps);
  }

  @Test
  void doesNotFireWhenNoControllerWired() {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);

    EmbeddingProviderLifecycle lifecycle =
        newLifecycle(mock(JobQueue.class), indexCountOps, commitOps);

    assertFalse(lifecycle.tryFinalizeFreshCompatibleStamp());
    verifyNoInteractions(commitOps);
  }

  @Test
  void normalFinalizeRefusesWhenStrictPendingCountCannotBeRead() throws IOException {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.queueDepth()).thenReturn(0L);

    // Keep the swallowing API independently "safe-looking": a restored implementation that calls
    // countByField() would observe zero and incorrectly certify on its second attempt.
    when(indexCountOps.countByField(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING))
        .thenReturn(0);
    when(indexCountOps.countByFieldOrThrow(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING))
        .thenThrow(new IOException("simulated reader failure"));

    EmbeddingCompatibilityController ecc = rebuildingWithPriorSuccessfulWriteEvidence();
    EmbeddingProviderLifecycle lifecycle = newLifecycle(jobQueue, indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(lifecycle.tryFinalizeRebuild(), "first strict read failure must refuse");
    assertFalse(lifecycle.tryFinalizeRebuild(), "second strict read failure must refuse");
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
    verify(indexCountOps, times(2))
        .countByFieldOrThrow(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    verify(indexCountOps, never())
        .countByField(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    verifyNoInteractions(commitOps);
  }

  @Test
  void shutdownFinalizeRefusesWhenStrictPendingCountCannotBeRead() throws IOException {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.queueDepth()).thenReturn(0L);

    // A one-shot shutdown finalize has no second-read opportunity; an unreadable count is still
    // refusal, even when the legacy swallowing method is stubbed to return zero.
    when(indexCountOps.countByField(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING))
        .thenReturn(0);
    when(indexCountOps.countByFieldOrThrow(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING))
        .thenThrow(new IOException("simulated shutdown reader failure"));

    EmbeddingCompatibilityController ecc = rebuildingWithPriorSuccessfulWriteEvidence();
    EmbeddingProviderLifecycle lifecycle = newLifecycle(jobQueue, indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(
        lifecycle.tryFinalizeRebuildAtShutdown(),
        "shutdown must refuse when the strict pending count is unreadable");
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
    verify(indexCountOps)
        .countByFieldOrThrow(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    verify(indexCountOps, never())
        .countByField(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    verifyNoInteractions(commitOps);
  }

  @Test
  void transientPendingCountReadFailureResetsTwoReadCertificationStreak() throws IOException {
    CommitOps commitOps = mock(CommitOps.class);
    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.queueDepth()).thenReturn(0L);

    // This sequence must be false,false,false,true: the fault between the first and second zero
    // clears the prior zero, so certification needs two fresh successful zero reads.
    when(indexCountOps.countByField(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING))
        .thenReturn(0);
    when(indexCountOps.countByFieldOrThrow(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING))
        .thenReturn(0)
        .thenThrow(new IOException("simulated transient reader failure"))
        .thenReturn(0)
        .thenReturn(0);

    EmbeddingCompatibilityController ecc = rebuildingWithPriorSuccessfulWriteEvidence();
    EmbeddingProviderLifecycle lifecycle = newLifecycle(jobQueue, indexCountOps, commitOps);
    lifecycle.setEmbeddingCompatController(ecc);

    assertFalse(lifecycle.tryFinalizeRebuild(), "first zero only arms the guard");
    assertFalse(lifecycle.tryFinalizeRebuild(), "read failure must refuse and reset the guard");
    assertFalse(lifecycle.tryFinalizeRebuild(), "first zero after the fault only re-arms it");
    assertTrue(lifecycle.tryFinalizeRebuild(), "second fresh zero certifies the evidenced rebuild");
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());
    verify(indexCountOps, times(4))
        .countByFieldOrThrow(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    verify(indexCountOps, never())
        .countByField(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    verify(commitOps).commitAndTrack(CommitReason.INDEXING_LOOP_REBUILD_STAMP);
  }

  private static EmbeddingCompatibilityController rebuildingWithPriorSuccessfulWriteEvidence() {
    EmbeddingCompatibilityController ecc =
        new EmbeddingCompatibilityController(Map::of, () -> 2L, () -> 2);
    ecc.refresh();
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, ecc.state());
    ecc.onForcedReindexRequested();
    ecc.noteSuccessfulEmbeddingObserved();
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
    return ecc;
  }

}
