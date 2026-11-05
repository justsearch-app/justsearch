/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Round-15 post-round finding, fix 3 — non-termination has to be DETECTABLE; E4b (D8) changed the
 * discriminator.
 *
 * <p>The work-set-identity detector warned whenever the same documents were selected cycle after
 * cycle, irrespective of progress, so a long document advancing its retained windows looked like a
 * stall. E4b replaces selection identity with eligible-service evidence: a lane that keeps
 * receiving eligible turns which return without publishing, terminally failing or advancing a
 * window is the frozen unit. Here every write is rejected as stale, so the unit returns but never
 * advances. Both directions stay pinned, because a detector that fires on healthy progress is
 * worse than none.
 */
@DisplayName("BackfillScheduler stall detection (round-15 post-round finding)")
@ExtendWith(MockitoExtension.class)
class BackfillSchedulerStallDetectionTest {

  /** Must mirror {@code BackfillScheduler.STALL_TURNS}: one non-advancing turn per cycle here. */
  private static final int STALL_CYCLES = 5;

  private static final String WARN_NEEDLE = "consecutive eligible turns without advancing";

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock IndexCountOps indexCountOps;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock JobQueue jobQueue;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock NerService nerService;

  /** Whether the writer rejects every row as stale, so a returned unit advances nothing. */
  private boolean frozen;

  /** Documents still PENDING for embedding. */
  private final Set<String> pending = new LinkedHashSet<>();

  private Logger schedulerLogger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void wireStalledPopulation() {
    io.justsearch.indexerworker.loop.ops.BackfillSnapshotTestSupport.install(documentFieldOps,indexingCoordinator);
    lenient().when(signalBus.isMainGpuActive()).thenReturn(false);
    lenient().when(signalBus.isEnergyReduced()).thenReturn(false);
    lenient().when(signalBus.shouldYieldGpuBackfill()).thenReturn(false);
    lenient().when(signalBus.hasPendingIngest()).thenReturn(false);
    lenient().when(indexCountOps.countByField(anyString(), anyString())).thenReturn(0);
    lenient().when(indexingCoordinator.bulkDeleteEpoch()).thenReturn(0L);
    lenient().when(embeddingProvider.isAvailable()).thenReturn(true);
    lenient().when(embeddingProvider.isUsingGpu()).thenReturn(false);
    lenient().when(nerService.isAvailable()).thenReturn(true);

    lenient()
        .doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(0)).run();
              return null;
            })
        .when(commitOps)
        .withNrtSuspended(any());

    // Only embedding is pending; NER is terminal, so its pending population is empty.
    lenient()
        .when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(inv -> SchemaFields.EMBEDDING_STATUS.equals(inv.getArgument(0))
            ? List.copyOf(pending) : List.of());

    lenient()
        .when(documentFieldOps.getDocumentContentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              Map<String, String> content = new HashMap<>();
              for (String id : ids) content.put(id, "content for " + id);
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
                        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
                        SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED));
              }
              return result;
            });

    // Every document is a single forward pass: the detector must work on ordinary documents too,
    // not only on the long-document shape the other two fixes address.
    lenient().when(embeddingProvider.documentWindowCount(anyString())).thenReturn(1);
    lenient()
        .when(embeddingProvider.embedDocumentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              List<float[]> vectors = new ArrayList<>(texts.size());
              for (int i = 0; i < texts.size(); i++) vectors.add(new float[] {0.1f, 0.2f});
              return vectors;
            });

    lenient()
        .when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<Map.Entry<String, Map<String, Object>>> batch = inv.getArgument(0);
              if (frozen) return new LuceneRuntimeTypes.BatchUpdateResult(0, batch.size());
              for (Map.Entry<String, Map<String, Object>> e : batch) {
                if (SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(
                    e.getValue().get(SchemaFields.EMBEDDING_STATUS))) {
                  pending.remove(e.getKey());
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
        () -> null,
        () -> null,
        () -> nerService,
        () -> null);
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
                2,
                defaults.nerBackfillBatchSize(),
                defaults.disambiguationBackfillBatchSize(),
                defaults.spladeBackfillBatchSize(),
                10, // busy count cap and interval (literal: both accessor spellings compile)
                5_000L,
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

  private long warnCount() {
    return logAppender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(WARN_NEEDLE))
        .count();
  }

  /** Runs one cycle with {@code docIds} pending; a frozen cycle's writes are all rejected. */
  private void runCycle(BackfillScheduler scheduler, List<String> docIds, boolean frozenCycle) {
    frozen = frozenCycle;
    pending.clear();
    pending.addAll(docIds);
    scheduler.runIdleCycle();
  }

  private static List<String> ids(int from, int toExclusive) {
    List<String> out = new ArrayList<>();
    for (int i = from; i < toExclusive; i++) out.add("doc-" + i);
    return out;
  }

  @Test
  // an eligible unit that keeps returning without advancing logs the stall WARN
  void frozenEligibleUnitLogsTheStallWarn() {
    BackfillScheduler scheduler = scheduler();
    for (int cycle = 0; cycle < STALL_CYCLES - 1; cycle++) runCycle(scheduler, ids(0, 6), true);
    assertEquals(0, warnCount(), "fewer than the episode threshold of returned turns stays quiet");
    runCycle(scheduler, ids(0, 6), true);
    assertEquals(1, warnCount(), "returned eligible service that advances nothing must WARN");
    verify(embeddingProvider, atLeast(STALL_CYCLES)).embedDocumentBatch(anyList());
  }

  /** PINS: one WARN per stall episode, not one per cycle — a per-cycle WARN is log spam. */
  @Test
  // the stall WARN is deduplicated across a single episode
  void stallWarnIsDeduplicatedPerEpisode() {
    BackfillScheduler scheduler = scheduler();
    for (int cycle = 0; cycle < STALL_CYCLES * 3; cycle++) runCycle(scheduler, ids(0, 6), true);
    assertEquals(1, warnCount(), "the stall WARN must fire once per episode");
  }

  /**
   * PINS: healthy progress does not WARN, including the old false positive: the same head is
   * selected every cycle, yet each cycle publishes it, so the lane is advancing.
   */
  @Test
  // publishing service never logs the stall WARN, even with an identical work-set
  void publishingWorkSetDoesNotWarn() {
    BackfillScheduler scheduler = scheduler();
    for (int cycle = 0; cycle < STALL_CYCLES * 3; cycle++) {
      runCycle(scheduler, cycle % 2 == 0 ? ids(0, 6) : ids(cycle * 6, cycle * 6 + 6), false);
    }
    assertEquals(0, warnCount());
  }

  /** PINS: a stall episode that recovers re-arms the detector. */
  @Test
  // the detector re-arms after the lane advances again
  void detectorReArmsAfterRecovery() {
    BackfillScheduler scheduler = scheduler();
    for (int cycle = 0; cycle < STALL_CYCLES; cycle++) runCycle(scheduler, ids(0, 6), true);
    assertEquals(1, warnCount(), "precondition: the first episode warned once");
    runCycle(scheduler, ids(0, 6), false);
    for (int cycle = 0; cycle < STALL_CYCLES; cycle++) runCycle(scheduler, ids(100, 106), true);
    assertEquals(2, warnCount(), "a second stall episode after a recovery must warn again");
  }
}
