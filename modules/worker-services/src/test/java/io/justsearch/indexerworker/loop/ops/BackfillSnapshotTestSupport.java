/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult;
import io.justsearch.indexing.SchemaFields;
import java.util.*;

/** Adapts the existing fake-index fixtures to the atomic input / conditional-write interfaces. */
public final class BackfillSnapshotTestSupport {
  private BackfillSnapshotTestSupport() {}

  public static void install(DocumentFieldOps fields, IndexingCoordinator coordinator) {
    install(fields,coordinator,false,Set.of(SchemaFields.IS_CHUNK,SchemaFields.EMBEDDING_STATUS,
        SchemaFields.EMBEDDING_RETRY_COUNT,SchemaFields.CHUNK_EMBEDDING_STATUS,SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,
        SchemaFields.SPLADE_STATUS,SchemaFields.SPLADE_RETRY_COUNT,SchemaFields.NER_STATUS,SchemaFields.NER_RETRY_COUNT));
  }

  public static void install(DocumentFieldOps fields, IndexingCoordinator coordinator,
      boolean individualContent, Set<String> batchedFields) {
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> installChecked(fields,coordinator,individualContent,batchedFields));
  }

  private static Map<String,DocumentFieldOps.EnrichmentInput> capture(DocumentFieldOps fields,
      List<String> ids, boolean individualContent,Set<String> batchedFields) {
      if (ids.isEmpty()) return Map.of();
      Map<String, String> contents = new HashMap<>();
      if (individualContent) {
        for (String id : ids) { String text = fields.getDocumentContent(id); if (text != null) contents.put(id, text); }
      } else contents.putAll(fields.getDocumentContentBatch(ids));
      Map<String, Map<String, String>> metadata = batchedFields.isEmpty()
          ? Map.of() : fields.getDocumentFieldsBatch(ids, batchedFields);
      Map<String, DocumentFieldOps.EnrichmentInput> result = new HashMap<>();
      for (String id : ids) {
        var values = new HashMap<>(metadata.getOrDefault(id, Map.of()));
        for (String retry : batchedFields.size() > 1 ? List.<String>of()
            : List.of(SchemaFields.EMBEDDING_RETRY_COUNT, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,
                SchemaFields.SPLADE_RETRY_COUNT, SchemaFields.NER_RETRY_COUNT)) {
          if (!values.containsKey(retry)) {
            String count = fields.getDocumentField(id, retry);
            if (count != null) values.put(retry, count);
          }
        }
        result.put(id, new DocumentFieldOps.EnrichmentInput(contents.get(id), values, null, Map.of()));
      }
      return result;
  }

  /** The fake writer accepts the submitted prefix, matching these fixtures' aggregate stubs. */
  public static void installReceipts(IndexingCoordinator coordinator) {
    lenient().doAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      var result = coordinator.updateDocumentsBatchIf(updates, call.getArgument(1));
      int accepted = result == null ? updates.size() : result.updatedCount();
      List<String> ids = updates.stream().map(Map.Entry::getKey).toList();
      return new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt(
          ids.subList(0, accepted), ids.subList(accepted, ids.size()), null);
    }).when(coordinator).publishEnrichmentBatch(anyList(), anyMap());
  }

  private static void installChecked(DocumentFieldOps fields,IndexingCoordinator coordinator,
      boolean individualContent,Set<String> batchedFields) throws Exception {
    installReceipts(coordinator);
    lenient().when(fields.getEnrichmentInputs(anyList())).thenAnswer(call ->
        capture(fields,call.getArgument(0),individualContent,batchedFields));
    lenient().when(fields.getEnrichmentInputs(anyList(), anyLong())).thenAnswer(call -> {
      List<String> ids = call.getArgument(0);
      long budget = call.getArgument(1);
      long retained = 0;
      Map<String, DocumentFieldOps.EnrichmentInput> result = new LinkedHashMap<>();
      for (String id : ids) {
        var input = capture(fields,List.of(id),true,batchedFields).get(id);
        if (input == null) continue;
        long chars = input.content() == null ? 0 : input.content().length();
        if (!result.isEmpty() && retained + chars > budget) break;
        result.put(id, input);
        retained += chars;
        if (retained >= budget) break;
      }
      return result;
    });
    lenient().when(coordinator.updateDocumentsBatchIf(anyList(), anyMap())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      var result = coordinator.updateDocumentsBatch(updates);
      return result == null ? new BatchUpdateResult(updates.size(), 0) : result;
    });

  }
}
