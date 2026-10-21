/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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
import io.justsearch.indexerworker.metrics.BatchTimingKeys;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tempdoc 710 Move 2 item 4: {@code BackfillScheduler.runIdleCycle()} must record the backfill
 * MODE ("combined" | "individual" | "idle") and per-stage timing/counts for WHICHEVER path
 * executed — before this move, {@code OperationalMetrics.recordStageTiming}/{@code
 * recordEnrichmentCompleted} were called only from inside {@code
 * CombinedEnrichmentBackfillOps.processCombinedBackfill}, so counters froze whenever the
 * individual-backfill path ran (710 S-B3 finding), and the mode itself was observable nowhere.
 *
 * <p>Uses an in-memory {@code fakeIndex} map (mirrors {@code CombinedEnrichmentBackfillOpsTest}'s
 * fixture) rather than fixed stubs so "did the combined pass find work" is driven by actual doc
 * state, not a hardcoded true/false (avoids the {@code unreachable-seed-green} pattern —
 * agent-lessons.md).
 */
@DisplayName("BackfillScheduler backfill-mode + per-stage recording (tempdoc 710 Move 2 item 4)")
@ExtendWith(MockitoExtension.class)
class BackfillSchedulerModeRecordingTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock IndexCountOps indexCountOps;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock JobQueue jobQueue;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock SpladeEncoder spladeEncoder;
  @Mock NerService nerService;

  private final Map<String, Map<String, Object>> fakeIndex = new LinkedHashMap<>();
  private final Map<String, String> contentByDoc = new HashMap<>();

  @BeforeEach
  void wireCommonMocks() {
    io.justsearch.indexerworker.loop.ops.BackfillSnapshotTestSupport.install(documentFieldOps,indexingCoordinator);
    lenient().when(signalBus.isMainGpuActive()).thenReturn(false);
    lenient().when(signalBus.isEnergyReduced()).thenReturn(false);
    lenient().when(signalBus.shouldYieldGpuBackfill()).thenReturn(false);
    lenient().when(embeddingProvider.isUsingGpu()).thenReturn(false);
    lenient().when(indexCountOps.countByField(anyString(), anyString())).thenReturn(0);

    // withNrtSuspended must actually invoke the tight-loop body — a bare mock void method
    // no-ops, which would make runIdleCycle()'s combined branch silently do nothing.
    lenient()
        .doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(0)).run();
              return null;
            })
        .when(commitOps)
        .withNrtSuspended(any());

    lenient()
        .when(documentFieldOps.getDocumentContent(anyString()))
        .thenAnswer(inv -> contentByDoc.get(inv.getArgument(0)));

    // Combined stages still fetch a batch; individual parent embedding now fetches one document
    // at a time to enforce its retained-content budget. Both read the same seeded source text.
    lenient()
        .when(documentFieldOps.getDocumentContentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              Map<String, String> m = new HashMap<>();
              for (String id : ids) {
                String c = contentByDoc.get(id);
                if (c != null) m.put(id, c);
              }
              return m;
            });

    lenient()
        .when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              java.util.Set<String> fields = inv.getArgument(1);
              Map<String, Map<String, String>> result = new HashMap<>();
              for (String id : ids) {
                Map<String, Object> state = fakeIndex.getOrDefault(id, Map.of());
                Map<String, String> filtered = new HashMap<>();
                for (String f : fields) {
                  Object v = state.get(f);
                  if (v != null) filtered.put(f, v.toString());
                }
                result.put(id, filtered);
              }
              return result;
            });

    lenient()
        .when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(
            inv -> {
              String field = inv.getArgument(0);
              String value = inv.getArgument(1);
              List<String> matches = new ArrayList<>();
              for (var e : fakeIndex.entrySet()) {
                if (value.equals(e.getValue().get(field))) {
                  matches.add(e.getKey());
                }
              }
              return matches;
            });

    lenient()
        .when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<Map.Entry<String, Map<String, Object>>> batch = inv.getArgument(0);
              int count = 0;
              for (var entry : batch) {
                fakeIndex.computeIfAbsent(entry.getKey(), k -> new HashMap<>()).putAll(entry.getValue());
                count++;
              }
              return new LuceneRuntimeTypes.BatchUpdateResult(count, 0);
            });
    lenient()
        .when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<Map.Entry<String, Map<String, Object>>> batch = inv.getArgument(0);
              int count = 0;
              for (var entry : batch) {
                fakeIndex.computeIfAbsent(entry.getKey(), k -> new HashMap<>()).putAll(entry.getValue());
                count++;
              }
              return new LuceneRuntimeTypes.BatchUpdateResult(count, 0);
            });

    lenient()
        .when(embeddingProvider.embedDocumentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              List<float[]> out = new ArrayList<>();
              for (String ignored : texts) {
                out.add(new float[] {1f, 2f});
              }
              return out;
            });
  }

  private void seedDoc(String docId, String content, Map<String, String> statusFields) {
    contentByDoc.put(docId, content);
    fakeIndex.computeIfAbsent(docId, k -> new HashMap<>()).putAll(statusFields);
  }

  private ResolvedConfig resolvedConfig() {
    ResolvedConfig config = mock(ResolvedConfig.class);
    ResolvedConfig.Rag rag = mock(ResolvedConfig.Rag.class);
    lenient().when(rag.chunkVectorsEnabled()).thenReturn(false);
    lenient().when(config.rag()).thenReturn(rag);
    ResolvedConfig.Ai ai = mock(ResolvedConfig.Ai.class);
    ResolvedConfig.Ai.Embedding embedding = mock(ResolvedConfig.Ai.Embedding.class);
    lenient().when(embedding.lateChunkingEnabled()).thenReturn(false);
    lenient().when(ai.embedding()).thenReturn(embedding);
    // Tempdoc 710 Wave-1.5 Move 4: pacing() now reads ai().backfillPacing() on every stage
    // dispatch — an unstubbed backfillPacing() would return null (Mockito default) and NPE.
    // DEFAULTS mirrors the pre-Move-4 hardcoded batch sizes, keeping this test's exercised
    // batch sizes unchanged.
    lenient().when(ai.backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
    lenient().when(config.ai()).thenReturn(ai);
    return config;
  }

  private BackfillScheduler scheduler(
      boolean embedAvail, boolean spladeAvail, boolean nerAvail) {
    lenient().when(embeddingProvider.isAvailable()).thenReturn(embedAvail);
    if (nerAvail) {
      lenient().when(nerService.isAvailable()).thenReturn(true);
    }
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
        spladeAvail ? () -> spladeEncoder : () -> null,
        () -> null,
        nerAvail ? () -> nerService : () -> null,
        () -> null);
  }

  @Test
  @DisplayName("combined mode: availCount>=2 + pending work records mode=combined and EMBED stage")
  void combinedMode_recordsModeAndStageTiming() {
    seedDoc(
        "doc-1",
        "content for doc one",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));

    BackfillScheduler scheduler = scheduler(true, true, true);

    long embedCompletedBefore =
        OperationalMetrics.getInstance()
            .getEnrichmentCompleted()
            .getOrDefault(BatchTimingKeys.EMBED, 0L);

    boolean didWork = scheduler.runIdleCycle();

    assertTrue(didWork, "combined pass should report work for the seeded pending doc");
    assertEquals("combined", OperationalMetrics.getInstance().getBackfillMode());
    long embedCompletedAfter =
        OperationalMetrics.getInstance()
            .getEnrichmentCompleted()
            .getOrDefault(BatchTimingKeys.EMBED, 0L);
    assertTrue(
        embedCompletedAfter > embedCompletedBefore,
        "combined pass must record EMBED enrichment-completed count (710 S-B3: this used to only"
            + " happen inside processCombinedBackfill, now BackfillScheduler must do it)");
  }

  @Test
  @DisplayName("individual mode: availCount<2 records mode=individual and EMBED stage")
  void individualMode_recordsModeAndStageTiming() {
    seedDoc(
        "doc-2",
        "content for doc two",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));

    // Only embed available (spladeAvail=false, nerAvail=false) -> availCount=1 < 2 -> individual.
    BackfillScheduler scheduler = scheduler(true, false, false);

    long embedCompletedBefore =
        OperationalMetrics.getInstance()
            .getEnrichmentCompleted()
            .getOrDefault(BatchTimingKeys.EMBED, 0L);

    scheduler.runIdleCycle();

    assertEquals("individual", OperationalMetrics.getInstance().getBackfillMode());
    long embedCompletedAfter =
        OperationalMetrics.getInstance()
            .getEnrichmentCompleted()
            .getOrDefault(BatchTimingKeys.EMBED, 0L);
    assertTrue(
        embedCompletedAfter > embedCompletedBefore,
        "individual mode must ALSO record EMBED enrichment-completed count — the exact gap 710"
            + " S-B3 found: individual-mode counters froze because only the combined pass"
            + " recorded anything");
  }

  @Test
  @DisplayName("idle mode: energy-reduced skips backfill entirely and records mode=idle")
  void idleMode_recordsIdle() {
    when(signalBus.isEnergyReduced()).thenReturn(true);
    BackfillScheduler scheduler = scheduler(true, true, true);

    boolean didWork = scheduler.runIdleCycle();

    assertEquals(false, didWork);
    assertEquals("idle", OperationalMetrics.getInstance().getBackfillMode());
  }

  /**
   * Tempdoc 813 §20a (owner finding, 2026-08-07). The individual branch pre-stamps "individual"
   * BEFORE running the pass (mid-pass observability), so a pass that then found nothing eligible
   * used to leave the gauge reading "individual" until the next cycle changed it — on a fully
   * enriched index, forever. {@code getBackfillMode}'s own contract calls that state "idle" ("no
   * backfill work was available/eligible last cycle"), so the pass now re-stamps it.
   */
  @Test
  @DisplayName("§20a: an individual pass that advances NOTHING re-stamps mode=idle")
  void individualMode_withNothingEligible_reStampsIdle() {
    // Gauge seeded to a value neither branch would produce by accident, so a passing assertion
    // cannot come from a leftover "idle" that was simply never overwritten (this class shares one
    // process-wide OperationalMetrics singleton across tests).
    OperationalMetrics.getInstance().recordBackfillMode("combined");

    // Nothing seeded: every stage query returns empty. Only embed available -> availCount=1 -> the
    // combined pass writes nothing -> the INDIVIDUAL branch is the one that runs.
    BackfillScheduler scheduler = scheduler(true, false, false);

    long embedCompletedBefore =
        OperationalMetrics.getInstance()
            .getEnrichmentCompleted()
            .getOrDefault(BatchTimingKeys.EMBED, 0L);

    boolean didWork = scheduler.runIdleCycle();

    assertEquals(false, didWork);
    // Right-reason guard #1: the backfill was NOT skipped wholesale (that path stamps "idle" from
    // the else-branch and would pass this test vacuously) — the pass ran and queried for work.
    verify(documentFieldOps, atLeastOnce()).queryDocIdsByField(anyString(), anyString(), anyInt());
    // Right-reason guard #2: it genuinely advanced nothing, so "idle" is the truthful gauge value
    // rather than a stamp that raced past real work.
    assertEquals(
        embedCompletedBefore,
        OperationalMetrics.getInstance()
            .getEnrichmentCompleted()
            .getOrDefault(BatchTimingKeys.EMBED, 0L),
        "no documents were eligible, so no stage may have recorded a completion");
    assertEquals("idle", OperationalMetrics.getInstance().getBackfillMode());
  }

  /**
   * The other direction of §20a's re-stamp: a pass that DID advance documents keeps "individual".
   * {@code runIndividualBackfills}' pacing flag is false even here (a parent-embedding batch never
   * sets it — only the chunk loop and a SPLADE pass over a non-empty backlog do), so a re-stamp
   * keyed on that flag instead of on ACTIVITY would stamp "idle" over a working cycle.
   */
  @Test
  @DisplayName("§20a: an individual pass that DOES advance documents keeps mode=individual")
  void individualMode_withWorkDone_keepsIndividual() {
    seedDoc(
        "doc-3",
        "content for doc three",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));

    BackfillScheduler scheduler = scheduler(true, false, false);

    long embedCompletedBefore =
        OperationalMetrics.getInstance()
            .getEnrichmentCompleted()
            .getOrDefault(BatchTimingKeys.EMBED, 0L);

    boolean didWork = scheduler.runIdleCycle();

    // The pacing flag stays false — this is exactly the signal a naive re-stamp would have used.
    assertEquals(false, didWork);
    assertTrue(
        OperationalMetrics.getInstance()
                .getEnrichmentCompleted()
                .getOrDefault(BatchTimingKeys.EMBED, 0L)
            > embedCompletedBefore,
        "the embedding stage must have advanced the seeded document");
    assertEquals("individual", OperationalMetrics.getInstance().getBackfillMode());
  }
}
