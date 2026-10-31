/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.justsearch.indexing.SchemaFields;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 809 finding 3 — SLOW-batch containment for the combined enrichment backfill.
 *
 * <p>{@link BackfillSchedulerTightLoopTest} (tempdoc 798) proved the tight loop terminates. It
 * could not prove anything about LATENCY, because every stub in it returns instantly: with an
 * instant batch, a check between batches looks exactly as responsive as a check inside one. The
 * field says otherwise — a batch is ~150 documents and was measured at ~63 s of GPU (43 s embed,
 * 15 s NER), so 798's between-batches guards overshot the 5 s cycle budget they enforce by ~12x,
 * and removing a watched root left a full minute of enrichment running on documents that had
 * already been deleted (writing zero of them).
 *
 * <p>The budget and no-progress cases model real stage duration. The synchronous event cases
 * instead record the event inside the first encode call, before it returns, and pin the exact
 * documents reached before the next unit. Together they check duration and interruption position.
 * The pipeline is embed-dominated: parents pending embedding, NER already terminal.
 *
 * <p>Each test pins one property, and each fails against the pre-fix code for a stated reason:
 *
 * <ol>
 *   <li>{@link #slowBatchOverrunningTheBudget_yieldsWithinBudgetPlusOneEncodeUnit()} — pre-fix the
 *       single {@code embedDocumentBatch} call over the whole batch runs to completion (~12.8 s
 *       here), so control returns only after it; the bound is budget + one encode slice.
 *   <li>{@link #pendingIngestArrivingMidBatch_preemptsWithoutFinishingTheBatch()} — pre-fix the
 *       signal is read only between batches, so a mid-batch arrival waits out the whole batch.
 *   <li>{@link #rootRemovedMidBatch_remainingDocumentsAreNeitherEncodedNorWritten()} — pre-fix
 *       every document is encoded and written regardless of the deletion.
 *   <li>{@link #budgetTripWithNoProgress_stillLogsTheWarn()} — a preservation test: 798's
 *       non-convergence WARN must survive the new early-exit path, including when it is the
 *       mode-selection probe (not a tight-loop batch) that spends the budget.
 * </ol>
 */
@DisplayName("BackfillScheduler slow-batch interruption (tempdoc 809 finding 3)")
@ExtendWith(MockitoExtension.class)
class BackfillSchedulerSlowBatchTest {

  /**
   * Simulated per-document embed cost. The live batch was ~150 docs in ~63 s (~420 ms/doc); 200 ms
   * keeps the same "one batch dwarfs the 5 s budget" relationship at a test-affordable wall clock.
   */
  private static final long EMBED_MS_PER_DOC = 200L;

  /** Documents per combined batch. 64 × 200 ms = 12.8 s, ~2.5x the 5 s cycle budget. */
  private static final int BATCH_DOCS = 64;

  /** Must mirror {@code CombinedEnrichmentBackfillOps.EMBED_ENCODE_SLICE}. */
  private static final int EMBED_SLICE = 8;

  private static final long SLICE_MS = EMBED_SLICE * EMBED_MS_PER_DOC;

  /** Must mirror {@code BackfillScheduler.CYCLE_BUDGET_MS}. */
  private static final long CYCLE_BUDGET_MS = 5_000L;

  /**
   * Scheduling slack. Generous on purpose: the property under test is "bounded by budget + ONE
   * atomic unit", not a precise timing, and a bound this loose still separates the fix from the
   * pre-fix behaviour by seconds.
   */
  private static final long SLACK_MS = 2_000L;

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock IndexCountOps indexCountOps;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock JobQueue jobQueue;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock NerService nerService;

  private final List<String> docIds = new ArrayList<>();
  /** Every doc id handed to the encoder, in order — the "what did we actually spend GPU on" log. */
  private final List<String> encodedDocIds = new ArrayList<>();

  private final AtomicInteger embedCalls = new AtomicInteger();
  private final AtomicLong monotonicNanos = new AtomicLong();
  private long modeledNanosPerEncode;
  private int combinedBatchDocs = BATCH_DOCS;
  private boolean nerPending;
  private io.justsearch.indexerworker.splade.SpladeEncoder sparseEncoder;
  /** Only synchronous event scenarios remove the artificial stage delay. */
  private volatile long embedMsPerDoc = EMBED_MS_PER_DOC;
  private final List<Integer> eventSlices = new ArrayList<>();
  private final List<List<String>> encodedAtEvents = new ArrayList<>();
  private final List<Long> eventElapsedNanos = new ArrayList<>();
  private long cycleStartedNanos;
  /** Doc ids that reached a Lucene write. */
  private final Set<String> writtenDocIds = new LinkedHashSet<>();
  /** Stands in for the worker's bulk-deletion epoch; a root removal bumps it. */
  private final AtomicLong bulkDeleteEpoch = new AtomicLong();

  private final AtomicBoolean pendingIngest = new AtomicBoolean(false);
  /** Number of completed embed slices after which the scenario's event fires. */
  private volatile int fireEventAfterSlice = Integer.MAX_VALUE;
  /** Which mid-batch event the embed stub fires when {@link #fireEventAfterSlice} is reached. */
  private volatile boolean pendingIngestScenario = false;

  private volatile boolean rootRemovalScenario = false;
  /** When true the embed stub returns null vectors — no updates, and NOT progress. */
  private volatile boolean embedReturnsNull = false;
  /**
   * When true the fake index behaves like the real one: a document written COMPLETED leaves the
   * pending population. Off by default so the other tests keep their "never drains" property, which
   * removes "the work simply finished" as an explanation for a cycle returning.
   */
  private volatile boolean drainingPopulation = false;

  private final Set<String> stillPending = new LinkedHashSet<>();
  /** Docs with blank content: they write a retry escalation but never advance a stage. */
  private volatile int blankContentDocs = 0;

  private Logger schedulerLogger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void wireSlowEmbedBatch() {
    io.justsearch.indexerworker.loop.ops.BackfillSnapshotTestSupport.install(documentFieldOps,indexingCoordinator);
    for (int i = 0; i < BATCH_DOCS; i++) {
      docIds.add(String.format("doc-%02d", i));
    }

    lenient().when(signalBus.isMainGpuActive()).thenReturn(false);
    lenient().when(signalBus.isEnergyReduced()).thenReturn(false);
    lenient().when(signalBus.shouldYieldGpuBackfill()).thenReturn(false);
    lenient().when(signalBus.hasPendingIngest()).thenAnswer(inv -> pendingIngest.get());
    lenient().when(indexCountOps.countByField(anyString(), anyString())).thenReturn(0);
    lenient().when(indexingCoordinator.bulkDeleteEpoch()).thenAnswer(inv -> bulkDeleteEpoch.get());

    lenient()
        .doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(0)).run();
              return null;
            })
        .when(commitOps)
        .withNrtSuspended(any());

    // Embedding + NER available => availCount == 2 => combined mode. SPLADE absent, so the wall
    // clock is the embed stage alone.
    lenient().when(embeddingProvider.isAvailable()).thenReturn(true);
    lenient().when(embeddingProvider.isUsingGpu()).thenReturn(true);
    lenient().when(nerService.isAvailable()).thenReturn(true);

    // The pending population never drains: the same ids come back every batch. That is the shape
    // of the field incident, and it removes "the work simply finished" as an explanation for a
    // test passing.
    stillPending.addAll(docIds);
    lenient()
        .when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(inv -> drainingPopulation ? List.copyOf(stillPending) : docIds);

    lenient()
        .when(documentFieldOps.getDocumentContentBatch(anyList()))
        .thenAnswer(
            inv -> {
              Map<String, String> content = new HashMap<>();
              List<String> ids = inv.getArgument(0);
              for (int i = 0; i < ids.size(); i++) {
                if (i < blankContentDocs) continue; // blank => escalation, never an encode
                content.put(ids.get(i), "enrichable content for " + ids.get(i));
              }
              return content;
            });

    // EMBEDDING pending, NER already terminal: only the embed stage participates.
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
                        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
                        SchemaFields.NER_STATUS, nerPending
                            ? SchemaFields.NER_STATUS_PENDING : SchemaFields.NER_STATUS_COMPLETED));
              }
              return result;
            });

    // Fire inside the encode call, before returning its vectors or starting another unit.
    // The default-clock anchor retains real delay; controlled cases charge completed encodes.
    lenient()
        .when(embeddingProvider.embedDocumentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              int call = embedCalls.incrementAndGet();
              synchronized (encodedDocIds) {
                for (String text : texts) {
                  encodedDocIds.add(text.substring(text.lastIndexOf(' ') + 1));
                }
              }
              if (embedMsPerDoc > 0) Thread.sleep(embedMsPerDoc * texts.size());
              monotonicNanos.addAndGet(modeledNanosPerEncode);
              if (call == fireEventAfterSlice) {
                pendingIngest.set(pendingIngestScenario);
                if (rootRemovalScenario) bulkDeleteEpoch.incrementAndGet();
                eventSlices.add(call);
                encodedAtEvents.add(List.copyOf(encodedDocIds));
                eventElapsedNanos.add(System.nanoTime() - cycleStartedNanos);
              }
              if (embedReturnsNull) return null;
              List<float[]> vectors = new ArrayList<>(texts.size());
              for (int i = 0; i < texts.size(); i++) vectors.add(new float[] {0.1f, 0.2f});
              return vectors;
            });

    lenient()
        .when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<Map.Entry<String, Map<String, Object>>> batch = inv.getArgument(0);
              synchronized (writtenDocIds) {
                for (Map.Entry<String, Map<String, Object>> e : batch) {
                  writtenDocIds.add(e.getKey());
                  // A document written COMPLETED leaves the pending population, exactly as the
                  // status query would stop returning it.
                  if (SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(
                      e.getValue().get(SchemaFields.EMBEDDING_STATUS))) {
                    stillPending.remove(e.getKey());
                  }
                }
              }
              return new LuceneRuntimeTypes.BatchUpdateResult(batch.size(), 0);
            });

    schedulerLogger = (Logger) LoggerFactory.getLogger(BackfillScheduler.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    schedulerLogger.addAppender(logAppender);
  }

  @AfterEach
  void detachAppender() {
    schedulerLogger.detachAppender(logAppender);
    logAppender.stop();
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
        () -> sparseEncoder,
        () -> null,
        () -> nerService,
        () -> null);
  }

  private BackfillScheduler scheduler(LongSupplier clock) {
    EmbeddingProviderLifecycle embeddingLifecycle =
        new EmbeddingProviderLifecycle(signalBus, jobQueue, indexCountOps, commitOps);
    embeddingLifecycle.setEmbeddingProvider(embeddingProvider);
    return new BackfillScheduler(
        documentFieldOps, indexingCoordinator, indexCountOps, commitOps, signalBus,
        io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(),
        embeddingLifecycle, new AtomicBoolean(true), this::resolvedConfig,
        () -> sparseEncoder, () -> null, () -> nerService, () -> null, clock);
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
    ResolvedConfig.Ai.BackfillPacing defaults = ResolvedConfig.Ai.BackfillPacing.DEFAULTS;
    lenient()
        .when(ai.backfillPacing())
        .thenReturn(
            new ResolvedConfig.Ai.BackfillPacing(
                defaults.pollBatchSize(),
                combinedBatchDocs,
                defaults.nerBackfillBatchSize(),
                defaults.disambiguationBackfillBatchSize(),
                defaults.spladeBackfillBatchSize(),
                defaults.spladeInterleaveBatchSize(),
                defaults.spladeInterleaveIntervalMs(),
                defaults.commitIntervalMs(),
                defaults.maxDocsBeforeCommit(),
                0,
                defaults.bgeM3BackfillBatchSize(),
                defaults.bgeM3InterleaveBatchSize(),
                defaults.foregroundDutyPct(),
                defaults.foregroundCooldownMs()));
    lenient().when(config.ai()).thenReturn(ai);
    return config;
  }

  /** Runs one idle cycle and returns its wall-clock duration in ms. */
  private long timeIdleCycle(BackfillScheduler scheduler) {
    long start = System.nanoTime();
    cycleStartedNanos = start;
    // A ceiling only, so a regression that never returns fails as a timeout instead of hanging the
    // suite. The real assertions are on the measured elapsed time below.
    assertTimeoutPreemptively(Duration.ofSeconds(60), scheduler::runIdleCycle);
    return (System.nanoTime() - start) / 1_000_000;
  }

  private void assertEventAtSlice(int slice) {
    assertEquals(slice, eventSlices.size(), "every cycle positively witnessed its event");
    assertEquals(slice, eventSlices.get(slice - 1).intValue(), "event fired in its encode call");
    assertEquals(docIds.subList(0, slice * EMBED_SLICE), encodedAtEvents.get(slice - 1),
        "event fired after exactly the intended slice's IDs and before any later encode unit");
    assertTrue(eventElapsedNanos.get(slice - 1) < CYCLE_BUDGET_MS * 1_000_000L,
        "the event fired before the cycle budget was spent");
  }

  private boolean loggedAtLevel(ch.qos.logback.classic.Level level, String needle) {
    return logAppender.list.stream()
        .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(needle));
  }

  /**
   * PINS: the cycle budget is enforceable at sub-batch granularity.
   *
   * <p>One batch costs {@code BATCH_DOCS × EMBED_MS_PER_DOC} = 12.8 s against a 5 s budget. Pre-fix
   * that batch was a single {@code embedDocumentBatch} call, so nothing could observe the deadline
   * until it finished: {@code runIdleCycle()} returned at ~12.8 s. The bound asserted here is the
   * honest one — the budget plus the one atomic unit that may already be in flight when it expires
   * (one 8-document encode slice, {@code OnnxEmbeddingEncoder.MAX_ORT_BATCH_SIZE}).
   */
  @Test
  @DisplayName("a single batch that outlives the cycle budget still yields within budget + one"
      + " encode slice")
  void slowBatchOverrunningTheBudget_yieldsWithinBudgetPlusOneEncodeUnit() {
    long elapsedMs = timeIdleCycle(scheduler());

    long bound = CYCLE_BUDGET_MS + SLICE_MS + SLACK_MS;
    assertTrue(
        elapsedMs <= bound,
        "runIdleCycle() must hand control back within the cycle budget plus at most one"
            + " in-flight encode unit ("
            + bound
            + "ms). Observed "
            + elapsedMs
            + "ms. A whole uninterruptible batch is "
            + (BATCH_DOCS * EMBED_MS_PER_DOC)
            + "ms, so a value near that means the budget is still only checked BETWEEN batches"
            + " (tempdoc 809 finding 3).");
    assertTrue(
        elapsedMs >= CYCLE_BUDGET_MS - SLICE_MS,
        "the cycle should have used most of its budget before yielding; returning after "
            + elapsedMs
            + "ms means something other than the budget ended it and this test no longer pins"
            + " the budget");
    assertTrue(
        embedCalls.get() > 1,
        "the embed stage must be issued as several interruptible units, not one call for the whole"
            + " batch; observed "
            + embedCalls.get()
            + " call(s)");
  }

  /**
   * PINS: ingest preemption reaches inside a running batch.
   *
   * <p>The synchronous signal comes up inside the first encode call, before it returns.
   * The separate slow-budget case retains real duration evidence. Primary indexing outranks background
   * enrichment, so the cycle must come back after the slice it was already committed to, not after
   * the remaining 56 documents.
   */
  @Test
  @DisplayName("pending ingest arriving MID-batch preempts without finishing the batch")
  void pendingIngestArrivingMidBatch_preemptsWithoutFinishingTheBatch() {
    embedMsPerDoc = 0;
    pendingIngestScenario = true;
    fireEventAfterSlice = 1;

    long elapsedMs = timeIdleCycle(scheduler());
    assertEventAtSlice(1);
    assertTrue(pendingIngest.get(), "the ingest event actually fired inside the encode call");
    assertEquals(docIds.subList(0, EMBED_SLICE), List.copyOf(encodedDocIds));

    assertEquals(
        1,
        embedCalls.get(),
        "backfill must stop starting encode units as soon as ingest work is waiting; a second"
            + " unit means the signal is only consulted between batches");
    assertEquals(
        EMBED_SLICE,
        encodedDocIds.size(),
        "only the in-flight slice may be spent on background enrichment once ingest is waiting;"
            + " encoding all "
            + BATCH_DOCS
            + " documents is the pre-fix behaviour");
    long bound = 2 * SLICE_MS + SLACK_MS;
    assertTrue(
        elapsedMs <= bound,
        "ingest must not wait out a whole enrichment batch ("
            + (BATCH_DOCS * EMBED_MS_PER_DOC)
            + "ms). Expected <= "
            + bound
            + "ms, observed "
            + elapsedMs
            + "ms");
  }

  /**
   * PINS: removing a watched root cancels the enrichment already in flight for its documents.
   *
   * <p>The removal lands on the worker as {@code IndexingCoordinator.deleteByPathPrefix}, which
   * bumps the bulk-deletion epoch; the batch captured that epoch when it selected its documents.
   * Here the removal happens while the first slice is encoding — exactly the reported incident,
   * where a batch went on to spend ~63 s of GPU and then wrote zero documents.
   *
   * <p>The two assertions are the two halves of the finding: the remaining documents are never
   * encoded (no wasted GPU) and never written (no work discarded at the write step).
   */
  @Test
  @DisplayName("a root removed MID-batch: its remaining documents are neither encoded nor written")
  void rootRemovedMidBatch_remainingDocumentsAreNeitherEncodedNorWritten() {
    embedMsPerDoc = 0;
    rootRemovalScenario = true;
    fireEventAfterSlice = 1;

    timeIdleCycle(scheduler());
    assertEventAtSlice(1);
    assertEquals(1L, bulkDeleteEpoch.get(), "the root-epoch event fired inside the encode call");
    assertEquals(docIds.subList(0, EMBED_SLICE), List.copyOf(encodedDocIds));

    List<String> survivors = docIds.subList(0, EMBED_SLICE);
    List<String> removed = docIds.subList(EMBED_SLICE, BATCH_DOCS);

    for (String id : removed) {
      assertTrue(
          !encodedDocIds.contains(id),
          "document "
              + id
              + " was still encoded after the root removal deleted it — the batch spent GPU on"
              + " documents that no longer exist (tempdoc 809 finding 3)");
      assertTrue(
          !writtenDocIds.contains(id),
          "document " + id + " reached a Lucene write after its root was removed");
    }
    assertEquals(
        EMBED_SLICE,
        encodedDocIds.size(),
        "only the encode unit already in flight when the removal landed may complete");
    assertTrue(
        writtenDocIds.containsAll(survivors),
        "work completed BEFORE the removal must still be written — cancelling is not the same as"
            + " discarding what was already earned");
  }

  /**
   * PINS: an interrupted batch leaves the work it skipped in a state the next cycle resumes.
   *
   * <p>The asymmetric-lifecycle question for this change: yielding mid-batch must not strand
   * documents. It cannot here by construction — the combined pass's population is derived from
   * {@code *_status} fields in the index, not from job-queue rows (so tempdoc 798's related-defect
   * #1, {@code enqueue} resetting job state, is not on this path), and a document the batch never
   * reached simply receives no update and stays PENDING. This test asserts that property instead of
   * asserting the argument: the fake index drains COMPLETED documents out of the pending population
   * the way the real status query does, so re-encoding an already-finished document and skipping a
   * never-started one would both show up.
   */
  @Test
  @DisplayName("documents an interrupted batch never reached are picked up by the next cycle")
  void documentsSkippedByAnAbortedBatchResumeOnTheNextCycle() {
    embedMsPerDoc = 0;
    drainingPopulation = true;
    pendingIngestScenario = true;
    fireEventAfterSlice = 1;

    BackfillScheduler scheduler = scheduler();
    timeIdleCycle(scheduler);
    assertEventAtSlice(1);
    assertTrue(pendingIngest.get(), "the first cycle was preempted by its ingest event");
    assertEquals(docIds.subList(EMBED_SLICE, BATCH_DOCS), List.copyOf(stillPending));
    assertEquals(docIds.subList(0, EMBED_SLICE), List.copyOf(writtenDocIds));

    assertEquals(
        docIds.subList(0, EMBED_SLICE),
        List.copyOf(encodedDocIds),
        "the first cycle should have embedded exactly its first slice before yielding to ingest");

    // Ingest drained; the next idle cycle resumes enrichment, and is preempted again after its
    // own first slice so the assertion stays about WHICH documents it picked.
    pendingIngest.set(false);
    fireEventAfterSlice = 2;
    timeIdleCycle(scheduler);
    assertEventAtSlice(2);
    assertTrue(pendingIngest.get(), "the resumed cycle fired a fresh ingest event");
    assertEquals(docIds.subList(2 * EMBED_SLICE, BATCH_DOCS), List.copyOf(stillPending));
    assertEquals(docIds.subList(0, 2 * EMBED_SLICE), List.copyOf(writtenDocIds));

    assertEquals(
        docIds.subList(0, 2 * EMBED_SLICE),
        List.copyOf(encodedDocIds),
        "the second cycle must continue with the documents the first one skipped — re-encoding the"
            + " already-COMPLETED first slice would mean the interrupted batch lost its work, and"
            + " skipping past doc-"
            + EMBED_SLICE
            + " would mean it stranded documents");
    assertEquals(
        BATCH_DOCS - 2 * EMBED_SLICE,
        stillPending.size(),
        "every document not yet embedded must still be PENDING and therefore re-selectable");
  }

  /**
   * PINS: 798's non-convergence WARN survives the new early exit.
   *
   * <p>Shape: a few blank-content documents that write a retry escalation every batch (activity,
   * never progress) plus a slow embed stage whose results are unusable, so the whole cycle
   * advances nothing while consuming the budget. This also covers the case 809 introduced — it is
   * the mode-selection PROBE batch, not a tight-loop batch, that spends the budget, and the
   * diagnostic must still be emitted rather than swallowed by the batch's own early return.
   */
  @Test
  @DisplayName("the budget-trip WARN still fires when a slow cycle advances nothing")
  void budgetTripWithNoProgress_stillLogsTheWarn() {
    embedMsPerDoc = 0;
    modeledNanosPerEncode = SLICE_MS * 1_000_000L;
    blankContentDocs = EMBED_SLICE; // write-but-never-advance documents
    embedReturnsNull = true; // the encoder runs but serves nothing usable

    lenient().when(indexingCoordinator.updateDocumentsBatch(anyList())).thenAnswer(call -> {
      List<?> updates = call.getArgument(0);
      return new LuceneRuntimeTypes.BatchUpdateResult(1, updates.size() - 1);
    });
    timeIdleCycle(scheduler(monotonicNanos::get));
    assertTrue(monotonicNanos.get() >= CYCLE_BUDGET_MS * 1_000_000L);

    assertTrue(
        loggedAtLevel(ch.qos.logback.classic.Level.WARN, "ZERO stage advancement"),
        "a cycle that spends its whole budget without advancing any document must still self-report"
            + " at WARN (tempdoc 798 D2c); observed log lines: "
            + logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
  }

  @ParameterizedTest
  @ValueSource(longs = {4_999_999_999L, 5_000_000_000L, 5_000_000_001L})
  void cycleDeadlineBeforeAtAndAfter_completedEncodeSpendsProbeBudget(long encodeNanos) {
    embedMsPerDoc = 0;
    modeledNanosPerEncode = encodeNanos;
    // Deliberately unrelated to System.nanoTime, and retained across both cycles.
    monotonicNanos.set(-100_000_000_000L);
    BackfillScheduler scheduler = scheduler(monotonicNanos::get);
    int unitsPerCycle = encodeNanos < 5_000_000_000L ? 2 : 1;
    for (int cycle = 1; cycle <= 2; cycle++) {
      // Each cycle must publish and log on its own, so drop the previous cycle's observations.
      writtenDocIds.clear();
      logAppender.list.clear();
      timeIdleCycle(scheduler);
      assertEquals(cycle * unitsPerCycle, embedCalls.get(),
          "each cycle samples a fresh deadline; the spent probe cannot start a tight-loop batch");
      assertEquals(cycle * unitsPerCycle * EMBED_SLICE, encodedDocIds.size());
      assertEquals(unitsPerCycle * EMBED_SLICE, writtenDocIds.size(),
          "the probe publishes its completed units before returning");
      assertTrue(loggedAtLevel(ch.qos.logback.classic.Level.INFO, "still advancing documents"));
      assertFalse(loggedAtLevel(ch.qos.logback.classic.Level.WARN, "ZERO stage advancement"));
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {2_999_999_999L, 3_000_000_000L, 3_000_000_001L})
  void embedShareBeforeAtAndAfter_reservesTimeForPendingNer(long encodeNanos) {
    embedMsPerDoc = 0;
    modeledNanosPerEncode = encodeNanos;
    nerPending = true;
    AtomicInteger nerCalls = new AtomicInteger();
    lenient().when(nerService.extractEntitiesBatch(anyList())).thenAnswer(inv -> {
      nerCalls.incrementAndGet();
      pendingIngest.set(true); // positively observed NER work stops the subsequent tight loop
      List<String> texts = inv.getArgument(0);
      return java.util.Collections.nCopies(texts.size(), io.justsearch.indexerworker.ner.NerResult.EMPTY);
    });
    // The before case spends just one nanosecond in its second encode, leaving NER runnable.
    AtomicInteger completedEncodes = new AtomicInteger();
    lenient().doAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      embedCalls.incrementAndGet();
      monotonicNanos.addAndGet(completedEncodes.getAndIncrement() == 0 ? encodeNanos : 1L);
      return java.util.Collections.nCopies(texts.size(), new float[] {0.1f, 0.2f});
    }).when(embeddingProvider).embedDocumentBatch(anyList());
    timeIdleCycle(scheduler(monotonicNanos::get));
    assertEquals(encodeNanos < 3_000_000_000L ? 2 : 1, embedCalls.get());
    assertEquals(1, nerCalls.get(), "the soft reservation must leave the later stage runnable");
    assertTrue(monotonicNanos.get() < 5_000_000_000L,
        "pending ingest, rather than the cycle budget, ends the tight loop");
  }

  private void wirePopulation(Map<String, Map<String, Object>> state) {
    embedMsPerDoc = 0;
    lenient().when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(call -> state.entrySet().stream()
            .filter(entry -> call.getArgument(1).equals(entry.getValue().get(call.getArgument(0))))
            .map(Map.Entry::getKey).toList());
    lenient().when(documentFieldOps.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(call -> state.entrySet().stream()
            .filter(entry -> call.getArgument(1).equals(entry.getValue().get(call.getArgument(0))))
            .map(Map.Entry::getKey).toList());
    lenient().when(documentFieldOps.getDocumentContentBatch(anyList())).thenAnswer(call -> {
      Map<String, String> texts = new HashMap<>();
      for (String id : (List<String>) call.getArgument(0)) texts.put(id, "long content for " + id);
      return texts;
    });
    lenient().when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet())).thenAnswer(call -> {
      Map<String, Map<String, String>> metadata = new HashMap<>();
      for (String id : (List<String>) call.getArgument(0)) {
        Map<String, String> values = new HashMap<>();
        for (String field : (Set<String>) call.getArgument(1)) {
          Object value = state.get(id).get(field);
          if (value != null) values.put(field, value.toString());
        }
        metadata.put(id, values);
      }
      return metadata;
    });
    lenient().when(indexingCoordinator.updateDocumentsBatch(anyList())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      updates.forEach(entry -> state.get(entry.getKey()).putAll(entry.getValue()));
      return new LuceneRuntimeTypes.BatchUpdateResult(updates.size(), 0);
    });
    lenient().when(nerService.extractEntitiesBatch(anyList())).thenReturn(
        List.of(io.justsearch.indexerworker.ner.NerResult.EMPTY));
  }

  @Test
  void persistentlyFailingPreservationDoesNotPoisonHealthyDocumentsAcrossCycles() throws Exception {
    Map<String, Map<String, Object>> state = new java.util.LinkedHashMap<>();
    Map<String, Object> failingState = new HashMap<>(Map.of(
        SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "COMPLETED",
        SchemaFields.NER_STATUS, "PENDING", SchemaFields.SPLADE, Map.of("original", 1f)));
    state.put("poison", failingState);
    wirePopulation(state);
    combinedBatchDocs = 128; // Two preservation slices, then another combined batch.
    modeledNanosPerEncode = 10_000_000L;
    List<List<String>> sparseAttempts = new ArrayList<>();
    sparseEncoder = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class);
    when(sparseEncoder.encodeBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      sparseAttempts.add(List.copyOf(texts));
      monotonicNanos.addAndGet(10_000_000L);
      if (texts.contains("long content for poison")) {
        throw new IllegalStateException("this document cannot regenerate its postings");
      }
      return java.util.Collections.nCopies(texts.size(), Map.of("preserved", 1f));
    });
    when(indexingCoordinator.updateDocumentsBatch(anyList())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      for (var entry : updates) {
        assertFalse("poison".equals(entry.getKey()), "only the failing update is withheld");
        assertEquals(Map.of("preserved", 1f), entry.getValue().get(SchemaFields.SPLADE),
            "every published outcome must carry regenerated postings");
        state.get(entry.getKey()).putAll(entry.getValue());
      }
      return new LuceneRuntimeTypes.BatchUpdateResult(updates.size(), 0);
    });
    BackfillScheduler scheduler = scheduler(monotonicNanos::get);
    for (int cycle = 1; cycle <= 3; cycle++) {
      sparseAttempts.clear();
      List<String> healthy = new ArrayList<>();
      for (int i = 0; i < 160; i++) {
        String id = "healthy-" + cycle + "-" + i;
        healthy.add(id);
        state.put(id, new HashMap<>(Map.of(
            SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "COMPLETED",
            SchemaFields.NER_STATUS, "PENDING")));
      }
      logAppender.list.clear();
      timeIdleCycle(scheduler);
      for (String id : healthy) {
        assertEquals("COMPLETED", state.get(id).get(SchemaFields.EMBEDDING_STATUS),
            id + " must advance even with a failing document at the head");
        assertEquals("COMPLETED_EMPTY", state.get(id).get(SchemaFields.NER_STATUS));
      }
      assertEquals(Map.of(
          SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "COMPLETED",
          SchemaFields.NER_STATUS, "PENDING", SchemaFields.SPLADE, Map.of("original", 1f)),
          failingState, "the withheld document keeps its postings, statuses and retry counts");
      assertTrue(sparseAttempts.stream()
          .anyMatch(texts -> texts.contains("long content for poison")),
          "the withheld parent must have a preservation attempt");
      assertFalse(loggedAtLevel(ch.qos.logback.classic.Level.WARN, "ZERO stage advancement"));
    }
    verify(indexingCoordinator, never()).updateDocumentIf(
        anyString(), any(DocumentFieldOps.EnrichmentInput.class), any());
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(ints = {0, BATCH_DOCS})
  void wholeBatchOfFailingPreservationDoesNotStarveFollowingHealthyParents(int leadingHealthy)
      throws Exception {
    Map<String, Map<String, Object>> state = new java.util.LinkedHashMap<>();
    List<String> healthy = new ArrayList<>();
    for (int i = 0; i < leadingHealthy + 2 * BATCH_DOCS; i++) {
      boolean failing = i >= leadingHealthy && i < leadingHealthy + BATCH_DOCS;
      String id = (failing ? "poison-" : "healthy-") + i;
      if (!failing) healthy.add(id);
      state.put(id, new HashMap<>(Map.of(
          SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "COMPLETED",
          SchemaFields.NER_STATUS, "PENDING", SchemaFields.SPLADE, Map.of("original", 1f))));
    }
    wirePopulation(state);
    modeledNanosPerEncode = 1_000_000L;
    List<List<String>> sparseAttempts = new ArrayList<>();
    sparseEncoder = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class);
    when(sparseEncoder.encodeBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      sparseAttempts.add(List.copyOf(texts));
      monotonicNanos.addAndGet(1_000_000L);
      if (texts.stream().anyMatch(text -> text.contains("poison-"))) {
        throw new IllegalStateException("this document cannot regenerate its postings");
      }
      return java.util.Collections.nCopies(texts.size(), Map.of("preserved", 1f));
    });
    when(indexingCoordinator.updateDocumentsBatch(anyList())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      for (var entry : updates) {
        assertTrue(healthy.contains(entry.getKey()), "failing parents must remain withheld");
        assertEquals(Map.of("preserved", 1f), entry.getValue().get(SchemaFields.SPLADE));
        state.get(entry.getKey()).putAll(entry.getValue());
      }
      return new LuceneRuntimeTypes.BatchUpdateResult(updates.size(), 0);
    });

    timeIdleCycle(scheduler(monotonicNanos::get));

    for (String id : healthy) {
      assertEquals("COMPLETED", state.get(id).get(SchemaFields.EMBEDDING_STATUS),
          id + " must advance in the same idle cycle despite a fully withheld batch");
      assertEquals("COMPLETED_EMPTY", state.get(id).get(SchemaFields.NER_STATUS));
    }
    for (var entry : state.entrySet()) {
      if (!entry.getKey().startsWith("poison-")) continue;
      assertEquals(Map.of(
          SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "COMPLETED",
          SchemaFields.NER_STATUS, "PENDING", SchemaFields.SPLADE, Map.of("original", 1f)),
          entry.getValue(), "withheld parents retain their fields without retry charges");
      assertTrue(sparseAttempts.stream()
          .anyMatch(texts -> texts.contains("long content for " + entry.getKey())),
          "each withheld parent must have a preservation attempt");
    }
    assertTrue(monotonicNanos.get() < CYCLE_BUDGET_MS * 1_000_000L);
    assertFalse(loggedAtLevel(ch.qos.logback.classic.Level.WARN, "ZERO stage advancement"));
    verify(indexingCoordinator, never()).updateDocumentIf(
        anyString(), any(DocumentFieldOps.EnrichmentInput.class), any());
  }

  @Test
  void costlyCompletedSpladePreservationCannotStarveNerOrDiscardEarnedVectors() throws Exception {
    Map<String, Map<String, Object>> state = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 100; i++) state.put("long-" + i, new HashMap<>(Map.of(
        SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "COMPLETED",
        SchemaFields.NER_STATUS, "PENDING")));
    wirePopulation(state);
    sparseEncoder = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class);
    AtomicInteger sparseEncodes = new AtomicInteger();
    lenient().when(sparseEncoder.encodeBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      sparseEncodes.addAndGet(texts.size());
      monotonicNanos.addAndGet(texts.size() * 3_000_000_000L);
      return java.util.Collections.nCopies(texts.size(), Map.of("preserved", 1f));
    });
    lenient().when(embeddingProvider.documentWindowCount(anyString())).thenReturn(64);
    lenient().when(embeddingProvider.embedDocumentWindows(anyString(), anyInt(), anyInt()))
        .thenAnswer(call -> {
          monotonicNanos.addAndGet(1_600_000_000L);
          return new EmbeddingProvider.WindowSlice(
              java.util.Collections.nCopies(32, new float[] {1, 0}), call.getArgument(1), 64);
        });
    lenient().when(nerService.extractEntitiesBatch(anyList())).thenAnswer(call -> {
      monotonicNanos.addAndGet(100_000_000L);
      return List.of(io.justsearch.indexerworker.ner.NerResult.EMPTY);
    });
    BackfillScheduler scheduler = scheduler(monotonicNanos::get);
    long previousNer = 0;
    long previousEmbed = 0;
    for (int cycle = 0; cycle < 4; cycle++) {
      logAppender.list.clear();
      timeIdleCycle(scheduler);
      long nerDone = state.values().stream().filter(doc -> "COMPLETED_EMPTY".equals(doc.get(SchemaFields.NER_STATUS))).count();
      long embedDone = state.values().stream().filter(doc -> "COMPLETED".equals(doc.get(SchemaFields.EMBEDDING_STATUS))).count();
      assertTrue(nerDone > previousNer, "every cycle must publish NER outcomes despite expensive preservation");
      assertTrue(embedDone > previousEmbed, "every cycle must publish its earned vector");
      assertFalse(loggedAtLevel(ch.qos.logback.classic.Level.WARN, "ZERO stage advancement"));
      previousNer = nerDone;
      previousEmbed = embedDone;
    }
    assertTrue(sparseEncodes.get() > 0);
  }

  @Test
  void stageOnlyDocumentsBeyondEmbeddingHeadAdvanceWithinTwoCycles() throws Exception {
    Map<String, Map<String, Object>> state = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 200; i++) state.put("head-" + i, new HashMap<>(Map.of(
        SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.NER_STATUS, "COMPLETED_EMPTY")));
    state.put("sparse-only", new HashMap<>(Map.of(SchemaFields.SPLADE_STATUS, "PENDING",
        SchemaFields.NER_STATUS, "COMPLETED_EMPTY")));
    state.put("ner-only", new HashMap<>(Map.of(SchemaFields.NER_STATUS, "PENDING")));
    wirePopulation(state);
    modeledNanosPerEncode = 3_000_000_000L;
    sparseEncoder = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class);
    lenient().when(sparseEncoder.encodeBatch(anyList())).thenAnswer(call ->
        java.util.Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("sparse", 1f)));
    BackfillScheduler scheduler = scheduler(monotonicNanos::get);
    for (int cycle = 0; cycle < 2; cycle++) timeIdleCycle(scheduler);
    assertEquals("COMPLETED", state.get("sparse-only").get(SchemaFields.SPLADE_STATUS));
    assertEquals("COMPLETED_EMPTY", state.get("ner-only").get(SchemaFields.NER_STATUS));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void deferredPreservationNeverFallsThroughToIndividualEmbeddingIncludingBackoff(boolean encoderRetires) throws Exception {
    Map<String, Map<String, Object>> state = new java.util.LinkedHashMap<>();
    state.put("doc", new HashMap<>(Map.of(
        SchemaFields.SPLADE_STATUS, "COMPLETED",
        SchemaFields.NER_STATUS, "COMPLETED_EMPTY",
        SchemaFields.EMBEDDING_STATUS, "PENDING")));
    wirePopulation(state);
    lenient().when(nerService.isAvailable()).thenReturn(false);
    lenient().when(documentFieldOps.getDocumentContent("doc")).thenReturn("long content for doc");
    sparseEncoder = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class);
    when(sparseEncoder.encodeBatch(anyList()))
        .thenThrow(new io.justsearch.ort.SessionTemporarilyUnavailableException("encoder unavailable"))
        .thenReturn(List.of(Map.of("preserved", 1f)));
    BackfillScheduler scheduler = scheduler(monotonicNanos::get);
    timeIdleCycle(scheduler);
    assertEquals("PENDING", state.get("doc").get(SchemaFields.EMBEDDING_STATUS));
    verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
    var recoveringEncoder = sparseEncoder;
    if (encoderRetires) sparseEncoder = null;
    timeIdleCycle(scheduler);
    sparseEncoder = recoveringEncoder;
    if (encoderRetires) {
      assertEquals("COMPLETED", state.get("doc").get(SchemaFields.EMBEDDING_STATUS),
          "an absent sparse encoder must not withhold another stage's outcome");
      verify(indexingCoordinator, atLeastOnce()).updateDocumentsBatch(anyList());
    } else {
      assertEquals("PENDING", state.get("doc").get(SchemaFields.EMBEDDING_STATUS));
      verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
    }
    verify(sparseEncoder).encodeBatch(anyList());
    var retryTime = BackfillScheduler.class.getDeclaredField("nextSpladeRetryTime");
    retryTime.setAccessible(true);
    retryTime.setLong(scheduler, 0);
    timeIdleCycle(scheduler);
    assertEquals("COMPLETED", state.get("doc").get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("COMPLETED", state.get("doc").get(SchemaFields.SPLADE_STATUS));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void systemicSpladeFailureBacksOffBothPathsWithoutDocumentRetries(boolean arenaOom) throws Exception {
    Map<String, Map<String, Object>> state = new java.util.LinkedHashMap<>();
    state.put("doc", new HashMap<>(Map.of(SchemaFields.SPLADE_STATUS, "PENDING",
        SchemaFields.NER_STATUS, "PENDING", SchemaFields.EMBEDDING_STATUS, "PENDING")));
    wirePopulation(state);
    sparseEncoder = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class);
    AtomicBoolean failing = new AtomicBoolean(true);
    AtomicInteger calls = new AtomicInteger();
    lenient().when(sparseEncoder.encodeBatch(anyList())).thenAnswer(call -> {
      calls.incrementAndGet();
      if (failing.get()) {
        if (arenaOom) throw new ai.onnxruntime.OrtException("BFCArena::AllocateRawInternal Failed to allocate memory for requested buffer of size 1234");
        throw new io.justsearch.ort.SessionTemporarilyUnavailableException("encoder unavailable");
      }
      return List.of(Map.of("recovered", 1f));
    });
    BackfillScheduler scheduler = scheduler(monotonicNanos::get);
    timeIdleCycle(scheduler);
    assertFalse(state.get("doc").containsKey(SchemaFields.SPLADE_RETRY_COUNT));
    int callsBeforeBackoff = calls.get();
    assertTrue(callsBeforeBackoff > 0);
    scheduler.runInterleavedSplade(System.currentTimeMillis());
    timeIdleCycle(scheduler);
    assertEquals(callsBeforeBackoff, calls.get(), "combined and interleaved paths share backoff");
    assertFalse(state.get("doc").containsKey(SchemaFields.SPLADE_RETRY_COUNT));
    failing.set(false);
    var retryTime = BackfillScheduler.class.getDeclaredField("nextSpladeRetryTime");
    retryTime.setAccessible(true);
    retryTime.setLong(scheduler, 0);
    timeIdleCycle(scheduler);
    assertEquals("COMPLETED", state.get("doc").get(SchemaFields.SPLADE_STATUS));
    var failureCount = BackfillScheduler.class.getDeclaredField("consecutiveSpladeFailures");
    failureCount.setAccessible(true);
    assertEquals(0, failureCount.getInt(scheduler));
  }

}
