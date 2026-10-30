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
import io.justsearch.indexerworker.loop.ops.BgeM3BackfillOps;
import io.justsearch.indexerworker.loop.ops.CombinedEnrichmentBackfillOps;
import io.justsearch.indexerworker.loop.ops.DisambiguationBackfillOps;
import io.justsearch.indexerworker.loop.ops.EmbeddingBackfillOps;
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
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedules backfill work for the {@link IndexingLoop} idle branch + interleaved SPLADE for
 * the primary-indexing branch.
 *
 * <p>Tempdoc 516 Slice 4d (Wave 6) — extracted from {@link IndexingLoop}. Owns the per-cycle
 * backfill orchestration: the combined enrichment tight loop, the per-stage fallback path
 * (embedding / chunk-embed / NER / SPLADE), and disambiguation. Owns the SPLADE retry-backoff
 * state ({@code consecutiveSpladeFailures}, {@code nextSpladeRetryTime},
 * {@code lastSpladeInterleaveTime}) and the disambiguation completion latch
 * ({@code disambiguationPassComplete}, {@code lastKnownNerCompletedCount}).
 *
 * <p>P5 boundary: a concrete final class with three entry points (runIdleCycle,
 * runInterleavedSplade, resetState). No strategy interface.
 *
 * <p>Cross-seam contract: encoders/services are read via supplier closures over
 * {@link IndexingLoop}'s volatile fields so the existing async-load swap-on-the-fly semantics
 * are preserved without IndexingLoop having to push updates.
 */
public final class BackfillScheduler {

  private static final Logger log = LoggerFactory.getLogger(BackfillScheduler.class);

  /**
   * Hard wall-clock budget for one {@link #runIdleCycle()} backfill burst (tempdoc 798).
   *
   * <p>Containment of last resort: whatever the input, control returns to {@code IndexingLoop
   * .runLoop()} — and therefore to the job-queue poll — within this window. The livelock this
   * bounds spun ~64 times/second on 2 documents for 20+ minutes and produced 59,420 identical
   * INFO lines with zero WARN and zero ERROR, so tripping the budget logs at WARN.
   */
  private static final long CYCLE_BUDGET_MS = 5_000L;

  /**
   * The embed stage's reserved share of one cycle (round-15 post-round finding; the starvation
   * residual recorded in tempdoc 813 §18).
   *
   * <p>Phases 3b/3c of the combined pass run after the embed stage and open by consulting the stop
   * signal, so an embed stage allowed to spend the whole {@link #CYCLE_BUDGET_MS} makes SPLADE and
   * NER structurally unrunnable — live evidence: {@code splade=0ms(ok=0)} and {@code ner=0ms(ok=0)}
   * on 54 consecutive cycles with SPLADE stuck at 1.05% coverage, both advancing the instant embed
   * finished. Past this point embed stops STARTING new units so the remaining ~40% reaches the
   * other stages; see {@code CombinedEnrichmentBackfillOps.embedShareSpent} for why that is not the
   * same signal as the yield/abort disjunction and why it does not apply to an embed-only backlog.
   *
   * <p>Cycle-absolute, not per-batch, on purpose: within one cycle the first batch takes the embed
   * share and later batches then serve the other stages, which is the round-robin the reservation
   * exists to produce.
   */
  private static final long EMBED_BUDGET_SHARE_MS = 3_000L;

  /**
   * Consecutive cycles with an identical combined work-set before the stall WARN fires (round-15
   * post-round finding).
   *
   * <p>Five ~5 s cycles is ~25 s of selecting the same documents — long past any legitimate
   * "one big batch spanning a couple of cycles" and far short of the 12+ minutes the field incident
   * ran before anyone noticed, because nothing in the log distinguished it from healthy progress.
   */
  private static final int STALL_SIGNATURE_CYCLES = 5;

  // Tempdoc 710 Wave-1.5 Move 4: the per-stage backfill batch sizes (formerly static fields
  // computed once from LoopPacingPolicy, plus the BGE-M3 pair which bypassed LoopPacingPolicy
  // entirely as bare literals here) all moved onto ResolvedConfig.Ai.BackfillPacing
  // (justsearch.backfill.* config surface). See the pacing() helper below — call sites read the
  // live snapshot the same way resolvedConfigSupplier.get().rag().chunkVectorsEnabled() already
  // does elsewhere in this class.

  /** Scheduler-local monotonic domain for cycle and embed-share deadlines only. */
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

  private long lastSpladeInterleaveTime = 0;
  private int consecutiveSpladeFailures = 0;
  private long nextSpladeRetryTime = 0;
  private boolean disambiguationPassComplete = false;
  private int lastKnownNerCompletedCount = 0;

