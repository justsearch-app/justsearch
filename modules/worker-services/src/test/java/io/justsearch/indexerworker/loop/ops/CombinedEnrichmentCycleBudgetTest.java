/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import ai.onnxruntime.OrtException;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@DisplayName("CombinedEnrichmentBackfillOps cycle budget")
@ExtendWith(MockitoExtension.class)
class CombinedEnrichmentCycleBudgetTest {

  private static final int WINDOW_SLICE = 32;
  private static final int WINDOWS_PER_LONG_DOC = 4 * WINDOW_SLICE;

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock EmbeddingProvider embeddingProvider;

  private final Map<String, Map<String, Object>> fakeIndex = new LinkedHashMap<>();
  private final Map<String, String> contentByDoc = new LinkedHashMap<>();

  /** Encoder units spent since the last {@link #newCycle()} — the simulated cycle budget. */
  private final AtomicInteger unitsThisCycle = new AtomicInteger();
  private final AtomicInteger singlePassRequests = new AtomicInteger();
  private final AtomicInteger elapsedMs = new AtomicInteger();
  private int probeCostMs;
  private int windowCostMs;
  private int batchCostMs;
  private int windowsPerLongDoc = WINDOWS_PER_LONG_DOC;
  private final List<List<String>> batchRequests = new ArrayList<>();

  /** Every {@code (docId, fromWindow)} the encoder was asked for, in order. */
  private final List<String> windowRequests = new ArrayList<>();

