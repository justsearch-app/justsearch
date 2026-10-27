/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tempdoc 798 — structural containment for the ingest livelock.
 *
 * <p>The worker's single {@code indexing-loop} thread alternates between polling the ingest job
 * queue and running background enrichment backfill. The combined-enrichment tight loop used to
 * continue on {@code CombinedOutcome.anyWorkDone()} — defined as {@code written > 0}, i.e. "the
 * batch write touched at least one Lucene doc". That is ACTIVITY, not PROGRESS: two documents that
 * are rewritten every batch but never advance a stage pin it true forever. In the field that spun
 * ~64 times/second for 20+ minutes on 2 documents (59,420 identical INFO lines, zero WARN, zero
 * ERROR) while every user ingest queued behind it sat unclaimed and every health surface stayed
 * green.
 *
 * <p>Three independent containments hold the loop, and each test below pins exactly ONE of them —
 * deliberately, so that none can pass on another's behalf (tempdoc 798 review TQ2; before the
 * restructure the 5s budget alone satisfied every case, which made them containment tests wearing
 * a progress test's name):
 *
 * <ol>
 *   <li>{@link #nonProgressingBatch_tightLoopStopsImmediately()} — the loop terminates on PROGRESS
 *       ({@code progressed}), not activity. Its bounds (one tight-loop batch, well under a second)
 *       are far tighter than {@code CYCLE_BUDGET_MS}, so the budget CANNOT satisfy it.
 *   <li>{@link #advancingButNeverDrainingPopulation_budgetReturnsControl()} — the monotonic budget
 *       returns control even when every batch genuinely advances documents, so progress-termination
 *       cannot satisfy it either.
 *   <li>{@link #pendingIngest_yieldsAfterOneBatch()} — pending ingest work breaks the loop, so
 *       primary indexing always beats backfill.
 * </ol>
 *
 * <p>Bite check (verified, tempdoc 798 review): reverting the tight loop's drive to {@code
 * wroteAnything()} makes case 1 fail on both of its assertions — the loop grinds on the
 * never-advancing pair for the full budget instead of handing control back after one batch.
 */
@DisplayName("BackfillScheduler tight-loop containment (tempdoc 798)")
@ExtendWith(MockitoExtension.class)
class BackfillSchedulerTightLoopTest {

  /** The two documents that can never advance — the shape of the field incident. */
  private static final List<String> STUCK_DOC_IDS = List.of("stuck-doc-1", "stuck-doc-2");

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock IndexCountOps indexCountOps;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock JobQueue jobQueue;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock SpladeEncoder spladeEncoder;
  @Mock NerService nerService;

  /** Counts combined batches so "at most one batch" is assertable. */
  private final AtomicInteger batchWrites = new AtomicInteger();
  private final AtomicLong monotonicNanos = new AtomicLong();

  @BeforeEach
  void wirePathologicalState() {
    io.justsearch.indexerworker.loop.ops.BackfillSnapshotTestSupport.install(documentFieldOps,indexingCoordinator);
    lenient().when(signalBus.isMainGpuActive()).thenReturn(false);
    lenient().when(signalBus.isEnergyReduced()).thenReturn(false);
    lenient().when(signalBus.shouldYieldGpuBackfill()).thenReturn(false);
    lenient().when(signalBus.hasPendingIngest()).thenReturn(false);
    lenient().when(indexCountOps.countByField(anyString(), anyString())).thenReturn(0);

    // The tight loop runs inside withNrtSuspended — a bare void mock would no-op the whole body.
    lenient()
        .doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(0)).run();
              return null;
            })
        .when(commitOps)
        .withNrtSuspended(any());

    // Embedding unavailable; SPLADE + NER available => availCount == 2 => combined mode.
    lenient().when(embeddingProvider.isAvailable()).thenReturn(false);
    lenient().when(embeddingProvider.isUsingGpu()).thenReturn(false);
    lenient().when(nerService.isAvailable()).thenReturn(true);

    // (1) The doc-id query NEVER drains: the same two ids come back on every call, however many
    // times the batch has already "succeeded" on them.
    lenient()
        .when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenReturn(STUCK_DOC_IDS);

    // (2) Blank content: neither the SPLADE nor the NER phase can advance these docs, so every
    // per-stage processed/failed count stays 0.
    lenient().when(documentFieldOps.getDocumentContentBatch(anyList())).thenReturn(Map.of());
    lenient()
        .when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              Map<String, Map<String, String>> result = new HashMap<>();
              for (String id : ids) {
                result.put(
                    id,
                    Map.of(
                        SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
                        SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
              }
              return result;
            });

    // (3) The write always claims both docs were updated — `written > 0` forever.
    lenient()
        .when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv -> {
              batchWrites.incrementAndGet();
              monotonicNanos.addAndGet(100_000_000L); // completed write consumes 100 ms of work
              List<?> batch = inv.getArgument(0);
              return new LuceneRuntimeTypes.BatchUpdateResult(batch.size(), 0);
            });
  }

  private BackfillScheduler scheduler() {
    EmbeddingProviderLifecycle embeddingLifecycle =
        new EmbeddingProviderLifecycle(signalBus, jobQueue, indexCountOps, commitOps);
    embeddingLifecycle.setEmbeddingProvider(embeddingProvider);
    return new BackfillScheduler(
        documentFieldOps,
        indexingCoordinator,
        indexCountOps,
        commitOps,
        signalBus,
        io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(),
        embeddingLifecycle,
        new AtomicBoolean(true),
        this::resolvedConfig,
        () -> spladeEncoder,
        () -> null,
        () -> nerService,
        () -> null,
        monotonicNanos::get);
  }

  private ResolvedConfig resolvedConfig() {
    ResolvedConfig config = mock(ResolvedConfig.class);
    ResolvedConfig.Rag rag = mock(ResolvedConfig.Rag.class);
    lenient().when(rag.chunkVectorsEnabled()).thenReturn(false);
    lenient().when(rag.chunkSpladeEnabled()).thenReturn(false);
    lenient().when(config.rag()).thenReturn(rag);
    ResolvedConfig.Ai ai = mock(ResolvedConfig.Ai.class);
    ResolvedConfig.Ai.Embedding embedding = mock(ResolvedConfig.Ai.Embedding.class);
    lenient().when(embedding.lateChunkingEnabled()).thenReturn(false);
    lenient().when(ai.embedding()).thenReturn(embedding);
    lenient().when(ai.backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
    lenient().when(config.ai()).thenReturn(ai);
    return config;
  }

  /**
   * PINS: the progress drive, and nothing else.
   *
   * <p>The two documents write every batch (a blank-content retry-count escalation) but never
   * advance a stage: they stay PENDING, stay selected, and the next batch is identical. The loop
   * must hand control back after the FIRST such batch.
   *
   * <p>Both bounds are chosen so the {@code CYCLE_BUDGET_MS} backstop cannot satisfy them: one
   * tight-loop batch (the budget would allow thousands) and a sub-second ceiling (the budget is
   * 5s). Revert {@code useCombinedRef[0] = tightLoopOutcome.progressed()} to {@code
   * wroteAnything()} and both fail.
   */
  @Test
  @DisplayName(
      "a batch that writes but advances nothing stops the tight loop AT ONCE — the budget is not"
          + " what saves it")
  void nonProgressingBatch_tightLoopStopsImmediately() {
    BackfillScheduler scheduler = scheduler();

    assertTimeoutPreemptively(
        Duration.ofMillis(900),
        scheduler::runIdleCycle,
        "runIdleCycle() must return as soon as a batch reports no progress. 900ms is far below"
            + " CYCLE_BUDGET_MS (5s), so a pass here cannot come from the budget backstop — only"
            + " from the loop terminating on `progressed` (tempdoc 798).");

    verify(indexingCoordinator, atLeastOnce()).updateDocumentsBatch(anyList());
    // One mode-selection probe batch + exactly one tight-loop batch that reports no progress.
    assertTrue(
        batchWrites.get() <= 2,
        "the tight loop must stop after the first non-progressing batch; observed "
            + batchWrites.get()
            + " combined batches, which means it was still driving on ACTIVITY (`written > 0`)"
            + " and only the cycle budget ended it");
  }

  /**
   * PINS: the monotonic budget, and nothing else.
   *
   * <p>Here every batch DOES advance documents — SPLADE encodes successfully for both docs, so
   * {@code progressed()} is true forever — while the pending-id query never drains (the exact
   * shape of an enrichment population that a competing writer keeps re-opening). Progress-based
   * termination is therefore structurally unable to end this loop; only {@link
   * BackfillScheduler} 's cycle budget can.
   */
  @Test
  @DisplayName(
      "a population that advances every batch but never drains: the cycle budget returns control")
  void advancingButNeverDrainingPopulation_budgetReturnsControl() throws Exception {
    wireAdvancingButNeverDraining();
    BackfillScheduler scheduler = scheduler();

    long startNanos = monotonicNanos.get();
    // Wall-clock watchdog only; completed writes, never clock reads, spend the modeled budget.
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        scheduler::runIdleCycle,
        "runIdleCycle() must return even when every batch reports progress — the cycle budget is"
            + " the containment of last resort (tempdoc 798)");
    long elapsedMs = (monotonicNanos.get() - startNanos) / 1_000_000;

    assertTrue(
        elapsedMs >= 4_000,
        "the loop should have run until the 5s budget expired (progress never stops it here);"
            + " returning after "
            + elapsedMs
            + "ms means something other than the budget ended it, so this test would no longer"
            + " pin the budget");
    assertTrue(
        batchWrites.get() > 10,
        "many batches should have run before the budget tripped; observed " + batchWrites.get());
  }

  /**
   * Re-stubs the fixture so every batch genuinely advances both documents: non-blank content plus a
   * SPLADE encode that returns a materializing weight map, with NER already terminal so only the
   * SPLADE stage participates. The doc-id query still never drains.
   */
  private void wireAdvancingButNeverDraining() throws Exception {
    lenient()
        .when(documentFieldOps.getDocumentContentBatch(anyList()))
        .thenAnswer(
            inv -> {
              Map<String, String> content = new HashMap<>();
              for (String id : STUCK_DOC_IDS) content.put(id, "enrichable content for " + id);
              return content;
            });
    lenient()
        .when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              Map<String, Map<String, String>> result = new HashMap<>();
              for (String id : ids) {
                result.put(
                    id,
                    Map.of(
                        SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
                        SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED));
              }
              return result;
            });
    lenient()
        .when(spladeEncoder.encodeBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> contents = inv.getArgument(0);
              List<Map<String, Float>> encoded = new java.util.ArrayList<>(contents.size());
              for (int i = 0; i < contents.size(); i++) encoded.add(Map.of("term", 1.5f));
              return encoded;
            });
  }

  /** PINS: the pending-ingest yield. The docs here advance every batch, so only the signal stops it. */
  @Test
  @DisplayName("pending ingest work breaks the tight loop after at most one combined batch")
  void pendingIngest_yieldsAfterOneBatch() throws Exception {
    wireAdvancingButNeverDraining();
    when(signalBus.hasPendingIngest()).thenReturn(true);
    BackfillScheduler scheduler = scheduler();

    // Sub-second, so neither the 5s budget nor progress-termination can be what returns control.
    assertTimeoutPreemptively(
        Duration.ofMillis(900),
        scheduler::runIdleCycle,
        "runIdleCycle() must return promptly when ingest jobs are waiting");

    // One batch is the mode-selection probe outside the tight loop; the guard fires before the
    // loop's first batch, so a second write would mean the signal is not consulted.
    assertTrue(
        batchWrites.get() <= 1,
        "background enrichment must yield to pending ingest after at most one combined batch,"
            + " observed "
            + batchWrites.get()
            + " batches");
  }
}