  /**
   * Cross-cycle per-window accumulator for long documents (round-15 post-round finding). Lives here
   * because this is the object whose lifetime spans cycles — the pending-ID caches deliberately do
   * not, so a per-cycle store would restore exactly the defect it removes.
   */
  private final io.justsearch.indexerworker.loop.ops.WindowedEmbedProgress windowedEmbedProgress =
      new io.justsearch.indexerworker.loop.ops.WindowedEmbedProgress();

  /** Stall detection state (round-15 post-round finding) — see {@link #noteWorkSet}. */
  private long lastWorkSetSignature = 0;

  private int repeatedWorkSetCycles = 0;
  private boolean stallWarnedThisEpisode = false;

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
    this.documentFieldOps = documentFieldOps;
    this.indexingCoordinator = indexingCoordinator;
    this.indexCountOps = indexCountOps;
    this.commitOps = commitOps;
    this.signalBus = signalBus;
    this.indexingPacing =
        java.util.Objects.requireNonNull(indexingPacing, "indexingPacing");
    this.embeddingLifecycle = embeddingLifecycle;
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
   * Runs one backfill cycle from the idle branch. Returns {@code true} if any backfill stage
   * (combined or individual) reported progress, so the caller can pick the active-vs-truly-idle
   * sleep duration.
   *
   * <p>Self-committing: combined-backfill tight loop commits every 5 batches + a final
   * commit; individual stages commit per their own contracts.
   *
   * <p>Bounded by construction (tempdoc 798): every loop inside terminates on PROGRESS rather than
   * activity, yields to pending ingest work, and is capped by {@link #CYCLE_BUDGET_MS}. Control
   * returns to the caller's job-queue poll regardless of what the enrichment population does.
   */
  public boolean runIdleCycle() {
    if (signalBus.isIndexingHeld()) return false;
    boolean backfillDidWork = false;
    final long cycleStartNanos = monotonicNanos.getAsLong();
    final long cycleDeadlineNanos =
        cycleStartNanos + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(CYCLE_BUDGET_MS);
    final long embedShareDeadlineNanos =
        cycleStartNanos + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(EMBED_BUDGET_SHARE_MS);

    boolean bulkHeld = signalBus.isBulkHeld();
    boolean runBackfill =
        LoopPacingPolicy.shouldRunBackfill(
            signalBus.isMainGpuActive(),
            signalBus.isEnergyReduced(),
            bulkHeld,
            bulkHeld && sparseOrNerOnGpu(),
            embeddingLifecycle.embeddingProvider());
    if (runBackfill) {
      var batchCommitCounter = new int[] {0};
      Set<String> publishedDocIds = new HashSet<>();
      CombinedEnrichmentBackfillOps.CombinedOutcome outcome =
          processCombinedBackfillIfApplicable(
              null, null, batchCommitCounter, cycleDeadlineNanos, embedShareDeadlineNanos,
              publishedDocIds);
      recordCombinedOutcome(outcome);
      // The mode-selection probe is the batch that re-selects the head of the pending queue each
      // cycle — the one whose signature repeated byte-identically for 12+ minutes in the field.
      noteWorkSet(outcome);
      // Mode selection reads ACTIVITY (did the combined pass touch anything at all); the tight
      // loop below reads PROGRESS. Tempdoc 798: these are different questions and conflating
      // them is what livelocked ingest.
      boolean useCombined = outcome.wroteAnything();
      if (useCombined) {
        // Tempdoc 710 Move 2 item 4: this cycle used the combined pass — the only path taken
        // between here and the next runIdleCycle() call (the tight loop below stays combined
        // until it drains or is interrupted), so the mode is settled at this point.
        OperationalMetrics.getInstance().recordBackfillMode("combined");
        // 334 Phase 8/10: tight loop with persistent pending-ID caches across iterations.
        var parentIdCache = new ArrayDeque<String>();
        var chunkIdCache = new ArrayDeque<String>();
        backfillDidWork = true;
        // Tempdoc 809 finding 3: an aborted probe means a stop condition came up DURING that batch
        // — pending ingest, the cycle budget, or a bulk deletion that invalidated the documents it
        // had selected. Starting a fresh tight-loop batch would re-select and re-encode against
        // that same condition, which is how the mid-batch signal ended up costing a further ~5s of
        // GPU instead of returning control. Mode selection itself is unchanged: this cycle still
        // used the combined pass, and the next idle cycle picks the enrichment up where it stopped.
        final boolean[] useCombinedRef = {!outcome.aborted()};
        final int[] tightLoopBatches = {1};
        // Tempdoc 809 finding 3: the mode-selection probe above is a full batch and is now
        // interruptible too, so it can be the call that spends the budget. Seeding from it keeps
        // the 798 WARN honest — without this, a cycle whose ONLY batch overran would report
        // nothing at all.
        final boolean[] budgetTripped = {outcome.aborted() && monotonicNanos.getAsLong() >= cycleDeadlineNanos};
        // Tempdoc 798 review F3: cumulative across the whole cycle, mode-selection probe included.
        // Budget exhaustion means very different things depending on this flag, and the diagnostic
        // must not assert non-convergence when enrichment was demonstrably converging.
        final boolean[] anyProgressThisCycle = {outcome.progressed()};
        // 334 Phase 8: NRT suspend during tight loop prevents mmap accumulation from
        // ControlledRealTimeReopenThread while commits are deferred (every 5 batches).
        commitOps.withNrtSuspended(
            () -> {
              while (useCombinedRef[0]) {
                if (!running.get() || signalBus.isIndexingHeld() || Thread.currentThread().isInterrupted()) break;
                // Tempdoc 885 item 3: yield to foreground load, do not abandon the cycle for it.
                indexingPacing.pace();
                if (signalBus.shouldYieldGpuBackfill()) break; // tempdoc 630: GPU-claimed OR energy-reduced
                // Tempdoc 798: primary indexing outranks background enrichment. Backfill resumes
                // next idle cycle; a queued ingest job the user is waiting on cannot.
                if (signalBus.hasPendingIngest()) break;
                if (monotonicNanos.getAsLong() >= cycleDeadlineNanos) {
                  budgetTripped[0] = true;
                  break;
                }
                CombinedEnrichmentBackfillOps.CombinedOutcome tightLoopOutcome =
                    processCombinedBackfillIfApplicable(
                        parentIdCache,
                        chunkIdCache,
                        batchCommitCounter,
                        cycleDeadlineNanos,
                        embedShareDeadlineNanos,
                        publishedDocIds);
                recordCombinedOutcome(tightLoopOutcome);
                // Tempdoc 798: PROGRESS, not activity. `wroteAnything()` stays true forever for a
                // document that is rewritten every batch without ever advancing a stage.
                useCombinedRef[0] = tightLoopOutcome.progressed();
                if (useCombinedRef[0]) {
                  anyProgressThisCycle[0] = true;
                  tightLoopBatches[0]++;
                }
                // Tempdoc 809 finding 3: the batch left its own document set unfinished because a
                // stop condition came up mid-batch. That condition is still true, so starting
                // another batch would only re-discover it after another sub-batch of GPU work.
                // Re-read the deadline first: an aborted batch is the most likely way the budget
                // is now spent, and breaking without this would silently drop 798's WARN.
                if (tightLoopOutcome.aborted()) {
                  if (monotonicNanos.getAsLong() >= cycleDeadlineNanos) budgetTripped[0] = true;
                  break;
                }
              }
              if (batchCommitCounter[0] > 0) {
                commitOps.commitAndTrack(CommitReason.BACKFILL_COMBINED_FINAL);
              }
            });
        if (budgetTripped[0]) {
          // Tempdoc 798 review F3: the budget is smaller than two large legitimate enrichment
          // batches, so its most common trigger is a healthy cycle that simply ran out of window.
          // Only a cycle in which NOTHING advanced is the non-convergence this WARN describes;
          // crying wolf on the healthy case trains operators to ignore the one signal 798 added.
          // Both branches return control to the job-queue poll — only the log level differs.
          if (anyProgressThisCycle[0]) {
            log.info(
                "Combined enrichment backfill reached its {}ms cycle budget after {} batches while"
                    + " still advancing documents — returning to the job-queue poll; the remaining"
                    + " enrichment resumes next idle cycle (tempdoc 798).",
                CYCLE_BUDGET_MS,
                tightLoopBatches[0]);
          } else {
            log.warn(
                "Combined enrichment backfill hit its {}ms cycle budget after {} batches with ZERO"
                    + " stage advancement — returning to the job-queue poll (tempdoc 798). This is"
                    + " the non-converging shape: the same documents are rewritten every batch"
                    + " without any stage advancing.",
                CYCLE_BUDGET_MS,
                tightLoopBatches[0]);
          }
        }
        if (tightLoopBatches[0] > 1) {
          log.debug("Tight backfill loop: {} consecutive batches", tightLoopBatches[0]);
        }
      } else {
        // Pre-stamped BEFORE the pass runs on purpose: mid-pass observability is the gauge's whole
        // purpose (710 S-B3), so an operator polling DURING a long individual pass must see
        // "individual" rather than the previous cycle's value.
        OperationalMetrics.getInstance().recordBackfillMode("individual");
        IndividualOutcome individual = runIndividualBackfills(cycleDeadlineNanos);
        backfillDidWork = individual.didWork();
        if (!individual.anyActivity()) {
          // ...but a pass that ran and advanced NOTHING is exactly what getBackfillMode() documents
          // as "idle" ("no backfill work was available/eligible last cycle"). Leaving "individual"
          // standing left a last-known gauge looking like live activity for as long as the worker
          // stayed idle — the worker half of the owner's 2026-08-07 finding (813 §20a), where a
          // fully-settled index reported backfillMode="individual" indefinitely.
          OperationalMetrics.getInstance().recordBackfillMode("idle");
        }
      }
    } else {
      OperationalMetrics.getInstance().recordBackfillMode("idle");
    }

    // Disambiguation is gated on no-other-work-this-cycle and never flips backfillDidWork.
    runDisambiguationIfReady(backfillDidWork);
    return backfillDidWork;
  }

  /**
   * Records one {@link CombinedEnrichmentBackfillOps.CombinedOutcome}'s per-stage counts/timing
   * into {@link OperationalMetrics} (tempdoc 710 Move 2 item 4 — replaces the {@code finally}
   * block that used to live inside {@code CombinedEnrichmentBackfillOps.processCombinedBackfill}).
   * A no-op when {@code outcome.recordTiming()} is false (nothing ran this call).
   */
  private static void recordCombinedOutcome(CombinedEnrichmentBackfillOps.CombinedOutcome outcome) {
    if (!outcome.recordTiming()) {
      return;
    }
    OperationalMetrics metrics = OperationalMetrics.getInstance();
    metrics.recordEnrichmentCompleted(BatchTimingKeys.EMBED, outcome.embedProcessed());
    metrics.recordEnrichmentCompleted(BatchTimingKeys.SPLADE, outcome.spladeProcessed());
    metrics.recordEnrichmentCompleted(BatchTimingKeys.NER, outcome.nerProcessed());
    metrics.recordStageTiming(BatchTimingKeys.EMBED, outcome.embedProcessed(), outcome.embedMs());
    metrics.recordStageTiming(
        BatchTimingKeys.SPLADE, outcome.spladeProcessed(), outcome.spladeMs());
    metrics.recordStageTiming(BatchTimingKeys.NER, outcome.nerProcessed(), outcome.nerMs());
    metrics.recordBatchTiming(BatchTimingKeys.FETCH, outcome.fetchMs());
    metrics.recordBatchTiming(BatchTimingKeys.WRITE, outcome.writeMs());
    metrics.recordBatchTiming(BatchTimingKeys.TOTAL, outcome.totalMs());
  }

  /**
   * Records one {@link StageOutcome}'s doc-count/timing into {@link OperationalMetrics} under the
   * given {@link BatchTimingKeys} key (tempdoc 710 Move 2 item 4 — individual-mode counters
   * previously froze because none of the individual {@code *BackfillOps} classes recorded
   * anything; only the combined pass did, 710 S-B3 finding).
   */
  private static void recordStageOutcome(String stageKey, StageOutcome outcome) {
    OperationalMetrics metrics = OperationalMetrics.getInstance();
    metrics.recordEnrichmentCompleted(stageKey, outcome.docsProcessed());
    metrics.recordStageTiming(stageKey, outcome.docsProcessed(), outcome.elapsedMs());
  }

  /**
   * Interleaves SPLADE/BGE-M3 backfill during primary indexing (tempdoc 278 item 4a).
   * Time-gated: one small batch every spladeInterleaveIntervalMs to limit primary-indexing
   * overhead (~13%).
   */
  public void runInterleavedSplade(long now) {
    if (spladeEncoderSupplier.get() == null && bgeM3EncoderSupplier.get() == null) return;
    // Spec E section 1.3 item 5: no interleaved sparse batch starts while bulk GPU work is held.
    if (signalBus.isIndexingHeld() || signalBus.isBulkHeld()) return;
    if (now < nextSpladeRetryTime) return;
    long spladeIntervalMs = pacing().spladeInterleaveIntervalMs();
    if (now - lastSpladeInterleaveTime < spladeIntervalMs) return;
    StageOutcome outcome = processSpladeBackfillInterleaved();
    recordStageOutcome(BatchTimingKeys.SPLADE, outcome);
    lastSpladeInterleaveTime = System.currentTimeMillis();
    recordSpladeBackfillResult(outcome.success());
  }

  /**
   * Whether the sparse (SPLADE or BGE-M3) or NER bulk encoder runs on the GPU (Spec E section 1.3
   * item 2): cycle entry is skipped under the bulk hold when any bulk encoder is on the GPU, not
   * just embedding.
   */
  private boolean sparseOrNerOnGpu() {
    SpladeEncoder splade = spladeEncoderSupplier.get();
    if (splade != null && splade.isGpuAvailable()) return true;
    BgeM3Encoder bge = bgeM3EncoderSupplier.get();
    if (bge != null && bge.isGpuAvailable()) return true;
    NerService ner = nerServiceSupplier.get();
    return ner != null && ner.isGpuAvailable();
  }

  /** Resets backoff/latch state. Called from {@code resetForProfiling}. */
  public void resetState() {
    lastSpladeInterleaveTime = 0;
    consecutiveSpladeFailures = 0;
    nextSpladeRetryTime = 0;
    disambiguationPassComplete = false;
    lastKnownNerCompletedCount = 0;
    windowedEmbedProgress.clear();
    lastWorkSetSignature = 0;
    repeatedWorkSetCycles = 0;
    stallWarnedThisEpisode = false;
  }

  /**
   * What one individual-backfill pass did, on the two axes this loop keeps deliberately separate.
   *
   * <p>{@code didWork} drives the caller's active-vs-truly-idle SLEEP pacing and keeps its exact
   * historical semantics (only the chunk tight-loop and a SPLADE pass over a non-empty backlog set
   * it — a parent-embedding batch does not). {@code anyActivity} answers the OPERATOR GAUGE's
   * different question: did any stage actually advance a document this cycle. Tempdoc 798 already
   * draws this line for the combined branch ("Mode selection reads ACTIVITY... the tight loop reads
   * PROGRESS... conflating them is what livelocked ingest"); reusing {@code didWork} for the gauge
   * would stamp "idle" over a cycle that embedded a batch, which is the same lie in the other
   * direction.
   */
  private record IndividualOutcome(boolean didWork, boolean anyActivity) {}

  private IndividualOutcome runIndividualBackfills(long cycleDeadlineNanos) {
    boolean backfillDidWork = false;
    boolean anyActivity = false;
    boolean embeddingCanProgress = embeddingLifecycle.embeddingProvider().isAvailable()
        && embeddingLifecycle.allowEmbeddingWrites();
    if (embeddingCanProgress) {
      StageOutcome outcome = processEmbeddingBackfill();
      recordStageOutcome(BatchTimingKeys.EMBED, outcome);
      anyActivity |= outcome.docsProcessed() > 0;
    }
    // Chunk vectors after parent embedding completes. 334 Phase 8 tight loop.
    if (embeddingCanProgress && resolvedConfigSupplier.get().rag().chunkVectorsEnabled()) {
      int pendingDocEmbeddings =
          indexCountOps.countByField(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      if (pendingDocEmbeddings == 0) {
        StageOutcome chunkOutcome = processChunkEmbeddingBackfill();
        recordStageOutcome(BatchTimingKeys.EMBED, chunkOutcome);
        anyActivity |= chunkOutcome.docsProcessed() > 0;
        boolean chunkDidWork = chunkOutcome.success();
        // Tempdoc 798 review F3: same distinction as the combined loop — a budget hit while chunks
        // were genuinely embedding is a healthy long cycle, not non-convergence.
        boolean anyChunkProgress = chunkOutcome.docsProcessed() > 0;
        while (chunkDidWork) {
          backfillDidWork = true;
          if (!running.get() || signalBus.isIndexingHeld() || Thread.currentThread().isInterrupted()) break;
          indexingPacing.pace(); // tempdoc 885 item 3: duty cycle, not a stop
          if (signalBus.shouldYieldGpuBackfill()) break; // tempdoc 630: GPU-claimed OR energy-reduced
          if (signalBus.hasPendingIngest()) break; // tempdoc 798: primary indexing outranks backfill
          if (monotonicNanos.getAsLong() >= cycleDeadlineNanos) {
            if (anyChunkProgress) {
              log.info(
                  "Chunk-embedding backfill reached its {}ms cycle budget while still embedding"
                      + " chunks — returning to the job-queue poll; the remainder resumes next idle"
                      + " cycle (tempdoc 798).",
                  CYCLE_BUDGET_MS);
            } else {
              log.warn(
                  "Chunk-embedding backfill hit its {}ms cycle budget with ZERO chunks embedded —"
                      + " returning to the job-queue poll (tempdoc 798).",
                  CYCLE_BUDGET_MS);
            }
            break;
          }
          chunkOutcome = processChunkEmbeddingBackfill();
          recordStageOutcome(BatchTimingKeys.EMBED, chunkOutcome);
          chunkDidWork = chunkOutcome.success();
          if (chunkOutcome.docsProcessed() > 0) {
            anyChunkProgress = true;
            anyActivity = true;
          }
        }
      }
    }
    NerService nerService = nerServiceSupplier.get();
    if (nerService != null && nerService.isAvailable()) {
      boolean embeddingsReady;
      if (!embeddingCanProgress) {
        embeddingsReady = true;
      } else {
        int pendingEmbeddings =
            indexCountOps.countByField(
                SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
        boolean chunksPending = false;
        if (resolvedConfigSupplier.get().rag().chunkVectorsEnabled()) {
          chunksPending =
              indexCountOps.countByField(
                      SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING)
                  > 0;
        }
        embeddingsReady = pendingEmbeddings == 0 && !chunksPending;
      }
      if (embeddingsReady) {
        StageOutcome outcome = processNerBackfill();
        recordStageOutcome(BatchTimingKeys.NER, outcome);
        anyActivity |= outcome.docsProcessed() > 0;
      }
    }
    // SPLADE after embedding nearly completes (tempdoc 312 item 39, relaxed 334 item 37).
    if ((spladeEncoderSupplier.get() != null || bgeM3EncoderSupplier.get() != null)
        && System.currentTimeMillis() >= nextSpladeRetryTime) {
      int pendingEmbedForSplade =
          embeddingCanProgress
              ? indexCountOps.countByField(
                  SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING)
              : 0;
      if (pendingEmbedForSplade < pacing().embeddingBackfillBatchSize()) {
        int spladePendingBefore = spladePendingCount();
        StageOutcome outcome = processSpladeBackfill();
        recordStageOutcome(BatchTimingKeys.SPLADE, outcome);
        anyActivity |= outcome.docsProcessed() > 0;
        boolean success = outcome.success();
        recordSpladeBackfillResult(success);
        if (success && spladePendingBefore > 0) {
          backfillDidWork = true;
        }
      }
    }
    return new IndividualOutcome(backfillDidWork, anyActivity);
  }

  private void runDisambiguationIfReady(boolean alreadyDidWork) {
    // 334 Phase 8: Disambiguation only when no enrichment work pending.
    DisambiguationService disambiguationService = disambiguationServiceSupplier.get();
    if (alreadyDidWork
        || disambiguationService == null
        || !disambiguationService.isAvailable()) {
      return;
    }
    int pendingNer =
        indexCountOps.countByField(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING);
    if (pendingNer != 0) return;
    // Tempdoc 798 review F4: deliberately COMPLETED only, NOT COMPLETED_EMPTY. This counter is a
    // change-detector for "new entities exist, so the disambiguation pass is stale". A
    // COMPLETED_EMPTY document is one NER ran on and found no entities — it contributes nothing to
    // the entity graph, so a batch that finishes entirely as COMPLETED_EMPTY genuinely has nothing
    // to re-disambiguate and must not re-trigger a full pass. A mixed batch still moves this
    // counter via its entity-bearing documents, so nothing is missed. (Contrast the coverage
    // counters in IndexCountOps, which ask "is the stage done?" — there both tokens must sum.)
    int nerCompleted =
        indexCountOps.countByField(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED);
    if (nerCompleted != lastKnownNerCompletedCount) {
      disambiguationPassComplete = false;
      lastKnownNerCompletedCount = nerCompleted;
    }
    if (!disambiguationPassComplete) {
      processDisambiguationBackfill();
      disambiguationPassComplete = true;
    }
  }

  private void recordSpladeBackfillResult(boolean success) {
    if (success) {
      consecutiveSpladeFailures = 0;
      nextSpladeRetryTime = 0;
    } else {
      consecutiveSpladeFailures++;
      long backoffMs =
          BackoffPolicy.spladeBackoffMs(LoopPacingPolicy.idleSleepMs(), consecutiveSpladeFailures);
      nextSpladeRetryTime = System.currentTimeMillis() + backoffMs;
      log.warn(
          "SPLADE backfill failed ({} consecutive), next retry in {}ms",
          consecutiveSpladeFailures,
          backoffMs);
    }
  }

  // ==================== Backfill delegates ====================

  /**
   * Non-termination detector (round-15 post-round finding): WARNs when the combined backfill keeps
   * selecting the IDENTICAL work-set cycle after cycle while still attempting encoder work.
   *
   * <p>The gap this closes is not "no diagnostic existed" but "the wrong discriminator was used".
   * 798's WARN fires only when a budget-spending cycle advanced NOTHING; the field incident
   * advanced 56-80 documents per cycle and never moved its queue head, so it took the healthy INFO
   * branch 54 times in a row. Progress is therefore deliberately NOT part of this condition —
   * work-set identity is. A draining backfill's selection changes as its head drains; a stalled
   * one's does not, whatever its per-cycle counters say.
   *
   * <p>Deduplicated per episode: one WARN, not one per cycle. The episode ends when the signature
   * changes, which re-arms it.
   */
  private void noteWorkSet(CombinedEnrichmentBackfillOps.CombinedOutcome outcome) {
    long signature = outcome.workSetSignature();
    // No selection (nothing pending), or the pass returned before any stage ran: neither is an
    // observation about whether enrichment is draining, so neither may extend OR reset an episode.
    if (signature == 0 || !outcome.recordTiming()) {
      return;
    }
    if (signature != lastWorkSetSignature) {
      lastWorkSetSignature = signature;
      repeatedWorkSetCycles = 1;
      stallWarnedThisEpisode = false;
      return;
    }
    repeatedWorkSetCycles++;
    if (repeatedWorkSetCycles >= STALL_SIGNATURE_CYCLES && !stallWarnedThisEpisode) {
      stallWarnedThisEpisode = true;
      log.warn(
          "Combined enrichment backfill has selected an IDENTICAL work-set for {} consecutive"
              + " cycles while still attempting encoder work — the queue head is not draining."
              + " Per-cycle counters can look healthy in this state (documents ARE being encoded);"
              + " what is not happening is documents LEAVING the pending population. The known"
              + " cause is documents whose enrichment cannot finish inside one cycle budget"
              + " (round-15 post-round finding). Check the combined-backfill summary line's"
              + " windowDocsResuming= and deferredForOtherStages= counters.",
          repeatedWorkSetCycles);
    }
  }

  /**
   * The composite "stop starting new enrichment work" signal handed to one combined batch (tempdoc
   * 809 finding 3). Exactly the conditions the tight loop below breaks on, evaluated at the batch's
   * internal sub-batch boundaries instead of only between batches — a batch is ~150 documents and
   * was measured at ~63 s, so between-batches was a 12x overshoot of the very budget it enforced.
   *
   * <p>Tempdoc 885 item 3: this is also the batch's pacing tick, so the call is deliberately not
   * side-effect-free — it may yield before answering. Foreground load left the stop set: under a
   * duty cycle a contended batch runs slower, it does not stop. The remaining reasons are all
   * genuine stops. Callers invoke it exactly once per sub-batch boundary.
   */
  private boolean paceAndCheckStopBackfillWork(long cycleDeadlineNanos) {
    indexingPacing.pace();
    return hardStopBackfillWork()
        || monotonicNanos.getAsLong() >= cycleDeadlineNanos;
  }

  /** Stop reasons that must not be hidden by a first-unit scheduling floor. */
  private boolean hardStopBackfillWork() {
    return !running.get()
        || signalBus.isIndexingHeld()
        || Thread.currentThread().isInterrupted()
        || signalBus.shouldYieldGpuBackfill()
        || signalBus.hasPendingIngest();
  }

  private CombinedEnrichmentBackfillOps.CombinedOutcome processCombinedBackfillIfApplicable(
      ArrayDeque<String> parentIdCache,
      ArrayDeque<String> chunkIdCache,
      int[] batchesSinceCommit,
      long cycleDeadlineNanos,
      long embedShareDeadlineNanos,
      Set<String> publishedDocIds) {
    StageAvailability available = stageAvailability();
    int availCount = (available.embedding() ? 1 : 0) + (available.sparse() ? 1 : 0)
        + (available.ner() ? 1 : 0);
    if (availCount < 2) {
      return CombinedEnrichmentBackfillOps.CombinedOutcome.none();
    }

    ResolvedConfig.Ai.BackfillPacing pacing = pacing();
    return CombinedEnrichmentBackfillOps.processCombinedBackfill(
        new CombinedEnrichmentBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            indexingPacing,
            embeddingLifecycle::embeddingProvider,
            spladeEncoderSupplier,
            nerServiceSupplier,
            () -> running.get() && !signalBus.isIndexingHeld(),
            embeddingLifecycle::allowEmbeddingWrites,
            pacing.embeddingBackfillBatchSize(),
            log,
            resolvedConfigSupplier.get().rag().chunkVectorsEnabled(),
            resolvedConfigSupplier.get().rag().chunkSpladeEnabled(),
            resolvedConfigSupplier.get().ai().embedding().lateChunkingEnabled(),
            pacing.chunkSlotsPerBatch(),
            parentIdCache != null ? parentIdCache : new ArrayDeque<>(),
            chunkIdCache != null ? chunkIdCache : new ArrayDeque<>(),
            batchesSinceCommit != null ? batchesSinceCommit : new int[] {0},
            () -> paceAndCheckStopBackfillWork(cycleDeadlineNanos),
            this::hardStopBackfillWork,
            () -> monotonicNanos.getAsLong() >= embedShareDeadlineNanos,
            windowedEmbedProgress,
            publishedDocIds,
            () -> System.currentTimeMillis() >= nextSpladeRetryTime,
            this::recordSpladeBackfillResult));
  }

  private StageOutcome processEmbeddingBackfill() {
    // BGE-M3 handles dense embeddings in its unified backfill pass — skip separate embedding
    if (bgeM3EncoderSupplier.get() != null) {
      log.debug("Embedding backfill skipped: BGE-M3 handles dense embeddings");
      return StageOutcome.none();
    }
    return EmbeddingBackfillOps.processEmbeddingBackfill(
        new EmbeddingBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            indexingPacing,
            embeddingLifecycle::embeddingProvider,
            () -> running.get() && !signalBus.isIndexingHeld(),
            embeddingLifecycle::allowEmbeddingWrites,
            pacing().embeddingBackfillBatchSize(),
            log));
  }

  private StageOutcome processChunkEmbeddingBackfill() {
    return EmbeddingBackfillOps.processChunkEmbeddingBackfill(
        new EmbeddingBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            indexingPacing,
            embeddingLifecycle::embeddingProvider,
            () -> running.get() && !signalBus.isIndexingHeld(),
            embeddingLifecycle::allowEmbeddingWrites,
            pacing().embeddingBackfillBatchSize(),
            log));
  }

