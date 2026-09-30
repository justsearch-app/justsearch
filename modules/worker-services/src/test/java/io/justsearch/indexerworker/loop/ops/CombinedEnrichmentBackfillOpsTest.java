/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OrtException;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Regression coverage for tempdoc 700: {@code CombinedEnrichmentBackfillOps} had no failure
 * escalation/backoff — an embedding/SPLADE/NER failure reset (or left) the doc's status PENDING
 * with no retry-count increment, so a deterministically-failing doc retried forever instead of
 * reaching {@code FAILED} at {@code *_MAX_RETRIES}, unlike its {@code EmbeddingBackfillOps} /
 * {@code SpladeBackfillOps} / {@code NerBackfillOps} siblings.
 *
 * <p>{@code DocumentFieldOps} / {@code IndexingCoordinator} are backed by an in-memory {@code
 * fakeIndex} map rather than static Mockito stubs: escalation is only observable *across* repeated
 * {@code processCombinedBackfill} cycles (retry count N feeds cycle N+1's read), so a fixed stub
 * would give a false green regardless of whether escalation actually happened
 * (unreachable-seed-green, see agent-lessons.md). This mirrors how {@code
 * EmbeddingBackfillOpsTest} builds its fixtures, adapted for the combined path's batched
 * pre-fetch + single-flush design.
 */
@DisplayName("CombinedEnrichmentBackfillOps")
@ExtendWith(MockitoExtension.class)
class CombinedEnrichmentBackfillOpsTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock SpladeEncoder spladeEncoder;
  @Mock NerService nerService;

  // LinkedHashMap: queryDocIdsByField below iterates this map, and the SPLADE partial-write
  // test depends on doc-a being requested (and thus indexed) before doc-b — a plain HashMap's
  // iteration order is not guaranteed to match seed order.
  private final Map<String, Map<String, Object>> fakeIndex = new java.util.LinkedHashMap<>();
  private final Map<String, String> contentByDoc = new HashMap<>();
  private final Set<String> poisonContents = new HashSet<>();

  @BeforeEach
  void wireFakeIndexAndDefaults() {
    lenient().when(embeddingProvider.isAvailable()).thenReturn(true);
    lenient().when(nerService.isAvailable()).thenReturn(true);

    lenient()
        .when(documentFieldOps.getDocumentContentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              Map<String, String> m = new HashMap<>();
              for (String id : ids) {
                String c = contentByDoc.get(id);
                if (c != null) {
                  m.put(id, c);
                  continue;
                }
                Map<String, Object> state = fakeIndex.getOrDefault(id, Map.of());
                if (!"true".equals(state.get(SchemaFields.IS_CHUNK))) continue;
                String parentId = (String) state.get(SchemaFields.PARENT_DOC_ID);
                String parentContent = contentByDoc.get(parentId);
                if (parentContent == null) continue;
                int start =
                    Integer.parseInt((String) state.get(SchemaFields.CHUNK_START_CHAR));
                int end = Integer.parseInt((String) state.get(SchemaFields.CHUNK_END_CHAR));
                if (start >= 0 && end >= start && end <= parentContent.length()) {
                  m.put(id, parentContent.substring(start, end));
                }
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
        .when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(inv -> matchingDocIds(inv.getArgument(0), inv.getArgument(1), false));

    lenient()
        .when(documentFieldOps.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(inv -> matchingDocIds(inv.getArgument(0), inv.getArgument(1), true));

    lenient()
        .when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<Map.Entry<String, Map<String, Object>>> batch = inv.getArgument(0);
              int count = 0;
              for (var entry : batch) {
                fakeIndex
                    .computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                    .putAll(entry.getValue());
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
              for (String t : texts) {
                out.add(poisonContents.contains(t) ? null : new float[] {1f, 2f});
              }
              return out;
            });
  }

  /** Mirrors the two selection queries: term match, optionally with chunk docs excluded. */
  private List<String> matchingDocIds(String field, String value, boolean excludeChunks) {
    List<String> matches = new ArrayList<>();
    for (var e : fakeIndex.entrySet()) {
      if (!value.equals(e.getValue().get(field))) continue;
      if (excludeChunks && "true".equals(e.getValue().get(SchemaFields.IS_CHUNK))) continue;
      matches.add(e.getKey());
    }
    return matches;
  }

  private void seedDoc(String docId, String content, Map<String, String> statusFields) {
    contentByDoc.put(docId, content);
    Map<String, Object> state = fakeIndex.computeIfAbsent(docId, k -> new HashMap<>());
    state.putAll(statusFields);
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      boolean embedEnabled, boolean spladeEnabled, boolean nerEnabled) {
    return context(embedEnabled, spladeEnabled, nerEnabled, false);
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      boolean embedEnabled, boolean spladeEnabled, boolean nerEnabled, boolean lateChunkingEnabled) {
    return context(embedEnabled, spladeEnabled, nerEnabled, lateChunkingEnabled, false, false);
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      boolean embedEnabled,
      boolean spladeEnabled,
      boolean nerEnabled,
      boolean lateChunkingEnabled,
      boolean chunkVectorsEnabled,
      boolean chunkSpladeEnabled) {
    return context(
        embedEnabled,
        spladeEnabled,
        nerEnabled,
        lateChunkingEnabled,
        chunkVectorsEnabled,
        chunkSpladeEnabled,
        () -> false,
        () -> false,
        new WindowedEmbedProgress());
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      boolean embedEnabled,
      boolean spladeEnabled,
      boolean nerEnabled,
      boolean lateChunkingEnabled,
      boolean chunkVectorsEnabled,
      boolean chunkSpladeEnabled,
      java.util.function.BooleanSupplier stopRequested) {
    return context(
        embedEnabled,
        spladeEnabled,
        nerEnabled,
        lateChunkingEnabled,
        chunkVectorsEnabled,
        chunkSpladeEnabled,
        stopRequested,
        () -> false,
        new WindowedEmbedProgress());
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      boolean embedEnabled,
      boolean spladeEnabled,
      boolean nerEnabled,
      boolean lateChunkingEnabled,
      boolean chunkVectorsEnabled,
      boolean chunkSpladeEnabled,
      java.util.function.BooleanSupplier stopRequested,
      java.util.function.BooleanSupplier embedShareSpent,
      WindowedEmbedProgress progress) {
    return new CombinedEnrichmentBackfillOps.BackfillContext(
        documentFieldOps,
        indexingCoordinator,
        commitOps,
        IndexingPacing.unthrottled(),
        embedEnabled ? () -> embeddingProvider : () -> null,
        spladeEnabled ? () -> spladeEncoder : () -> null,
        nerEnabled ? () -> nerService : () -> null,
        () -> true,
        () -> true,
        100,
        LoggerFactory.getLogger(CombinedEnrichmentBackfillOpsTest.class),
        chunkVectorsEnabled,
        chunkSpladeEnabled,
        lateChunkingEnabled,
        50,
        new ArrayDeque<>(),
        new ArrayDeque<>(),
        new int[] {0},
        stopRequested,
        () -> false,
        embedShareSpent,
        progress);
  }

  private CombinedEnrichmentBackfillOps.BackfillContext embedOnlyContext() {
    return context(true, false, false);
  }

  /** Seeds a chunk doc (IS_CHUNK/PARENT_DOC_ID/CHUNK_INDEX/span fields) directly into fakeIndex. */
  private void seedChunkDoc(
      String chunkId, String parentId, int chunkIndex, int startChar, int endChar, String status) {
    Map<String, Object> state = fakeIndex.computeIfAbsent(chunkId, k -> new HashMap<>());
    state.put(SchemaFields.IS_CHUNK, "true");
    state.put(SchemaFields.PARENT_DOC_ID, parentId);
    state.put(SchemaFields.CHUNK_INDEX, String.valueOf(chunkIndex));
    state.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(startChar));
    state.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(endChar));
    state.put(SchemaFields.CHUNK_EMBEDDING_STATUS, status);
  }

  @Test
  @DisplayName(
      "tempdoc 717 (P1): a blank-content chunk is escalated (retry), never marked COMPLETED without"
          + " a vector")
  void blankContentChunk_escalatesInsteadOfMarkingCompleted() {
    // A pending chunk whose parent content cannot be reconstructed — a fetch/consistency anomaly.
    // The old behavior marked CHUNK_EMBEDDING_STATUS=COMPLETED with no embed (a silent data-less
    // COMPLETED, the F-032 "status lies" class). It must now escalate via the retry seam.
    seedChunkDoc("chunk-blank", "parent-0", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
                context(true, false, false, false, true, false))
            .wroteAnything();

    assertTrue(didWork);
    Map<String, Object> chunkState = fakeIndex.get("chunk-blank");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING,
        chunkState.get(SchemaFields.CHUNK_EMBEDDING_STATUS),
        "a blank-content chunk must NOT be marked COMPLETED without a vector (tempdoc 717 P1)");
    assertEquals(
        "1",
        chunkState.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT),
        "the chunk is escalated via the retry-count seam instead");
  }

  @Test
  @DisplayName(
      "tempdoc 717 (P1): a persistently blank-content chunk reaches FAILED at max retries and stops"
          + " being reselected")
  void blankContentChunk_reachesFailedAtMaxRetries() {
    seedChunkDoc("chunk-blank", "parent-0", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    for (int cycle = 1; cycle < SchemaFields.EMBEDDING_MAX_RETRIES; cycle++) {
      boolean didWork =
          CombinedEnrichmentBackfillOps.processCombinedBackfill(
                  context(true, false, false, false, true, false))
              .wroteAnything();
      assertTrue(didWork, "cycle " + cycle + " should still find the pending chunk");
      Map<String, Object> state = fakeIndex.get("chunk-blank");
      assertEquals(String.valueOf(cycle), state.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      assertEquals(
          SchemaFields.EMBEDDING_STATUS_PENDING,
          state.get(SchemaFields.CHUNK_EMBEDDING_STATUS),
          "must not be FAILED before EMBEDDING_MAX_RETRIES");
    }

    // Final cycle: retry count reaches EMBEDDING_MAX_RETRIES -> FAILED (never COMPLETED).
    CombinedEnrichmentBackfillOps.processCombinedBackfill(
            context(true, false, false, false, true, false))
        .wroteAnything();
    Map<String, Object> state = fakeIndex.get("chunk-blank");
    assertEquals(
        String.valueOf(SchemaFields.EMBEDDING_MAX_RETRIES),
        state.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_FAILED, state.get(SchemaFields.CHUNK_EMBEDDING_STATUS));

    boolean ranAgain =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
                context(true, false, false, false, true, false))
            .wroteAnything();
    assertFalse(ranAgain, "a FAILED chunk must not be re-selected for another attempt");
  }

  @Test
  @DisplayName("embedding failure increments retry count on the batched write (no status change)")
  void embeddingFailure_incrementsRetryCount_onSingleCycle() {
    seedDoc(
        "doc-bad",
        "poison content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    poisonContents.add("poison content");

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).wroteAnything();

    assertTrue(didWork);
    Map<String, Object> docState = fakeIndex.get("doc-bad");
    assertEquals("1", docState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING,
        docState.get(SchemaFields.EMBEDDING_STATUS),
        "must not be marked FAILED before EMBEDDING_MAX_RETRIES is reached");
    verify(indexingCoordinator, times(1)).updateDocumentsBatch(anyList());
    verify(indexingCoordinator, never()).updateDocument(anyString(), anyMap());
    verify(documentFieldOps, never()).getDocumentField(anyString(), anyString());
  }

  @Test
  @DisplayName(
      "embedding failure reaches FAILED at EMBEDDING_MAX_RETRIES and stops being re-selected")
  void embeddingFailure_reachesFailedAtMaxRetries_andStopsBeingReselected() {
    seedDoc(
        "doc-bad",
        "poison content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    poisonContents.add("poison content");

    for (int cycle = 1; cycle < SchemaFields.EMBEDDING_MAX_RETRIES; cycle++) {
      boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).wroteAnything();
      assertTrue(didWork, "cycle " + cycle + " should still find the pending doc");
      Map<String, Object> docState = fakeIndex.get("doc-bad");
      assertEquals(String.valueOf(cycle), docState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals(
          SchemaFields.EMBEDDING_STATUS_PENDING, docState.get(SchemaFields.EMBEDDING_STATUS));
    }

    // Final cycle: retry count reaches EMBEDDING_MAX_RETRIES -> FAILED.
    boolean lastCycleDidWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).wroteAnything();
    assertTrue(lastCycleDidWork);
    Map<String, Object> docState = fakeIndex.get("doc-bad");
    assertEquals(
        String.valueOf(SchemaFields.EMBEDDING_MAX_RETRIES),
        docState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.EMBEDDING_STATUS_FAILED, docState.get(SchemaFields.EMBEDDING_STATUS));

    // Poison-pill stops being re-selected: a further cycle finds nothing pending and does no work.
    boolean ranAgain =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).wroteAnything();
    assertFalse(ranAgain, "a FAILED doc must not be re-selected for another attempt");
    verify(embeddingProvider, times(SchemaFields.EMBEDDING_MAX_RETRIES))
        .embedDocumentBatch(anyList());
  }

  @Test
  @DisplayName("embedding success path is unchanged: vector written, retry count reset to 0")
  void embeddingSuccess_writesVectorAndResetsRetryCount() {
    seedDoc(
        "doc-ok",
        "good content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).wroteAnything();

    assertTrue(didWork);
    Map<String, Object> docState = fakeIndex.get("doc-ok");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, docState.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", docState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertNotNull(docState.get(SchemaFields.VECTOR));
  }

  @Test
  @DisplayName(
      "mixed batch: one doc succeeds and one fails, each gets the right per-doc update, in a"
          + " single batched write")
  void mixedBatch_oneSucceedsOneFails_perDocUpdatesCorrect_singleBatchedWrite() {
    seedDoc(
        "doc-ok",
        "good content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedDoc(
        "doc-bad",
        "poison content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    poisonContents.add("poison content");

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).wroteAnything();

    assertTrue(didWork);
    Map<String, Object> okState = fakeIndex.get("doc-ok");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, okState.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", okState.get(SchemaFields.EMBEDDING_RETRY_COUNT));

    Map<String, Object> badState = fakeIndex.get("doc-bad");
    assertEquals("1", badState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, badState.get(SchemaFields.EMBEDDING_STATUS));

    // Tempdoc-312 invariant: exactly one batched write per cycle, never a per-doc updateDocument.
    verify(indexingCoordinator, times(1)).updateDocumentsBatch(anyList());
    verify(indexingCoordinator, never()).updateDocument(anyString(), anyMap());
  }

  /**
   * Tempdoc 798 review F2 — the {@code progressed} contract, which is the combined tight loop's
   * termination signal (see {@code BackfillScheduler.runIdleCycle}).
   *
   * <p>The Phase-2 blank-content escalation branch produces a real write every batch. Only its
   * TERMINAL step is progress: until the retry counter reaches {@code *_MAX_RETRIES} the document
   * is still PENDING, still selected, and the next batch would be identical — so the loop must
   * hand control back to the ingest poll rather than spin. This is the case where {@code
   * wroteAnything()} and {@code progressed()} diverge, and it is the only reason the tight loop's
   * drive is not just a rename of the pre-798 one.
   *
   * <p>The fake index here PERSISTS the retry count, so the escalation actually advances across
   * cycles (unreachable-seed-green, agent-lessons.md) and the terminal cycle is reached for real.
   */
  @Test
  @DisplayName(
      "blank-content escalation: writes every cycle, but progressed() is false until the terminal"
          + " FAILED step (tempdoc 798 F2)")
  void blankContentEscalation_progressedOnlyOnTheTerminalCycle() {
    seedDoc(
        "doc-blank",
        "",
        Map.of(
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
            SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));

    for (int cycle = 1; cycle < SchemaFields.SPLADE_MAX_RETRIES; cycle++) {
      var outcome =
          CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, true));
      assertTrue(
          outcome.wroteAnything(),
          "cycle " + cycle + ": the escalation write lands, so ACTIVITY is true");
      assertFalse(
          outcome.progressed(),
          "cycle "
              + cycle
              + ": the doc only bumped its retry counter — it is still PENDING and still selected,"
              + " so the tight loop must NOT continue on it");
      assertEquals(
          String.valueOf(cycle), fakeIndex.get("doc-blank").get(SchemaFields.SPLADE_RETRY_COUNT));
    }

    var terminal = CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, true));
    assertTrue(
        terminal.progressed(),
        "the terminal cycle flips both stages to FAILED — the doc leaves the pending population,"
            + " which is exactly what CombinedOutcome#progressed promises counts");
    Map<String, Object> state = fakeIndex.get("doc-blank");
    assertEquals(SchemaFields.SPLADE_STATUS_FAILED, state.get(SchemaFields.SPLADE_STATUS));
    assertEquals(SchemaFields.NER_STATUS_FAILED, state.get(SchemaFields.NER_STATUS));

    var drained = CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, true));
    assertFalse(drained.wroteAnything(), "a FAILED doc must not be re-selected");
    assertFalse(drained.progressed());
  }

  /**
   * The other half of {@code progressed}'s contract (tempdoc 798 review F2): an embed batch whose
   * result is null or size-mismatched gives its documents NO update at all — not even a retry bump
   * — so nothing changed on disk and the next batch would be byte-identical. Counting those docs
   * as progress kept the tight loop spinning on a systematically failing encoder until the cycle
   * budget expired.
   */
  @Test
  @DisplayName("embed batch size mismatch is NOT progress (no doc received any update)")
  void embedBatchSizeMismatch_isNotProgress() {
    seedDoc(
        "doc-x",
        "content x",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedDoc(
        "doc-y",
        "content y",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    // One vector back for two requested docs — the index-aligned loop cannot use it.
    when(embeddingProvider.embedDocumentBatch(anyList()))
        .thenReturn(List.of(new float[] {1f, 2f}));

    var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext());

    assertFalse(outcome.wroteAnything(), "no update map was populated, so no write went out");
    assertFalse(
        outcome.progressed(),
        "a doc that received no update has not advanced — reporting progress here spins the tight"
            + " loop against a systematically failing embed batch until the cycle budget expires");
    assertNull(
        fakeIndex.get("doc-x").get(SchemaFields.EMBEDDING_RETRY_COUNT),
        "the size-mismatch branch deliberately leaves the docs untouched for the next cycle");
  }

  @Test
  @DisplayName("SPLADE failure increments retry count then reaches FAILED at SPLADE_MAX_RETRIES")
  void spladeFailure_incrementsRetryCount_thenReachesFailedAtMaxRetries() throws Exception {
    seedDoc(
        "doc-bad",
        "splade poison",
        Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING));
    when(spladeEncoder.encodeBatch(anyList())).thenThrow(new RuntimeException("encoder boom"));

    for (int cycle = 1; cycle < SchemaFields.SPLADE_MAX_RETRIES; cycle++) {
      boolean didWork =
          CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false))
              .wroteAnything();
      assertTrue(didWork, "cycle " + cycle + " should still find the pending doc");
      Map<String, Object> docState = fakeIndex.get("doc-bad");
      assertEquals(String.valueOf(cycle), docState.get(SchemaFields.SPLADE_RETRY_COUNT));
      assertNull(docState.get(SchemaFields.SPLADE_STATUS_FAILED));
      assertEquals(SchemaFields.SPLADE_STATUS_PENDING, docState.get(SchemaFields.SPLADE_STATUS));
    }

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false));
    Map<String, Object> docState = fakeIndex.get("doc-bad");
    assertEquals(
        String.valueOf(SchemaFields.SPLADE_MAX_RETRIES), docState.get(SchemaFields.SPLADE_RETRY_COUNT));
    assertEquals(SchemaFields.SPLADE_STATUS_FAILED, docState.get(SchemaFields.SPLADE_STATUS));

    boolean ranAgain =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false))
            .wroteAnything();
    assertFalse(ranAgain, "a FAILED doc must not be re-selected for another attempt");
  }

  @Test
  @DisplayName(
      "SPLADE batch catch does not clobber a doc that already got a successful write earlier in"
          + " the same batch (partial-result-then-exception case)")
  void spladeBatchPartialWrite_doesNotClobberAlreadyCompletedDocOnEscalation() throws Exception {
    seedDoc(
        "doc-a",
        "splade content a",
        Map.of(
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_PENDING,
            SchemaFields.SPLADE_RETRY_COUNT,
            "0"));
    seedDoc(
        "doc-b",
        "splade content b",
        Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING));

    // Encoder returns a short (size-1) result for a 2-doc request: index 0 succeeds, index 1
    // throws IndexOutOfBoundsException inside the loop — same trigger class as the AIOOBE
    // crash-loop this codebase has hardened against elsewhere (EmbeddingBackfillOps).
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false));

    Map<String, Object> aState = fakeIndex.get("doc-a");
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, aState.get(SchemaFields.SPLADE_STATUS));
    assertEquals(
        "0",
        aState.get(SchemaFields.SPLADE_RETRY_COUNT),
        "doc-a's successful write must not be overwritten by the batch-catch escalation");

    Map<String, Object> bState = fakeIndex.get("doc-b");
    assertEquals("1", bState.get(SchemaFields.SPLADE_RETRY_COUNT));
    assertEquals(SchemaFields.SPLADE_STATUS_PENDING, bState.get(SchemaFields.SPLADE_STATUS));
  }

  @Test
  @DisplayName("NER failure increments retry count then reaches FAILED at NER_MAX_RETRIES")
  void nerFailure_incrementsRetryCount_thenReachesFailedAtMaxRetries() throws Exception {
    seedDoc(
        "doc-bad",
        "ner poison",
        Map.of(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
    when(nerService.extractEntitiesBatch(anyList())).thenThrow(new RuntimeException("ner boom"));

    for (int cycle = 1; cycle < SchemaFields.NER_MAX_RETRIES; cycle++) {
      boolean didWork =
          CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, false, true))
              .wroteAnything();
      assertTrue(didWork, "cycle " + cycle + " should still find the pending doc");
      Map<String, Object> docState = fakeIndex.get("doc-bad");
      assertEquals(String.valueOf(cycle), docState.get(SchemaFields.NER_RETRY_COUNT));
      assertEquals(SchemaFields.NER_STATUS_PENDING, docState.get(SchemaFields.NER_STATUS));
    }

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, false, true));
    Map<String, Object> docState = fakeIndex.get("doc-bad");
    assertEquals(
        String.valueOf(SchemaFields.NER_MAX_RETRIES), docState.get(SchemaFields.NER_RETRY_COUNT));
    assertEquals(SchemaFields.NER_STATUS_FAILED, docState.get(SchemaFields.NER_STATUS));

    boolean ranAgain =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, false, true))
            .wroteAnything();
    assertFalse(ranAgain, "a FAILED doc must not be re-selected for another attempt");
  }

  @Test
  @DisplayName(
      "all three encoders in one cycle: an embedding failure for a doc does not clobber that same"
          + " doc's successful SPLADE/NER writes, and everything flushes in one batched write")
  void allThreeEncoders_embeddingFailsOthersSucceed_singleMergedBatchedWrite() throws Exception {
    seedDoc(
        "doc-multi",
        "multi content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
            SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
    poisonContents.add("multi content");
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));
    when(nerService.extractEntitiesBatch(anyList()))
        .thenReturn(List.of(new NerResult(List.of("Alice"), List.of(), List.of())));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, true, true))
            .wroteAnything();

    assertTrue(didWork);
    Map<String, Object> state = fakeIndex.get("doc-multi");
    // Embedding failed -> escalated, not clobbered by the later SPLADE/NER phases.
    assertEquals("1", state.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, state.get(SchemaFields.EMBEDDING_STATUS));
    // SPLADE and NER succeeded independently in the same per-doc entry.
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    assertEquals(SchemaFields.NER_STATUS_COMPLETED, state.get(SchemaFields.NER_STATUS));
    assertEquals(List.of("Alice"), state.get(SchemaFields.ENTITY_PERSONS_RAW));

    verify(indexingCoordinator, times(1)).updateDocumentsBatch(anyList());
    verify(indexingCoordinator, never()).updateDocument(anyString(), anyMap());
  }

  @Test
  @DisplayName(
      "parent COMPLETED SPLADE is re-derived with pending embedding before the bundled RMW")
  void completedParentSplade_withPendingEmbedding_isReencodedInSameWrite() throws Exception {
    seedDoc(
        "parent-embed",
        "parent content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED));
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(List.of(Map.of("parent", 1.0f)));

    var outcome =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, true, false));

    assertTrue(outcome.wroteAnything());
    verify(spladeEncoder).encodeBatch(List.of("parent content"));
    Map<String, Object> state = fakeIndex.get("parent-embed");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, state.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    assertEquals(Map.of("parent", 1.0f), state.get(SchemaFields.SPLADE));
    verify(indexingCoordinator, times(1)).updateDocumentsBatch(anyList());
  }

  @Test
  @DisplayName("parent COMPLETED SPLADE is re-derived with pending NER before the bundled RMW")
  void completedParentSplade_withPendingNer_isReencodedInSameWrite() throws Exception {
    seedDoc(
        "parent-ner",
        "parent content",
        Map.of(
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED,
            SchemaFields.NER_STATUS,
            SchemaFields.NER_STATUS_PENDING));
    when(spladeEncoder.encodeBatch(anyList())).thenReturn(List.of(Map.of("parent", 1.0f)));
    when(nerService.extractEntitiesBatch(anyList())).thenReturn(List.of(NerResult.EMPTY));

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, true));

    verify(spladeEncoder).encodeBatch(List.of("parent content"));
    Map<String, Object> state = fakeIndex.get("parent-ner");
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    assertEquals(Map.of("parent", 1.0f), state.get(SchemaFields.SPLADE));
    assertEquals(SchemaFields.NER_STATUS_COMPLETED_EMPTY, state.get(SchemaFields.NER_STATUS));
  }

  @Test
  @DisplayName(
      "a stop before the re-derived SPLADE stage withholds the parent's unrelated update")
  void completedParentSplade_stopBeforeSplade_preservesExistingRmwInputs() throws Exception {
    seedDoc(
        "parent-stop",
        "parent content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED));
    fakeIndex.get("parent-stop").put(SchemaFields.SPLADE, Map.of("old", 2.0f));
    java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
    when(embeddingProvider.embedDocumentBatch(anyList()))
        .thenAnswer(
            inv -> {
              stop.set(true);
              return List.of(new float[] {3f, 4f});
            });

    var outcome =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            context(true, true, false, false, false, false, stop::get));

    assertTrue(outcome.aborted());
    assertFalse(outcome.progressed());
    verify(spladeEncoder, never()).encodeBatch(anyList());
    verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
    Map<String, Object> state = fakeIndex.get("parent-stop");
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, state.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    assertEquals(Map.of("old", 2.0f), state.get(SchemaFields.SPLADE));
  }

  @Test
  @DisplayName("a stop after re-derived SPLADE but before NER withholds SPLADE-only churn")
  void completedParentSplade_stopBeforeNer_requiresNerOutcome() throws Exception {
    seedDoc(
        "parent-stop-ner",
        "parent content",
        Map.of(
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED,
            SchemaFields.NER_STATUS,
            SchemaFields.NER_STATUS_PENDING));
    java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
    when(spladeEncoder.encodeBatch(anyList()))
        .thenAnswer(
            ignored -> {
              stop.set(true);
              return List.of(Map.of("fresh", 1.0f));
            });

    var outcome =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            context(false, true, true, false, false, false, stop::get));

    assertTrue(outcome.aborted());
    assertFalse(outcome.progressed());
    verify(nerService, never()).extractEntitiesBatch(anyList());
    verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
  }

  @Test
  @DisplayName("preservation-only SPLADE does not reserve the embedding share")
  void completedParentPreservationDoesNotDeferItsOwnPendingEmbedding() throws Exception {
    for (String id : List.of("first", "second")) {
      seedDoc(id, id + " content", Map.of(
          SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
          SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED));
    }
    when(embeddingProvider.documentWindowCount(anyString())).thenReturn(2);
    when(embeddingProvider.embedDocumentWindows(anyString(), anyInt(), anyInt()))
        .thenReturn(new EmbeddingProvider.WindowSlice(
            List.of(new float[] {1f, 0f}, new float[] {0f, 1f}), 0, 2));
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(List.of(Map.of("fresh", 1.0f), Map.of("fresh", 1.0f)));

    var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(true, true, false, false, false, false,
            () -> false, () -> true, new WindowedEmbedProgress()));

    assertTrue(outcome.progressed());
    verify(embeddingProvider).embedDocumentWindows(eq("first content"), eq(0), anyInt());
    verify(embeddingProvider).embedDocumentWindows(eq("second content"), eq(0), anyInt());
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED,
        fakeIndex.get("second").get(SchemaFields.EMBEDDING_STATUS));
    assertTrue(fakeIndex.get("second").containsKey(SchemaFields.SPLADE));
  }

  @Test
  @DisplayName("real pending SPLADE reserves its share while deferring a completed-parent bundle")
  void realPendingSpladeAllowsDeferralButNeverWritesThePreservationOnlyUpdate() throws Exception {
    seedDoc("window-first", "first content", Map.of(
        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedDoc("parent-deferred", "deferred content", Map.of(
        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
        SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED));
    seedDoc("real-pending", "pending content", Map.of(
        SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING));
    when(embeddingProvider.documentWindowCount(anyString())).thenReturn(2);
    when(embeddingProvider.embedDocumentWindows(anyString(), anyInt(), anyInt()))
        .thenReturn(new EmbeddingProvider.WindowSlice(List.of(new float[] {1f, 0f}), 0, 2));
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(List.of(Map.of("fresh", 1.0f), Map.of("fresh", 1.0f)));

    var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(true, true, false, false, false, false,
            () -> false, () -> true, new WindowedEmbedProgress()));

    assertTrue(outcome.progressed(), "real pending SPLADE advanced durably");
    verify(indexingCoordinator).updateDocumentsBatch(argThat(batch ->
        batch.size() == 1 && batch.getFirst().getKey().equals("real-pending")));
    assertFalse(fakeIndex.get("parent-deferred").containsKey(SchemaFields.SPLADE));
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING,
        fakeIndex.get("parent-deferred").get(SchemaFields.EMBEDDING_STATUS));
  }

  @Test
  @DisplayName("partial aggregate write retains completed windows and cannot claim progress")
  void partialWriteRetainsCompletedWindowsUntilTheirActualRetryWrite() throws Exception {
    for (String id : List.of("first", "second")) {
      seedDoc(id, id + " content", Map.of(
          SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
          SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED));
    }
    when(embeddingProvider.documentWindowCount(anyString())).thenReturn(2);
    when(embeddingProvider.embedDocumentWindows(anyString(), anyInt(), anyInt()))
        .thenReturn(new EmbeddingProvider.WindowSlice(
            List.of(new float[] {1f, 0f}, new float[] {0f, 1f}), 0, 2));
    when(spladeEncoder.encodeBatch(anyList())).thenAnswer(invocation -> {
      List<String> content = invocation.getArgument(0);
      return content.stream().map(ignored -> Map.of("fresh", 1.0f)).toList();
    });
    var writes = new java.util.concurrent.atomic.AtomicInteger();
    when(indexingCoordinator.updateDocumentsBatch(anyList())).thenAnswer(invocation -> {
      List<Map.Entry<String, Map<String, Object>>> batch = invocation.getArgument(0);
      boolean partial = writes.getAndIncrement() == 0;
      int written = 0;
      for (var entry : batch) {
        if (!partial || entry.getKey().equals("first")) {
          fakeIndex.get(entry.getKey()).putAll(entry.getValue());
          written++;
        }
      }
      return new LuceneRuntimeTypes.BatchUpdateResult(written, batch.size() - written);
    });
    var progress = new WindowedEmbedProgress();
    var context = context(true, true, false, false, false, false,
        () -> false, () -> false, progress);

    var partial = CombinedEnrichmentBackfillOps.processCombinedBackfill(context);
    assertTrue(partial.wroteAnything());
    assertFalse(partial.progressed());
    assertEquals(2, progress.trackedDocuments(), "aggregate count cannot identify the skipped doc");
    assertTrue(progress.isComplete("second"));
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING,
        fakeIndex.get("second").get(SchemaFields.EMBEDDING_STATUS));

    var retried = CombinedEnrichmentBackfillOps.processCombinedBackfill(context);
    assertTrue(retried.progressed());
    verify(embeddingProvider, times(1)).embedDocumentWindows(eq("second content"), eq(0), anyInt());
    assertFalse(progress.isComplete("second"));
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED,
        fakeIndex.get("second").get(SchemaFields.EMBEDDING_STATUS));
  }

  @Test
  @DisplayName("a withheld completed window is reused without another encoder call")
  void completedWindowWithheld_thenRetryUsesCachedPooledVector() throws Exception {
    seedDoc(
        "parent-window",
        "window content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED));
    WindowedEmbedProgress progress = new WindowedEmbedProgress();
    java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
    when(embeddingProvider.documentWindowCount("window content")).thenReturn(2);
    when(embeddingProvider.embedDocumentWindows("window content", 0, 32))
        .thenAnswer(
            ignored -> {
              stop.set(true);
              return new EmbeddingProvider.WindowSlice(
                  List.of(new float[] {1f, 0f}, new float[] {0f, 1f}), 0, 2);
            });
    when(spladeEncoder.encodeBatch(anyList())).thenReturn(List.of(Map.of("fresh", 1.0f)));

    var withheld =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            context(
                true, true, false, false, false, false, stop::get, () -> false, progress));
    assertFalse(withheld.progressed());
    assertEquals(1, progress.trackedDocuments());
    verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());

    stop.set(false);
    var committed =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            context(
                true, true, false, false, false, false, stop::get, () -> false, progress));

    assertTrue(committed.progressed());
    assertEquals(0, progress.trackedDocuments());
    verify(embeddingProvider, times(1)).embedDocumentWindows("window content", 0, 32);
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED,
        fakeIndex.get("parent-window").get(SchemaFields.EMBEDDING_STATUS));
  }

  @Test
  @DisplayName("a re-derived parent SPLADE failure writes a truthful retry outcome")
  void completedParentSplade_encoderFailure_writesRetryOutcome() throws Exception {
    seedDoc(
        "parent-failure",
        "parent content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED));
    when(spladeEncoder.encodeBatch(anyList())).thenThrow(new RuntimeException("encoder boom"));

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, true, false));

    verify(spladeEncoder).encodeBatch(List.of("parent content"));
    Map<String, Object> state = fakeIndex.get("parent-failure");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, state.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("1", state.get(SchemaFields.SPLADE_RETRY_COUNT));
    // This map fixture does not implement Lucene's RMW reset policy. Assert the writer input
    // here; the real-index regression asserts the resulting PENDING status and absent postings.
    verify(indexingCoordinator)
        .updateDocumentsBatch(
            argThat(
                batch ->
                    batch.stream()
                        .anyMatch(
                            entry ->
                                entry.getKey().equals("parent-failure")
                                    && "1"
                                        .equals(
                                            entry
                                                .getValue()
                                                .get(SchemaFields.SPLADE_RETRY_COUNT))
                                    && !entry.getValue().containsKey(SchemaFields.SPLADE_STATUS)
                                    && !entry.getValue().containsKey(SchemaFields.SPLADE))));
  }

  @Test
  @DisplayName("absent, FAILED, and COMPLETED_EMPTY SPLADE states are never falsely encoded")
  void parentSpladeNonCompletedStates_areNotReencoded() throws Exception {
    seedDoc(
        "parent-absent",
        "content absent",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedDoc(
        "parent-failed",
        "content failed",
        Map.of(
            SchemaFields.EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_FAILED));
    seedDoc(
        "parent-empty",
        "content empty",
        Map.of(
            SchemaFields.EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY));
    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, true, false));

    verify(spladeEncoder, never()).encodeBatch(anyList());
    assertNull(fakeIndex.get("parent-absent").get(SchemaFields.SPLADE));
    assertEquals(
        SchemaFields.SPLADE_STATUS_FAILED,
        fakeIndex.get("parent-failed").get(SchemaFields.SPLADE_STATUS));
    assertEquals(
        SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY,
        fakeIndex.get("parent-empty").get(SchemaFields.SPLADE_STATUS));
  }

  // ---------------------------------------------------------------------------------------
  // Tempdoc 691 forensics fold-in: the late-chunking single-pass embed strategy is now a
  // sub-phase INSIDE the combined pass's own embed phase (Phase 3a-i), not a separate RMW pass.
  // A separate pass's VECTOR write used to be silently destroyed by this pass's later
  // SPLADE/NER-only RMW for the same doc (Lucene RMW drops non-stored fields absent from the
  // current write — VECTOR is non-stored) — live evidence: vector nDCG 0.016 on legal-clerc.
  // Folding the strategy in means the vector always lands in the SAME per-doc update map as
  // SPLADE/NER, so every doc still gets exactly one bundled write. §Phase M's CLS-pooling finding
  // still holds: this strategy is VECTOR-only, chunk docs keep their existing separate per-chunk
  // CLS embed path untouched.
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "late-chunking flag ON: chunked parent's VECTOR comes from embedWithSpans (whole doc, empty"
          + " span array) and lands in the SAME per-doc update map as its SPLADE/NER results — one"
          + " bundled write, embedDocumentBatch never called for this doc's content")
  void lateChunking_flagOn_chunkedParent_singlePassVectorBundledWithSpladeAndNer() throws Exception {
    seedDoc(
        "parent-1",
        "parent content here, long enough to be chunked",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
            SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-1", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);
    seedChunkDoc("chunk-2", "parent-1", 1, 10, 20, SchemaFields.EMBEDDING_STATUS_PENDING);

    float[] docVector = {1f, 2f};
    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenReturn(new EmbeddingService.ChunkedEmbedding(docVector, List.of(), 1));
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));
    when(nerService.extractEntitiesBatch(anyList()))
        .thenReturn(List.of(new NerResult(List.of("Alice"), List.of(), List.of())));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, true, true, true))
            .wroteAnything();

    assertTrue(didWork);
    verify(embeddingProvider, times(1))
        .embedWithSpans(
            eq("parent content here, long enough to be chunked"),
            argThat(spans -> spans.length == 0));
    verify(embeddingProvider, never()).embedDocumentBatch(anyList());

    Map<String, Object> parentState = fakeIndex.get("parent-1");
    assertArrayEquals(docVector, (float[]) parentState.get(SchemaFields.VECTOR));
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, parentState.get(SchemaFields.SPLADE_STATUS));
    assertEquals(SchemaFields.NER_STATUS_COMPLETED, parentState.get(SchemaFields.NER_STATUS));
    assertEquals(List.of("Alice"), parentState.get(SchemaFields.ENTITY_PERSONS_RAW));

    // Chunk docs are untouched — VECTOR-only mode never derives a CHUNK_VECTOR, and
    // chunkVectorsEnabled=false in this harness keeps them out of the batch entirely.
    Map<String, Object> chunk1State = fakeIndex.get("chunk-1");
    assertNull(chunk1State.get(SchemaFields.CHUNK_VECTOR));
    Map<String, Object> chunk2State = fakeIndex.get("chunk-2");
    assertNull(chunk2State.get(SchemaFields.CHUNK_VECTOR));

    // Tempdoc-312 invariant: exactly one batched write, containing VECTOR+SPLADE+NER together
    // for parent-1 — the whole point of the fold-in is that no separate RMW can drop the vector.
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(
                list ->
                    list.size() == 1
                        && list.get(0).getKey().equals("parent-1")
                        && list.get(0).getValue().containsKey(SchemaFields.VECTOR)
                        && list.get(0).getValue().containsKey(SchemaFields.SPLADE)
                        && list.get(0).getValue().containsKey(SchemaFields.NER_STATUS)));
  }

  @Test
  @DisplayName(
      "late-chunking flag ON: embedWithSpans returns null (content exceeds the raised single-pass"
          + " limit) — folds INLINE into resumable document windows, still"
          + " ONE bundled write for the parent")
  void lateChunking_flagOn_overLimitParent_nullEmbedWithSpans_foldsIntoWindowedBatch() {
    seedDoc(
        "parent-long",
        "content that exceeds the raised single-pass limit",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-long", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class))).thenReturn(null);
    when(embeddingProvider.embedDocumentWindows(anyString(), eq(0), anyInt()))
        .thenReturn(new EmbeddingProvider.WindowSlice(List.of(new float[] {1f, 2f}), 0, 1));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, false, false, true))
            .wroteAnything();

    assertTrue(didWork);
    verify(embeddingProvider, times(1)).embedWithSpans(anyString(), any(int[][].class));
    verify(embeddingProvider, times(1))
        .embedDocumentWindows(eq("content that exceeds the raised single-pass limit"), eq(0), anyInt());
    verify(embeddingProvider, never()).embedDocumentBatch(anyList());
    verify(embeddingProvider, never())
        .documentWindowCount("content that exceeds the raised single-pass limit");

    Map<String, Object> parentState = fakeIndex.get("parent-long");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertNotNull(parentState.get(SchemaFields.VECTOR));
    Map<String, Object> chunkState = fakeIndex.get("chunk-1");
    assertNull(chunkState.get(SchemaFields.CHUNK_VECTOR));

    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(list -> list.size() == 1 && list.get(0).getKey().equals("parent-long")));
  }

  @Test
  @DisplayName(
      "late-chunking flag ON: GPU arena-OOM (wrapped RuntimeException) on the single-pass call —"
          + " folds INLINE into the windowed batch same as the over-limit null case")
  void lateChunking_flagOn_arenaOom_foldsIntoWindowedBatch() {
    seedDoc(
        "parent-oom",
        "poison parent content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.EMBEDDING_RETRY_COUNT, "0"));
    seedChunkDoc("chunk-1", "parent-oom", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    OrtException arenaOom =
        new OrtException(
            "BFCArena::AllocateRawInternal: Available memory of 536870912 is smaller than"
                + " requested bytes of 1073741824");
    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenThrow(
            new RuntimeException("Late-chunking embed failed: " + arenaOom.getMessage(), arenaOom));
    when(embeddingProvider.embedDocumentWindows(anyString(), eq(0), anyInt()))
        .thenReturn(new EmbeddingProvider.WindowSlice(List.of(new float[] {1f, 2f}), 0, 1));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, false, false, true))
            .wroteAnything();

    assertTrue(didWork);
    verify(embeddingProvider, times(1))
        .embedDocumentWindows(eq("poison parent content"), eq(0), anyInt());
    verify(embeddingProvider, never()).embedDocumentBatch(anyList());
    verify(embeddingProvider, never()).documentWindowCount("poison parent content");

    Map<String, Object> parentState = fakeIndex.get("parent-oom");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED,
        parentState.get(SchemaFields.EMBEDDING_STATUS),
        "windowed fallback success completes the parent, same as any other embed success");
    assertEquals("0", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertNotNull(parentState.get(SchemaFields.VECTOR));
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(list -> list.size() == 1 && list.get(0).getKey().equals("parent-oom")));
  }

  @Test
  @DisplayName(
      "late-chunking flag ON: embedWithSpans throws a non-arena-OOM exception — PARENT ONLY gets"
          + " retry-count escalation (tempdoc 700 parity), not marked complete; SPLADE still"
          + " succeeds independently in the same bundled write")
  void lateChunking_flagOn_embedWithSpansThrowsNonArenaOom_escalatesParentOnly_spladeStillBundled()
      throws Exception {
    seedDoc(
        "parent-bad",
        "poison parent content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-bad", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenThrow(new RuntimeException("ORT boom"));
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, true, false, true))
            .wroteAnything();

    assertTrue(didWork, "a failure-escalation write is still recorded as work");
    verify(embeddingProvider, never()).embedDocumentBatch(anyList());

    Map<String, Object> parentState = fakeIndex.get("parent-bad");
    assertEquals("1", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING,
        parentState.get(SchemaFields.EMBEDDING_STATUS),
        "must not be marked FAILED before EMBEDDING_MAX_RETRIES is reached");
    // SPLADE succeeded independently in the SAME per-doc entry, not clobbered by the embed
    // failure.
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, parentState.get(SchemaFields.SPLADE_STATUS));

    Map<String, Object> chunkState = fakeIndex.get("chunk-1");
    assertNull(chunkState.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));

    verify(indexingCoordinator, times(1)).updateDocumentsBatch(anyList());
  }

  @Test
  @DisplayName(
      "late-chunking flag ON: a chunkless parent (no PARENT_DOC_ID-matching chunk docs) uses the"
          + " normal windowed batch — embedWithSpans is never called for it")
  void lateChunking_flagOn_chunklessParent_usesNormalWindowedBatch() {
    seedDoc(
        "parent-chunkless",
        "chunkless parent content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, false, false, true))
            .wroteAnything();

    assertTrue(didWork);
    verify(embeddingProvider, never()).embedWithSpans(anyString(), any());
    verify(embeddingProvider, times(1))
        .embedDocumentBatch(
            argThat(texts -> texts.size() == 1 && texts.contains("chunkless parent content")));

    Map<String, Object> parentState = fakeIndex.get("parent-chunkless");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertNotNull(parentState.get(SchemaFields.VECTOR));
  }

  @Test
  @DisplayName(
      "late-chunking flag OFF: strict no-op vs today — combined pass embeds a chunked parent via"
          + " the ordinary windowed batch, embedWithSpans is never called")
  void lateChunkingOff_chunkedParentStillEmbedsInCombinedPass_embedWithSpansNeverCalled() {
    seedDoc(
        "parent-chunked",
        "chunked parent content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-chunked", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, false, false, false))
            .wroteAnything();

    assertTrue(didWork);
    verify(embeddingProvider, never()).embedWithSpans(anyString(), any());
    Map<String, Object> parentState = fakeIndex.get("parent-chunked");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertNotNull(parentState.get(SchemaFields.VECTOR));
    verify(embeddingProvider, times(1))
        .embedDocumentBatch(argThat(texts -> texts.contains("chunked parent content")));
  }

  // ---------------------------------------------------------------------------------------
  // Tempdoc 712: chunk-level SPLADE enrichment (flag-gated, default OFF). Chunk docs are
  // seeded splade_status=PENDING at index time (ChunkDocumentWriter) and picked up by the
  // combined pass's splade-status query. Their text is reconstructed from the stored parent plus
  // chunk offsets — so the parent lane's blank-content early-out historically marked their splade
  // COMPLETED without ever encoding (silent data-less COMPLETED; the mechanism behind the dead
  // chunk-sparse sub-leg, F-033). Flag OFF pins that historical behavior byte-identically; flag ON
  // encodes the reconstructed slice into the splade FeatureField in one bundled write.
  // ---------------------------------------------------------------------------------------

  /** Seeds a parent-backed chunk slice plus a SPLADE status (712 chunk-sparse fixtures). */
  private void seedSpladeChunkDoc(
      String chunkId, String parentId, String expectedChunkText, String spladeStatus,
      String chunkEmbeddingStatusOrNull) {
    contentByDoc.put(parentId, expectedChunkText);
    Map<String, Object> state = fakeIndex.computeIfAbsent(chunkId, k -> new HashMap<>());
    state.put(SchemaFields.IS_CHUNK, "true");
    state.put(SchemaFields.PARENT_DOC_ID, parentId);
    state.put(SchemaFields.CHUNK_START_CHAR, "0");
    state.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(expectedChunkText.length()));
    state.put(SchemaFields.SPLADE_STATUS, spladeStatus);
    if (chunkEmbeddingStatusOrNull != null) {
      state.put(SchemaFields.CHUNK_EMBEDDING_STATUS, chunkEmbeddingStatusOrNull);
    }
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag OFF (default): a splade-PENDING chunk doc is not selected, not enrolled"
          + " and not rewritten — the pass converges on the first cycle (tempdoc 931)")
  void chunkSpladeOff_chunkDocSpladePending_isNotSelectedOrRewritten() throws Exception {
    seedSpladeChunkDoc(
        "chunk-1", "parent-1", "chunk body text", SchemaFields.SPLADE_STATUS_PENDING, null);

    CombinedEnrichmentBackfillOps.CombinedOutcome outcome =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false));

    assertFalse(outcome.wroteAnything(), "a stage that does not apply must not rewrite the doc");
    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertEquals(SchemaFields.SPLADE_STATUS_PENDING, state.get(SchemaFields.SPLADE_STATUS));
    assertNull(
        state.get(SchemaFields.SPLADE_RETRY_COUNT),
        "a retry count for a stage that was never attempted is the rewrite-without-advance shape");
    assertNull(state.get(SchemaFields.SPLADE), "flag off must never write sparse data");
    verify(spladeEncoder, never()).encodeBatch(anyList());
    verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag OFF: repeating the cycle past SPLADE_MAX_RETRIES never drives a chunk to"
          + " terminal FAILED and never reports progress — the non-converging loop of tempdoc 931")
  void chunkSpladeOff_repeatedCycles_neverEscalateChunkToFailed() throws Exception {
    seedSpladeChunkDoc(
        "chunk-1", "parent-1", "chunk body text", SchemaFields.SPLADE_STATUS_PENDING, null);

    for (int cycle = 0; cycle <= SchemaFields.SPLADE_MAX_RETRIES; cycle++) {
      CombinedEnrichmentBackfillOps.CombinedOutcome outcome =
          CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false));
      assertFalse(outcome.wroteAnything(), "cycle " + cycle + " rewrote a doc it cannot advance");
      assertFalse(outcome.progressed(), "cycle " + cycle + " claimed progress it did not make");
    }

    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertEquals(
        SchemaFields.SPLADE_STATUS_PENDING,
        state.get(SchemaFields.SPLADE_STATUS),
        "flag-off chunks must stay PENDING so flipping the flag on picks them up");
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag OFF: a BLANK-content chunk escalates only the stage that was attempted —"
          + " its chunk embedding, never the SPLADE stage the flag turned off")
  void chunkSpladeOff_blankContentChunk_escalatesEmbeddingOnly() throws Exception {
    // Arrives through the chunk-embedding cache, not the splade-status selection: its parent
    // content cannot be reconstructed, so the embed stage genuinely failed and owns a retry seam.
    seedChunkDoc("chunk-blank", "parent-0", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);
    fakeIndex.get("chunk-blank").put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);

    CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(true, true, false, false, true, false));

    Map<String, Object> state = fakeIndex.get("chunk-blank");
    assertEquals("1", state.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
    assertNull(
        state.get(SchemaFields.SPLADE_RETRY_COUNT),
        "the SPLADE stage was never attempted for this chunk — it has nothing to retry");
    assertEquals(SchemaFields.SPLADE_STATUS_PENDING, state.get(SchemaFields.SPLADE_STATUS));
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag OFF: a chunk's splade_status must not consume a batch slot the parent"
          + " backlog needs — the splade-pending selection excludes chunk documents")
  void chunkSpladeOff_chunkDocsAreExcludedFromTheSpladePendingSelection() throws Exception {
    seedSpladeChunkDoc(
        "chunk-1", "parent-1", "chunk body text", SchemaFields.SPLADE_STATUS_PENDING, null);
    seedDoc(
        "parent-2",
        "parent body text",
        Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING));
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false));

    verify(documentFieldOps, never())
        .queryDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS), eq(SchemaFields.SPLADE_STATUS_PENDING), anyInt());
    verify(spladeEncoder, times(1))
        .encodeBatch(argThat(texts -> texts.size() == 1 && texts.contains("parent body text")));
    assertEquals(
        SchemaFields.SPLADE_STATUS_COMPLETED,
        fakeIndex.get("parent-2").get(SchemaFields.SPLADE_STATUS));
  }

  @Test
  @DisplayName(
      "a chunk doc pulled in by the splade-status query must not have EMBEDDING_STATUS or"
          + " NER_STATUS manufactured for it — an ABSENT status means the stage does not apply")
  void chunkDocParentLane_absentStatusesAreNotManufactured() throws Exception {
    // Flag ON, so the splade-status query is the route that pulls the chunk in (flag off it is
    // excluded from that selection entirely — tempdoc 931).
    seedSpladeChunkDoc(
        "chunk-1", "parent-1", "chunk body text", SchemaFields.SPLADE_STATUS_PENDING, null);
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));

    CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(true, true, true, false, false, true));

    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertNull(
        state.get(SchemaFields.EMBEDDING_STATUS),
        "a chunk doc has no embedding_status; stamping COMPLETED here claims a vector that will"
            + " never exist and livelocks against the RMW reset policy");
    assertNull(state.get(SchemaFields.NER_STATUS), "a chunk doc has no ner_status");
    assertNull(state.get(SchemaFields.VECTOR));
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag ON, parent-lane pickup (splade-status query): a splade-PENDING chunk doc"
          + " is encoded from its reconstructed parent slice and completed in ONE bundled write")
  void chunkSpladeOn_parentLanePickup_encodesReconstructedSlice_oneBundledWrite()
      throws Exception {
    seedSpladeChunkDoc(
        "chunk-1", "parent-1", "chunk body text", SchemaFields.SPLADE_STATUS_PENDING, null);
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
                context(false, true, false, false, false, true))
            .wroteAnything();

    assertTrue(didWork);
    verify(spladeEncoder, times(1))
        .encodeBatch(argThat(texts -> texts.size() == 1 && texts.contains("chunk body text")));
    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertEquals(Map.of("tok", 1.0f), state.get(SchemaFields.SPLADE));
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    assertEquals("0", state.get(SchemaFields.SPLADE_RETRY_COUNT));
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(
                list ->
                    list.size() == 1
                        && list.get(0).getKey().equals("chunk-1")
                        && list.get(0).getValue().containsKey(SchemaFields.SPLADE)));
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag ON, chunk-lane pickup: CHUNK_VECTOR and SPLADE land in the SAME single"
          + " bundled write; a COMPLETED splade is re-derived rather than destroyed-and-requeued"
          + " (the RMW cannot carry postings it does not re-derive — tempdoc 711 reset-status)")
  void chunkSpladeOn_chunkLane_denseAndSpladeOneBundledWrite_reDerivesCompletedSplade()
      throws Exception {
    seedSpladeChunkDoc(
        "chunk-1",
        "parent-1",
        "chunk body text",
        SchemaFields.SPLADE_STATUS_COMPLETED,
        SchemaFields.EMBEDDING_STATUS_PENDING);
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 2.0f))));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
                context(true, true, false, false, true, true))
            .wroteAnything();

    assertTrue(didWork);
    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertNotNull(state.get(SchemaFields.CHUNK_VECTOR));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED, state.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertEquals(Map.of("tok", 2.0f), state.get(SchemaFields.SPLADE));
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(
                list ->
                    list.size() == 1
                        && list.get(0).getKey().equals("chunk-1")
                        && list.get(0).getValue().containsKey(SchemaFields.CHUNK_VECTOR)
                        && list.get(0).getValue().containsKey(SchemaFields.SPLADE)));
  }

  @Test
  @DisplayName(
      "an encode that produced no materialisable weight writes COMPLETED_EMPTY, not COMPLETED —"
          + " COMPLETED would be a data-less claim the write-time contract rejects and the RMW"
          + " reset lane would bounce back to PENDING forever")
  void spladeEncodeWithNoMaterialisableWeight_writesCompletedEmpty() throws Exception {
    seedSpladeChunkDoc(
        "chunk-1", "parent-1", "chunk body text", SchemaFields.SPLADE_STATUS_PENDING, null);
    // Empty map and all-non-positive weights both materialise ZERO FeatureField postings
    // (FieldMapper.addFields emits one only for weight > 0).
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 0.0f))));

    CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(false, true, false, false, false, true));

    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertEquals(
        SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY, state.get(SchemaFields.SPLADE_STATUS));
    assertEquals("0", state.get(SchemaFields.SPLADE_RETRY_COUNT));
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag ON: a splade-FAILED chunk doc is NOT resurrected — dense enrichment"
          + " proceeds, sparse is left alone (poison-pill respected)")
  void chunkSpladeOn_spladeFailedRespected_noResurrect() throws Exception {
    seedSpladeChunkDoc(
        "chunk-1",
        "parent-1",
        "chunk body text",
        SchemaFields.SPLADE_STATUS_FAILED,
        SchemaFields.EMBEDDING_STATUS_PENDING);

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
                context(true, true, false, false, true, true))
            .wroteAnything();

    assertTrue(didWork);
    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertNotNull(state.get(SchemaFields.CHUNK_VECTOR));
    assertNull(state.get(SchemaFields.SPLADE), "FAILED splade must not be re-encoded");
    assertEquals(SchemaFields.SPLADE_STATUS_FAILED, state.get(SchemaFields.SPLADE_STATUS));
    verify(spladeEncoder, never()).encodeBatch(anyList());
  }
}
