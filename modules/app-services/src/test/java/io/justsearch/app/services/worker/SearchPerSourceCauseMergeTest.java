/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchTrace;
import io.justsearch.ipc.TraceDegradation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec D test H2 (§1.6, step 2b): when the head merges per-source responses, the merged trace keeps
 * each leg's semantic budget reason together with the strongest cause, in the order
 * {@code stuck > loading > contended}, whatever order the sources arrive in.
 */
final class SearchPerSourceCauseMergeTest {

  private static final String SBE = "SEMANTIC_BUDGET_EXCEEDED";

  @Test
  void contributingBackfillKeepsItsStrongestCause() {
    var first = dense(SBE, "contended").toBuilder()
        .addResults(io.justsearch.ipc.SearchResult.newBuilder().setId("source"))
        .setTotalHits(1).build();
    var failedBackfill = SearchPerSourceExecutor.mergeSearchResponses(
        List.of(first), 2, SearchRequest.getDefaultInstance(), request -> null);
    assertEquals(List.of("source"),
        failedBackfill.getResultsList().stream().map(io.justsearch.ipc.SearchResult::getId).toList());
    assertEquals("contended", failedBackfill.getSearchTrace().getDegradation().getHybridFallbackCause());
    var backfill = dense(SBE, "stuck").toBuilder()
        .addResults(io.justsearch.ipc.SearchResult.newBuilder().setId("source"))
        .addResults(io.justsearch.ipc.SearchResult.newBuilder().setId("backfill"))
        .setTotalHits(2).build();
    var merged = SearchPerSourceExecutor.mergeSearchResponses(
        List.of(first), 2, SearchRequest.getDefaultInstance(), request -> backfill);
    assertEquals(List.of("source", "backfill"),
        merged.getResultsList().stream().map(io.justsearch.ipc.SearchResult::getId).toList());
    assertEquals(2, merged.getTotalHits());
    assertEquals(SBE, merged.getSearchTrace().getDegradation().getHybridFallbackReason());
    assertEquals("stuck", merged.getSearchTrace().getDegradation().getHybridFallbackCause());
  }

  @Test
  void theStrongestDenseCauseWinsWithItsReason() {
    for (List<SearchResponse> sources :
        List.of(
            List.of(dense(SBE, "contended"), dense(SBE, "stuck"), dense(SBE, "loading")),
            List.of(dense(SBE, "stuck"), dense(SBE, "loading"), dense(SBE, "contended")),
            List.of(dense(SBE, "loading"), dense("NO_EMBEDDING_SERVICE", ""), dense(SBE, "stuck")))) {
      TraceDegradation merged = merge(sources);
      assertTrue(merged.getHybridFallback());
      assertEquals(SBE, merged.getHybridFallbackReason());
      assertEquals("stuck", merged.getHybridFallbackCause());
    }
  }

  @Test
  void aBudgetMissIsNotOverwrittenByALaterReasonWithoutACause() {
    TraceDegradation merged =
        merge(List.of(dense(SBE, "loading"), dense("EMBEDDING_EXCEPTION", "")));
    assertEquals(SBE, merged.getHybridFallbackReason());
    assertEquals("loading", merged.getHybridFallbackCause());
  }

  @Test
  void theSpladeLegMergesIndependentlyOfTheDenseLeg() {
    TraceDegradation merged =
        merge(
            List.of(
                splade(SBE, "contended"),
                splade(SBE, "loading"),
                dense("NO_EMBEDDING_SERVICE", "")));
    assertEquals(SBE, merged.getSpladeSkipReason());
    assertEquals("loading", merged.getSpladeSkipCause());
    assertEquals("NO_EMBEDDING_SERVICE", merged.getHybridFallbackReason());
    assertEquals("", merged.getHybridFallbackCause());
  }

  @Test
  void withoutCausesTheLastReasonStillWins() {
    TraceDegradation merged =
        merge(List.of(dense("NO_EMBEDDING_SERVICE", ""), dense("EMBEDDING_EXCEPTION", "")));
    assertEquals("EMBEDDING_EXCEPTION", merged.getHybridFallbackReason());
    assertEquals("", merged.getHybridFallbackCause());
  }

  private static TraceDegradation merge(List<SearchResponse> sources) {
    SearchResponse merged =
        SearchPerSourceExecutor.mergeSearchResponses(
            sources,
            0,
            SearchRequest.getDefaultInstance(),
            req -> {
              throw new AssertionError("no backfill expected");
            });
    return merged.getSearchTrace().getDegradation();
  }

  private static SearchResponse dense(String reason, String cause) {
    return response(
        TraceDegradation.newBuilder()
            .setHybridFallback(true)
            .setHybridFallbackReason(reason)
            .setHybridFallbackCause(cause)
            .setSpladeExecuted(true)
            .build());
  }

  private static SearchResponse splade(String reason, String cause) {
    return response(
        TraceDegradation.newBuilder()
            .setSpladeExecuted(false)
            .setSpladeSkipReason(reason)
            .setSpladeSkipCause(cause)
            .build());
  }

  private static SearchResponse response(TraceDegradation degradation) {
    return SearchResponse.newBuilder()
        .setSearchTrace(SearchTrace.newBuilder().setDegradation(degradation))
        .build();
  }
}
