/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.indexerworker.services.SearchOutcome;
import io.justsearch.indexerworker.services.SearchReasonCode;
import io.justsearch.indexerworker.services.input.*;
import io.justsearch.indexerworker.services.plan.*;
import io.justsearch.ipc.SearchRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

class SearchHelpQuerySemanticsTest {
  @Test
  void helpRebuildReadsTheSparseDecisionSyntax() throws Exception {
    assertDecisionSyntax(false);
  }

  @Test
  void helpRebuildReadsTheMultiLegDecisionSyntax() throws Exception {
    assertDecisionSyntax(true);
  }

  @Test
  void cursorSkipsTheHelpQueryAndCountEntirely() throws Exception {
    var text = mock(TextQueryOps.class);
    var count = mock(IndexCountOps.class);
    var response = build(text, count, request().setCursor("next-page").build(), sparse());
    assertFalse(response.hasHelpMatches());
    verify(text, never()).buildTextQuery(anyString(), any(), any());
    verify(count, never()).countQueryOrThrow(any());
  }

  private static void assertDecisionSyntax(boolean multiLeg) throws Exception {
    var text = mock(TextQueryOps.class);
    var count = mock(IndexCountOps.class);
    when(text.buildTextQuery(anyString(), any(), any())).thenReturn(new MatchAllDocsQuery());
    when(count.countQueryOrThrow(any(Query.class))).thenReturn(7);
    SearchDecision decision = multiLeg
        ? new SearchDecision.MultiLegDecision(LuceneRuntimeTypes.QuerySyntax.LUCENE,
            new LegSet.Bm25Only(10), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), skip())
        : sparse();
    var response = build(text, count, request().build(), decision);
    assertEquals(7, response.getHelpMatches().getCount());
    // Deliberately differs from the wire default SIMPLE: the decision owns the parse.
    verify(text).buildTextQuery(eq("needle"), argThat(filters ->
        List.of("justsearch-help").equals(filters.collection())),
        eq(LuceneRuntimeTypes.QuerySyntax.LUCENE));
  }

  private static SearchRequest.Builder request() {
    return SearchRequest.newBuilder().setQuery("needle").addExcludedCollections("justsearch-help");
  }

  private static ChunkMergeDirective skip() {
    return new ChunkMergeDirective.Skip(SearchReasonCode.SKIPPED_EMPTY_BASE_RESULTS);
  }

  private static SearchDecision sparse() {
    return new SearchDecision.SparseShortcut(LuceneRuntimeTypes.QuerySyntax.LUCENE,
        10, false, Optional.empty(), skip());
  }

  private static io.justsearch.ipc.SearchResponse build(TextQueryOps text, IndexCountOps count,
      SearchRequest request, SearchDecision decision) {
    var inputs = new SearchInputs(request,
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build(), null,
        new CorpusCapabilities(false, true, 0, 0),
        new EncodingResults(new VectorEncoding.NotRequested(), new SpladeEncoding.NotRequested(),
            new BgeM3Encoding.NotRequested()), QppMetrics.ZERO,
        EmbeddingCompatBoundary.of(null, false), Map.of(), null);
    var builder = new SearchResponseBuilder(count, mock(DocumentFieldOps.class), text, null, () -> null);
    return builder.build(SearchOutcome.empty(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0)),
        decision, inputs);
  }
}
