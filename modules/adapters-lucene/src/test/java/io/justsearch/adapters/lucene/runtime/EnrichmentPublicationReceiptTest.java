/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnrichmentPublicationReceiptTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test
  void mixedStaleBatchNamesOnlyAcceptedDocuments() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "first");
      seed(runtime, "stale");
      seed(runtime, "last");
      runtime.commitOps().maybeRefreshBlocking();
      var inputs = runtime.documentFieldOps().getEnrichmentInputs(List.of("first", "stale", "last"));
      runtime.indexingCoordinator().updateDocument("stale", Map.of(SchemaFields.CONTENT, "replacement"));
      var receipt = assertDoesNotThrow(() -> runtime.indexingCoordinator().publishEnrichmentBatch(
          List.of(update("first"), update("stale"), update("last")), inputs));
      assertEquals(List.of("first", "last"), receipt.acceptedIds());
      assertEquals(List.of("stale"), receipt.rejectedIds());
      assertNull(receipt.failure());
      assertEquals(2, receipt.result().updatedCount());
      assertEquals(1, receipt.result().notFoundCount());
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("stale", SchemaFields.NER_STATUS));
    }
  }

  @Test
  void laterMutationFailureRetainsAcceptedPrefixAndUnattemptedSuffix() throws Exception {
    try (var runtime = open()) {
      for (String id : List.of("first", "broken", "last")) seed(runtime, id);
      runtime.commitOps().maybeRefreshBlocking();
      var inputs = runtime.documentFieldOps().getEnrichmentInputs(List.of("first", "broken", "last"));
      Map<String, Object> invalid = Map.of(SchemaFields.SPLADE, "invalid sparse vector",
          SchemaFields.SPLADE_STATUS, "COMPLETED");
      var receipt = assertDoesNotThrow(() -> runtime.indexingCoordinator().publishEnrichmentBatch(
          List.of(update("first"), Map.entry("broken", invalid), update("last")), inputs));
      assertEquals(List.of("first"), receipt.acceptedIds());
      assertTrue(receipt.rejectedIds().isEmpty(), "failed/unattempted mutations are not stale rejections");
      assertNotNull(receipt.failure());
      assertThrows(RuntimeException.class, receipt::result);
      runtime.commitOps().commitAndTrack();
      assertEquals("COMPLETED_EMPTY", runtime.documentFieldOps().getDocumentField("first", SchemaFields.NER_STATUS));
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("last", SchemaFields.NER_STATUS));
    }
  }

  @Test
  void acceptedPrefixRetainsOnlyAppliedMapperCorrections() throws Exception {
    try (var runtime = open()) {
      for (String id : List.of("dropped", "valid", "broken", "tail")) seed(runtime, id);
      runtime.commitOps().maybeRefreshBlocking();
      var inputs = runtime.documentFieldOps().getEnrichmentInputs(List.of("dropped", "valid", "broken", "tail"));
      Map<String, Object> dropped = Map.of(SchemaFields.VECTOR, new float[] {Float.NaN, 1},
          SchemaFields.EMBEDDING_STATUS, "COMPLETED", SchemaFields.NER_STATUS, "COMPLETED_EMPTY");
      Map<String, Object> valid = Map.of(SchemaFields.VECTOR, new float[] {1, 0},
          SchemaFields.EMBEDDING_STATUS, "COMPLETED");
      Map<String, Object> invalid = Map.of(SchemaFields.SPLADE, "invalid sparse vector",
          SchemaFields.SPLADE_STATUS, "COMPLETED");
      var receipt = assertDoesNotThrow(() -> runtime.indexingCoordinator().publishEnrichmentBatch(
          List.of(Map.entry("dropped", dropped), Map.entry("valid", valid),
              Map.entry("broken", invalid), Map.entry("tail", dropped)), inputs));
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("dropped", SchemaFields.EMBEDDING_STATUS));
      assertEquals("COMPLETED_EMPTY", runtime.documentFieldOps().getDocumentField("dropped", SchemaFields.NER_STATUS));
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("valid", SchemaFields.EMBEDDING_STATUS));
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("tail", SchemaFields.EMBEDDING_STATUS));
      assertEquals(Map.of("dropped", java.util.Set.of(SchemaFields.EMBEDDING_STATUS)),
          receipt.correctedFailedStatuses());
      assertEquals(List.of("dropped", "valid"), receipt.acceptedIds());
      assertNotNull(receipt.failure());
    }
  }

  private RunningRuntime open() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(2).fields());
    for (String name : List.of(SchemaFields.NER_STATUS, SchemaFields.NER_RETRY_COUNT,
        SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT)) {
      fields.add(new FieldDef(name, name.endsWith("_count") ? "long" : "keyword",
          true, true, List.of("filter"), null, null, false));
    }
    fields.add(new FieldDef(SchemaFields.SPLADE, "splade", false, false, List.of(), null,
        null, false, "reset-status:splade_status"));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("enrichment-receipt-test", fields))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static void seed(RunningRuntime runtime, String id) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "uid-" + id,
        SchemaFields.CONTENT, "body " + id, SchemaFields.NER_STATUS, "PENDING",
        SchemaFields.NER_RETRY_COUNT, "0", SchemaFields.EMBEDDING_STATUS, "PENDING")));
  }

  private static Map.Entry<String, Map<String, Object>> update(String id) {
    return Map.entry(id, Map.of(SchemaFields.NER_STATUS, "COMPLETED_EMPTY", SchemaFields.NER_RETRY_COUNT, "0"));
  }
}
