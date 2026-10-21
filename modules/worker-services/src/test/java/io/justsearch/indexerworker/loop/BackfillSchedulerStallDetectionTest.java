/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
 * Round-15 post-round finding, fix 3 — non-termination has to be DETECTABLE.
 *
 * <p>The field incident ran 12+ minutes emitting nothing but the ordinary INFO summary line, 54
 * times, byte-identical. Tempdoc 798's non-convergence WARN exists and did not fire, and that is
 * not a bug in 798: its discriminator is "the budget expired and NOTHING advanced", while this
 * incident advanced 56-80 documents per cycle. The discriminator it lacked is WORK-SET IDENTITY —
 * a draining backfill's selection changes as its head drains, a stalled one's does not, whatever
 * its per-cycle counters say.
 *
 * <p>Both directions are pinned here, because a detector that fires on healthy progress is worse
 * than none: operators learn to ignore it, which is exactly the trap tempdoc 750 recorded for round
 * 8 (a signature that repeats on a HEALTHY build).
 */
@DisplayName("BackfillScheduler stall detection (round-15 post-round finding)")
@ExtendWith(MockitoExtension.class)
class BackfillSchedulerStallDetectionTest {

  /** Must mirror {@code BackfillScheduler.STALL_SIGNATURE_CYCLES}. */
  private static final int STALL_CYCLES = 5;

  private static final String WARN_NEEDLE = "selected an IDENTICAL work-set";

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock IndexCountOps indexCountOps;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock JobQueue jobQueue;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock NerService nerService;

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

    lenient()
        .when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(inv -> List.copyOf(pending));

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

  private long warnCount() {
    return logAppender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(WARN_NEEDLE))
        .count();
  }

  /**
   * Runs one cycle against a work-set that is restored to {@code docIds} beforehand — the field
   * shape: real encoder work and real writes every cycle, and the head of the queue nonetheless
   * identical the next time it is selected.
   */
  private void runFrozenCycle(BackfillScheduler scheduler, List<String> docIds) {
    pending.clear();
    pending.addAll(docIds);
    scheduler.runIdleCycle();
  }

  private static List<String> ids(int from, int toExclusive) {
    List<String> out = new ArrayList<>();
    for (int i = from; i < toExclusive; i++) out.add("doc-" + i);
    return out;
  }

  /**
   * PINS: the stall WARN fires when the work-set is frozen while encoder work is still happening.
   *
   * <p>Note what this fixture does NOT do: it does not stop the work. Every cycle encodes, writes,
   * and reports PROGRESS — {@code progressed()} is true throughout. That is the whole point. The
   * field incident advanced 56-80 documents per cycle, which is exactly why tempdoc 798's
   * "budget spent with ZERO advancement" WARN stayed silent for 12+ minutes. A detector that keys
   * on progress cannot see this state; one that keys on work-set identity can.
   */
  @Test
  @DisplayName("an identical work-set across N cycles logs the stall WARN")
  void frozenWorkSetLogsTheStallWarn() {
    BackfillScheduler scheduler = scheduler();
    List<String> head = ids(0, 6);
    for (int cycle = 0; cycle < STALL_CYCLES; cycle++) {
      runFrozenCycle(scheduler, head);
    }

    assertTrue(
        warnCount() >= 1,
        "a work-set unchanged across "
            + STALL_CYCLES
            + " cycles while encoder work is still being attempted must WARN; the field ran this"
            + " state for 12+ minutes and logged nothing but the ordinary INFO summary");
    assertFalse(
        logAppender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("ZERO")),
        "tempdoc 798's non-convergence WARN must NOT be what fired here — documents advanced every"
            + " cycle. If it did, this fixture is not reproducing the field state and the new"
            + " detector is untested");
  }

  /** PINS: one WARN per stall episode, not one per cycle — a per-cycle WARN is log spam. */
  @Test
  @DisplayName("the stall WARN is deduplicated across a single episode")
  void stallWarnIsDeduplicatedPerEpisode() {
    BackfillScheduler scheduler = scheduler();
    List<String> head = ids(0, 6);
    for (int cycle = 0; cycle < STALL_CYCLES * 3; cycle++) {
      runFrozenCycle(scheduler, head);
    }

    assertEquals(
        1,
        warnCount(),
        "the stall WARN must fire once per episode; "
            + warnCount()
            + " occurrences over "
            + (STALL_CYCLES * 3)
            + " cycles is the per-cycle spam that trains operators to ignore it");
  }

  /**
   * PINS: healthy progress does not WARN.
   *
   * <p>Same cycle count, same encoder work, same writes — the ONE difference is that the head of
   * the queue moves, because each cycle's documents drain and new ones arrive (ongoing ingest). A
   * detector that fires on this is a false alarm on every large corpus, which is the trap tempdoc
   * 750 recorded for round 8: a repeating signature that is HEALTHY.
   */
  @Test
  @DisplayName("a draining work-set never logs the stall WARN")
  void drainingWorkSetDoesNotWarn() {
    BackfillScheduler scheduler = scheduler();
    for (int cycle = 0; cycle < STALL_CYCLES * 3; cycle++) {
      runFrozenCycle(scheduler, ids(cycle * 6, cycle * 6 + 6));
    }

    assertEquals(
        0,
        warnCount(),
        "healthy draining progress must not trip the stall detector; a false positive here makes"
            + " the signal worthless");
  }

  /**
   * PINS: a stall episode that recovers re-arms the detector.
   *
   * <p>Without the reset a second, genuine stall after a recovery would be silent — the same class
   * of defect (a diagnostic that exists but cannot fire) this fix removes.
   */
  @Test
  @DisplayName("the detector re-arms after the work-set changes")
  void detectorReArmsAfterRecovery() {
    BackfillScheduler scheduler = scheduler();
    for (int cycle = 0; cycle < STALL_CYCLES; cycle++) {
      runFrozenCycle(scheduler, ids(0, 6));
    }
    assertEquals(1, warnCount(), "precondition: the first episode warned once");

    // ...the head moves (episode over), then freezes again on a different selection.
    for (int cycle = 0; cycle < STALL_CYCLES; cycle++) {
      runFrozenCycle(scheduler, ids(100, 106));
    }

    assertEquals(
        2,
        warnCount(),
        "a second stall episode after a recovery must warn again; observed " + warnCount());
  }
}
