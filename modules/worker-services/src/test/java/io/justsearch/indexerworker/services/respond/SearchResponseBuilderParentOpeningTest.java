/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.PipelineConfig;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class SearchResponseBuilderParentOpeningTest {
  @Test
  void onlyCrossEncoderPipelinesReadAndCarryTheBoundedParentOpening() {
    var fields = mock(DocumentFieldOps.class);
    String opening = "opening ".repeat(400);
    when(fields.getDocumentField(anyString(), eq(SchemaFields.CONTENT_PREVIEW)))
        .thenReturn(opening);
    var builder = new SearchResponseBuilder(null, fields, null, null, () -> null);
    var hits = IntStream.range(0, 5).mapToObj(i ->
        new LuceneRuntimeTypes.SearchHit("chunk:" + i, 5 - i,
            Map.of(SchemaFields.PARENT_DOC_ID, "parent:" + i,
                SchemaFields.CHUNK_INDEX, "1", SchemaFields.CHUNK_CONTENT, "needle")))
        .toList();
    var input = new LuceneRuntimeTypes.SearchResult(hits, hits.size(), 0L);

    var lexical = builder.toGrpcResponseBuilder(input, 0L, "needle",
        PipelineConfig.newBuilder().setSparseEnabled(true).build(), null, false, false).build();
    for (var hit : lexical.getResultsList()) {
      assertFalse(hit.containsFields("parent_opening"));
    }
    verify(fields, never()).getDocumentField(anyString(), eq(SchemaFields.CONTENT_PREVIEW));

    var reranked = builder.toGrpcResponseBuilder(input, 0L, "needle",
        PipelineConfig.newBuilder().setSparseEnabled(true).setCrossEncoderEnabled(true).build(),
        null, false, false).build();
    // LambdaMART/per-source merging can promote any of these into the Head's first three.
    assertEquals(5, reranked.getResultsCount());
    for (var hit : reranked.getResultsList()) {
      assertEquals(opening.substring(0, 1600), hit.getFieldsOrThrow("parent_opening"));
    }
    verify(fields, times(5)).getDocumentField(anyString(), eq(SchemaFields.CONTENT_PREVIEW));
    assertEquals(List.of("parent:0", "parent:1", "parent:2", "parent:3", "parent:4"),
        reranked.getResultsList().stream().map(io.justsearch.ipc.SearchResult::getId).toList());
  }
}
