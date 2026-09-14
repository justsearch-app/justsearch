/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controls embedding/vector compatibility based on model fingerprint matching.
 *
 * <p>This controller implements the safety policy to prevent silent data corruption when
 * the embedding model changes while vectors exist in the index. It gates both embedding
 * writes (during indexing) and embedding queries (vector/hybrid search).
 *
 * <h2>States</h2>
 * <ul>
 *   <li><b>COMPATIBLE</b>: stored fingerprint == current fingerprint → allow everything</li>
 *   <li><b>BLOCKED_LEGACY</b>: stored fingerprint missing → whole-index legacy recovery required</li>
 *   <li><b>BLOCKED_MISMATCH</b>: stored fingerprint != current → full-generation migration required</li>
 *   <li><b>REBUILDING</b>: whole-index re-embedding established, waiting for completion</li>
 *   <li><b>UNAVAILABLE</b>: no current model available → embeddings unavailable</li>
 * </ul>
 *
 * <h2>Policy</h2>
 * <ul>
 *   <li>During BLOCKED_* states, embedding writes and vector/hybrid queries are blocked.</li>
 *   <li>Whole-index recovery establishes re-embedding before transitioning to REBUILDING.
 *       A selected force-ingest request cannot establish global provenance.</li>
 *   <li>When rebuild completes — {@code pending_embedding == 0} <b>and</b> evidence that at least
 *       one embedding actually succeeded (tempdoc 819 defect B) — stamp the fingerprint and
 *       transition to COMPATIBLE. The global job queue is deliberately NOT part of the
 *       certification condition; the success evidence is, because {@code pending == 0} is the
 *       absence of outstanding work, not proof of success: a rebuild in which every document
 *       FAILED satisfies it and would otherwise stamp an attestation over zero vectors.</li>
 * </ul>
 *
 * <h2>Stamp evidence (tempdoc 819 defect B)</h2>
 *
 * <p>{@link #fingerprintToStamp()} offers the fingerprint only when the attestation is EARNED. It
 * is on the every-commit hot path ({@code CommitOps.commit()}), so the check is IO-free and reads
 * flags established elsewhere. The stamp is permitted when any of these holds:
 *
 * <ul>
 *   <li>the attestation is already persisted on disk and matches the current model — withholding it
 *       from later commits would <i>un-stamp</i> a healthy index, which is strictly worse;</li>
 *   <li>at least one successful embedding has been observed (monotone latch); or</li>
 *   <li>the stamp was explicitly waived for a caller that can prove provenance structurally
 *       ({@link #permitStampWithoutEmbeddingEvidence}).</li>
 * </ul>
 *
 * <p>Deliberately NOT a permitting fact: "the index was empty at {@link #refresh()}". An
 * empty-index permit was the only mechanism able to grant an <i>unearned durable</i> attestation —
 * a document-less commit (delete/reset RPCs, the background commit timer) could spend it before the
 * first write revoked it, permanently stamping a fingerprint no embedding ever earned (#470
 * adversarial review, D2). An empty index needs no stamp: the next boot re-derives
 * {@code NEW_INDEX_NO_FINGERPRINT} from {@code docCount == 0}, and the stamp lands the moment real
 * evidence exists (inline embed via the write path, or the backfill via
 * {@code reconcileStampEvidence}).
 */
public final class EmbeddingCompatibilityController {
  private static final Logger log = LoggerFactory.getLogger(EmbeddingCompatibilityController.class);

  /** Commit metadata key for the embedding model fingerprint. */
  public static final String COMMIT_META_KEY = "embedding_model_sha256";

  /**
   * Compatibility state enum.
   */
  public enum State {
    /** Stored fingerprint matches current → allow embedding writes + vector/hybrid queries. */
    COMPATIBLE,
    /** No stored fingerprint (legacy index) → whole-index legacy recovery required. */
    BLOCKED_LEGACY,
    /** Stored fingerprint != current → full-generation migration required. */
    BLOCKED_MISMATCH,
    /** Whole-index re-embedding established, waiting for completion. */
    REBUILDING,
    /** No current embedding model available → embeddings unavailable. */
    UNAVAILABLE
  }

  /**
   * Terminal reason code for a rebuild that drained ({@code pending == 0}) without a single
   * successful embedding (tempdoc 819 defect B). Distinct from {@code REBUILD_IN_PROGRESS} so an
   * operator can tell "still working" from "finished with nothing to show"; part of the
   * {@code SearchReasonCode} wire vocabulary.
   */
  public static final String REBUILD_FAILED_NO_VECTORS = "REBUILD_FAILED_NO_VECTORS";

  private final Supplier<Map<String, String>> storedMetadataSupplier;
  private final LongSupplier docCountSupplier;
  private final java.util.function.IntSupplier completedEmbeddingCountSupplier;
  private final AtomicReference<State> state = new AtomicReference<>(State.UNAVAILABLE);
  private final AtomicReference<String> currentFingerprint = new AtomicReference<>();
  private final AtomicReference<String> storedFingerprint = new AtomicReference<>();
  private final AtomicReference<String> reasonCode = new AtomicReference<>("INITIALIZING");
  private final AtomicReference<String> lastAutoRescueReason = new AtomicReference<>();
  private volatile boolean rebuildRequested = false;
  private volatile boolean rebuildCompleted = false;

  // ---- Stamp evidence (tempdoc 819 defect B). All IO-free to read. ----

  /** Monotone: set the first time a successful embedding is observed. Never cleared. */
  private volatile boolean anySuccessfulEmbeddingObserved = false;
  /** The current model's fingerprint was already persisted in the index's commit metadata. */
  private volatile boolean attestationAlreadyOnDisk = false;
  /** Evidence waived by a caller that can prove provenance structurally. */
  private volatile boolean stampEvidenceWaived = false;
  /** A zero-evidence certification was refused; do not re-attempt within this boot. */
  private volatile boolean zeroVectorRefusalTerminal = false;

  private final java.util.concurrent.atomic.AtomicBoolean zeroVectorRefusalLogged =
      new java.util.concurrent.atomic.AtomicBoolean(false);
  private final java.util.concurrent.atomic.AtomicBoolean completedCountReadFailureLogged =
      new java.util.concurrent.atomic.AtomicBoolean(false);
  private final java.util.concurrent.atomic.AtomicBoolean stampRefusalLogged =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /** Tri-state result of asking the index whether any embedding succeeded. */
  private enum Evidence {
    /** At least one successful embedding exists. */
    PRESENT,
    /** The index was read successfully and holds no successful embedding. */
    ABSENT,
    /** The count could not be read — never treat this as {@link #ABSENT}. */
    UNREADABLE
  }

  /**
   * Creates a new compatibility controller with no index-side success evidence available.
   *
   * <p>This overload fails CLOSED for certification: {@link #checkRebuildCompletion} will refuse
   * unless {@link #noteSuccessfulEmbeddingObserved()} or
   * {@link #permitStampWithoutEmbeddingEvidence} established evidence in-process. Production wiring
   * uses the three-argument constructor.
   *
   * @param storedMetadataSupplier supplier that returns the latest commit metadata from the index
   *                               (e.g., {@code LuceneLifecycleManager::latestCommitUserDataBestEffort})
   * @param docCountSupplier supplier for current index doc count (used to treat empty/new indexes as safe)
   */
  public EmbeddingCompatibilityController(
      Supplier<Map<String, String>> storedMetadataSupplier,
      LongSupplier docCountSupplier) {
    this(storedMetadataSupplier, docCountSupplier, () -> 0);
  }

  /**
   * Creates a new compatibility controller.
   *
   * <p><b>Both count suppliers must THROW on a read failure, never return 0.</b> Both answers are
   * safety-relevant: {@code docCount == 0} means "new index, safe to stamp" and
   * {@code completedEmbeddings == 0} means "this rebuild produced nothing". A supplier that
   * swallows an {@code IOException} to 0 (as the plain {@code IndexCountOps.docCount()} /
   * {@code countByField()} do) turns a transient reader fault into a PERMIT — the exact hole
   * tempdoc 819 defect B closes. Use the {@code …OrThrow} variants.
   *
   * @param storedMetadataSupplier supplier that returns the latest commit metadata from the index
   *                               (e.g., {@code LuceneLifecycleManager::latestCommitUserDataBestEffort})
   * @param docCountSupplier supplier for current index doc count (used to treat empty/new indexes as safe)
   * @param completedEmbeddingCountSupplier supplier for the count of documents whose embedding
   *     completed successfully — the certification evidence
   */
  public EmbeddingCompatibilityController(
      Supplier<Map<String, String>> storedMetadataSupplier,
      LongSupplier docCountSupplier,
      java.util.function.IntSupplier completedEmbeddingCountSupplier) {
    this.storedMetadataSupplier = Objects.requireNonNull(storedMetadataSupplier, "storedMetadataSupplier");
    this.docCountSupplier = Objects.requireNonNull(docCountSupplier, "docCountSupplier");
    this.completedEmbeddingCountSupplier =
        Objects.requireNonNull(completedEmbeddingCountSupplier, "completedEmbeddingCountSupplier");
  }

  /**
   * Initializes/refreshes the compatibility state based on current vs stored fingerprints.
   *
   * <p>Call this at Worker startup after index is opened, and whenever the index is rebuilt.
   *
   * <p><b>Not safe to call while a rebuild is in flight.</b> This method unconditionally
   * re-derives state from the fingerprint suppliers, but the stored fingerprint isn't stamped
   * until certification ({@link #checkRebuildCompletion}) — so a mid-rebuild call would re-read
   * the stale/missing stored fingerprint, re-derive {@code BLOCKED_LEGACY}/{@code
   * BLOCKED_MISMATCH}, and silently clobber the in-flight rebuild's {@code REBUILDING} state and
   * certification progress. Guarded below: a call while {@link State#REBUILDING} is a no-op.
   */
  public void refresh() {
    if (state.get() == State.REBUILDING) {
      log.warn(
          "Embedding compatibility: refresh() called while a rebuild is in flight; ignoring — the"
              + " rebuild's certification path owns the state until it completes or the process"
              + " restarts");
      return;
    }

    Optional<String> current = EmbeddingFingerprint.get();
    currentFingerprint.set(current.orElse(null));

    if (current.isEmpty()) {
      state.set(State.UNAVAILABLE);
      reasonCode.set("NO_EMBEDDING_MODEL");
      log.info("Embedding compatibility: UNAVAILABLE (no embedding model found)");
      return;
    }

    Map<String, String> stored = storedMetadataSupplier.get();
    String storedFp = stored == null ? null : stored.get(COMMIT_META_KEY);
    storedFingerprint.set(storedFp);

    if (storedFp == null || storedFp.isBlank()) {
      attestationAlreadyOnDisk = false;
      // If the index is empty (fresh install / new generation), it's safe to start writing vectors.
      // The stamp is NOT pre-permitted (no empty-index permit — see the class javadoc, #470 D2):
      // it lands once real embedding evidence exists. `docs < 0` means the count could not be read
      // (tempdoc 819 defect B): that must NOT read as "empty" — fall through to BLOCKED_LEGACY.
      long docs = safeDocCount();
      if (docs == 0L) {
        state.set(State.COMPATIBLE);
        reasonCode.set("NEW_INDEX_NO_FINGERPRINT");
        log.info(
            "Embedding compatibility: COMPATIBLE (new/empty index; fingerprint will be stamped"
                + " once the first embedding succeeds)");
        return;
      }

      state.set(State.BLOCKED_LEGACY);
      reasonCode.set("LEGACY_INDEX_NO_FINGERPRINT");
      log.warn(
          "Embedding compatibility: BLOCKED_LEGACY (index has no embedding fingerprint; docCount={}"
              + "; -1 = the count could not be read, which fails closed by design). "
              + "Embedding writes and vector/hybrid queries require whole-index legacy recovery.",
          docs);
      return;
    }

    if (storedFp.equals(current.get())) {
      // The attestation is already persisted for THIS model. Later commits must keep offering it —
      // withholding it would strip the fingerprint from the next commit and re-flag the index
      // BLOCKED_LEGACY on the following boot (tempdoc 819 defect B: the gate governs the FIRST
      // persistence of an attestation, never the preservation of one already earned).
      attestationAlreadyOnDisk = true;
      state.set(State.COMPATIBLE);
      reasonCode.set("FINGERPRINT_MATCH");
      rebuildCompleted = true; // Already compatible
      log.info("Embedding compatibility: COMPATIBLE (fingerprint matches: {}...)",
          storedFp.substring(0, Math.min(16, storedFp.length())));
      return;
    }

    attestationAlreadyOnDisk = false;
    state.set(State.BLOCKED_MISMATCH);
    reasonCode.set("FINGERPRINT_MISMATCH");
    log.warn("Embedding compatibility: BLOCKED_MISMATCH. "
        + "Stored: {}..., Current: {}... "
        + "Embedding writes and vector/hybrid queries require a full-generation rebuild.",
        storedFp.substring(0, Math.min(16, storedFp.length())),
        current.get().substring(0, Math.min(16, current.get().length())));
  }

  /**
   * Called by whole-index recovery after it establishes re-embedding coverage.
   *
   * <p>This triggers transition to REBUILDING state if currently blocked. Selected force
   * ingestion is not sufficient authority: untouched vectors may still have another provenance.
   */
  public void onForcedReindexRequested() {
    rebuildRequested = true;
    State currentState = state.get();
    if (currentState == State.BLOCKED_LEGACY || currentState == State.BLOCKED_MISMATCH) {
      state.set(State.REBUILDING);
      reasonCode.set("REBUILD_IN_PROGRESS");
      rebuildCompleted = false;
      log.info("Embedding compatibility: transitioned to REBUILDING (whole-index recovery established)");
    }
  }

  /**
   * Best-effort helper: auto-start an embedding rebuild for a legacy index that has documents but no
   * stored embedding fingerprint.
   *
   * <p>Fires for any {@link State#BLOCKED_LEGACY} / {@code LEGACY_INDEX_NO_FINGERPRINT} index with
   * documents — <b>regardless</b> of the completed/pending/failed distribution. This broadens the
   * former all-pending-only trigger, which left a fully-embedded-but-never-stamped index (the state
   * the pre-fix in-place backfill path leaves after a restart) stuck in BLOCKED_LEGACY forever with
   * no recovery path.
   *
   * <p>Vectors on documents already marked {@code COMPLETED} but committed WITHOUT a fingerprint have
   * unknowable provenance (they may have been written by a different embedding model), so the caller
   * is responsible for re-marking such documents PENDING, committing and refreshing before this call so the backfill
   * re-embeds them under the current model.
   *
   * <p><b>The on-disk signature this rescues (tempdoc 730 A4 §THEORIZE A).</b> A commit finalized
   * while the ECC was not {@code COMPATIBLE}/{@code REBUILDING}-complete could persist SPLADE's
   * fingerprint (an unconditional supplier) while silently omitting the embedding one (a
   * state-gated supplier). Every subsequent restart then re-resolves {@link State#BLOCKED_LEGACY},
   * {@link #fingerprintToStamp()} never offers a value, and the index is stuck until intervention —
   * whether the documents are all PENDING or already COMPLETED. This method deliberately does not
   * distinguish those two distributions: back-stamping would fabricate provenance for vectors we
   * cannot prove came from the current model (the mixed-provenance risk the tempdoc 730 A1 revert
   * identified), so the only safe rescue in BOTH cases is a real re-embed, owned by the whole-index
   * legacy recovery caller in EmbeddingRecoveryOps. Safety comes from the caller having
   * verified complete, visible re-marking first; this method does not itself read the index or take
   * distribution counts.
   *
   * @param docCount total (parent) docs in the index
   * @return true if the controller transitioned to REBUILDING
   */
  public boolean maybeAutoStartRebuildForBlockedLegacy(long docCount) {
    if (state.get() != State.BLOCKED_LEGACY) return false;
    if (!"LEGACY_INDEX_NO_FINGERPRINT".equals(reasonCode.get())) return false;
    if (docCount <= 0) return false;

    log.info(
        "Embedding compatibility: auto-starting REBUILDING (reason=legacy_no_fingerprint; legacy"
            + " index has no persisted embedding fingerprint, so any existing vectors are"
            + " unattested — re-embedding to earn a verifiable stamp rather than back-stamping"
            + " unattested vectors). docCount={}",
        docCount);
    lastAutoRescueReason.set("legacy_no_fingerprint");
    onForcedReindexRequested();
    return state.get() == State.REBUILDING;
  }

  /**
   * Certifies rebuild completion from two facts: the embedding-scoped {@code pendingEmbeddingCount
   * == 0}, <b>and</b> evidence that at least one embedding actually succeeded.
   *
   * <p>The global {@code queueDepth} is <b>not</b> part of the certification condition (it is
   * accepted only as a diagnostic to log): a job stuck in {@code PROCESSING}, or simply ongoing
   * unrelated ingestion, must never block embedding certification. Coverage==100% algebraically
   * implies {@code pending==0} on the same counter, and new documents arriving after the stamp are
   * handled by the normal COMPATIBLE incremental path — global idleness adds nothing but livelock.
   *
   * <p><b>Why {@code pending == 0} alone is not enough (tempdoc 819 defect B).</b> Failed documents
   * are not pending, so a rebuild in which EVERY document failed satisfies it — observed live as
   * {@code completed=0 pending=0 failed=5 coverage=0%} certifying COMPATIBLE and stamping the
   * fingerprint over zero vectors, which permanently closes the BLOCKED_LEGACY recovery path for
   * that index. {@code pending == 0} is the absence of outstanding work, not evidence of success.
   * The poison-pill distribution (some succeeded, some permanently failed) MUST still certify —
   * that is what "at least one" preserves, and it is why the condition is not "no failures".
   *
   * <p>Evidence is the in-process success latch, an explicit waiver, or a live COMPLETED count read
   * through a supplier that THROWS on failure. A read failure REFUSES but is not terminal (the next
   * tick retries); a trustworthy zero REFUSES terminally for this boot — nothing will change it,
   * since the backfill only ever picks up PENDING documents — sets {@link #REBUILD_FAILED_NO_VECTORS}
   * and logs once at ERROR. {@link #refresh()} runs once per boot, so the next boot re-derives
   * BLOCKED_LEGACY and the rescue retries the rebuild from scratch.
   *
   * <p>This certifier flips COMPATIBLE on the FIRST evidenced {@code pending==0} read; the
   * indexing-loop caller ({@code EmbeddingProviderLifecycle.tryFinalizeRebuild}) debounces with a
   * two-consecutive-reads guard, while the deterministic blue/green cutover
   * ({@code finalizeEmbeddingRebuildBeforeCutover}) calls this once on an already-drained green.
   *
   * @param queueDepth current job queue depth — logged as diagnostics only, does NOT gate
   * @param pendingEmbeddingCount count of documents with embedding_status=PENDING
   * @return true if rebuild just completed (fingerprint should be stamped)
   */
  public boolean checkRebuildCompletion(long queueDepth, int pendingEmbeddingCount) {
    if (state.get() != State.REBUILDING) {
      return false;
    }

    if (pendingEmbeddingCount != 0) {
      return false;
    }

    if (zeroVectorRefusalTerminal) {
      return false;
    }

    Evidence evidence = observeEmbeddingSuccessEvidence();
    if (evidence == Evidence.UNREADABLE) {
      return false;
    }
    if (evidence == Evidence.ABSENT) {
      zeroVectorRefusalTerminal = true;
      reasonCode.set(REBUILD_FAILED_NO_VECTORS);
      if (zeroVectorRefusalLogged.compareAndSet(false, true)) {
        log.error(
            "Embedding compatibility: REFUSING to certify the rebuild — pending_embedding=0 but NOT"
                + " ONE embedding succeeded (queueDepth={}). Certifying here would stamp"
                + " {} over an index with zero vectors and permanently close the recovery"
                + " path. Staying REBUILDING with reason={}; dense/hybrid retrieval stays blocked."
                + " Fix the embedding runtime (see the ORT/embedding errors above) and restart the"
                + " worker — the next boot re-derives BLOCKED_LEGACY and retries the rebuild.",
            queueDepth,
            COMMIT_META_KEY,
            REBUILD_FAILED_NO_VECTORS);
      }
      return false;
    }

    log.info(
        "Embedding compatibility: rebuild complete (pending_embedding=0, embedding success"
            + " observed; queueDepth={} not gating)",
        queueDepth);
    rebuildCompleted = true;
    state.set(State.COMPATIBLE);
    reasonCode.set("REBUILD_COMPLETED");
    return true;
  }

  /**
   * Records that an embedding was produced successfully. Monotone — once set, never cleared; a
   * later failure does not retract the fact that the model produced a vector.
   */
  public void noteSuccessfulEmbeddingObserved() {
    anySuccessfulEmbeddingObserved = true;
  }

  /**
   * Loop-side reconciliation: if no in-process evidence exists yet, ask the INDEX whether any
   * embedding has completed, latching a positive answer (tempdoc 821 §O.1).
   *
   * <p><b>Performs index IO. NEVER call this from {@link #fingerprintToStamp()} or any other
   * commit-path code</b> — that method is on the every-commit hot path and is contractually
   * IO-free. This is for the indexing loop's idle / post-batch drain, where a reader acquisition is
   * already ordinary. It is also the pull-side that picks up the embedding backfill's successes
   * without the backfill having to push a signal.
   *
   * <p>{@code UNREADABLE} yields false by design: a count that could not be read is not evidence of
   * anything, so the caller declines now and the next drain retries.
   *
   * @return true if the stamp is earned (already-held evidence, or a live COMPLETED count &gt; 0)
   */
  public boolean reconcileStampEvidence() {
    if (hasStampEvidence()) {
      return true;
    }
    return observeEmbeddingSuccessEvidence() == Evidence.PRESENT;
  }

  /**
   * Waives the success-evidence requirement for callers that can prove the attestation's soundness
   * structurally rather than by counting vectors.
   *
   * <p>The one production user is the corruption-recovery rebuild (tempdoc 819): the active
   * generation was recovered to EMPTY by the adapter and the green generation is being rebuilt from
   * source, so every vector it can contain came from the current model by construction — the
   * attestation is vacuously true even at zero successes. Refusing there would block the cutover
   * forever and leave the user serving the EMPTY blue with no keyword results either, which is
   * strictly worse than the normal cutover's fail-closed behaviour (where blue still holds the
   * user's corpus and keeping it costs nothing).
   *
   * @param reason low-cardinality tag naming the waiving path, for the log line
   */
  public void permitStampWithoutEmbeddingEvidence(String reason) {
    stampEvidenceWaived = true;
    log.warn(
        "Embedding compatibility: stamp evidence WAIVED (reason={}) — this generation's provenance"
            + " is guaranteed structurally, so certification may proceed with zero successful"
            + " embeddings",
        reason);
  }

  /** IO-free: has the stamp been earned? See the class javadoc for the three permitting facts. */
  private boolean hasStampEvidence() {
    return attestationAlreadyOnDisk || anySuccessfulEmbeddingObserved || stampEvidenceWaived;
  }

  /** Consults the in-process facts first, then the index. Latches a PRESENT result. */
  private Evidence observeEmbeddingSuccessEvidence() {
    if (anySuccessfulEmbeddingObserved || stampEvidenceWaived) {
      return Evidence.PRESENT;
    }
    int completed;
    try {
      completed = completedEmbeddingCountSupplier.getAsInt();
    } catch (RuntimeException e) {
      if (completedCountReadFailureLogged.compareAndSet(false, true)) {
        log.warn(
            "Embedding compatibility: could not read the completed-embedding count; declining to"
                + " certify this rebuild rather than reading the failure as zero. Will retry.",
            e);
      }
      return Evidence.UNREADABLE;
    }
    if (completed > 0) {
      anySuccessfulEmbeddingObserved = true;
      return Evidence.PRESENT;
    }
    return Evidence.ABSENT;
  }

  /**
   * Called after a successful commit that stamped the new fingerprint.
   * Finalizes the transition to COMPATIBLE.
   */
  public void onFingerprintStamped() {
    String fp = currentFingerprint.get();
    storedFingerprint.set(fp);
    state.set(State.COMPATIBLE);
    reasonCode.set("FINGERPRINT_MATCH");
    log.info("Embedding compatibility: fingerprint stamped, now COMPATIBLE ({}...)",
        fp == null ? "null" : fp.substring(0, Math.min(16, fp.length())));
  }

  // ===== Gates =====

  /**
   * Returns true if embedding writes are allowed.
   *
   * <p>Writes are allowed in COMPATIBLE and REBUILDING states.
   */
  public boolean allowEmbeddingWrites() {
    State s = state.get();
    return s == State.COMPATIBLE || s == State.REBUILDING;
  }

  /**
   * Returns true if vector/hybrid queries are allowed.
   *
   * <p>Queries are only allowed in COMPATIBLE state (not during REBUILDING).
   */
  public boolean allowQueryEmbeddings() {
    return state.get() == State.COMPATIBLE;
  }

  /**
   * Returns the fingerprint to stamp in commit metadata, if stamping is allowed.
   *
   * <p>Returns non-empty only when BOTH hold:
   * <ul>
   *   <li>State is COMPATIBLE (already matches), or state is REBUILDING and rebuild has
   *       completed; AND</li>
   *   <li>the attestation has been earned — see {@link #hasStampEvidence()} and the class javadoc
   *       (tempdoc 819 defect B; a fresh/empty index earns it via its first successful embedding,
   *       never by emptiness alone — #470 D2).</li>
   * </ul>
   *
   * <p><b>Must stay IO-free.</b> {@code CommitOps.commit()} consults this on EVERY commit,
   * including the background timer commit; an index query here would put a reader acquisition on
   * the commit path.
   */
  public Optional<String> fingerprintToStamp() {
    State s = state.get();
    if (s == State.COMPATIBLE || (s == State.REBUILDING && rebuildCompleted)) {
      if (!hasStampEvidence()) {
        // Fresh install (NEW_INDEX_NO_FINGERPRINT): evidence is legitimately absent between the
        // first document commit and the backfill's first successful embedding — withholding here
        // is the design working, not a fault, so it logs nothing (#470 D3). Any other state
        // reaching this point without evidence is worth a once-per-boot warning.
        if (!"NEW_INDEX_NO_FINGERPRINT".equals(reasonCode.get())
            && stampRefusalLogged.compareAndSet(false, true)) {
          log.warn(
              "Embedding compatibility: state={} but no evidence that any embedding succeeded —"
                  + " withholding {} from commit metadata rather than attesting to vectors that do"
                  + " not exist (tempdoc 819). Logged once per boot.",
              s,
              COMMIT_META_KEY);
        }
        return Optional.empty();
      }
      String fp = currentFingerprint.get();
      if (fp != null) {
        storedFingerprint.set(fp);
        reasonCode.compareAndSet("NEW_INDEX_NO_FINGERPRINT", "FINGERPRINT_MATCH");
      }
      return Optional.ofNullable(fp);
    }
    return Optional.empty();
  }

  /**
   * Commit projection: preserve known old provenance while mismatch blocks all embedding writes.
   * This is not permission to stamp the current model; {@link #fingerprintToStamp()} remains the
   * attestation gate. During REBUILDING the old fingerprint is withheld because vectors can be mixed.
   * No index IO or additional authority is introduced on the commit path.
   */
  public Optional<String> fingerprintForCommit() {
    if (state.get() == State.BLOCKED_MISMATCH) {
      return Optional.ofNullable(storedFingerprint.get());
    }
    return fingerprintToStamp();
  }

  // ===== Accessors for status reporting =====

  /** Returns the current compatibility state. */
  public State state() {
    return state.get();
  }

  /** Returns the current embedding model fingerprint (or null if unavailable). */
  public String currentFingerprint() {
    return currentFingerprint.get();
  }

  /** Returns the stored embedding model fingerprint from index (or null if missing/legacy). */
  public String storedFingerprint() {
    return storedFingerprint.get();
  }

  /** Returns a stable reason code for the current state. */
  public String reasonCode() {
    return reasonCode.get();
  }

  /** Returns true if whole-index re-embedding has been requested. */
  public boolean isRebuildRequested() {
    return rebuildRequested;
  }

  /**
   * Returns a tag naming which auto-rescue path (if any) last triggered a {@link State#REBUILDING}
   * transition — currently only {@code
   * "legacy_no_fingerprint"} ({@link #maybeAutoStartRebuildForBlockedLegacy}). {@code null} if no
   * auto-rescue has fired. This is a diagnostic-only tag (tempdoc 730 A4) distinct from {@link
   * #reasonCode()} — it is deliberately NOT part of the {@code SearchReasonCode} wire contract,
   * since the auto-rescue path still resolves the shared, contract-stable {@code
   * "REBUILD_IN_PROGRESS"} reason for query-time degradation messaging.
   */
  public String lastAutoRescueReason() {
    return lastAutoRescueReason.get();
  }

  /**
   * Re-reads the stored fingerprint from Lucene commit metadata after a successful commit.
   *
   * <p>Call this after any non-rebuild commit to keep the cached {@code storedFingerprint}
   * in sync with what was actually persisted. On a fresh index, the first commit stamps
   * the fingerprint via {@link EmbeddingMetadataOverlay}, but the ECC is never notified
   * because {@link #onFingerprintStamped()} only fires on the REBUILDING completion path.
   */
  public void refreshStoredFingerprintAfterCommit() {
    try {
      Map<String, String> stored = storedMetadataSupplier.get();
      String fp = stored == null ? null : stored.get(COMMIT_META_KEY);
      if (fp != null && !fp.isBlank()) {
        String prev = storedFingerprint.get();
        if (!fp.equals(prev)) {
          storedFingerprint.set(fp);
          log.debug("ECC: refreshed storedFingerprint after commit ({}...)",
              fp.substring(0, Math.min(16, fp.length())));
        }
        // Read back from the ACTUAL commit metadata: if the attestation for the current model is
        // now persisted, later commits must keep offering it (tempdoc 819 — the evidence gate
        // governs first persistence, not preservation).
        if (fp.equals(currentFingerprint.get())) {
          attestationAlreadyOnDisk = true;
        }
      }
    } catch (Exception e) {
      log.debug("ECC: failed to refresh storedFingerprint after commit: {}", e.getMessage());
    }
  }

  /**
   * Reads the doc count, mapping a read FAILURE to {@code -1} — deliberately NOT to {@code 0},
   * which {@link #refresh()} would take as "new empty index, safe to stamp". This is why the
   * supplier is contractually required to throw rather than swallow (tempdoc 819 defect B).
   */
  private long safeDocCount() {
    try {
      return Math.max(0L, docCountSupplier.getAsLong());
    } catch (Exception e) {
      log.warn("Embedding compatibility: doc-count read failed; treating as NOT-empty (fail closed)", e);
      return -1L;
    }
  }
}
