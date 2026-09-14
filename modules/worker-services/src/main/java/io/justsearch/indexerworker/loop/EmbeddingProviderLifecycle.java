/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingFingerprint;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.embed.NoopEmbeddingTelemetryEvents;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the embedding-provider lifecycle: setters, change listeners, GPU handoff,
 * unload, the rebuild-finalize commit decision, and the post-commit
 * stored-fingerprint refresh.
 *
 * <p>Tempdoc 516 Slice 4c — extracted from {@link IndexingLoop} per Appendix A.1.
 * The 309 §33 regression history (single-Consumer slot dropped KnowledgeServer's
 * nulling on unload) is preserved by keeping the dual primary-slot + additional-
 * listener-list pattern. The {@link Object} lifecycle lock + the
 * {@code lastMainGpuActiveState} double-check pattern are preserved exactly so the
 * cross-process GPU handoff protocol from ADR-0004 keeps its semantics.
 *
 * <p>Cross-seam interactions, per Appendix A.6:
 * <ul>
 *   <li>{@link #tryFinalizeRebuild()} (constraint #11) makes the rebuild-stamp
 *       commit decision and fires the commit + ECC stamp, then returns true so the
 *       caller can reset its commit-driver bookkeeping
 *       ({@code lastCommitTime}, {@code indexedSinceCommit}). The lifecycle
 *       cannot mutate those fields directly — they belong to the loop residue.
 *   <li>{@link #refreshStoredFingerprintAfterCommit(CommitReason)} (constraint
 *       #12) is the {@link CommitOps.CommitCompletedListener} target; registered
 *       by {@code IndexingLoop} at construction so the three previously-scattered
 *       refresh call sites collapse into a single subscription.
 * </ul>
 *
 * <p>P5 boundary: a concrete class, not a strategy. Multi-listener semantics use a
 * single-slot primary + a {@link CopyOnWriteArrayList} of additionals (per the 309
 * §33 fix) — a closed pattern, not an extensible event bus.
 */
public final class EmbeddingProviderLifecycle {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingProviderLifecycle.class);

  private final WorkerSignalBus signalBus;
  private final JobQueue jobQueue;
  private final IndexCountOps indexCountOps;
  private final CommitOps commitOps;

  private volatile EmbeddingProvider embeddingProvider = NoOpEmbeddingProvider.INSTANCE;
  private volatile EmbeddingService embeddingServiceForLifecycle;
  private volatile EmbeddingTelemetryEvents embeddingEvents = NoopEmbeddingTelemetryEvents.INSTANCE;
  private volatile EmbeddingCompatibilityController embeddingCompatController;

  /**
   * GPU lifecycle mutex. Acquired by {@link #handleGpuStateTransition()} around the
   * double-check + unload sequence. Initial {@link #lastMainGpuActiveState} is
   * {@code false}: per 309 §33, the worker must start in "Main hasn't claimed GPU"
   * so no spurious RELOADING transition fires on startup.
   */
  private final Object embeddingLifecycleLock = new Object();
  private volatile boolean lastMainGpuActiveState = false;

  /**
   * Two-consecutive-reads guard for {@link #tryFinalizeRebuild()} (tempdoc 726 F1). Counts how many
   * consecutive finalize attempts have observed {@code pendingEmbeddings==0}; certification fires only
   * on the second, guarding a mid-flush race where a document is written between the count and the
   * stamp. Mutated on the single indexing-loop thread only.
   */
  private int pendingZeroStreak = 0;

  /**
   * Last {@code pendingEmbeddings} value logged by the finalize-waiting notice, so the on-change
   * INFO fires when the value moves rather than on every loop iteration. {@code -1} = not yet logged.
   */
  private int lastLoggedWaitPending = -1;

  /**
   * WARN-once latch for a failing {@code jobQueue.queueDepth()} diagnostics read in
   * {@link #tryFinalizeRebuild()}; reset on the next successful read. Loop-thread only.
   */
  private boolean queueDepthReadFailureLogged = false;

  private volatile Consumer<EmbeddingProvider> embeddingProviderChangeListener;

  /** Tempdoc 518 Appendix F W4.3 — additional-listeners branch migrated to the shared
   *  substrate. The primary single-slot listener above keeps its volatile single-slot
   *  semantics (different shape per Appendix E §E.3's 2.5-of-3 framing). */
  private final io.justsearch.observable.ObservableNotifier<EmbeddingProvider>
      additionalChangeListeners =
          new io.justsearch.observable.ObservableNotifier<>(
              "EmbeddingProviderChangeListener");

  public EmbeddingProviderLifecycle(
      WorkerSignalBus signalBus,
      JobQueue jobQueue,
      IndexCountOps indexCountOps,
      CommitOps commitOps) {
    this.signalBus = signalBus;
    this.jobQueue = jobQueue;
    this.indexCountOps = indexCountOps;
    this.commitOps = commitOps;
  }

  // ---- setters / accessors ----

  public void setEmbeddingProvider(EmbeddingProvider provider) {
    this.embeddingProvider = provider != null ? provider : NoOpEmbeddingProvider.INSTANCE;
    this.embeddingServiceForLifecycle = provider instanceof EmbeddingService es ? es : null;
  }

  public EmbeddingProvider embeddingProvider() {
    return embeddingProvider;
  }

  public void setEmbeddingTelemetryEvents(EmbeddingTelemetryEvents events) {
    this.embeddingEvents = events != null ? events : NoopEmbeddingTelemetryEvents.INSTANCE;
  }

  public void setEmbeddingCompatController(EmbeddingCompatibilityController controller) {
    this.embeddingCompatController = controller;
  }

  public EmbeddingCompatibilityController embeddingCompatController() {
    return embeddingCompatController;
  }

  public void setEmbeddingProviderChangeListener(Consumer<EmbeddingProvider> listener) {
    this.embeddingProviderChangeListener = listener;
  }

  public void addEmbeddingProviderChangeListener(Consumer<EmbeddingProvider> listener) {
    if (listener != null) {
      additionalChangeListeners.register(listener);
    }
  }

  // ---- gates ----

  /** Returns true iff embedding writes are currently permitted (per the ECC). */
  public boolean allowEmbeddingWrites() {
    var controller = embeddingCompatController;
    return controller == null || controller.allowEmbeddingWrites();
  }

  // ---- GPU handoff ----

  /**
   * Handles cross-process GPU state transitions (ADR-0004). When Main claims the GPU
   * the embedding model is unloaded if it uses VRAM; when Main releases, the
   * {@code SessionHandle}'s {@code releaseGpu()/acquire()} pair lazily reacquires on
   * next use — no reload here.
   *
   * <p>Tempdoc 397 §14.11 Stage 4b: no reload. Tempdoc 309 §33: initial state
   * preserved so no spurious RELOADING fires.
   */
  public void handleGpuStateTransition() {
    boolean currentGpuActiveState = signalBus.isMainGpuActive();
    if (currentGpuActiveState == lastMainGpuActiveState) {
      return;
    }
    synchronized (embeddingLifecycleLock) {
      if (currentGpuActiveState == lastMainGpuActiveState) {
        return;
      }
      if (currentGpuActiveState) {
        if (embeddingProvider.isUsingGpu()) {
          releaseEmbeddingGpuSession();
        } else {
          log.info("GPU transition: Main claimed GPU, but embeddings are CPU-only - continuing without unload");
        }
      } else {
        log.info(
            "GPU transition: Main released GPU — Worker will reclaim VRAM on next embed acquire");
      }
      lastMainGpuActiveState = currentGpuActiveState;
    }
  }

  /**
   * Tempdoc 598 R4: on the ADR-0004 GPU handoff (Main claims the GPU for the Online chat model) the
   * embedder yields its GPU session — freeing VRAM — but the {@link EmbeddingService} stays alive so
   * query embedding continues on the deferred CPU session. This replaces the former full
   * {@link #unloadEmbeddingService() unload-to-NoOp}, which made semantic search and RAG go dead the
   * moment chat came Online (598 PART I). The provider is NOT swapped to {@code NoOp} and listeners
   * are NOT notified, so the search path keeps issuing dense legs (now CPU-served) and AUTO
   * retrieval stays HYBRID instead of collapsing to keyword.
   *
   * <p>Bulk backfill stays paused regardless: {@code LoopPacingPolicy.shouldRunBackfill} keys off
   * {@code mainGpuActive} AND {@code isUsingGpu()} (the static config flag, still {@code true} after
   * the live GPU session is released), so the loop does not resume bulk embedding on the CPU. On the
   * falling edge (Main releases the GPU) the handle lazily re-creates its GPU session on the next
   * acquire — no reload here, exactly as before.
   *
   * <p>Package-private so tests can invoke directly.
   */
  void releaseEmbeddingGpuSession() {
    EmbeddingService svc = embeddingServiceForLifecycle;
    if (svc == null) {
      log.debug("GPU transition: No embedding service to release");
      return;
    }
    log.info(
        "GPU transition: RELEASING embedding GPU session, keeping CPU query path (Main claimed GPU"
            + " for Online Mode)");
    embeddingEvents.onUnload(EmbeddingTelemetryEvents.UnloadReason.GPU_HANDOFF);
    try {
      svc.releaseGpuSession();
    } catch (Exception e) {
      log.warn("GPU transition: Error releasing embedding GPU session", e);
    }
    log.info(
        "GPU transition: Embedding GPU session released, VRAM yielded; query-embed continues on"
            + " CPU");
  }

  /**
   * Full unload to {@code NoOp} (close + provider swap + notify). Retained for shutdown/test paths;
   * the live GPU handoff now uses {@link #releaseEmbeddingGpuSession()} (tempdoc 598 R4) so query
   * embedding survives Online. Package-private so tests can invoke directly via reflection.
   */
  void unloadEmbeddingService() {
    EmbeddingService svc = embeddingServiceForLifecycle;
    if (svc == null) {
      log.debug("GPU transition: No embedding service to unload");
      return;
    }
    log.info("GPU transition: UNLOADING embedding model (Main claimed GPU for Online Mode)");
    embeddingEvents.onUnload(EmbeddingTelemetryEvents.UnloadReason.GPU_HANDOFF);
    try {
      svc.close();
    } catch (Exception e) {
      log.warn("GPU transition: Error unloading embedding service", e);
    }
    embeddingProvider = NoOpEmbeddingProvider.INSTANCE;
    embeddingServiceForLifecycle = null;
    notifyEmbeddingProviderChange(NoOpEmbeddingProvider.INSTANCE);
    log.info("GPU transition: Embedding model unloaded, VRAM released");
  }

  private void notifyEmbeddingProviderChange(EmbeddingProvider provider) {
    Consumer<EmbeddingProvider> listener = embeddingProviderChangeListener;
    if (listener != null) {
      try {
        listener.accept(provider);
      } catch (Exception e) {
        log.warn("GPU transition: Embedding provider change listener failed", e);
      }
    }
    // Tempdoc 518 Appendix F W4.3 — substrate-driven dispatch. The notifier owns the
    // exception-swallow + log on each subscriber; we keep the WARN call-site message
    // shape so existing log scrapers and operator playbooks are unaffected.
    additionalChangeListeners.notifyAll(provider);
  }

  // ---- rebuild-finalize ----

  /**
   * Returns true iff the lifecycle has just issued the rebuild-stamp commit
   * (constraint #11). When true, the caller must reset its commit-driver
   * bookkeeping ({@code lastCommitTime}, {@code indexedSinceCommit}) and call
   * {@code metrics.recordCommit()}. Returning the decision (rather than mutating
   * those fields here) keeps the commit-driver counter a residue-only mutation.
   *
   * <p>This is the only point where an intentional empty commit is forced to
   * persist the updated fingerprint metadata.
   */
  public boolean tryFinalizeRebuild() {
    return finalizeRebuild(false);
  }

  /**
   * One-shot rebuild finalize for the shutdown path — same certification, minus the
   * two-consecutive-reads debounce.
   *
   * <p>The debounce (tempdoc 726 F1) guards a mid-flush race: during a RUNNING loop a document can
   * be written between the pending count and the stamp, so a lone {@code pending == 0} read is not
   * yet proof the rebuild drained. That reasoning does not hold at shutdown, where the loop has
   * stopped consuming and no further documents will be written — and there, requiring a second read
   * is actively harmful: {@code IndexingLoop.finalizeShutdownCommit()} gets exactly ONE call. A
   * worker that stops right after rebuild completion (before any further loop iteration) would
   * leave {@code pendingZeroStreak == 0}, the single debounced call would decline, the stamp would
   * never persist, and the next boot's {@code refresh()} would re-flag BLOCKED_LEGACY on a rebuild
   * that genuinely completed — exactly the "no subsequent commit" ratchet hole tempdoc 730 review
   * item 2 exists to close. Mirrors the existing one-shot precedent {@code
   * finalizeEmbeddingRebuildBeforeCutover}, which likewise certifies once on an already-drained
   * green.
   */
  public boolean tryFinalizeRebuildAtShutdown() {
    return finalizeRebuild(true);
  }

  private boolean finalizeRebuild(boolean oneShot) {
    var controller = embeddingCompatController;
    if (controller == null) return false;
    if (controller.state() != EmbeddingCompatibilityController.State.REBUILDING) {
      pendingZeroStreak = 0;
      return false;
    }

    // queueDepth is read for diagnostics only (tempdoc 726 F1) — it does NOT gate certification, so
    // a failed read must not block the finalize attempt either (a broken diagnostics read would
    // otherwise re-create the certification livelock this fix removes). -1 = read failed.
    long queueDepth;
    try {
      queueDepth = jobQueue.queueDepth();
      queueDepthReadFailureLogged = false;
    } catch (Exception e) {
      if (!queueDepthReadFailureLogged) {
        log.warn(
            "tryFinalizeRebuild: failed to read job queue depth (diagnostics only; continuing)", e);
        queueDepthReadFailureLogged = true;
      }
      queueDepth = -1L;
    }

    int pendingEmbeddings;
    try {
      pendingEmbeddings =
          indexCountOps.countByFieldOrThrow(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    } catch (Exception e) {
      pendingZeroStreak = 0;
      log.warn(
          "tryFinalizeRebuild: failed to read pending-embedding count; skipping this finalize"
              + " attempt",
          e);
      return false;
    }

    // F1: certify on the embedding-scoped fact pendingEmbeddings==0, confirmed on two consecutive
    // reads to guard a mid-flush race (a doc written between the count and the stamp). The global
    // queue depth (incl. jobs stuck in PROCESSING) is deliberately not consulted.
    if (pendingEmbeddings != 0) {
      pendingZeroStreak = 0;
      maybeLogFinalizeWaiting(pendingEmbeddings, queueDepth);
      return false;
    }
    if (!oneShot && ++pendingZeroStreak < 2) {
      maybeLogFinalizeWaiting(pendingEmbeddings, queueDepth);
      return false;
    }

    if (!controller.checkRebuildCompletion(queueDepth, pendingEmbeddings)) {
      pendingZeroStreak = 0;
      return false;
    }

    try {
      commitOps.commitAndTrack(CommitReason.INDEXING_LOOP_REBUILD_STAMP);
      controller.onFingerprintStamped();
      pendingZeroStreak = 0;
      lastLoggedWaitPending = -1;
      return true;
    } catch (RuntimeException e) {
      log.error("Failed to commit embedding fingerprint after rebuild completion", e);
      pendingZeroStreak = 0;
      return false;
    }
  }

  /**
   * On-change INFO so a stuck rebuild is one grep away without flooding the log every loop iteration
   * (tempdoc 726 F2). Logs only when {@code pendingEmbeddings} moves from the last logged value.
   */
  private void maybeLogFinalizeWaiting(int pendingEmbeddings, long queueDepth) {
    if (pendingEmbeddings != lastLoggedWaitPending) {
      log.info(
          "embedding rebuild finalize waiting: pendingEmbeddings={} (queueDepth={})",
          pendingEmbeddings,
          queueDepth);
      lastLoggedWaitPending = pendingEmbeddings;
    }
  }

  // ---- A3: fresh-index -> COMPATIBLE stamp guarantee (tempdoc 730) ----

  /**
   * Tempdoc 730 review hardening (item 3): backoff gate for {@link
   * #tryFinalizeFreshCompatibleStamp()} so a persistently-failing forced commit does not retry on
   * every idle/batch drain (as often as once per second). {@code 0} means "no attempt currently
   * backing off" — either nothing has failed yet, the last attempt succeeded, or the guard's
   * preconditions stopped holding (state left COMPATIBLE) since the last failure, which resets
   * this to {@code 0} so a later, genuinely new need to stamp is not stuck behind a stale window.
   */
  private final AtomicLong freshStampBackoffUntilMs = new AtomicLong(0L);

  /** At most one retry attempt per this window after a failed fresh-stamp commit. */
  private static final long FRESH_STAMP_RETRY_BACKOFF_MS = TimeUnit.MINUTES.toMillis(5);

  /**
   * Tempdoc 730 A3: forces an intentional commit the first time a COMPATIBLE index with existing
   * documents still lacks a persisted embedding fingerprint, outside the REBUILDING path.
   *
   * <p>{@link #tryFinalizeRebuild()} is the only intentional-commit guarantee that exists today,
   * and it is REBUILDING-only. The fresh-index -> COMPATIBLE path (docCount == 0 at open ->
   * {@code NEW_INDEX_NO_FINGERPRINT} -> COMPATIBLE) never enters REBUILDING, so — even with A1's
   * unconditional stamp supplier — its persistence still depends on an *ordinary* commit landing
   * while the model is actually producing a fingerprint at that instant. If the finalizing commit
   * lands before the fingerprint is available, the omission can persist indefinitely: ordinary
   * idle commits only fire when there is new content to commit ({@code indexedSinceCommit > 0}),
   * so a steady-state index with no further writes never gets another chance. This closes that
   * window: once the controller reports COMPATIBLE with docs and a model fingerprint, but the
   * last-known stored fingerprint is still absent, the next idle/post-batch drain fires one
   * forced commit to persist it.
   *
   * @return true iff this call issued the stamp-persisting commit (caller resets its
   *     commit-driver bookkeeping, mirroring {@link #tryFinalizeRebuild()}).
   */
  public boolean tryFinalizeFreshCompatibleStamp() {
    var controller = embeddingCompatController;
    if (controller == null) return false;
    if (controller.state() != EmbeddingCompatibilityController.State.COMPATIBLE) {
      // Left COMPATIBLE (or never entered it) — any prior backoff no longer applies to whatever
      // triggers the next genuine need to stamp.
      freshStampBackoffUntilMs.set(0L);
      return false;
    }

    String stored = controller.storedFingerprint();
    if (stored != null && !stored.isBlank()) return false; // already persisted

    String current = controller.currentFingerprint();
    if (current == null || current.isBlank()) return false; // model not producing a fp yet

    // Tempdoc 730 review hardening (item 3): also require the LIVE fingerprint, not just the
    // ECC's cached currentFingerprint() snapshot. The two can diverge (the model going offline
    // after the ECC last refreshed, without a fresh refresh() to notice); gating on the cache
    // alone let this method believe stamping was safe and refire its forced commit on every idle
    // drain instead of correctly declining.
    if (EmbeddingFingerprint.get().isEmpty()) return false;

    long docCount;
    try {
      docCount = indexCountOps.docCount();
    } catch (Exception e) {
      return false;
    }
    if (docCount <= 0) return false;

    // Tempdoc 821 §O.1 (+ #470 D2: the empty-index permit is gone entirely), so COMPATIBLE
    // without stamp evidence is reachable — and there the stamp supplier returns empty, so the
    // forced commit below would persist NOTHING and refire on every drain (a commit storm).
    // reconcileStampEvidence() is also the pull-side that latches the embedding backfill's
    // successes, replacing a push signal from EmbeddingBackfillOps. It reads the index, which is
    // why it sits after the docCount check rather than at the top of the guard chain.
    if (!controller.reconcileStampEvidence()) return false;

    long now = System.currentTimeMillis();
    long backoffUntil = freshStampBackoffUntilMs.get();
    if (backoffUntil != 0L && now < backoffUntil) {
      return false; // a prior attempt failed; not due for retry yet
    }

    try {
      commitOps.commitAndTrack(CommitReason.INDEXING_LOOP_FRESH_STAMP);
      freshStampBackoffUntilMs.set(0L);
      return true;
    } catch (RuntimeException e) {
      if (backoffUntil == 0L) {
        // First failure since the last success/state-change: log once, then go quiet until the
        // backoff window elapses instead of logging + retrying every drain.
        log.error("Failed to commit embedding fingerprint stamp for fresh-compatible index", e);
      }
      freshStampBackoffUntilMs.set(now + FRESH_STAMP_RETRY_BACKOFF_MS);
      return false;
    }
  }

  // ---- commit-completed listener target (constraint #12) ----

  /**
   * Target for {@link CommitOps.CommitCompletedListener} registration in
   * {@code IndexingLoop}'s constructor. Best-effort: syncs ECC's cached
   * {@code storedFingerprint} with what Lucene actually persisted. Replaces the
   * three previously-scattered {@code refreshEccStoredFingerprint} calls at
   * IndexingLoop's idle / time-buffer / shutdown commit sites.
   *
   * <p>Skipped for {@link CommitReason#INDEXING_LOOP_REBUILD_STAMP}: that path is the
   * rebuild-stamp commit driven by {@link #tryFinalizeRebuild()}, which calls
   * {@link EmbeddingCompatibilityController#onFingerprintStamped()} immediately after
   * the commit. {@code onFingerprintStamped} writes the new fingerprint directly into
   * ECC's stored field (and transitions state to {@code COMPATIBLE}). Calling
   * {@code refreshStoredFingerprintAfterCommit} too would re-read the same value from
   * Lucene's commit metadata — at best a redundant read, at worst a subtle ordering
   * dependency. Pre-Slice-4c the rebuild-stamp path never called refresh; preserve
   * that semantics.
   */
  public void refreshStoredFingerprintAfterCommit(CommitReason reason) {
    if (reason == CommitReason.INDEXING_LOOP_REBUILD_STAMP) {
      return;
    }
    var controller = embeddingCompatController;
    if (controller != null) {
      controller.refreshStoredFingerprintAfterCommit();
    }
  }
}
