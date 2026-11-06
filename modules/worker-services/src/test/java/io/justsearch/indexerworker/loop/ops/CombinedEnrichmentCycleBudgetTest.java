/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
  @Mock EmbeddingService embeddingProvider;

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
  private Supplier<EmbeddingProvider> providerSupplier = () -> embeddingProvider;

  private final List<List<String>> batchRequests = new ArrayList<>();

  /** Every {@code (docId, fromWindow)} the encoder was asked for, in order. */
  private final List<String> windowRequests = new ArrayList<>();

  @BeforeEach
  void wireFakeIndex() throws Exception {
    BackfillSnapshotTestSupport.installLaneFixtures(documentFieldOps,indexingCoordinator);
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



  private void seedChunks(int count) {
    for (int i = 0; i < count; i++) {
      String id = "chunk-" + String.format(java.util.Locale.ROOT,"%02d",i);
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

  private LaneBackfillTestSupport.BackfillContext timedContext(
      WindowedEmbedProgress progress, BooleanSupplier hardStop) {
    probeCostMs = 80;
    windowCostMs = 270;
    batchCostMs = 70;
    return new LaneBackfillTestSupport.BackfillContext(documentFieldOps,
        indexingCoordinator,
        IndexingPacing.unthrottled(),
        providerSupplier,
        () -> null,
        () -> null,
        () -> true,
        () -> true,
        100,
        LoggerFactory.getLogger(CombinedEnrichmentCycleBudgetTest.class),
        true,
        false,
        true,
        50,
        () -> elapsedMs.get() >= 5_000 || hardStop.getAsBoolean(),
        hardStop,
        progress);
  }








  @Test
  @DisplayName("the chunk-only tail completes on ordinary dense units")
  void chunksAdvanceDuringResumableParentBacklogAndThroughTheChunkOnlyTail() {
    seedChunks(50);
    newCycle();
    int windowsBefore = windowRequests.size();
    LaneBackfillTestSupport.runLanes(timedContext(new WindowedEmbedProgress(), () -> false));
    assertEquals(50, completed(SchemaFields.CHUNK_EMBEDDING_STATUS),
        "the chunk-only tail must finish on the ordinary batch path");
    assertEquals(windowsBefore, windowRequests.size());
    assertEquals(0, singlePassRequests.get());
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
    var outcome = LaneBackfillTestSupport.runLanes(
        timedContext(progress, () -> !bulkDelete && unitsThisCycle.get() >= stopAfterUnits));
    assertTrue(outcome.aborted());
    assertEquals(stopAfterUnits, unitsThisCycle.get(),
        "P1: a synchronous stop signal must prevent the next encoder call");
    assertEquals(0, completed(SchemaFields.EMBEDDING_STATUS), "no partial parent publication");
    for (var state : fakeIndex.values()) {
      assertTrue(!state.containsKey(SchemaFields.VECTOR));
      assertTrue(!state.containsKey(SchemaFields.EMBEDDING_RETRY_COUNT));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"systemic", "hard-stop", "bulk-delete"})
  @DisplayName("R1/R2/R3: a failed priority chunk turn cannot restart embedding in the parent turn")
  void failedPriorityChunkBatchStopsEmbeddingAcrossTurns(String failureKind) {
    seedLateChunkedLongParent("new-parent");
    seedLongEmbedDoc("window-parent");
    seedChunks(16);
    if (failureKind.equals("bulk-delete")) {
      lenient().when(indexingCoordinator.bulkDeleteEpoch()).thenAnswer(
          inv -> unitsThisCycle.get() > 0 ? 1L : 0L);
    }
    lenient().doAnswer(inv -> {
      batchRequests.add(List.copyOf(inv.getArgument(0)));
      unitsThisCycle.incrementAndGet();
      throw new CancellationException("encoder session cancelled");
    }).when(embeddingProvider).embedDocumentBatch(anyList());
    newCycle();
    var outcome = LaneBackfillTestSupport.runLanes(
        LaneBackfillTestSupport.onlyStage(timedContext(new WindowedEmbedProgress(),
            () -> failureKind.equals("hard-stop") && unitsThisCycle.get() > 0), EnrichmentAccounting.Stage.CHUNK_EMBED));
    assertEquals(0, singlePassRequests.get(),
        "P1: a failed priority chunk turn must not start a parent single-pass call");
    assertTrue(windowRequests.isEmpty(),
        "P1: a failed priority chunk turn must not start a parent window call");
    assertEquals(1, batchRequests.size(),
        "P1: the abandoned priority chunk batch must not be retried in the same cycle");
    assertEquals(8, batchRequests.getFirst().size(),
        "S3: the priority turn is at most one encoder batch");
    org.mockito.Mockito.verify(embeddingProvider, org.mockito.Mockito.never())
        .embedDocument(anyString(), org.mockito.ArgumentMatchers.any());
    assertEquals(1, unitsThisCycle.get(), "P1: only the throwing batch may run");
    assertEquals(!failureKind.equals("systemic"), outcome.aborted(),
        "R1/R2/R3: only a stop signal or deletion marks the batch aborted");
    assertEquals(0, outcome.embedProcessed());
    assertEquals(0, completed(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertEquals(0, completed(SchemaFields.EMBEDDING_STATUS));
    for (var state : fakeIndex.values()) {
      assertTrue(!state.containsKey(SchemaFields.VECTOR));
      assertTrue(!state.containsKey(SchemaFields.CHUNK_VECTOR));
      assertTrue(!state.containsKey(SchemaFields.EMBEDDING_RETRY_COUNT));
      assertTrue(!state.containsKey(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
    }
  }

  @Test
  @DisplayName("P4/P10: non-systemic priority batch failures retain individual retry accounting")
  void nonSystemicPriorityBatchFailureStillChargesIndividualRetries() {
    seedLateChunkedLongParent("short-parent");
    contentByDoc.put("short-parent", "SHORT:short-parent");
    seedChunks(16);
    lenient().doAnswer(inv -> {
      batchRequests.add(List.copyOf(inv.getArgument(0)));
      unitsThisCycle.incrementAndGet();
      throw new IllegalArgumentException("bad batch input");
    }).when(embeddingProvider).embedDocumentBatch(anyList());
    lenient().doAnswer(inv -> {
      singlePassRequests.incrementAndGet();
      unitsThisCycle.incrementAndGet();
      return new EmbeddingService.ChunkedEmbedding(new float[] {0f, 1f}, List.of(), 1);
    }).when(embeddingProvider).embedWithSpans(
        anyString(), org.mockito.ArgumentMatchers.any(int[][].class));
    newCycle();
    var outcome = LaneBackfillTestSupport.runLanes(
        timedContext(new WindowedEmbedProgress(), () -> false));
    assertEquals(1, outcome.embedProcessed(), "P4: parent work still completes after a bad batch");
    assertEquals(1, singlePassRequests.get());
    assertEquals(2, batchRequests.size(), "each chunk batch is attempted only once");
    for (int i = 0; i < 16; i++) {
      var state = fakeIndex.get("chunk-" + String.format(java.util.Locale.ROOT,"%02d",i));
      assertEquals("1", state.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT),
          "P4/P10: each failed individual fallback charges exactly one retry");
      assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING,
          state.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertTrue(!state.containsKey(SchemaFields.CHUNK_VECTOR));
    }
  }

  @Test
  @DisplayName("R4: successful individual fallbacks allow the parent turn")
  void successfulPriorityFallbacksAllowParentTurn() {
    seedLateChunkedLongParent("parent");
    seedChunks(8);
    lenient().doThrow(new IllegalArgumentException("bad batch"))
        .when(embeddingProvider).embedDocumentBatch(anyList());
    lenient().when(embeddingProvider.embedDocument(anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new float[] {1f, 0f});
    var outcome = LaneBackfillTestSupport.runLanes(
        timedContext(new WindowedEmbedProgress(), () -> false));
    assertEquals(8, completed(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertEquals(1, completed(SchemaFields.EMBEDDING_STATUS));
    assertEquals(1, singlePassRequests.get(), "R4: normal fallback exit must start the parent turn");
    assertTrue(!outcome.aborted());
  }

  private void cancelSecondFallbackOnDeletion() {
    AtomicLong epoch = new AtomicLong();
    AtomicInteger individuals = new AtomicInteger();
    lenient().when(indexingCoordinator.bulkDeleteEpoch()).thenAnswer(inv -> epoch.get());
    lenient().doAnswer(inv -> {
      batchRequests.add(List.copyOf(inv.getArgument(0)));
      throw new IllegalArgumentException("bad batch");
    }).when(embeddingProvider).embedDocumentBatch(anyList());
    lenient().when(embeddingProvider.embedDocument(anyString(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> {
          if (individuals.incrementAndGet() == 2) {
            epoch.incrementAndGet();
            throw new CancellationException("deletion cancelled encoder");
          }
          return new float[] {1f, 0f};
        });
  }

  @Test
  @DisplayName("R5: later priority fallback cancellation preserves success and reports abort")
  void priorityFallbackCancellationReportsAbort() {
    seedLateChunkedLongParent("parent");
    seedChunks(8);
    cancelSecondFallbackOnDeletion();
    var outcome = LaneBackfillTestSupport.runLanes(
        timedContext(new WindowedEmbedProgress(), () -> false));
    assertEquals(1, completed(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertTrue(outcome.aborted(), "R5: epoch change after a later fallback must abort the cycle");
    assertEquals(1, batchRequests.size());
    assertEquals(0, singlePassRequests.get());
    assertTrue(windowRequests.isEmpty());
  }

  @Test
  @DisplayName("R6: ordinary remainder retains main's fallback cancellation stop accounting")
  void ordinaryFallbackCancellationMatchesMain() {
    for (int i = 0; i < 24; i++) {
      seedLongEmbedDoc("short-" + i);
      contentByDoc.put("short-" + i, "SHORT:" + i);
    }
    cancelSecondFallbackOnDeletion();
    var outcome = LaneBackfillTestSupport.runLanes(
        timedContext(new WindowedEmbedProgress(), () -> false));
    assertEquals(1, completed(SchemaFields.EMBEDDING_STATUS));
    assertTrue(outcome.aborted(), "R6: main's next-slice stop check must still run");
    assertEquals(1, batchRequests.size());
  }

  @ParameterizedTest
  @CsvSource({"single,hard-stop", "single,bulk-delete", "single,systemic",
      "window,hard-stop", "window,bulk-delete", "window,systemic"})
  @DisplayName("R7: parent single-pass/window stops retain main's outcomes")
  void parentStopsMatchMain(String route, String event) {
    for (int i = 0; i < 2; i++) {
      if (route.equals("single")) seedLateChunkedLongParent("parent-" + i);
      else seedLongEmbedDoc("parent-" + i);
    }
    AtomicInteger calls = new AtomicInteger();
    if (event.equals("bulk-delete")) {
      lenient().when(indexingCoordinator.bulkDeleteEpoch())
          .thenAnswer(inv -> calls.get() > 0 ? 1L : 0L);
    }
    if (route.equals("single")) {
      lenient().when(embeddingProvider.embedWithSpans(
          anyString(), org.mockito.ArgumentMatchers.any(int[][].class))).thenAnswer(inv -> {
            calls.incrementAndGet();
            if (event.equals("systemic")) throw new CancellationException("cancelled");
            return null;
          });
    } else {
      lenient().when(embeddingProvider.embedDocumentWindows(anyString(), anyInt(), anyInt()))
          .thenAnswer(inv -> {
            calls.incrementAndGet();
            if (event.equals("systemic")) throw new CancellationException("cancelled");
            return new EmbeddingProvider.WindowSlice(
                java.util.Collections.nCopies(WINDOW_SLICE, new float[] {1f, 0f}), 0,
                WINDOWS_PER_LONG_DOC);
          });
    }
    var outcome = LaneBackfillTestSupport.runLanes(timedContext(
        new WindowedEmbedProgress(), () -> event.equals("hard-stop") && calls.get() > 0));
    assertEquals(1, calls.get(), "R7: no encoder call after parent stop");
    assertEquals(!event.equals("systemic"), outcome.aborted());
    assertEquals(0, outcome.embedProcessed());
    assertEquals(0, completed(SchemaFields.EMBEDDING_STATUS));
    assertTrue(batchRequests.isEmpty());
    for (var state : fakeIndex.values()) {
      assertTrue(!state.containsKey(SchemaFields.EMBEDDING_RETRY_COUNT));
      assertTrue(!state.containsKey(SchemaFields.VECTOR));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("R8: provider retirement/replacement in the priority turn prevents parent work")
  void changedProviderStopsAtTurnBoundary(boolean replaced) {
    seedLateChunkedLongParent("parent");
    seedChunks(8);
    var replacement = mock(EmbeddingProvider.class);
    AtomicReference<EmbeddingProvider> current = new AtomicReference<>(embeddingProvider);
    providerSupplier = current::get;
    lenient().doAnswer(inv -> {
      batchRequests.add(List.copyOf(inv.getArgument(0)));
      if (replaced) current.set(replacement);
      else lenient().when(embeddingProvider.isAvailable()).thenReturn(false);
      return java.util.Collections.nCopies(8, new float[] {1f, 0f});
    }).when(embeddingProvider).embedDocumentBatch(anyList());
    var outcome = LaneBackfillTestSupport.runLanes(
        timedContext(new WindowedEmbedProgress(), () -> false));
    assertEquals(0, singlePassRequests.get(), "R8: retired provider cannot start parent work");
    assertTrue(windowRequests.isEmpty());
    assertEquals(1, batchRequests.size());
    assertEquals(0, outcome.embedProcessed());
    assertTrue(!outcome.aborted(), "R8: provider retirement alone is not a stop signal");
    org.mockito.Mockito.verify(replacement, org.mockito.Mockito.never()).embedDocumentBatch(anyList());
    org.mockito.Mockito.verify(replacement, org.mockito.Mockito.never())
        .embedWithSpans(anyString(), org.mockito.ArgumentMatchers.any(int[][].class));
    org.mockito.Mockito.verify(replacement, org.mockito.Mockito.never())
        .embedDocumentWindows(anyString(), anyInt(), anyInt());
    org.mockito.Mockito.verify(replacement, org.mockito.Mockito.never())
        .embedDocument(anyString(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(replacement, org.mockito.Mockito.never()).embedDocument(anyString());
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
    LaneBackfillTestSupport.runLanes(timedContext(progress, () -> false));
    newCycle();
    LaneBackfillTestSupport.runLanes(timedContext(progress, () -> false));
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
      LaneBackfillTestSupport.runLanes(timedContext(progress, () -> false));
      assertEquals(Integer.toString(attempt),
          fakeIndex.get("bad-window").get(SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals(attempt == SchemaFields.EMBEDDING_MAX_RETRIES
              ? SchemaFields.EMBEDDING_STATUS_FAILED : SchemaFields.EMBEDDING_STATUS_PENDING,
          fakeIndex.get("bad-window").get(SchemaFields.EMBEDDING_STATUS));
      assertEquals(0, progress.trackedDocuments());
    }
    assertEquals(SchemaFields.EMBEDDING_MAX_RETRIES, singlePassRequests.get());
    newCycle();
    var outcome = LaneBackfillTestSupport.runLanes(
        timedContext(progress, () -> false));
    assertTrue(!outcome.attempted(), "a terminal failure leaves the pending population");

  }

  @Test
  @DisplayName("R9: normal turn order and embedding budget stay unchanged")
  void normalEncoderSequenceMatchesRoundOne() {
    windowsPerLongDoc = 42;
    seedLateChunkedLongParent("parent");
    seedLongEmbedDoc("short");
    contentByDoc.put("short", "SHORT:short");
    seedChunks(16);
    var outcome = LaneBackfillTestSupport.runLanes(
        timedContext(new WindowedEmbedProgress(), () -> false));
    assertEquals(830, elapsedMs.get(), "R9: encoder work consumes the same modeled budget");
    assertEquals(18, outcome.embedProcessed());
    assertTrue(!outcome.aborted());
  }

}
