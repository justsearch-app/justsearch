/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 774 Stage 1 — unit coverage for the chunk-branch lever primitives that are static /
 * package-private on {@link SearchExecutor}: the collapse-cap ({@code collapseChunkHitsToParents})
 * and the chunk-side recall-complete protected-set selection ({@code collectRawLegTopNParents}, on
 * the RAW pre-collapse leg results).
 */
@DisplayName("SearchExecutor chunk-branch levers (774 Stage 1)")
final class SearchExecutorChunkBranchLeversTest {

  /** A raw chunk-leg hit: distinct chunk docId, parent via PARENT_DOC_ID field. */
  private static SearchHit chunkHit(String chunkId, String parentId) {
    return new SearchHit(chunkId, 1.0f, Map.of(SchemaFields.PARENT_DOC_ID, parentId));
  }

  @Nested
  @DisplayName("collectRawLegTopNParents (chunk-side recall-complete protected set, pre-collapse)")
  class CollectRawLegTopNParents {

    @Test
    @DisplayName("each leg's top-N chunks map to parents, deduped across legs (bm25→dense→splade)")
    void perLegTopN_dedupedToParents() {
      SearchResult bm25 =
          new SearchResult(
              List.of(chunkHit("c-p1", "p1"), chunkHit("c-p2", "p2"), chunkHit("c-p3", "p3")),
              3,
              0);
      SearchResult dense =
          new SearchResult(
              List.of(chunkHit("c-p4", "p4"), chunkHit("c-p1b", "p1")), 2, 0);
      SearchResult splade = new SearchResult(List.of(), 0, 0);

      // topN=2: bm25 top-2 → p1,p2; dense top-2 → p4, p1(dup). p3 is at bm25 rank 3 → excluded.
      List<SearchHit> out = SearchExecutor.collectRawLegTopNParents(bm25, dense, splade, 2);
      List<String> ids = out.stream().map(SearchHit::docId).collect(Collectors.toList());
      assertEquals(List.of("p1", "p2", "p4"), ids);
    }

    @Test
    @DisplayName("hits are normalized to parent form (docId == parentId)")
    void hitsNormalizedToParent() {
      SearchResult bm25 = new SearchResult(List.of(chunkHit("chunk-xyz", "parent-A")), 1, 0);
      List<SearchHit> out =
          SearchExecutor.collectRawLegTopNParents(bm25, new SearchResult(List.of(), 0, 0),
              new SearchResult(List.of(), 0, 0), 5);
      assertEquals(1, out.size());
      assertEquals("parent-A", out.get(0).docId());
    }

    @Test
    @DisplayName("topN <= 0 yields an empty protected set (lever off → no work)")
    void topNZero_empty() {
      SearchResult bm25 = new SearchResult(List.of(chunkHit("c", "p")), 1, 0);
      SearchResult empty = new SearchResult(List.of(), 0, 0);
      assertTrue(SearchExecutor.collectRawLegTopNParents(bm25, empty, empty, 0).isEmpty());
    }
  }

  @Nested
  @DisplayName("isCandidateBudgetSaturated (lane F PR 0b — the exhaustive-kNN carve-out)")
  class CandidateBudgetSaturation {

    /** A leg result with a returned-hit count and an independently chosen totalHits. */
    private SearchResult leg(int returnedHits, long totalHits) {
      List<SearchHit> hits =
          java.util.stream.IntStream.range(0, returnedHits)
              .mapToObj(i -> chunkHit("c" + i, "p" + i))
              .collect(Collectors.toList());
      return new SearchResult(hits, totalHits, 0);
    }

    @Test
    @DisplayName("default: a totalHits above the budget saturates (unchanged behaviour)")
    void totalHitsAboveBudgetSaturates() {
      assertTrue(SearchExecutor.isCandidateBudgetSaturated(leg(2, 500), 100, false));
    }

    @Test
    @DisplayName("exhaustive dense leg: the same totalHits no longer saturates")
    void exhaustiveIgnoresTotalHits() {
      assertFalse(SearchExecutor.isCandidateBudgetSaturated(leg(2, 500), 100, true));
    }

    @Test
    @DisplayName("exhaustive mode still saturates on the RETURNED hit count")
    void exhaustiveStillHonoursReturnedHitCount() {
      // The hit count measures how hard the leg pushed against the budget; the switch does not
      // touch it, so the retry branch keeps firing on the case it was written for.
      assertTrue(SearchExecutor.isCandidateBudgetSaturated(leg(10, 10), 10, true));
    }

    @Test
    @DisplayName("a below-budget leg is not saturated in either mode")
    void belowBudgetNeverSaturates() {
      assertFalse(SearchExecutor.isCandidateBudgetSaturated(leg(3, 7), 100, false));
      assertFalse(SearchExecutor.isCandidateBudgetSaturated(leg(3, 7), 100, true));
    }

    @Test
    @DisplayName("null result / non-positive budget are not saturated in either mode")
    void degenerateInputs() {
      assertFalse(SearchExecutor.isCandidateBudgetSaturated(null, 100, false));
      assertFalse(SearchExecutor.isCandidateBudgetSaturated(null, 100, true));
      assertFalse(SearchExecutor.isCandidateBudgetSaturated(leg(5, 500), 0, false));
      assertFalse(SearchExecutor.isCandidateBudgetSaturated(leg(5, 500), 0, true));
    }
  }

  @Nested
  @DisplayName("collapseChunkHitsToParents (collapse-cap lever mechanism)")
  class CollapseCap {

    private SearchResult fiveDistinctParents() {
      return new SearchResult(
          List.of(
              new SearchHit("c1", 5f, Map.of(SchemaFields.PARENT_DOC_ID, "p1")),
              new SearchHit("c2", 4f, Map.of(SchemaFields.PARENT_DOC_ID, "p2")),
              new SearchHit("c3", 3f, Map.of(SchemaFields.PARENT_DOC_ID, "p3")),
              new SearchHit("c4", 2f, Map.of(SchemaFields.PARENT_DOC_ID, "p4")),
              new SearchHit("c5", 1f, Map.of(SchemaFields.PARENT_DOC_ID, "p5"))),
          5,
          0);
    }

    @Test
    @DisplayName("the collapse limit caps the parent count (limit×multiplier at the call site)")
    void collapseLimitCapsParents() {
      // limit=2 (e.g. limit=1 × multiplier=2) → 2 parents; limit=4 → 4 parents.
      assertEquals(2, SearchExecutor.collapseChunkHitsToParents(fiveDistinctParents(), 2).hits().size());
      assertEquals(4, SearchExecutor.collapseChunkHitsToParents(fiveDistinctParents(), 4).hits().size());
    }
  }
}
