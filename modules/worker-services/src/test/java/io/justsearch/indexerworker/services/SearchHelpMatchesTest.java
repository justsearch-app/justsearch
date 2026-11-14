/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.Message;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchHelpMatchesTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @Test
  void sameResponseSeparatesHelpFromRowsCountsFacetsAndWindowAndIntersectsFilters() throws Exception {
    try (RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      for (int i = 0; i < 120; i++) {
        var fields = new HashMap<String, Object>();
        String id = (i < 60 ? "/files/" : "/help/") + i;
        fields.put(SchemaFields.DOC_ID, id);
        fields.put(SchemaFields.DOC_UID, "uid-" + i);
        fields.put(SchemaFields.PATH, id);
        fields.put(SchemaFields.CONTENT, i < 60 ? "needle" : "needle helponly ".repeat(20));
        fields.put(SchemaFields.MIME, i < 60 ? "text/plain" : "text/html");
        fields.put(SchemaFields.COLLECTION, i < 60 ? "files" : "justsearch-help");
        fields.put(SchemaFields.SIZE_BYTES, i < 60 ? 10L : 20L);
        fields.put(SchemaFields.MODIFIED_AT, i < 60 ? 100L : 200L);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var service = new WorkerSearchService(runtime);
      var request = SearchRequest.newBuilder().setQuery("needle").setLimit(50)
          .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SEARCH)
          .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true))
          .setFacets(FacetSpec.newBuilder().setInclude(true)
              .addFields(FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(10)));
      var exclusion = request.getDescriptorForType().findFieldByName("excluded_collections");
      assertNotNull(exclusion, "Search request must support optional excluded collections");
      request.addRepeatedField(exclusion, "justsearch-help");
      var response = service.search(request.build(), CallContext.none());
      assertEquals(50, response.getResultsCount());
      assertTrue(response.getResultsList().stream().allMatch(hit -> hit.getId().startsWith("/files/")));
      assertEquals(60, response.getMatchCount());
      assertEquals(60L, response.getFacetsOrThrow(SchemaFields.MIME).getCountsOrThrow("text/plain"));
      assertFalse(response.getFacetsOrThrow(SchemaFields.MIME).containsCounts("text/html"));
      assertHelpCount(response, 60);
      var helpOnly = service.search(request.clone().setQuery("helponly").build(), CallContext.none());
      assertEquals(0, helpOnly.getResultsCount());
      assertEquals(0, helpOnly.getMatchCount());
      assertHelpCount(helpOnly, 60);
      for (SearchFilters filter : List.of(
          SearchFilters.newBuilder().setPathPrefix("/files").build(),
          SearchFilters.newBuilder().addMime("text/plain").build(),
          SearchFilters.newBuilder().addDocIds("/files/0").build(),
          SearchFilters.newBuilder().setModifiedAt(TimeRangeMs.newBuilder().setToMs(100)).build(),
          SearchFilters.newBuilder().setSizeBytes(ByteRange.newBuilder().setToBytes(10)).build())) {
        assertHelpCount(service.search(request.clone().setFilters(filter).build(), CallContext.none()), 0);
      }
      var ordinaryCaller = service.search(request.clone().clearField(exclusion).build(), CallContext.none());
      assertEquals(120, ordinaryCaller.getMatchCount());
      var helpField = ordinaryCaller.getDescriptorForType().findFieldByName("help_matches");
      assertFalse(ordinaryCaller.hasField(helpField));
    }
  }

  private static void assertHelpCount(SearchResponse response, long expected) {
    var field = response.getDescriptorForType().findFieldByName("help_matches");
    assertNotNull(field, "help count travels with the same search response");
    assertTrue(response.hasField(field));
    var matches = (Message) response.getField(field);
    assertEquals(expected, matches.getField(matches.getDescriptorForType().findFieldByName("count")));
    assertEquals(false, matches.getField(matches.getDescriptorForType().findFieldByName("capped")));
  }
}