  private StageOutcome processNerBackfill() {
    return NerBackfillOps.processNerBackfill(
        new NerBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            indexingPacing,
            nerServiceSupplier,
            () -> running.get() && !signalBus.isIndexingHeld(),
            pacing().nerBackfillBatchSize(),
            log));
  }

  /**
   * Whether chunk documents are SPLADE work at all (tempdoc 712 {@code rag.chunk_splade.enabled}).
   *
   * <p>Flag off, no lane may select a chunk on {@code splade_status}: chunks written during an
   * earlier flag-on window still carry PENDING ({@code ChunkDocumentWriter} omits the field
   * entirely while the flag is off), so a lane that ignores this flag encodes sparse data
   * the configuration says not to produce, and the chunk-sparse retrieval leg
   * ({@code SearchExecutor#chunkMerge}) then scores against a population that depends on how far
   * backfill happened to get.
   */
  private boolean chunkSpladeEnabled() {
    return resolvedConfigSupplier.get().rag().chunkSpladeEnabled();
  }

  /** Outstanding SPLADE work over the population the lanes will actually select from. */
  private int spladePendingCount() {
    return chunkSpladeEnabled()
        ? indexCountOps.countByField(
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)
        : indexCountOps.countNonChunkByField(
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
  }

  private StageOutcome processSpladeBackfill() {
    BgeM3Encoder bge = bgeM3EncoderSupplier.get();
    if (bge != null) {
      return BgeM3BackfillOps.processBgeM3Backfill(
          new BgeM3BackfillOps.BackfillContext(
              documentFieldOps,
              indexingCoordinator,
              commitOps,
              signalBus,
              () -> bge,
              () -> running.get() && !signalBus.isIndexingHeld(),
              pacing().bgeM3BackfillBatchSize(),
              true,
              chunkSpladeEnabled(),
              log));
    }
    return SpladeBackfillOps.processSpladeBackfill(
        new SpladeBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            indexingPacing,
            spladeEncoderSupplier,
            () -> running.get() && !signalBus.isIndexingHeld(),
            pacing().spladeBackfillBatchSize(),
            true,
            chunkSpladeEnabled(),
            log));
  }

  private StageOutcome processSpladeBackfillInterleaved() {
    BgeM3Encoder bge = bgeM3EncoderSupplier.get();
    if (bge != null) {
      return BgeM3BackfillOps.processBgeM3Backfill(
          new BgeM3BackfillOps.BackfillContext(
              documentFieldOps,
              indexingCoordinator,
              commitOps,
              signalBus,
              () -> bge,
              () -> running.get() && !signalBus.isIndexingHeld(),
              pacing().bgeM3InterleaveBatchSize(),
              false,
              chunkSpladeEnabled(),
              log));
    }
    return SpladeBackfillOps.processSpladeBackfill(
        new SpladeBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            indexingPacing,
            spladeEncoderSupplier,
            () -> running.get() && !signalBus.isIndexingHeld(),
            pacing().spladeInterleaveBatchSize(),
            false,
            chunkSpladeEnabled(),
            log));
  }

  private void processDisambiguationBackfill() {
    DisambiguationBackfillOps.processDisambiguationBackfill(
        new DisambiguationBackfillOps.BackfillContext(
            documentFieldOps,
            indexingPacing,
            disambiguationServiceSupplier,
            () -> running.get() && !signalBus.isIndexingHeld(),
            pacing().disambiguationBackfillBatchSize(),
            log));
  }
}
