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
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> installChecked(fields,coordinator,individualContent,batchedFields,false));
  }

  public static void installLaneFixtures(DocumentFieldOps fields, IndexingCoordinator coordinator,
      boolean individualContent, Set<String> batchedFields) {
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> installChecked(fields,coordinator,individualContent,batchedFields,true));
  }

  public static void installLaneFixtures(DocumentFieldOps fields, IndexingCoordinator coordinator) {
    install(fields, coordinator);
  }

  private static Map<String,DocumentFieldOps.EnrichmentInput> capture(DocumentFieldOps fields,
      List<String> ids, boolean individualContent,Set<String> batchedFields, boolean laneMetadata) {
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
        // Older batch fixtures specified pending rows in their query stub, without stored metadata.
        // Lane operations also check the captured status, so make that fixture input explicit.
        if (laneMetadata && batchedFields.size() <= 1) {
          for (String status : List.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.CHUNK_EMBEDDING_STATUS,
              SchemaFields.SPLADE_STATUS, SchemaFields.NER_STATUS)) {
            if (fields.queryDocIdsByField(status, "PENDING", Integer.MAX_VALUE).contains(id)) {
              values.putIfAbsent(status, "PENDING");
              if (status.equals(SchemaFields.CHUNK_EMBEDDING_STATUS)) values.put(SchemaFields.IS_CHUNK, "true");
            }
          }
        }
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
      var stable = updates.stream().map(entry -> Map.entry(entry.getKey(),
          (Map<String, Object>) new HashMap<>(entry.getValue()))).toList();
      var result = coordinator.updateDocumentsBatchIf(stable, call.getArgument(1));
      int accepted = result == null ? updates.size() : result.updatedCount();
      List<String> ids = updates.stream().map(Map.Entry::getKey).toList();
      return new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt(
          ids.subList(0, accepted), ids.subList(accepted, ids.size()), null);
    }).when(coordinator).publishEnrichmentBatch(anyList(), anyMap());
  }

  private static void installChecked(DocumentFieldOps fields,IndexingCoordinator coordinator,
      boolean individualContent,Set<String> batchedFields, boolean laneMetadata) throws Exception {
    installReceipts(coordinator);
    installSelection(fields);
    lenient().doAnswer(call -> {
      List<String> ids = call.getArgument(0);
      Map<String, Map<String, String>> metadata = new LinkedHashMap<>(fields.getDocumentFieldsBatch(ids, call.getArgument(1)));
      for (String id : ids) {
        var row = new HashMap<>(metadata.getOrDefault(id, Map.of()));
        if (laneMetadata && batchedFields.size() <= 1) for (String status : List.of(SchemaFields.EMBEDDING_STATUS,
            SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.SPLADE_STATUS, SchemaFields.NER_STATUS)) {
          if (fields.queryDocIdsByField(status, "PENDING", Integer.MAX_VALUE).contains(id)) {
            row.putIfAbsent(status, "PENDING");
            if (status.equals(SchemaFields.CHUNK_EMBEDDING_STATUS)) row.put(SchemaFields.IS_CHUNK, "true");
          }
        }
        metadata.put(id, row);
      }
      return metadata;
    }).when(fields).getDocumentFieldsBatchOrThrow(anyList(), anySet());
    lenient().doAnswer(call -> capture(fields,call.getArgument(0),individualContent,batchedFields,laneMetadata))
        .when(fields).getEnrichmentInputs(anyList());
    lenient().doAnswer(call -> {
      List<String> ids = call.getArgument(0);
      long budget = call.getArgument(1);
      long retained = 0;
      var captured = individualContent ? Map.<String, DocumentFieldOps.EnrichmentInput>of()
          : capture(fields,ids,false,batchedFields,laneMetadata);
      Map<String, DocumentFieldOps.EnrichmentInput> result = new LinkedHashMap<>();
      for (String id : ids) {
        var input = individualContent ? capture(fields,List.of(id),true,batchedFields,laneMetadata).get(id)
            : captured.get(id);
        if (input == null) continue;
        long chars = input.content() == null ? 0 : input.content().length();
        if (!result.isEmpty() && retained + chars > budget) break;
        result.put(id, input);
        retained += chars;
        if (retained >= budget) break;
      }
      return result;
    }).when(fields).getEnrichmentInputs(anyList(), anyLong());
    lenient().doAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      var result = coordinator.updateDocumentsBatch(updates);
      return result == null ? new BatchUpdateResult(updates.size(), 0) : result;
    }).when(coordinator).updateDocumentsBatchIf(anyList(), anyMap());

  }

  /** Keep fake-index ordering, content and faults behind the new paged read signatures. */
  public static void installSelection(DocumentFieldOps fields) throws Exception {
    lenient().doAnswer(call -> {
      List<String> ids = call.getArgument(0);
      if (ids.isEmpty()) return Map.of();
      var inputs = fields.getEnrichmentInputs(ids);
      var metadata = new LinkedHashMap<String, Map<String, String>>();
      inputs.forEach((id, input) -> metadata.put(id, input.fields()));
      return metadata;
    }).when(fields).getDocumentFieldsBatchOrThrow(anyList(), anySet());
    lenient().doAnswer(call -> {
          String stage = call.getArgument(0); int limit = call.getArgument(1);
          boolean excludeChunks = call.getArgument(2); String after = call.getArgument(3);
          List<String> ids = excludeChunks ? fields.queryNonChunkDocIdsByField(stage, "PENDING", limit)
              : fields.queryDocIdsByField(stage, "PENDING", limit);
          List<String> remaining = ids.stream().sorted().filter(id -> after == null || id.compareTo(after) > 0).toList();
          return new DocumentFieldOps.PendingIdPage(remaining.stream().limit(limit).toList(), remaining.size() <= limit);
        }).when(fields).queryPendingPage(anyString(), anyInt(), anyBoolean(), nullable(String.class));
    lenient().doAnswer(call -> {
      String stage = call.getArgument(0); int limit = call.getArgument(1); String after = call.getArgument(3);
      var all = fields.queryPendingPage(stage, Integer.MAX_VALUE, false, after);
      var metadata = fields.getDocumentFieldsBatchOrThrow(all.ids(), Set.of(SchemaFields.IS_CHUNK));
      var ids = all.ids().stream().filter(id -> "true".equals(
          metadata.getOrDefault(id, Map.of()).get(SchemaFields.IS_CHUNK))).toList();
      return new DocumentFieldOps.PendingIdPage(ids.stream().limit(limit).toList(), ids.size() <= limit);
    }).when(fields).queryPendingPage(anyString(), anyInt(), eq(DocumentFieldOps.PendingDocumentKind.CHUNK), nullable(String.class));
    lenient().doAnswer(call -> {
      List<String> ids = call.getArgument(0);
      var inputs = fields.getEnrichmentInputs(ids, call.getArgument(1));
      return new DocumentFieldOps.EnrichmentInputPage(inputs,
          ids.stream().takeWhile(inputs::containsKey).toList());
    }).when(fields).getEnrichmentInputPage(anyList(), anyLong());
  }
}
