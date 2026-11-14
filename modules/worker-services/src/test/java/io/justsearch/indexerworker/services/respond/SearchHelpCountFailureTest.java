/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.indexerworker.services.SearchOutcome;
import io.justsearch.indexerworker.services.input.*;
import io.justsearch.indexerworker.services.plan.SearchDecision;
import io.justsearch.ipc.SearchRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

class SearchHelpCountFailureTest {
  @Test
  void countFailureOmitsHelpMatchesWithoutChangingMainResponse() throws Exception {
    for (Exception failure : List.of(new IOException("reader unavailable"), new IllegalStateException("count unavailable"))) {
      var count = mock(IndexCountOps.class);
      when(count.countQueryOrThrow(any(Query.class))).thenThrow(failure);
      var response = build(count);
      var field = response.getDescriptorForType().findFieldByName("help_matches");
      assertNotNull(field, "independent Help count field");
      assertFalse(response.hasField(field), "failure must not be presented as a count");
      assertEquals(999, response.getTotalHits(), "retrieval telemetry is preserved, never substituted");
      assertEquals(0, response.getMatchCount());
    }
  }

  private static io.justsearch.ipc.SearchResponse build(IndexCountOps count) throws Exception {
    var request = SearchRequest.newBuilder().setQuery("needle");
    var exclusion = request.getDescriptorForType().findFieldByName("excluded_collections");
    assertNotNull(exclusion, "request-specific exclusion");
    request.addRepeatedField(exclusion, "justsearch-help");
    var filters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build();
    var inputs = new SearchInputs(request.build(), filters, null,
        new CorpusCapabilities(false, true, 0, 0),
        new EncodingResults(new VectorEncoding.NotRequested(), new SpladeEncoding.NotRequested(), new BgeM3Encoding.NotRequested()),
        QppMetrics.ZERO, EmbeddingCompatBoundary.of(null, false), Map.of(), null);
    var text = mock(TextQueryOps.class);
    when(text.buildTextQuery(anyString(), any(), any())).thenReturn(new MatchAllDocsQuery());
    var builder = new SearchResponseBuilder(count, mock(DocumentFieldOps.class), text, null, () -> null);
    return builder.build(SearchOutcome.empty(new LuceneRuntimeTypes.SearchResult(List.of(), 999, 0)),
        new SearchDecision.EmptyQueryDecision(50, "TEXT"), inputs);
  }
}
