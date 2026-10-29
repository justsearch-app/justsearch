/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.disambiguation.ClusterEntry;
import io.justsearch.indexerworker.disambiguation.EntityClusterSnapshot;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchFilters;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.TimeRangeMs;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D11: actually add an entity alias while preserving every independently supplied restriction. */
class EntityExpansionRestrictionTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @Test
  void expandedEntityKeepsDocumentCollectionFolderDateAndSizeScopes() throws Exception {
    var cluster = EntityClusterSnapshot.fromEntries(List.of(
        new ClusterEntry("John Smith", "PERSON", "c", "John Smith", 1, 1, 1),
        new ClusterEntry("J. Smith", "PERSON", "c", "John Smith", 1, 1, 1)));
    var config = mock(ResolvedConfig.class);
    var search = mock(ResolvedConfig.Search.class);
    when(config.search()).thenReturn(search);
    var capture = new SearchInputCapture(null, mock(IndexCountOps.class), mock(CommitOps.class),
        mock(DocumentFieldOps.class), () -> config, () -> null, Map::of,
        () -> new SearchInputCapture.EncoderSnapshot(null, () -> cluster, () -> null, null, null, null));
    var scope = List.of("/inside/target", "/outside/folder", "/inside/date", "/inside/size", "/inside/collection", "/inside/entity");
    var filters = SearchFilters.newBuilder().addEntityPersons("J. Smith")
        .addAllDocIds(scope).addCollection("notes").setPathPrefix("/inside")
        .setModifiedAt(TimeRangeMs.newBuilder().setFromMs(100).setToMs(200));
    var sizeField = filters.getDescriptorForType().findFieldByName("size_bytes");
    if (sizeField != null) {
      var size = filters.newBuilderForField(sizeField);
      size.setField(size.getDescriptorForType().findFieldByName("from_bytes"), 0L);
      size.setField(size.getDescriptorForType().findFieldByName("to_bytes"), 500L);
      filters.setField(sizeField, size.build());
    }
    var input = capture.capture(SearchRequest.newBuilder().setQuery("needle")
        .setPipeline(PipelineConfig.getDefaultInstance()).setFilters(filters).build(), false, null);
    var result = input.runtimeFilters();
    assertEquals(Set.of("John Smith", "J. Smith"), Set.copyOf(result.entityPersons()));
    assertEquals(scope, result.docIds());
    assertEquals(List.of("notes"), result.collection());
    assertEquals("/inside", result.pathPrefix());
    assertEquals(100L, result.modifiedFromMs());
    assertEquals(200L, result.modifiedToMs());
    assertEquals(0L, result.getClass().getMethod("sizeFromBytes").invoke(result));
    assertEquals(500L, result.getClass().getMethod("sizeToBytes").invoke(result));
    var base = io.justsearch.configuration.FieldCatalogDef.forChunkTesting(0);
    var fields = new java.util.ArrayList<>(base.fields());
    fields.add(new io.justsearch.configuration.FieldCatalogDef.FieldDef(
        io.justsearch.indexing.SchemaFields.ENTITY_PERSONS_RAW, "keyword", true, true,
        List.of("filter"), null, null, true));
    var catalog = new io.justsearch.configuration.FieldCatalogDef("expanded-scope", fields);
    try (var runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(catalog)
        .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      var ids = new java.util.ArrayList<>(scope);
      ids.add("/inside/docid");
      for (String id : ids) {
        var data = new java.util.HashMap<String, Object>();
        data.put("doc_id", id);
        data.put("doc_uid", id + "#0");
        data.put("path", id);
        data.put("collection", id.endsWith("collection") ? "other" : "notes");
        data.put("modified_at", id.endsWith("date") ? 300L : 150L);
        data.put("size_bytes", id.endsWith("size") ? 501L : 0L);
        data.put(io.justsearch.indexing.SchemaFields.ENTITY_PERSONS_RAW,
            List.of(id.endsWith("entity") ? "Other Person" : "John Smith"));
        runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(data));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      var matches = runtime.readPathOps().search(
          io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.buildFilterQueryOnly(result), 20,
          Set.of("path"), io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
      assertEquals(List.of("/inside/target"), matches.hits().stream().map(h -> h.docId()).toList());
    }
  }
}
