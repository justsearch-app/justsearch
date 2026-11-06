/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.loop.ops.EnrichmentAccounting;
import io.justsearch.indexerworker.loop.ops.EnrichmentAccounting.Stage;
import io.justsearch.indexerworker.loop.ops.BgeM3BackfillOps;
import io.justsearch.indexerworker.loop.ops.CombinedEnrichmentBackfillOps;
import io.justsearch.indexerworker.loop.ops.DisambiguationBackfillOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.loop.ops.LoopPacingPolicy;
import io.justsearch.indexerworker.loop.ops.NerBackfillOps;
import io.justsearch.indexerworker.loop.ops.SpladeBackfillOps;
import io.justsearch.indexerworker.loop.ops.StageOutcome;
import io.justsearch.indexerworker.metrics.BatchTimingKeys;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one enrichment scheduler (E4b): it owns when enrichment runs, from busy primary processing
 * ({@link #runInterleavedEnrichment}) and from idle time ({@link #runIdleCycle}).
 *
 * <p><b>Lanes and units.</b> Five lanes rotate: dense parent, dense chunk, sparse parent, sparse
 * chunk and NER. When BGE-M3 is configured it is one unified lane that owns everything its
 * operation selects (sparse-pending parents and chunks, writing dense and sparse together) and
 * replaces the dense-parent and both sparse lanes; dense chunk and NER stay separate. A turn runs
 * exactly one admitted E3 unit of its lane, which finishes with its result or retained progress
 * kept; hard stops and deadlines keep their E3 meaning inside the unit.
 *
 * <p><b>Ledger.</b> Each lane {@code i} keeps charged service {@code S_i}. A turn goes to the
 * eligible lane with the least {@code S_i}; ties go to the first tied lane after a round-robin
 * pointer that advances past each chosen lane. Every admitted turn is charged {@code max(measured
 * time, 10 ms)}: wall time from the unit's selection to its publication receipt, failed and
 * zero-completion attempts included, foreground pacing waits excluded. A lane that (re)becomes
 * eligible gets {@code S_i = max(S_i, min S_j)} over the lanes that stayed eligible, retaining
 * the last eligible minimum across a global eligibility gap. Debt is kept and catch-up bursts
 * are impossible. The ledger, cursors, retained routes and holds are in
 * memory; a restart starts a fresh ledger.
 *
 * <p><b>Bound.</b> While lane {@code i} stays continuously eligible, every other lane receives
 * between two turns of {@code i} at most {@code (S_i - S_j) + C_max} of charged service, where
 * {@code C_max} is the largest single-turn charge; with {@code k} eligible lanes and initial
 * spread {@code D}, {@code i} waits at most {@code (k-1)(D + C_max)} of charged service. There is
 * no wall-clock promise: acquisition waits (up to 5 min), pacing waits and the spacing of busy
 * calls after primary batches are outside it.
 *
 * <p><b>Callers.</b> A due busy call admits one turn at the interleave interval; pending ingestion
 * limits it to that turn but cannot veto it. An idle cycle repeats turns until its deadline (or as
 * many turns as its budget holds at the charge floor), a hard stop, pending ingestion (after the
 * admitted unit finishes), or no eligible lane can still advance this cycle.
 *
 * <p><b>Commits (interim).</b> The scheduler counts enrichment-dirty documents for both callers
 * and commits after a turn once the existing document or time threshold is reached, and at the
 * end of every idle cycle and every busy call that finds no eligible lane if anything is dirty. A
 * failed commit keeps the dirty count and is retried at the next check.
 */
public final class BackfillScheduler {
  private static final Logger log = LoggerFactory.getLogger(BackfillScheduler.class);

  /** Wall-clock budget of one idle cycle (tempdoc 798 containment). */
  private static final long CYCLE_BUDGET_MS = 5_000L;
  private static final long CYCLE_BUDGET_NANOS = CYCLE_BUDGET_MS * 1_000_000L;
  /** Floor of a turn's charge, so zero-time outcomes cannot monopolise the ledger. */
  private static final long MIN_CHARGE_NANOS = 10_000_000L;
  /** Turns one idle cycle may admit: its budget at the charge floor, whatever the clock does. */
  private static final int MAX_CYCLE_TURNS = (int) (CYCLE_BUDGET_NANOS / MIN_CHARGE_NANOS);
  /** Consecutive returned turns without advancement before one diagnostic episode. */
  private static final int STALL_TURNS = 5;

  /**
   * Rotation order. BGE-M3, when configured, takes the dense-parent position and replaces the
   * dense-parent and both sparse lanes; the two are never eligible together.
   */
  enum Lane { DENSE_PARENT, BGE_M3, DENSE_CHUNK, SPARSE_PARENT, SPARSE_CHUNK, NER }

  /** One admitted turn, as the test-visible observer and the DEBUG line report it. */
  record Turn(Lane lane, String caller, long chargeNanos, StageOutcome outcome, boolean advancement,
      boolean interrupted) {}

  /** Test-visible per-turn observer (set reflectively by scheduler tests); no public API. */
  private java.util.function.Consumer<Turn> turnObserver = turn -> {};

  private static final List<Lane> ROTATION = List.of(Lane.values());
  private final Map<Lane, Long> service = new EnumMap<>(Lane.class);
  private final Set<Lane> eligible = EnumSet.noneOf(Lane.class);
  private long reentryFloor;
  private final Map<Lane, Integer> unsuccessful = new EnumMap<>(Lane.class);
  /** Round-robin tie pointer: index into {@link #ROTATION}. */
  private int nextLane;
  /**
   * The dense operation's own per-call exclusion: documents it published, or saw rejected while
   * their revision stayed the same, are not re-encoded in the same scheduler call.
   */
  private final Set<String> densePublished = new HashSet<>();
  private long dirtyDocuments;
  private long lastEnrichmentCommitNanos;

  /** Scheduler-local monotonic domain for cycle, embed-share and retry deadlines. */
  private final LongSupplier monotonicNanos;
  private final DocumentFieldOps documentFieldOps;
  private final IndexingCoordinator indexingCoordinator;
  private final IndexCountOps indexCountOps;
  private final CommitOps commitOps;
  private final WorkerSignalBus signalBus;
  /** Tempdoc 885 item 3: the duty cycle backfill yields against instead of stopping. */
  private final IndexingPacing indexingPacing;
  private final EmbeddingProviderLifecycle embeddingLifecycle;
  private final AtomicBoolean running;
  private final Supplier<ResolvedConfig> resolvedConfigSupplier;
  private final Supplier<SpladeEncoder> spladeEncoderSupplier;
  private final Supplier<BgeM3Encoder> bgeM3EncoderSupplier;
  private final Supplier<NerService> nerServiceSupplier;
  private final Supplier<DisambiguationService> disambiguationServiceSupplier;

  private long lastEnrichmentInterleaveTime = 0;
  private static final class StageBackoff {
    Object provider;
    int failures;
    long retryNanos;
  }

  private final java.util.IdentityHashMap<Object, StageBackoff> acquisitionBackoffs =
      new java.util.IdentityHashMap<>();

  private final EnumMap<Stage, StageBackoff> stageBackoffs = new EnumMap<>(Stage.class);

  /**
   * Cross-cycle per-window accumulator for long documents (round-15 post-round finding). Lives here
   * because this object, like the pending cursors, survives idle turns and reader refreshes.
   */
  private final io.justsearch.indexerworker.loop.ops.WindowedEmbedProgress windowedEmbedProgress =
      new io.justsearch.indexerworker.loop.ops.WindowedEmbedProgress();

  /** Per-stage keyset positions survive idle turns and reader refreshes. */
  private final io.justsearch.indexerworker.loop.ops.EnrichmentSelection enrichmentSelection =
      new io.justsearch.indexerworker.loop.ops.EnrichmentSelection();

  public BackfillScheduler(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      IndexCountOps indexCountOps,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      IndexingPacing indexingPacing,
      EmbeddingProviderLifecycle embeddingLifecycle,
      AtomicBoolean running,
      Supplier<ResolvedConfig> resolvedConfigSupplier,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      Supplier<BgeM3Encoder> bgeM3EncoderSupplier,
      Supplier<NerService> nerServiceSupplier,
      Supplier<DisambiguationService> disambiguationServiceSupplier) {
    this(
        documentFieldOps, indexingCoordinator, indexCountOps, commitOps, signalBus, indexingPacing,
        embeddingLifecycle, running, resolvedConfigSupplier, spladeEncoderSupplier,
        bgeM3EncoderSupplier, nerServiceSupplier, disambiguationServiceSupplier, System::nanoTime);
  }

  BackfillScheduler(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      IndexCountOps indexCountOps,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      IndexingPacing indexingPacing,
      EmbeddingProviderLifecycle embeddingLifecycle,
      AtomicBoolean running,
      Supplier<ResolvedConfig> resolvedConfigSupplier,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      Supplier<BgeM3Encoder> bgeM3EncoderSupplier,
      Supplier<NerService> nerServiceSupplier,
      Supplier<DisambiguationService> disambiguationServiceSupplier,
      LongSupplier monotonicNanos) {
    this.monotonicNanos = java.util.Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    this.lastEnrichmentCommitNanos = monotonicNanos.getAsLong();
    this.documentFieldOps = documentFieldOps;
    this.indexingCoordinator = indexingCoordinator;
    this.indexCountOps = indexCountOps;
    this.commitOps = commitOps;
    this.signalBus = signalBus;
    this.indexingPacing =
        java.util.Objects.requireNonNull(indexingPacing, "indexingPacing");
    this.embeddingLifecycle = embeddingLifecycle;
    if (embeddingLifecycle != null && indexingCoordinator != null) {
      embeddingLifecycle.bindEnrichmentCoordinator(indexingCoordinator);
    }
    this.running = running;
    this.resolvedConfigSupplier = resolvedConfigSupplier;
    this.spladeEncoderSupplier = spladeEncoderSupplier;
    this.bgeM3EncoderSupplier = bgeM3EncoderSupplier;
    this.nerServiceSupplier = nerServiceSupplier;
    this.disambiguationServiceSupplier = disambiguationServiceSupplier;
  }

  /**
   * Resolves the current enrichment-backfill pacing snapshot (tempdoc 710 Wave-1.5 Move 4). Falls
   * back to {@link ResolvedConfig.Ai.BackfillPacing#DEFAULTS} — byte-identical to the pre-Move-4
   * hardcoded literals — when no config is available, e.g. a test double supplying {@code () ->
   * null} for {@code resolvedConfigSupplier}.
   */
  private ResolvedConfig.Ai.BackfillPacing pacing() {
    ResolvedConfig config = resolvedConfigSupplier.get();
    return config != null ? config.ai().backfillPacing() : ResolvedConfig.Ai.BackfillPacing.DEFAULTS;
  }

  /**
   * Whether each enrichment stage can drain its backlog right now (Spec E §1.7, step E1).
   *
   * @param embedding an embedding provider is available and embedding writes are allowed
   * @param sparse a SPLADE or BGE-M3 encoder is loaded
   * @param ner the NER service is loaded and available
   */
  public record StageAvailability(boolean embedding, boolean sparse, boolean ner) {
    /** No stage can run: what status reports when no loop exists. */
    public static final StageAvailability NONE = new StageAvailability(false, false, false);
  }

  /**
   * The live per-stage availability, by the same checks the combined backfill pass uses to decide
   * which stages it runs. The status surface reads this method so that status and loop cannot
   * disagree about whether a backlog will drain (Spec E §1.7: "produced from the same provider
   * checks the loop uses").
   */
  public StageAvailability stageAvailability() {
    boolean embedding =
        embeddingLifecycle.embeddingProvider().isAvailable()
            && embeddingLifecycle.allowEmbeddingWrites();
    boolean sparse =
        spladeEncoderSupplier.get() != null || bgeM3EncoderSupplier.get() != null;
    NerService nerService = nerServiceSupplier.get();
    boolean ner = nerService != null && nerService.isAvailable();
    return new StageAvailability(embedding, sparse, ner);
  }

  /**
   * Repeats fair turns until the cycle deadline (or its turn bound), a hard stop, pending
   * ingestion, or no eligible lane that can still advance this cycle. Returns whether any turn
   * advanced work, for the caller's active-versus-idle sleep choice.
   */
  public boolean runIdleCycle() {
    boolean activity = false;
    boolean admitted = false;
    long deadline = monotonicNanos.getAsLong() + CYCLE_BUDGET_NANOS;
    int turns = 0;
    enrichmentSelection.beginCycle();
    densePublished.clear();
    // A lane that returned without advancing, or whose unit was cancelled or held, is not offered
    // again in this cycle; it is eligible again next cycle on the same clock.
    var finished = EnumSet.noneOf(Lane.class);
    try {
      while (monotonicNanos.getAsLong() < deadline && turns < MAX_CYCLE_TURNS) {
        Turn turn = runTurn("idle", deadline, finished);
        if (turn == null) break;
        admitted = true;
        turns++;
        activity |= turn.advancement();
        if (!turn.advancement() || turn.interrupted()) finished.add(turn.lane());
        // Primary indexing outranks background enrichment: the admitted unit finished, no other.
        if (signalBus.hasPendingIngest()) break;
      }
    } finally {
      commitEnrichment(true);
    }
    if (monotonicNanos.getAsLong() >= deadline || turns >= MAX_CYCLE_TURNS) {
      // Tempdoc 798: only a budget spent with nothing advanced is the non-converging shape.
      if (activity) {
        log.info("Enrichment reached its {}ms cycle budget while still advancing documents —"
            + " returning to the job-queue poll; the remainder resumes next idle cycle"
            + " (tempdoc 798).", CYCLE_BUDGET_MS);
      } else {
        log.warn("Enrichment hit its {}ms cycle budget with ZERO stage advancement — returning to"
            + " the job-queue poll (tempdoc 798).", CYCLE_BUDGET_MS);
      }
    }
    if (!hardStopBackfillWork() && monotonicNanos.getAsLong() < deadline) {
      boolean disambiguated = runDisambiguationIfReady(activity);
      activity |= disambiguated;
    }
    OperationalMetrics.getInstance().recordBackfillMode(admitted ? "active" : "idle");
    return activity;
  }

  /**
   * One due busy call admits at most one lane turn at the enrichment interleave interval, even
   * while ingestion remains pending (a soft yield, not a veto).
   */
  public void runInterleavedEnrichment(long now) {
    OperationalMetrics.getInstance().recordBackfillMode("idle");
    if (now - lastEnrichmentInterleaveTime < pacing().enrichmentInterleaveIntervalMs()) return;
    if (hardStopBackfillWork()) {
      return;
    }
    lastEnrichmentInterleaveTime = now;
    enrichmentSelection.beginCycle();
    densePublished.clear();
    Turn turn = runTurn("busy", Long.MAX_VALUE, Set.of());
    if (turn == null) {
      commitEnrichment(true);
      OperationalMetrics.getInstance().recordBackfillMode("idle");
    }
  }

  private Turn runTurn(String caller, long deadline, Set<Lane> finished) {
    // Pacing waits belong to foreground responsiveness, never to a lane's service debt.
    indexingPacing.pace();
    if (hardStopBackfillWork() || monotonicNanos.getAsLong() >= deadline) return null;
    Lane chosen = chooseLane(finished);
    if (chosen == null) return null;
    OperationalMetrics.getInstance().recordBackfillMode("active");
    var authority = indexingCoordinator.enrichmentAuthority();
    long epoch = indexingCoordinator.bulkDeleteEpoch();
    long routes = windowedEmbedProgress.routesRetained();
    long start = monotonicNanos.getAsLong();
    long yielded = indexingPacing.yieldedNanosOnCurrentThread();
    StageOutcome outcome = processLane(chosen, caller.equals("busy"), deadline);
    // A source or authority change during the unit ends this lane's cycle, as cancellation does.
    boolean interrupted = interrupted(outcome)
        || authority != indexingCoordinator.enrichmentAuthority()
        || epoch != indexingCoordinator.bulkDeleteEpoch();
    long charge = Math.max(MIN_CHARGE_NANOS, monotonicNanos.getAsLong() - start
        - (indexingPacing.yieldedNanosOnCurrentThread() - yielded));
    service.merge(chosen, charge, Long::sum);
    reentryFloor = eligible.stream().mapToLong(this::charged).min().orElse(reentryFloor);
    enrichmentSelection.served(outcome.accounting());
    recordStageOutcome(switch (chosen) {
      case DENSE_PARENT, DENSE_CHUNK -> BatchTimingKeys.EMBED;
      case SPARSE_PARENT, SPARSE_CHUNK, BGE_M3 -> BatchTimingKeys.SPLADE;
      case NER -> BatchTimingKeys.NER;
    }, outcome);
    if ((chosen == Lane.SPARSE_PARENT || chosen == Lane.SPARSE_CHUNK || chosen == Lane.BGE_M3)
        && outcome.accounting().storageFailures().isEmpty()
        && outcome.accounting().acquisitions().stream().allMatch(EnrichmentAccounting.Acquisition::available)) {
      // The sparse operations report "not a systemic failure" through success.
      recordBackfillResult(Stage.SPLADE, outcome.success());
    }
    var counters = outcome.accounting().counters();
    // Advancement: a published, charged or terminal outcome, or retained progress. A charged row
    // is not re-selected in the same call, so a failing row cannot keep its lane busy.
    boolean advancement = counters.entrySet().stream().anyMatch(entry -> entry.getValue() > 0
        && (entry.getKey().endsWith(".published") || entry.getKey().endsWith(".completed_empty")
            || entry.getKey().endsWith(".rejected_output") || entry.getKey().endsWith(".failed_charged")
            || entry.getKey().endsWith(".failed") || entry.getKey().endsWith(".window_advances")))
        // A retained classification or fallback route is retained progress of its unit.
        || windowedEmbedProgress.routesRetained() != routes;
    // Every accepted row is enrichment-dirty, even when a later write in the unit failed.
    dirtyDocuments += outcome.accounting().tickets().entrySet().stream()
        .filter(entry -> switch (entry.getValue()) {
          case PUBLISHED, COMPLETED_EMPTY, FAILED_CHARGED, REJECTED_OUTPUT -> true;
          default -> false;
        }).map(entry -> entry.getKey().documentId()).distinct().count();
    noteService(chosen, outcome, advancement);
    Turn turn = new Turn(chosen, caller, charge, outcome, advancement, interrupted);
    log.debug("Enrichment turn lane={} caller={} chargeMs={} advancement={} outcome={}",
        chosen, caller, charge / 1_000_000L, advancement, counters);
    turnObserver.accept(turn);
    commitEnrichment(false);
    return turn;
  }

  /** Least charged eligible lane, ties after the round-robin pointer; re-entry keeps debt. */
  private Lane chooseLane(Set<Lane> finished) {
    Set<Lane> current = EnumSet.noneOf(Lane.class);
    for (Lane lane : ROTATION) if (laneEligible(lane)) current.add(lane);
    // Retain the last eligible minimum when no lane stayed eligible, including across empty calls.
    Set<Lane> stayed = EnumSet.copyOf(current);
    stayed.retainAll(eligible);
    long minimum = stayed.stream().mapToLong(this::charged).min().orElse(reentryFloor);
    for (Lane lane : current) {
      if (!eligible.contains(lane)) service.put(lane, Math.max(charged(lane), minimum));
    }
    eligible.clear();
    eligible.addAll(current);
    reentryFloor = current.stream().mapToLong(this::charged).min().orElse(reentryFloor);
    Lane chosen = null;
    for (int offset = 0; offset < ROTATION.size(); offset++) {
      Lane lane = ROTATION.get((nextLane + offset) % ROTATION.size());
      if (eligible.contains(lane) && !finished.contains(lane)
          && (chosen == null || charged(lane) < charged(chosen))) {
        chosen = lane;
      }
    }
    if (chosen != null) nextLane = (ROTATION.indexOf(chosen) + 1) % ROTATION.size();
    return chosen;
  }

  private long charged(Lane lane) {
    return service.getOrDefault(lane, 0L);
  }

  /** Whether a turn ended in cancellation, a storage failure or an unavailable provider. */
  private static boolean interrupted(StageOutcome outcome) {
    var accounting = outcome.accounting();
    return !accounting.storageFailures().isEmpty()
        || accounting.acquisitions().stream().anyMatch(acquisition -> !acquisition.available())
        || accounting.tickets().containsValue(EnrichmentAccounting.Disposition.CANCELLED);
  }

  /**
   * Stall diagnostic from eligible service, not selection identity: retained window advancement
   * is progress, and holds or backoff are not unsuccessful service. One WARN per episode.
   */
  private void noteService(Lane lane, StageOutcome outcome, boolean advancement) {
    if (advancement) {
      unsuccessful.remove(lane);
    } else if (outcome.attempted() && !interrupted(outcome)
        && unsuccessful.merge(lane, 1, Integer::sum) == STALL_TURNS) {
      log.warn("Enrichment lane {} received {} consecutive eligible turns without advancing any"
          + " document (no publication, terminal outcome or window advancement): its eligible"
          + " units are not draining.", lane, STALL_TURNS);
    }
  }

  private boolean laneEligible(Lane lane) {
    boolean bge = bgeM3EncoderSupplier.get() != null;
    NerService ner = nerServiceSupplier.get();
    boolean allowed = switch (lane) {
      // BGE-M3 owns parent dense inference; chunk dense keeps its own lane as before.
      case DENSE_PARENT, DENSE_CHUNK -> (lane == Lane.DENSE_CHUNK || !bge)
          && embeddingLifecycle.embeddingProvider().isAvailable()
          && embeddingLifecycle.allowEmbeddingWrites()
          && unitAllowed(embeddingLifecycle.embeddingProvider().isUsingGpu())
          && stageAllowed(lane == Lane.DENSE_PARENT ? Stage.EMBED : Stage.CHUNK_EMBED)
          && (lane == Lane.DENSE_PARENT || resolvedConfigSupplier.get().rag().chunkVectorsEnabled());
      case SPARSE_PARENT, SPARSE_CHUNK -> !bge && spladeEncoderSupplier.get() != null
          && sparseUnitAllowed() && stageAllowed(Stage.SPLADE)
          && (lane == Lane.SPARSE_PARENT || chunkSpladeEnabled());
      case NER -> ner != null && ner.isAvailable() && stageAllowed(Stage.NER)
          && unitAllowed(LoopPacingPolicy.requiresGpu(ner.isGpuAvailable(), ner.getOrtCudaStatus()));
      // Held if its provider is held or any stage it represents has a storage hold.
      case BGE_M3 -> bge && sparseUnitAllowed() && acquisitionAllowed(bgeM3EncoderSupplier.get())
          && storageAllowed(Stage.SPLADE) && storageAllowed(Stage.EMBED)
          && storageAllowed(Stage.CHUNK_EMBED);
    };
    if (!allowed) return false;
    String field = switch (lane) {
      case DENSE_PARENT -> SchemaFields.EMBEDDING_STATUS;
      case DENSE_CHUNK -> SchemaFields.CHUNK_EMBEDDING_STATUS;
      case SPARSE_PARENT, SPARSE_CHUNK, BGE_M3 -> SchemaFields.SPLADE_STATUS;
      case NER -> SchemaFields.NER_STATUS;
    };
    var kind = switch (lane) {
      case SPARSE_PARENT -> DocumentFieldOps.PendingDocumentKind.PARENT;
      case SPARSE_CHUNK -> DocumentFieldOps.PendingDocumentKind.CHUNK;
      case BGE_M3 -> chunkSpladeEnabled() ? DocumentFieldOps.PendingDocumentKind.ALL
          : DocumentFieldOps.PendingDocumentKind.PARENT;
      default -> DocumentFieldOps.PendingDocumentKind.ALL;
    };
    try {
      // Same overloads as the lane selectors, so eligibility and selection read one population.
      return !(kind == DocumentFieldOps.PendingDocumentKind.CHUNK
          ? documentFieldOps.queryPendingPage(field, 1, kind, null)
          : documentFieldOps.queryPendingPage(field, 1,
              kind == DocumentFieldOps.PendingDocumentKind.PARENT, null)).ids().isEmpty();
    } catch (java.io.IOException failure) {
      log.warn("Cannot establish enrichment lane eligibility for {}", lane, failure);
      return false;
    }
  }

  private void commitEnrichment(boolean orderlyYield) {
    if (dirtyDocuments == 0) return;
    long now = monotonicNanos.getAsLong();
    if (!orderlyYield && !LoopPacingPolicy.isTimeCommitTriggered(
        (now - lastEnrichmentCommitNanos) / 1_000_000L, dirtyDocuments, pacing().commitIntervalMs())
        && !LoopPacingPolicy.isBufferCommitTriggered(dirtyDocuments, pacing().maxDocsBeforeCommit())) return;
    try {
      commitOps.commitAndTrack(orderlyYield ? CommitReason.BACKFILL_COMBINED_FINAL : CommitReason.BACKFILL_COMBINED);
      dirtyDocuments = 0;
      lastEnrichmentCommitNanos = now;
    } catch (RuntimeException failure) {
      log.warn("Enrichment commit failed; dirty documents remain pending for the next check", failure);
    }
  }

  /**
   * Records one {@link StageOutcome}'s doc-count/timing into {@link OperationalMetrics} under the
   * given {@link BatchTimingKeys} key (tempdoc 710 Move 2 item 4 — individual-mode counters
   * previously froze because none of the individual {@code *BackfillOps} classes recorded
   * anything; only the combined pass did, 710 S-B3 finding).
   */
  private void recordStageOutcome(String stageKey, StageOutcome outcome) {
    recordBackoffOutcomes(outcome.accounting());
    OperationalMetrics metrics = OperationalMetrics.getInstance();
    metrics.recordEnrichmentOutcomes(outcome.accounting().counters());
    metrics.recordEnrichmentCompleted(stageKey, outcome.docsProcessed());
    if (outcome.attempted()) {
      metrics.recordStageTiming(stageKey, outcome.docsProcessed(), outcome.elapsedMs());
    }
  }

  private boolean unitAllowed(boolean gpu) {
    return !signalBus.isEnergyReduced()
        && (!gpu || (!signalBus.isBulkHeld() && !signalBus.isMainGpuActive()));
  }

  private boolean sparseUnitAllowed() {
    BgeM3Encoder bge = bgeM3EncoderSupplier.get();
    SpladeEncoder sparse = spladeEncoderSupplier.get();
    return unitAllowed(bge != null
        ? !bge.isBulkOnCpu() && LoopPacingPolicy.requiresGpu(bge.isGpuAvailable(), bge.getOrtCudaStatus())
        : sparse != null && LoopPacingPolicy.requiresGpu(sparse.isGpuAvailable(), sparse.getOrtCudaStatus()));
  }

  /** Resets backoff/latch state. Called from {@code resetForProfiling}. */
  public void resetState() {
    lastEnrichmentInterleaveTime = 0;
    stageBackoffs.clear();
    acquisitionBackoffs.clear();
    windowedEmbedProgress.clear();
    enrichmentSelection.clear();
    densePublished.clear();
    service.clear();
    eligible.clear();
    reentryFloor = 0;
    unsuccessful.clear();
    nextLane = 0;
    // Dirty publication state survives a profiling reset until a successful commit.
  }

  private boolean runDisambiguationIfReady(boolean alreadyDidWork) {
    // 334 Phase 8: Disambiguation only when no enrichment work pending.
    DisambiguationService disambiguationService = disambiguationServiceSupplier.get();
    if (alreadyDidWork
        || disambiguationService == null
        || !disambiguationService.isAvailable()) {
      return false;
    }
    try {
      if (indexCountOps.countByFieldOrThrow(
          SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING) != 0) return false;
      return processDisambiguationBackfill();
    } catch (java.io.IOException failure) {
      log.warn("Cannot establish disambiguation readiness; retrying next cycle", failure);
    }
    return false;
  }

  private Object provider(Stage stage) {
    BgeM3Encoder bge = bgeM3EncoderSupplier.get();
    return switch (stage) {
      case EMBED -> bge != null ? bge : embeddingLifecycle.embeddingProvider();
      case CHUNK_EMBED -> embeddingLifecycle.embeddingProvider();
      case SPLADE -> bge != null ? bge : spladeEncoderSupplier.get();
      case NER -> nerServiceSupplier.get();
    };
  }

  private StageBackoff backoff(Stage stage) {
    var state = stageBackoffs.computeIfAbsent(stage, ignored -> new StageBackoff());
    Object current = provider(stage);
    if (state.provider != current) {
      state.provider = current;
      state.failures = 0;
      state.retryNanos = 0;
    }
    return state;
  }

  private boolean stageAllowed(Stage stage) {
    return storageAllowed(stage) && acquisitionAllowed(provider(stage));
  }

  private boolean storageAllowed(Stage stage) {
    var state = backoff(stage);
    return state.failures == 0 || monotonicNanos.getAsLong() >= state.retryNanos;
  }

  private boolean acquisitionAllowed(Object provider) {
    acquisitionBackoffs.keySet().removeIf(held -> java.util.Arrays.stream(Stage.values())
        .noneMatch(candidate -> provider(candidate) == held));
    var acquisition = acquisitionBackoffs.get(provider);
    return acquisition == null || monotonicNanos.getAsLong() >= acquisition.retryNanos;
  }

  private void recordBackfillResult(Stage stage, boolean success) {
    var state = stageBackoffs.get(stage);
    // Results belong to the provider admitted for this turn, even if it retires during publication.
    if (state != null && state.provider != provider(stage)) {
      backoff(stage);
      return;
    }
    if (state == null) state = backoff(stage);
    if (success) {
      state.failures = 0;
      state.retryNanos = 0;
    } else {
      state.failures = Math.min(state.failures + 1, 63);
      long backoffMs = BackoffPolicy.spladeBackoffMs(LoopPacingPolicy.idleSleepMs(), state.failures);
      state.retryNanos = monotonicNanos.getAsLong()
          + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(backoffMs);
      log.warn("{} backfill failed ({} consecutive), next retry in {}ms", stage, state.failures, backoffMs);
    }
  }

  private void recordBackoffOutcomes(EnrichmentAccounting.Summary summary) {
    for (var outcome : summary.acquisitions()) {
      Object selected = outcome.provider();
      if (java.util.Arrays.stream(Stage.values()).noneMatch(stage -> provider(stage) == selected)) continue;
      if (outcome.available()) {
        acquisitionBackoffs.remove(selected);
      } else {
        var state = acquisitionBackoffs.computeIfAbsent(selected, ignored -> new StageBackoff());
        state.failures = Math.min(state.failures + 1, 63);
        state.retryNanos = monotonicNanos.getAsLong() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            BackoffPolicy.spladeBackoffMs(LoopPacingPolicy.idleSleepMs(), state.failures));
      }
    }
    for (Stage stage : Stage.values()) {
      if (summary.storageFailures().contains(stage)) recordBackfillResult(stage, false);
      // Sparse success is the operation's "not systemic" result, recorded by the turn.
      else if (stage != Stage.SPLADE && summary.tickets().entrySet().stream().anyMatch(entry ->
          entry.getKey().stage() == stage && switch (entry.getValue()) {
            case PUBLISHED, COMPLETED_EMPTY, FAILED_CHARGED, REJECTED_OUTPUT -> true;
            default -> false;
          })) {
        recordBackfillResult(stage, true);
      }
    }
  }

  private boolean hardStopBackfillWork() {
    return !running.get() || signalBus.isIndexingHeld()
        || Thread.currentThread().isInterrupted() || signalBus.isEnergyReduced();
  }

  private boolean chunkSpladeEnabled() {
    return resolvedConfigSupplier.get().rag().chunkSpladeEnabled();
  }

  private StageOutcome processLane(Lane lane, boolean busy, long deadline) {
    int cap = busy ? pacing().enrichmentInterleaveBatchSize() : pacing().embeddingBackfillBatchSize();
    return switch (lane) {
      case DENSE_PARENT, DENSE_CHUNK -> CombinedEnrichmentBackfillOps.processDenseUnit(
          new CombinedEnrichmentBackfillOps.BackfillContext(documentFieldOps, indexingCoordinator,
              indexingPacing, embeddingLifecycle::embeddingProvider,
              () -> !hardStopBackfillWork(), embeddingLifecycle::allowEmbeddingWrites, cap, log,
              resolvedConfigSupplier.get().rag().chunkVectorsEnabled(),
              resolvedConfigSupplier.get().ai().embedding().lateChunkingEnabled(),
              busy ? cap : pacing().chunkSlotsPerBatch(), () -> hardStopBackfillWork() || monotonicNanos.getAsLong() >= deadline,
              this::hardStopBackfillWork, windowedEmbedProgress, densePublished,
              this::unitAllowed, this::stageAllowed),
          enrichmentSelection, lane == Lane.DENSE_PARENT ? Stage.EMBED : Stage.CHUNK_EMBED);
      case SPARSE_PARENT, SPARSE_CHUNK -> {
        var context = new SpladeBackfillOps.BackfillContext(documentFieldOps,
        indexingCoordinator,
        indexingPacing,
        spladeEncoderSupplier,
        () -> !hardStopBackfillWork(),
        busy ? cap : pacing().spladeBackfillBatchSize(),
        chunkSpladeEnabled(),
        log,
        () -> monotonicNanos.getAsLong() < deadline && sparseUnitAllowed());
        yield lane == Lane.SPARSE_PARENT
            ? SpladeBackfillOps.processParentBackfill(context, enrichmentSelection)
            : SpladeBackfillOps.processChunkBackfill(context, enrichmentSelection);
      }
      case NER -> NerBackfillOps.processNerUnit(new NerBackfillOps.BackfillContext(documentFieldOps,
        indexingCoordinator,
        indexingPacing,
        nerServiceSupplier,
        () -> !hardStopBackfillWork(),
        1,
        log,
        () -> monotonicNanos.getAsLong() < deadline && unitAllowed(nerServiceSupplier.get() != null
              && LoopPacingPolicy.requiresGpu(nerServiceSupplier.get().isGpuAvailable(),
                  nerServiceSupplier.get().getOrtCudaStatus()))), enrichmentSelection);
      case BGE_M3 -> BgeM3BackfillOps.processBgeM3Backfill(new BgeM3BackfillOps.BackfillContext(
          documentFieldOps, indexingCoordinator, commitOps, signalBus, bgeM3EncoderSupplier,
          () -> !hardStopBackfillWork(), busy ? pacing().bgeM3InterleaveBatchSize()
              : pacing().bgeM3BackfillBatchSize(), false, chunkSpladeEnabled(), log,
          () -> monotonicNanos.getAsLong() < deadline && sparseUnitAllowed()), enrichmentSelection);
    };
  }

  private boolean processDisambiguationBackfill() {
    return DisambiguationBackfillOps.processDisambiguationBackfill(
        new DisambiguationBackfillOps.BackfillContext(
            documentFieldOps,
            indexingPacing,
            disambiguationServiceSupplier,
            () -> running.get() && !signalBus.isIndexingHeld(),
            pacing().disambiguationBackfillBatchSize(),
            log,
            indexingCoordinator));
  }
}
