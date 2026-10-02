/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CorpusProfile;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchSort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchInputCaptureCorpusProfileTest {
  @Test
  void ineligibleRequestsNeverProbeChunksOrProfileTheCorpus() {
    verifySkipped(false, request());
    verifySkipped(true, request().setCursor("next-page"));
    verifySkipped(true, request().setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE));
    verifySkipped(true, request().setSort(SearchSort.SEARCH_SORT_PATH_ASC));
    verifySkipped(true, request().setQuery(" "));
  }

  @Test
  void eligibleRequestProfilesOnlyWhenChunksExist() {
    var counts = mock(IndexCountOps.class);
    var fields = mock(DocumentFieldOps.class);
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenReturn(List.of());
    var capture = capture(true, counts, fields);
    assertEquals(false, capture.capture(request().build(), false, null).corpus().hasChunkDocs());
    verifyNoInteractions(counts);

    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenReturn(List.of("chunk"));
    when(counts.getOrComputeCorpusProfile()).thenReturn(CorpusProfile.EMPTY);
    assertEquals(true, capture.capture(request().build(), false, null).corpus().hasChunkDocs());
    verify(counts).getOrComputeCorpusProfile();
  }

  private static void verifySkipped(boolean enabled, SearchRequest.Builder request) {
    var counts = mock(IndexCountOps.class);
    var fields = mock(DocumentFieldOps.class);
    capture(enabled, counts, fields).capture(request.build(), false, null);
    verifyNoInteractions(counts, fields);
  }

  private static SearchRequest.Builder request() {
    return SearchRequest.newBuilder().setQuery("needle")
        .setPipeline(PipelineConfig.getDefaultInstance());
  }

  private static SearchInputCapture capture(
      boolean enabled, IndexCountOps counts, DocumentFieldOps fields) {
    var config = mock(ResolvedConfig.class);
    var search = mock(ResolvedConfig.Search.class);
    when(config.search()).thenReturn(search);
    when(search.chunkAwareEnabled()).thenReturn(enabled);
    return new SearchInputCapture(
        null, counts, mock(CommitOps.class), fields, () -> config, () -> null, Map::of,
        () -> new SearchInputCapture.EncoderSnapshot(null, null, () -> null, null, null, null));
  }
}
