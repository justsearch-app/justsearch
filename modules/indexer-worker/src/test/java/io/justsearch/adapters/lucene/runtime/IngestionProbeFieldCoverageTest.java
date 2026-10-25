/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;

import io.justsearch.configuration.FieldCatalogDef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Capture the actual probe document so every mapper storage family has a coverage oracle. */
final class IngestionProbeFieldCoverageTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test
  void actualCanaryCoversEveryMapperFieldFamilyIncludingVectors() throws Exception {
    var fields = new ArrayList<>(FieldCatalogDef.forTesting(4).fields());
    fields.add(new FieldCatalogDef.FieldDef("canary_tags", "keyword", true, true,
        List.of("filter"), null, null, true));
    fields.add(new FieldCatalogDef.FieldDef("splade_status", "keyword", true, true,
        List.of("filter"), null, null, false));
    fields.add(new FieldCatalogDef.FieldDef("splade", "splade", false, false,
        List.of(), null, null, false, "reset-status:splade_status"));
    var captured = new AtomicReference<Document>();
    try (var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("canary-coverage", fields))
        .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      try (var writers = mockConstruction(IndexWriter.class, (writer, context) -> {
        doAnswer(call -> {
          captured.set(call.getArgument(0));
          return 0L;
        }).when(writer).addDocument(any());
      })) {
        runtime.indexingCoordinator().probeIngestion();
        assertEquals(1, writers.constructed().size());
      }
      Document document = captured.get();
      assertNotNull(document);
      System.out.println("Actual canary field families: " + document.getFields());
      assertAll(
          () -> assertTrue(fields.stream().allMatch(f -> document.getFields(f.id()).length > 0),
              "every configured mapper field receives representative input"),
          () -> assertTrue(document.getFields().stream().anyMatch(f ->
              f.fieldType().docValuesType() == DocValuesType.SORTED), "keyword doc values"),
          () -> assertTrue(document.getFields().stream().anyMatch(f ->
              f.fieldType().docValuesType() == DocValuesType.SORTED_SET), "multi-valued doc values"),
          () -> assertTrue(document.getFields().stream().anyMatch(f ->
              f.fieldType().docValuesType() == DocValuesType.NUMERIC), "numeric/boolean doc values"),
          () -> assertTrue(document.getFields().stream().anyMatch(f ->
              f.fieldType().pointDimensionCount() > 0), "numeric points"),
          () -> assertTrue(document.getFields().stream().anyMatch(f ->
              f.fieldType().stored()), "stored fields"),
          () -> {
            assertNotNull(document.getField("vector"), "dense vectors are populated");
            assertEquals(4, document.getField("vector").fieldType().vectorDimension(),
                "dense vectors use the catalog dimension");
          },
          () -> assertTrue(document.getField("splade") instanceof FeatureField, "sparse features"));
    }
  }
}