  @BeforeEach
  void wireFakeIndex() throws Exception {
    BackfillSnapshotTestSupport.install(documentFieldOps,indexingCoordinator);
    lenient().when(embeddingProvider.isAvailable()).thenReturn(true);
    lenient().when(indexingCoordinator.bulkDeleteEpoch()).thenReturn(0L);

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
              Set<String> fields = inv.getArgument(1);
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
        .when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<Map.Entry<String, Map<String, Object>>> batch = inv.getArgument(0);
              for (var entry : batch) {
                fakeIndex
                    .computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                    .putAll(entry.getValue());
              }
              return new LuceneRuntimeTypes.BatchUpdateResult(batch.size(), 0);
            });

    // The window-granular encoder seam. Long documents (content marked LONG:) report several
    // windows; everything else is one forward pass and stays on the historical batch path.
    lenient()
        .when(embeddingProvider.documentWindowCount(anyString()))
        .thenAnswer(
            inv -> {
              String text = inv.getArgument(0);
              return text.startsWith("LONG:") ? windowsPerLongDoc : 1;
            });

    lenient()
        .when(embeddingProvider.embedDocumentWindows(anyString(), anyInt(), anyInt()))
        .thenAnswer(
            inv -> {
              String text = inv.getArgument(0);
              int from = inv.getArgument(1);
              int max = inv.getArgument(2);
              elapsedMs.addAndGet(windowCostMs);
              windowRequests.add(docIdOf(text) + "@" + from);
              unitsThisCycle.incrementAndGet();
              int count = Math.max(0, Math.min(max, windowsPerLongDoc - from));
              List<float[]> vectors = new ArrayList<>(count);
              for (int i = 0; i < count; i++) {
                vectors.add(new float[] {1f, 0f});
              }
              return new EmbeddingProvider.WindowSlice(vectors, from, windowsPerLongDoc);
            });

    lenient()
        .when(embeddingProvider.embedDocumentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              batchRequests.add(List.copyOf(texts));
              elapsedMs.addAndGet(batchCostMs);
              unitsThisCycle.incrementAndGet();
              List<float[]> out = new ArrayList<>(texts.size());
              for (int i = 0; i < texts.size(); i++) out.add(new float[] {1f, 0f});
              return out;
            });

    lenient()
        .when(embeddingProvider.embedWithSpans(anyString(), org.mockito.ArgumentMatchers.any(int[][].class)))
        .thenAnswer(
            inv -> {
              elapsedMs.addAndGet(probeCostMs);
              singlePassRequests.incrementAndGet();
              unitsThisCycle.incrementAndGet();
              return null;
            });

  }

  private static String docIdOf(String content) {
    return content.substring(content.lastIndexOf(':') + 1);
  }

  private void seedLongEmbedDoc(String docId) {
    contentByDoc.put(docId, "LONG:" + docId);
    fakeIndex
        .computeIfAbsent(docId, k -> new HashMap<>())
        .put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
  }

  private void seedLateChunkedLongParent(String docId) {
    seedLongEmbedDoc(docId);
    fakeIndex
        .computeIfAbsent(docId + "-chunk", k -> new HashMap<>())
        .put(SchemaFields.PARENT_DOC_ID, docId);
  }

  private void newCycle() {
    unitsThisCycle.set(0);
    elapsedMs.set(0);
  }

  private int pendingEmbedDocs() {
    int pending = 0;
    for (var state : fakeIndex.values()) {
      if (SchemaFields.EMBEDDING_STATUS_PENDING.equals(state.get(SchemaFields.EMBEDDING_STATUS))) {
        pending++;
      }
    }
    return pending;
  }

  private void seedChunks(int count) {
    for (int i = 0; i < count; i++) {
      String id = "chunk-" + i;
      contentByDoc.put(id, "CHUNK:" + id);
      fakeIndex.put(id, new HashMap<>(Map.of(
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING)));
    }
  }

  private long completed(String statusField) {
    return fakeIndex.values().stream()
        .filter(state -> SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(state.get(statusField)))
        .count();
  }

  private CombinedEnrichmentBackfillOps.BackfillContext timedContext(
      WindowedEmbedProgress progress, BooleanSupplier hardStop) {
    probeCostMs = 80;
    windowCostMs = 270;
    batchCostMs = 70;
    return new CombinedEnrichmentBackfillOps.BackfillContext(
        documentFieldOps, indexingCoordinator, commitOps, IndexingPacing.unthrottled(),
        () -> embeddingProvider, () -> null, () -> null, () -> true, () -> true,
        100, LoggerFactory.getLogger(CombinedEnrichmentCycleBudgetTest.class),
        true, false, true, 50, new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0},
        () -> elapsedMs.get() >= 5_000 || hardStop.getAsBoolean(), hardStop,
        () -> elapsedMs.get() >= 3_000, progress);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("S2/S3/S6: one costly fallback probe leaves time for parents and chunks")
  void manyLateChunkedParentsDoNotSpendTheCycleOnClassification(boolean arenaOom) {
    windowsPerLongDoc = 42;
    for (int i = 0; i < 100; i++) seedLateChunkedLongParent("long-" + i);
    seedChunks(50);
    if (arenaOom) {
      lenient().when(embeddingProvider.embedWithSpans(
          anyString(), org.mockito.ArgumentMatchers.any(int[][].class))).thenAnswer(inv -> {
            singlePassRequests.incrementAndGet();
            unitsThisCycle.incrementAndGet();
            elapsedMs.addAndGet(probeCostMs);
            throw new RuntimeException(new OrtException(
                "BFCArena::AllocateRawInternal: Failed to allocate memory"));
          });
    }
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    for (int cycle = 0; cycle < 4; cycle++) {
      newCycle();
      int probesBefore = singlePassRequests.get();
      int windowsBefore = windowRequests.size();
      var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(
          timedContext(progress, () -> false));
      assertTrue(outcome.wroteAnything(), "each cycle must publish or advance windows");
      assertTrue(windowRequests.size() > windowsBefore, "every cycle must embed real windows");
      assertEquals(1, singlePassRequests.get() - probesBefore,
          "at most one over-limit/OOM classification may consume a cycle");
      assertTrue(completed(SchemaFields.EMBEDDING_STATUS) >= cycle + 1,
          "parents must complete steadily with 100 parents pending");
      assertTrue(completed(SchemaFields.CHUNK_EMBEDDING_STATUS) > 0,
          "chunks must publish while parents are pending");
      for (var state : fakeIndex.values()) {
        assertTrue(!state.containsKey(SchemaFields.EMBEDDING_RETRY_COUNT)
                || "0".equals(state.get(SchemaFields.EMBEDDING_RETRY_COUNT)),
            "null/OOM fallback and unclassified deferral must never charge retries");
      }
    }
    assertTrue(pendingEmbedDocs() > 90, "the test must retain a large parent backlog");
    assertEquals(50, completed(SchemaFields.CHUNK_EMBEDDING_STATUS));
  }

  @Test
  @DisplayName("S2/S3: resumable parents use the whole budget but give chunks a bounded turn")
  void chunksAdvanceDuringResumableParentBacklogAndThroughTheChunkOnlyTail() {
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    for (int i = 0; i < 100; i++) {
      String id = "long-" + i;
      seedLateChunkedLongParent(id);
      progress.record(id, contentByDoc.get(id), WINDOWS_PER_LONG_DOC, 0,
          java.util.Collections.nCopies(WINDOW_SLICE, new float[] {1f, 0f}));
    }
    seedChunks(50);
    long chunksBefore = 0;
    for (int cycle = 0; cycle < 6; cycle++) {
      newCycle();
      int batchesBefore = batchRequests.size();
      CombinedEnrichmentBackfillOps.processCombinedBackfill(timedContext(progress, () -> false));
      long chunksAfter = completed(SchemaFields.CHUNK_EMBEDDING_STATUS);
      assertTrue(chunksAfter > chunksBefore,
          "chunks must publish each cycle (stronger than once per three cycles)");
      assertEquals(1, batchRequests.size() - batchesBefore,
          "chunk work ahead of the parent windows is bounded to one encoder batch");
      assertTrue(elapsedMs.get() >= 5_000,
          "an embedding-only backlog must use the full cycle, ignoring the 3s stage share");
      chunksBefore = chunksAfter;
    }
    assertTrue(pendingEmbedDocs() > 0);
    assertEquals(0, singlePassRequests.get(), "resuming parents bypass classification");
    fakeIndex.entrySet().removeIf(e -> e.getValue().containsKey(SchemaFields.EMBEDDING_STATUS));
    newCycle();
    int windowsBefore = windowRequests.size();
    CombinedEnrichmentBackfillOps.processCombinedBackfill(timedContext(progress, () -> false));
    assertEquals(50, completed(SchemaFields.CHUNK_EMBEDDING_STATUS),
        "the chunk-only tail must finish on the ordinary batch path");
    assertEquals(windowsBefore, windowRequests.size());
  }

  @ParameterizedTest
  @CsvSource({"1,false", "2,false", "3,false", "1,true", "2,true", "3,true"})
  @DisplayName("P1/P4: hard preemption at classification, chunk and window boundaries")
  void hardPreemptionStopsAtEachEmbeddingBoundary(int stopAfterUnits, boolean bulkDelete) {
    if (bulkDelete) {
      lenient().when(indexingCoordinator.bulkDeleteEpoch()).thenAnswer(
          inv -> unitsThisCycle.get() >= stopAfterUnits ? 1L : 0L);
    }
    for (int i = 0; i < 100; i++) seedLateChunkedLongParent("long-" + i);
    seedChunks(50);
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    newCycle();
    var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(
        timedContext(progress, () -> !bulkDelete && unitsThisCycle.get() >= stopAfterUnits));
    assertTrue(outcome.aborted());
    assertEquals(stopAfterUnits, unitsThisCycle.get());
    assertTrue(windowRequests.size() <= 1, "preemption permits at most one window slice");
    assertTrue(windowRequests.stream().allMatch(r -> r.equals("long-0@0")),
        "preemption must stop before another window or parent starts");
    assertEquals(0, completed(SchemaFields.EMBEDDING_STATUS), "no partial parent publication");
    for (var state : fakeIndex.values()) {
      assertTrue(!state.containsKey(SchemaFields.VECTOR));
      assertTrue(!state.containsKey(SchemaFields.EMBEDDING_RETRY_COUNT));
    }
  }

  @Test
  @DisplayName("P13: unclassified short parents keep their single-pass vector on a later cycle")
  void classificationDeferralDoesNotDowngradeShortParentsToWindows() {
    seedLateChunkedLongParent("short-first");
    seedLateChunkedLongParent("long-middle");
    seedLateChunkedLongParent("short-last");
    lenient().when(embeddingProvider.embedWithSpans(
        anyString(), org.mockito.ArgumentMatchers.any(int[][].class))).thenAnswer(inv -> {
          singlePassRequests.incrementAndGet();
          unitsThisCycle.incrementAndGet();
          if (inv.<String>getArgument(0).contains("long-middle")) return null;
          return new EmbeddingService.ChunkedEmbedding(new float[] {0f, 1f}, List.of(), 1);
        });
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    newCycle();
    CombinedEnrichmentBackfillOps.processCombinedBackfill(timedContext(progress, () -> false));
    newCycle();
    CombinedEnrichmentBackfillOps.processCombinedBackfill(timedContext(progress, () -> false));
    assertEquals(0f, ((float[]) fakeIndex.get("short-last").get(SchemaFields.VECTOR))[0]);
    assertEquals(1f, ((float[]) fakeIndex.get("short-last").get(SchemaFields.VECTOR))[1]);
    assertTrue(windowRequests.stream().allMatch(r -> r.startsWith("long-middle@")));
  }

  @Test
  @DisplayName("P4: real window failures still consume one retry and reach FAILED")
  void realWindowFailureStillReachesTheRetryLimit() {
    seedLateChunkedLongParent("bad-window");
    lenient().when(embeddingProvider.embedDocumentWindows(anyString(), anyInt(), anyInt()))
        .thenThrow(new IllegalArgumentException("bad window input"));
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    for (int attempt = 1; attempt <= SchemaFields.EMBEDDING_MAX_RETRIES; attempt++) {
      newCycle();
      CombinedEnrichmentBackfillOps.processCombinedBackfill(timedContext(progress, () -> false));
      assertEquals(Integer.toString(attempt),
          fakeIndex.get("bad-window").get(SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals(attempt == SchemaFields.EMBEDDING_MAX_RETRIES
              ? SchemaFields.EMBEDDING_STATUS_FAILED : SchemaFields.EMBEDDING_STATUS_PENDING,
          fakeIndex.get("bad-window").get(SchemaFields.EMBEDDING_STATUS));
      assertEquals(0, progress.trackedDocuments());
    }
    assertEquals(SchemaFields.EMBEDDING_MAX_RETRIES, singlePassRequests.get());
    newCycle();
    var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(
        timedContext(progress, () -> false));
    assertTrue(!outcome.recordTiming(), "a terminal failure leaves the pending population");
  }

}
