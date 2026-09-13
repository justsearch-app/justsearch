/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Round-15 post-round finding — long-document head-of-line blocking in the combined enrichment
 * backfill.
 *
 * <p>Field evidence (570-doc corpus, mean file ~47 KB, 67 files over 100 KB): the work-set line
 * {@code Combined backfill: docs=150 (embed=148,splade=48,chunks=50)} was byte-identical across 54
 * consecutive ~5 s cycles for 12+ minutes. Every cycle embedded 56-80 documents' worth of encoder
 * windows with {@code fail=0}, and every cycle {@code splade=0ms(ok=0)} and {@code ner=0ms(ok=0)}.
 * Two defects compounded:
 *
 * <ol>
 *   <li><b>No per-window resumption.</b> A document longer than the encoder's context window is
 *       embedded as N windows that are mean-pooled into one vector, and every path that produced
 *       that vector did all N windows inside one uninterruptible call, materialising the pooled
 *       result only at the very end. A document whose windowing could not finish inside one cycle
 *       therefore discarded every window it had computed and restarted at window 0 next cycle —
 *       forever.
 *   <li><b>No per-stage budget reservation.</b> Phases 3b/3c run after the embed stage and open by
 *       consulting the stop signal, so an embed stage permitted to spend the whole cycle budget
 *       makes SPLADE and NER structurally unrunnable.
 * </ol>
 *
 * <p>The scheduler's real levers (wall-clock deadlines) are replaced here by deterministic
 * ENCODER-UNIT counters — the tests must pin scheduling behaviour, not sleep through it. One
 * "unit" is exactly what the production code counts as one: one window slice, one SPLADE batch, one
 * NER document.
 *
 * <p>Each test states what it does against the pre-fix code:
 *
 * <ul>
 *   <li>{@link #longDocumentResumesAcrossCyclesInsteadOfRestarting()} — with a per-cycle budget
 *       smaller than one document's windowing, the pre-fix pass never completes a single document.
 *       Its control ({@link #longDocumentWithoutCrossCycleProgressNeverCompletes()}) reproduces
 *       exactly that, using the same fixture with a per-cycle accumulator.
 *   <li>{@link #embedStageCannotConsumeTheWholeCycleWhileSpladeAndNerHaveWork()} — its control
 *       ({@link #withoutTheReservationEmbedStarvesSpladeAndNer()}) is the field's
 *       {@code splade=0ms(ok=0), ner=0ms(ok=0)}.
 * </ul>
 */
@DisplayName("CombinedEnrichmentBackfillOps long-document scheduling (round-15 post-round finding)")
@ExtendWith(MockitoExtension.class)
class CombinedEnrichmentLongDocumentTest {

  /** Must mirror {@code CombinedEnrichmentBackfillOps.EMBED_WINDOW_SLICE}. */
  private static final int WINDOW_SLICE = 32;

  /** Windows per long document: 4 slices, i.e. 4 encoder units to finish one document. */
  private static final int WINDOWS_PER_LONG_DOC = 4 * WINDOW_SLICE;

  /**
   * Encoder units one simulated cycle may spend. Deliberately BELOW {@code WINDOWS_PER_LONG_DOC /
   * WINDOW_SLICE}: no document can be finished inside a single cycle, which is the field condition
   * and the only condition under which resumption is distinguishable from restarting.
   */
  private static final int UNITS_PER_CYCLE = 3;

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock SpladeEncoder spladeEncoder;
  @Mock NerService nerService;

  private final Map<String, Map<String, Object>> fakeIndex = new LinkedHashMap<>();
  private final Map<String, String> contentByDoc = new LinkedHashMap<>();

  /** Encoder units spent since the last {@link #newCycle()} — the simulated cycle budget. */
  private final AtomicInteger unitsThisCycle = new AtomicInteger();
  private final AtomicInteger singlePassRequests = new AtomicInteger();

  /** Every {@code (docId, fromWindow)} the encoder was asked for, in order. */
  private final List<String> windowRequests = new ArrayList<>();

  @BeforeEach
  void wireFakeIndex() throws Exception {
    lenient().when(embeddingProvider.isAvailable()).thenReturn(true);
    lenient().when(nerService.isAvailable()).thenReturn(true);
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
              return text.startsWith("LONG:") ? WINDOWS_PER_LONG_DOC : 1;
            });

    lenient()
        .when(embeddingProvider.embedDocumentWindows(anyString(), anyInt(), anyInt()))
        .thenAnswer(
            inv -> {
              String text = inv.getArgument(0);
              int from = inv.getArgument(1);
              int max = inv.getArgument(2);
              windowRequests.add(docIdOf(text) + "@" + from);
              unitsThisCycle.incrementAndGet();
              int count = Math.max(0, Math.min(max, WINDOWS_PER_LONG_DOC - from));
              List<float[]> vectors = new ArrayList<>(count);
              for (int i = 0; i < count; i++) {
                vectors.add(new float[] {1f, 0f});
              }
              return new EmbeddingProvider.WindowSlice(vectors, from, WINDOWS_PER_LONG_DOC);
            });

    lenient()
        .when(embeddingProvider.embedDocumentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              unitsThisCycle.incrementAndGet();
              List<float[]> out = new ArrayList<>(texts.size());
              for (int i = 0; i < texts.size(); i++) out.add(new float[] {1f, 0f});
              return out;
            });

    lenient()
        .when(embeddingProvider.embedWithSpans(anyString(), org.mockito.ArgumentMatchers.any(int[][].class)))
        .thenAnswer(
            inv -> {
              singlePassRequests.incrementAndGet();
              unitsThisCycle.incrementAndGet();
              return null;
            });

    lenient()
        .when(spladeEncoder.encodeBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              unitsThisCycle.incrementAndGet();
              List<Map<String, Float>> out = new ArrayList<>(texts.size());
              for (int i = 0; i < texts.size(); i++) out.add(Map.of("t", 1f));
              return out;
            });

    lenient()
        .when(nerService.extractEntitiesBatch(anyList()))
        .thenAnswer(
            inv -> {
              unitsThisCycle.incrementAndGet();
              return List.of(NerResult.EMPTY);
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

  private void seedSpladeAndNerPending(String docId) {
    Map<String, Object> state = fakeIndex.computeIfAbsent(docId, k -> new HashMap<>());
    state.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
    state.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING);
  }

  private void newCycle() {
    unitsThisCycle.set(0);
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

  /**
   * @param stopAfterUnits the cycle budget, in encoder units
   * @param embedShareUnits the embed stage's reserved share, in encoder units ({@link
   *     Integer#MAX_VALUE} = no reservation, i.e. the pre-fix behaviour)
   */
  private CombinedEnrichmentBackfillOps.BackfillContext context(
      WindowedEmbedProgress progress,
      boolean spladeEnabled,
      boolean nerEnabled,
      int stopAfterUnits,
      int embedShareUnits) {
    return context(
        progress,
        spladeEnabled,
        nerEnabled,
        stopAfterUnits,
        embedShareUnits,
        false,
        () -> false);
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      WindowedEmbedProgress progress,
      boolean spladeEnabled,
      boolean nerEnabled,
      int stopAfterUnits,
      int embedShareUnits,
      boolean lateChunkingEnabled,
      BooleanSupplier hardStop) {
    BooleanSupplier stop = () -> unitsThisCycle.get() >= stopAfterUnits;
    BooleanSupplier embedShare = () -> unitsThisCycle.get() >= embedShareUnits;
    return new CombinedEnrichmentBackfillOps.BackfillContext(
        documentFieldOps,
        indexingCoordinator,
        commitOps,
        IndexingPacing.unthrottled(),
        () -> embeddingProvider,
        spladeEnabled ? () -> spladeEncoder : () -> null,
        nerEnabled ? () -> nerService : () -> null,
        () -> true,
        () -> true,
        100,
        LoggerFactory.getLogger(CombinedEnrichmentLongDocumentTest.class),
        false,
        false,
        lateChunkingEnabled,
        0,
        new ArrayDeque<>(),
        new ArrayDeque<>(),
        new int[] {0},
        stop,
        hardStop,
        embedShare,
        progress);
  }

  @Test
  @DisplayName("late-chunk null fallback records one real window, then resumes without another probe")
  void lateChunkNullFallbackRecordsAndResumesAWindowAfterTheProbeSpentTheDeadline() {
    seedLateChunkedLongParent("long-fallback");
    WindowedEmbedProgress progress = new WindowedEmbedProgress();

    newCycle();
    CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(progress, false, false, 1, Integer.MAX_VALUE, true, () -> false));

    assertEquals(1, singlePassRequests.get(), "the first cycle classifies through single-pass once");
    assertEquals(List.of("long-fallback@0"), windowRequests);
    assertEquals(
        WINDOW_SLICE,
        progress.nextWindow("long-fallback", "LONG:long-fallback"),
        "the deadline spent by the probe must not prevent the first resumable window");

    newCycle();
    CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(progress, false, false, 1, Integer.MAX_VALUE, true, () -> false));

    assertEquals(1, singlePassRequests.get(), "a matching partial must bypass single-pass later");
    assertEquals(List.of("long-fallback@0", "long-fallback@32"), windowRequests);
    verify(embeddingProvider, never()).documentWindowCount("LONG:long-fallback");
  }

  @Test
  @DisplayName("hard preemption after a late-chunk probe still prevents the fallback window")
  void hardPreemptionAfterProbeIsNotHiddenByTheWindowFloor() {
    seedLateChunkedLongParent("long-hard-stop");
    WindowedEmbedProgress progress = new WindowedEmbedProgress();

    newCycle();
    CombinedEnrichmentBackfillOps.CombinedOutcome outcome =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            context(
                progress,
                false,
                false,
                1,
                Integer.MAX_VALUE,
                true,
                () -> unitsThisCycle.get() >= 1));

    assertTrue(outcome.aborted(), "a hard stop must still abort after the classification probe");
    assertTrue(windowRequests.isEmpty(), "shutdown/yield/ingest preemption must prevent new GPU work");
    assertEquals(0, progress.trackedDocuments());
  }

  // ------------------------------------------------------------------------------------------
  // Fix 1 — per-window progress survives the cycle boundary
  // ------------------------------------------------------------------------------------------

  /**
   * PINS: a long document resumes at its next window in a later cycle, so the work-set drains.
   *
   * <p>Three documents, each needing 4 encoder units, against a 3-unit cycle budget. No document
   * can be finished by a single cycle, so under the pre-fix "all windows or nothing" contract the
   * pending set is pinned at 3 forever — which is precisely what the field showed. The load-bearing
   * assertion is not merely that the set drains but that <b>no document is ever asked for window 0
   * twice</b>: that distinguishes "completed incrementally" from "completed eventually because the
   * budget happened to be generous enough on some later cycle".
   */
  @Test
  @DisplayName("a long document resumes at its next window across cycles and the work-set drains")
  void longDocumentResumesAcrossCyclesInsteadOfRestarting() {
    seedLongEmbedDoc("long-a");
    seedLongEmbedDoc("long-b");
    seedLongEmbedDoc("long-c");
    // A second stage with work, so the run also exercises the reservation path rather than a
    // synthetic embed-only shape.
    seedSpladeAndNerPending("long-a");

    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    List<Integer> pendingPerCycle = new ArrayList<>();
    pendingPerCycle.add(pendingEmbedDocs());

    for (int cycle = 0; cycle < 8 && pendingEmbedDocs() > 0; cycle++) {
      newCycle();
      CombinedEnrichmentBackfillOps.processCombinedBackfill(
          context(progress, true, true, UNITS_PER_CYCLE, Integer.MAX_VALUE));
      pendingPerCycle.add(pendingEmbedDocs());
    }

    assertEquals(
        0,
        pendingEmbedDocs(),
        "the long-document embed backlog must drain. Observed pending-per-cycle "
            + pendingPerCycle
            + " — a constant sequence is the field incident (54 identical cycles, nothing"
            + " completing)");

    for (int i = 1; i < pendingPerCycle.size(); i++) {
      assertTrue(
          pendingPerCycle.get(i) <= pendingPerCycle.get(i - 1),
          "the pending work-set must never grow: " + pendingPerCycle);
    }
    assertTrue(
        pendingPerCycle.get(pendingPerCycle.size() - 1) < pendingPerCycle.get(0),
        "the pending work-set must strictly decrease over the run: " + pendingPerCycle);

    for (String docId : List.of("long-a", "long-b", "long-c")) {
      long restarts = windowRequests.stream().filter(r -> r.equals(docId + "@0")).count();
      assertEquals(
          1,
          restarts,
          docId
              + " was embedded from window 0 "
              + restarts
              + " times. More than once means the document RESTARTED rather than resumed — the"
              + " defect itself, even though the run happened to finish. Requests: "
              + windowRequests);
    }
    assertEquals(
        0,
        progress.trackedDocuments(),
        "a completed document must release its accumulator entry");
  }

  /**
   * CONTROL (red against the fix's absence): the same fixture with progress that does NOT survive
   * the cycle boundary — the pre-fix contract, where a document's windows exist only for the
   * duration of one call.
   *
   * <p>This is the field incident reproduced deterministically: encoder work every cycle, zero
   * documents completing, the pending set frozen. If this test ever goes green, the fix's premise
   * (that resumption is what drains the head, not luck) is wrong.
   */
  @Test
  @DisplayName("CONTROL: without cross-cycle window progress the same documents never complete")
  void longDocumentWithoutCrossCycleProgressNeverCompletes() {
    seedLongEmbedDoc("long-a");
    seedLongEmbedDoc("long-b");
    seedLongEmbedDoc("long-c");

    for (int cycle = 0; cycle < 8; cycle++) {
      newCycle();
      CombinedEnrichmentBackfillOps.processCombinedBackfill(
          // A FRESH accumulator per cycle == no cross-cycle memory == the pre-fix behaviour.
          context(new WindowedEmbedProgress(), false, true, UNITS_PER_CYCLE, Integer.MAX_VALUE));
    }

    assertEquals(
        3,
        pendingEmbedDocs(),
        "without cross-cycle window progress no document can finish, since one document needs more"
            + " encoder units than a cycle has. A drain here would mean the fixture is not"
            + " reproducing the field condition and the sibling test proves nothing");
    assertTrue(
        windowRequests.stream().filter(r -> r.equals("long-a@0")).count() > 1,
        "the pre-fix shape re-embeds the head document from window 0 every cycle; observed "
            + windowRequests);
  }

  // ------------------------------------------------------------------------------------------
  // Fix 2 — the embed stage cannot consume the whole cycle
  // ------------------------------------------------------------------------------------------

  /**
   * PINS: SPLADE and NER get scheduled while a long-document embed backlog exists.
   *
   * <p>The embed backlog here is larger than the whole cycle budget, so without a reservation the
   * embed stage reaches the stop signal first and both later phases open with it already true —
   * {@code splade=0ms(ok=0)}, {@code ner=0ms(ok=0)}, every cycle, which is what pinned SPLADE at
   * 1.05% coverage for the duration of the stall.
   */
  @Test
  @DisplayName("embed yields its reserved share so SPLADE and NER get non-zero work")
  void embedStageCannotConsumeTheWholeCycleWhileSpladeAndNerHaveWork() {
    for (int i = 0; i < 4; i++) {
      seedLongEmbedDoc("long-" + i);
      seedSpladeAndNerPending("long-" + i);
    }

    newCycle();
    CombinedEnrichmentBackfillOps.CombinedOutcome outcome =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            // Cycle budget 10 units; embed reserved 2 — the rest belongs to the other stages.
            context(new WindowedEmbedProgress(), true, true, 10, 2));

    assertTrue(
        outcome.spladeProcessed() > 0,
        "SPLADE must receive scheduling time while a long-document embed backlog exists; observed"
            + " spladeProcessed="
            + outcome.spladeProcessed());
    assertTrue(
        outcome.nerProcessed() > 0,
        "NER must receive scheduling time while a long-document embed backlog exists; observed"
            + " nerProcessed="
            + outcome.nerProcessed());
    assertTrue(
        outcome.wroteAnything(),
        "the batch must still write what it enriched before yielding the embed share");
  }

  /**
   * CONTROL (red against the fix's absence): with no reservation the embed stage spends the whole
   * cycle and both later stages measure zero — the field's per-cycle log line verbatim.
   */
  @Test
  @DisplayName("CONTROL: without the reservation embed consumes the cycle and starves SPLADE/NER")
  void withoutTheReservationEmbedStarvesSpladeAndNer() {
    for (int i = 0; i < 4; i++) {
      seedLongEmbedDoc("long-" + i);
      seedSpladeAndNerPending("long-" + i);
    }

    newCycle();
    CombinedEnrichmentBackfillOps.CombinedOutcome outcome =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            context(new WindowedEmbedProgress(), true, true, 10, Integer.MAX_VALUE));

    assertEquals(
        0,
        outcome.spladeProcessed(),
        "this control must reproduce the starvation; a non-zero value means the fixture no longer"
            + " creates an embed backlog that outlives the cycle and the sibling test is vacuous");
    assertEquals(
        0,
        outcome.nerProcessed(),
        "this control must reproduce the starvation (ner=0ms(ok=0) in the field log)");
  }

  /**
   * PINS: the reservation does NOT apply to a pure-embed backlog.
   *
   * <p>A wrong-gate here would be silent and expensive: handing 40% of every cycle to stages with
   * nothing to do would slow the very backlog this work exists to drain, and no test of the
   * starvation case would notice.
   */
  @Test
  @DisplayName("an embed-only backlog keeps the whole cycle — the reservation does not misfire")
  void embedOnlyBacklogIsNotThrottledByTheReservation() {
    seedLongEmbedDoc("long-a");
    seedLongEmbedDoc("long-b");

    newCycle();
    // Share exhausted from the very first unit; with no other stage pending it must be ignored.
    CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(new WindowedEmbedProgress(), false, false, 6, 0));

    assertTrue(
        unitsThisCycle.get() >= 4,
        "an embed-only backlog must be allowed to use the whole cycle budget; the embed stage spent"
            + " only "
            + unitsThisCycle.get()
            + " of 6 units, so the reservation fired with no stage to reserve for");
  }

  /** PINS: a document assembled window-by-window gets the same vector one pass would produce. */
  @Test
  @DisplayName("windows accumulated across cycles pool to the same unit vector")
  void resumedWindowsPoolToTheSameVector() {
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    String content = "LONG:doc";
    List<float[]> firstHalf = List.of(new float[] {3f, 4f}, new float[] {3f, 4f});
    List<float[]> secondHalf = List.of(new float[] {0f, 1f});

    assertEquals(0, progress.nextWindow("doc", content));
    assertEquals(2, progress.record("doc", content, 3, 0, firstHalf));
    assertTrue(!progress.isComplete("doc"), "two of three windows is not complete");
    assertEquals(3, progress.record("doc", content, 3, 2, secondHalf));
    assertTrue(progress.isComplete("doc"));

    float[] pooled = progress.complete("doc");
    // mean = (2, 3); L2-normalized = (2, 3) / sqrt(13)
    double norm = Math.sqrt(13.0);
    assertEquals(2.0 / norm, pooled[0], 1e-5);
    assertEquals(3.0 / norm, pooled[1], 1e-5);
    assertEquals(1, progress.trackedDocuments(), "pooling alone must retain the entry until RMW");
    assertEquals(3, progress.nextWindow("doc", content));
    progress.forget("doc");
    assertEquals(0, progress.trackedDocuments(), "successful RMW owns accumulator release");
  }

  /** PINS: a partial belonging to different content is discarded, never blended. */
  @Test
  @DisplayName("changed content invalidates a partial instead of blending two revisions")
  void changedContentDiscardsThePartial() {
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    progress.record("doc", "LONG:v1", 4, 0, List.of(new float[] {1f, 0f}));
    assertEquals(1, progress.nextWindow("doc", "LONG:v1"));
    assertEquals(
        0,
        progress.nextWindow("doc", "LONG:v2"),
        "a partial computed from other content must not be resumed into a new revision's vector");

    // Same LENGTH, different bytes: a document edited in place is the realistic case, and a
    // fingerprint that only counted length would silently blend the two revisions' windows.
    progress.record("doc2", "LONG:aaa", 4, 0, List.of(new float[] {1f, 0f}));
    assertEquals(
        0,
        progress.nextWindow("doc2", "LONG:bbb"),
        "a same-length edit must invalidate the partial too");
  }

  /**
   * PINS: a slice that does not start at the tracked resume point is ignored.
   *
   * <p>Accepting it would double-count windows into the running sum — a vector that is silently
   * wrong, indexes cleanly, and is undetectable from any status field.
   */
  @Test
  @DisplayName("an out-of-order slice is rejected rather than double-counted")
  void outOfOrderSliceIsRejected() {
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    String content = "LONG:doc";
    assertEquals(1, progress.record("doc", content, 3, 0, List.of(new float[] {1f, 0f})));
    assertEquals(
        1,
        progress.record("doc", content, 3, 0, List.of(new float[] {1f, 0f})),
        "replaying window 0 must not advance the resume point or the sum");
    assertEquals(
        1,
        progress.record("doc", content, 3, 2, List.of(new float[] {1f, 0f})),
        "skipping ahead of the resume point must be refused, not accepted with a gap");
  }

  /** PINS: in-flight partials are observable and are dropped by a profiling reset. */
  @Test
  @DisplayName("in-flight partials are tracked and cleared")
  void partialsAreTrackedAndCleared() {
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    assertEquals(0, progress.trackedDocuments());
    progress.record("doc-a", "LONG:a", 4, 0, List.of(new float[] {1f, 0f}));
    progress.record("doc-b", "LONG:b", 4, 0, List.of(new float[] {1f, 0f}));
    assertEquals(2, progress.trackedDocuments(), "two documents are mid-windowing");

    progress.forget("doc-a");
    assertEquals(1, progress.trackedDocuments());
    progress.clear();
    assertEquals(0, progress.trackedDocuments(), "a reset must drop every partial");
  }
}
