package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.configuration.FieldCatalogDef.VectorSpec;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.DocValuesType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FieldMapper} using explicit configuration injection.
 *
 * <p>These tests use {@link FieldCatalogDef} POJOs instead of relying on
 * filesystem auto-discovery, making them hermetic and deterministic.
 */
class FieldMapperTest {

  @Test
  void realCatalogDoubleUsesStoredRawNumericDocValuesAndPoints() {
    var mapper = new FieldMapper(new io.justsearch.configuration.JustSearchConfigurationLoader().loadFieldCatalog());
    String quality = SchemaFields.EXTRACTION_QUALITY_SCORE;
    for (Object value : List.of(0.1, 0.0, 1.0, "0.1", 1L, new java.math.BigDecimal("0.1"))) {
      var doc = mapper.toDocument(Map.of(SchemaFields.DOC_ID, "valid", quality, value), null);
      double expected = Double.parseDouble(value.toString());
      assertTrue(doc.getFields().stream().anyMatch(f -> f.name().equals(quality)
          && f.fieldType().stored() && f.numericValue() instanceof Double
          && f.numericValue().doubleValue() == expected));
      assertTrue(doc.getFields().stream().anyMatch(f -> f.name().equals(quality)
          && f.fieldType().docValuesType() == DocValuesType.NUMERIC
          && Double.longBitsToDouble(f.numericValue().longValue()) == expected));
      assertTrue(doc.getFields().stream().anyMatch(f -> f.name().equals(quality)
          && f.fieldType().pointDimensionCount() == 1));
    }
  }

  @Test
  void invalidDoubleIsOmittedWithoutLosingOtherFields() {
    var mapper = new FieldMapper(new io.justsearch.configuration.JustSearchConfigurationLoader().loadFieldCatalog());
    for (Object value : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        "NaN", "Infinity", "nonsense", true, List.of(0.1))) {
      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, "kept");
      fields.put(SchemaFields.EXTRACTION_QUALITY_SCORE, value);
      var doc = mapper.toDocument(fields, null);
      assertEquals("kept", doc.get(SchemaFields.DOC_ID));
      assertEquals(0, doc.getFields(SchemaFields.EXTRACTION_QUALITY_SCORE).length, String.valueOf(value));
    }
  }

  @Test
  void ingestionCanaryExercisesRealCatalogDouble() {
    var mapper = new FieldMapper(new io.justsearch.configuration.JustSearchConfigurationLoader().loadFieldCatalog());
    var doc = mapper.ingestionCanaryDocument();
    assertTrue(doc.getFields().stream().anyMatch(f -> f.name().equals(SchemaFields.EXTRACTION_QUALITY_SCORE)
        && f.fieldType().stored() && f.numericValue().doubleValue() == 0.1));
    assertTrue(doc.getFields().stream().anyMatch(f -> f.name().equals(SchemaFields.EXTRACTION_QUALITY_SCORE)
        && f.fieldType().docValuesType() == DocValuesType.NUMERIC));
  }

  /**
   * Creates a comprehensive test catalog with all commonly used fields.
   *
   * <p>Uses a small vector dimension (4) for fast, lightweight tests.
   */
  private static FieldCatalogDef createTestCatalog() {
    return createTestCatalog(4);
  }

  /**
   * Creates a test catalog with a specific vector dimension.
   */
  private static FieldCatalogDef createTestCatalog(int vectorDim) {
    return new FieldCatalogDef("test-v1", List.of(
        // Primary key (required)
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        // Tiebreaker (required)
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        // Text fields
        new FieldDef("title", "text", true, false, List.of("highlight"), null, "icu", false),
        new FieldDef("content", "text", false, false, List.of("highlight"), null, "icu", false),
        // Keyword fields
        new FieldDef("path", "keyword", true, true, List.of("filter", "sort"), null, null, false),
        new FieldDef("mime", "keyword", false, true, List.of("filter", "facet"), null, null, false),
        new FieldDef("language", "keyword", false, true, List.of("filter", "facet"), null, null, false),
        new FieldDef("source_sha256", "keyword", true, false, List.of(), null, null, false),
        // Numeric fields
        new FieldDef("created_at", "long", false, true, List.of("filter", "sort"), null, null, false),
        new FieldDef("modified_at", "long", false, true, List.of("filter", "sort"), null, null, false),
        new FieldDef("size_bytes", "long", false, true, List.of("filter", "sort"), null, null, false),
        // Boolean field
        new FieldDef("ocr_present", "boolean", false, true, List.of("filter"), null, null, false),
        // Vector field
        new FieldDef("vector", "vector", false, false, List.of("vector"), new VectorSpec(vectorDim), null, false)
    ));
  }

  @Test
  void mapsTextStored() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Map<String, Object> in = Map.of("title", "Hello");
    Document doc = fm.toDocument(in, null);
    assertEquals("Hello", doc.get("title")); // stored text accessible
  }

  @Test
  void mapsKeywordStoredDocValues() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Map<String, Object> in =
        Map.of(
            "path", "/tmp/file.txt",
            SchemaFields.DOC_ID, "doc-1",
            SchemaFields.DOC_UID, "doc-1#1");
    Document doc = fm.toDocument(in, null);
    assertEquals("/tmp/file.txt", doc.get("path")); // stored keyword accessible
    assertTrue(doc.getFields().stream().anyMatch(f -> f.name().equals("path")));
  }

  @Test
  void mapsStoredOnlyKeyword() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Document doc = fm.toDocument(Map.of("source_sha256", "a".repeat(64)), null);

    assertEquals("a".repeat(64), doc.get("source_sha256"));
    assertTrue(
        doc.getFields("source_sha256")[0].fieldType().docValuesType()
            == DocValuesType.NONE);
  }

  @Test
  void mapsLongBooleanDocValues() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Map<String, Object> in = new HashMap<>();
    in.put("size_bytes", 123L);
    in.put("ocr_present", true);
    Document doc = fm.toDocument(in, null);
    // Not stored by default; verify fields were added
    assertTrue(doc.getFields().size() >= 2);
  }

  @Test
  void mapsVectorWithDimensionCheck() {
    // Use a catalog with dim=4 and a matching 4-dim vector
    FieldMapper fm = new FieldMapper(createTestCatalog(4));
    float[] vec = new float[4];
    Map<String, Object> in = Map.of("vector", vec);
    Document doc = fm.toDocument(in, null);
    assertNotNull(doc);
    assertTrue(doc.getFields().size() >= 1);
  }

  /**
   * Tempdoc 931 §C.3: a non-finite vector used to reach Lucene's {@code KnnFloatVectorField},
   * which throws — and the throw aborted the caller's whole batch ({@code
   * IndexingCoordinator.indexBatch} builds every document before it writes any), so one degenerate
   * embedding cost every other document in it. The vector is still refused; the document is not.
   */
  @Test
  void dropsNonFiniteVectorsWithoutLosingTheDocument() {
    FieldMapper fm = new FieldMapper(createTestCatalog(4));

    for (float[] degenerate :
        List.of(
            new float[] {Float.NaN, 0.0f, 0.0f, 0.0f},
            new float[] {0.0f, Float.POSITIVE_INFINITY, 0.0f, 0.0f})) {
      FieldMapper.DroppedVectorReport report = new FieldMapper.DroppedVectorReport();
      Map<String, Object> in = new HashMap<>();
      in.put("doc_id", "doc-0");
      in.put("title", "kept");
      in.put("vector", degenerate);

      Document doc = fm.toDocument(in, report);

      assertNull(doc.getField("vector"), "the unusable vector must not reach Lucene");
      assertNotNull(doc.getField("doc_id"), "every other field of the document survives");
      assertNotNull(doc.getField("title"), "every other field of the document survives");
      assertEquals(1, report.count(), "the drop is counted for the caller's batch WARN + metric");
      assertEquals(List.of("doc-0/vector"), report.sampleDocIds());
      assertNotNull(report.lastReason(), "the rejection reason is carried for the WARN");
    }
  }

  /**
   * A healthy vector is untouched by the drop path — the report stays empty. A zero vector is
   * healthy under the catalog's Euclidean similarity (Lucene accepts it), so it is written, not
   * dropped; that changes only if a normalizing similarity is adopted.
   */
  @Test
  void aUsableVectorIsNotReportedAsDropped() {
    FieldMapper fm = new FieldMapper(createTestCatalog(4));

    for (float[] usable : List.of(new float[] {3f, 4f, 0f, 0f}, new float[4])) {
      FieldMapper.DroppedVectorReport report = new FieldMapper.DroppedVectorReport();
      Document doc = fm.toDocument(Map.of("doc_id", "doc-0", "vector", usable), report);
      assertNotNull(doc.getField("vector"));
      assertEquals(0, report.count());
    }
  }

  @Test
  void vectorDimensionMismatchFails() {
    // Catalog expects dim=4, but we provide dim=100
    FieldMapper fm = new FieldMapper(createTestCatalog(4));
    float[] vec = new float[100];
    Map<String, Object> in = Map.of("vector", vec);
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> fm.toDocument(in, null));
    assertTrue(ex.getMessage().contains("dimension mismatch"),
        "Error should mention dimension mismatch");
  }

  @Test
  void unknownFieldsYieldFallbackStored() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Map<String, Object> in = Map.of("unknown_field", "v");
    Document doc = fm.toDocument(in, null);
    assertTrue(doc.getFields().size() >= 1); // fallback _ingest_ts stored field
  }

  @Test
  void keywordNotStoredStillAddsDocValues() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    // Note: mime has roles: ["filter", "facet"], so it gets StringField (Fix #5)
    // StringField.stringValue() is available in memory, but Store.NO means not persisted
    Map<String, Object> in = Map.of("mime", "pdf");
    Document doc = fm.toDocument(in, null);
    // Verify field was added (StringField + SortedDocValuesField)
    assertTrue(doc.getFields().stream().anyMatch(f -> f.name().equals("mime")));
  }

  @Test
  void booleanNumericMapping() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Map<String, Object> in = Map.of("ocr_present", 0); // numeric -> false
    Document doc = fm.toDocument(in, null);
    assertNotNull(doc);
    assertTrue(doc.getFields().size() >= 1);
  }

  @Test
  void stringToLongAndBooleanParsing() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Map<String, Object> in = new HashMap<>();
    in.put("size_bytes", "456"); // string parse to long
    in.put("ocr_present", "true"); // string parse to boolean
    in.put(SchemaFields.DOC_ID, "doc-parse");
    in.put(SchemaFields.DOC_UID, "doc-parse#1");
    Document doc = fm.toDocument(in, null);
    assertTrue(doc.getFields().size() >= 2);
  }

  @Test
  void vectorFromList() {
    // Use a catalog with dim=4 and a matching 4-element list
    FieldMapper fm = new FieldMapper(createTestCatalog(4));
    List<Double> lst = new ArrayList<>();
    for (int i = 0; i < 4; i++) lst.add(0.0);
    Map<String, Object> in =
        Map.of(
            "vector", lst,
            SchemaFields.DOC_ID, "doc-vector",
            SchemaFields.DOC_UID, "doc-vector#1");
    Document doc = fm.toDocument(in, null);
    assertNotNull(doc);
    assertTrue(doc.getFields().size() >= 1);
  }

  @Test
  void primaryKeyFieldIndexedAndDocValued() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Document doc =
        fm.toDocument(
            Map.of(SchemaFields.DOC_ID, "doc-42", SchemaFields.DOC_UID, "doc-42#0"), null);
    List<IndexableField> fields = Arrays.asList(doc.getFields(SchemaFields.DOC_ID));
    assertTrue(fields.stream().anyMatch(f -> f.fieldType().indexOptions() != IndexOptions.NONE));
    assertTrue(fields.stream().anyMatch(f -> f.fieldType().docValuesType() != DocValuesType.NONE));
  }

  @Test
  void docUidOnlyDocValues() {
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Document doc =
        fm.toDocument(
            Map.of(SchemaFields.DOC_ID, "doc-uid", SchemaFields.DOC_UID, "doc-uid#0"), null);
    List<IndexableField> fields = Arrays.asList(doc.getFields(SchemaFields.DOC_UID));
    assertTrue(fields.stream().allMatch(f -> f.fieldType().indexOptions() == IndexOptions.NONE));
    assertTrue(fields.stream().anyMatch(f -> f.fieldType().docValuesType() != DocValuesType.NONE));
  }

  // ========== Additional tests to verify injection works ==========

  @Test
  void explicitCatalog_overridesAutoDiscovery() {
    // Create catalog with dim=8
    FieldMapper explicitMapper = new FieldMapper(createTestCatalog(8));
    assertEquals(Integer.valueOf(8), explicitMapper.ssotVectorDimensionOrNull(),
        "Explicit injection should use our dimension");
  }

  @Test
  void differentCatalogs_produceDifferentBehavior() {
    FieldMapper dim4Mapper = new FieldMapper(createTestCatalog(4));
    FieldMapper dim16Mapper = new FieldMapper(createTestCatalog(16));

    assertEquals(Integer.valueOf(4), dim4Mapper.ssotVectorDimensionOrNull());
    assertEquals(Integer.valueOf(16), dim16Mapper.ssotVectorDimensionOrNull());

    // 4-dim vector should work with dim4Mapper but fail with dim16Mapper
    float[] vec4 = new float[4];
    Map<String, Object> in = Map.of("vector", vec4);

    assertNotNull(dim4Mapper.toDocument(in, null), "4-dim vector should work with dim=4 catalog");
    assertThrows(IllegalArgumentException.class, () -> dim16Mapper.toDocument(in, null),
        "4-dim vector should fail with dim=16 catalog");
  }

  @Test
  void keywordWithFilterRoleIsIndexed() {
    // Fix #5: Verify that keyword fields with 'filter' role get StringField (inverted index)
    // This enables TermQuery lookups for embedding_status, vdu_status, etc.
    FieldMapper fm = new FieldMapper(createTestCatalog());
    Map<String, Object> in = Map.of(
        "mime", "application/pdf",
        SchemaFields.DOC_ID, "doc-filter",
        SchemaFields.DOC_UID, "doc-filter#1");
    Document doc = fm.toDocument(in, null);

    // Verify StringField was added (has IndexOptions != NONE)
    List<IndexableField> mimeFields = Arrays.asList(doc.getFields("mime"));
    assertTrue(mimeFields.stream()
        .anyMatch(f -> f.fieldType().indexOptions() != IndexOptions.NONE),
        "Keyword with filter role should have inverted index (StringField)");
    // Also verify DocValues still present
    assertTrue(mimeFields.stream()
        .anyMatch(f -> f.fieldType().docValuesType() != DocValuesType.NONE),
        "Keyword with filter role should still have DocValues for sorting");
  }

  // ========== VDU Field Mapping Tests ==========

  /**
   * Creates a test catalog including VDU-specific fields.
   * Matches the schema in SSOT/catalogs/fields.v1.json.
   */
  private static FieldCatalogDef createVduTestCatalog() {
    return new FieldCatalogDef("vdu-test-v1", List.of(
        // Primary key (required)
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        // Content fields
        new FieldDef("content", "text", false, false, List.of("highlight"), null, "icu", false),
        // VDU fields (matching SSOT)
        new FieldDef("vdu_status", "keyword", true, true, List.of("filter"), null, null, false),
        new FieldDef("vdu_retry_count", "long", true, true, List.of(), null, null, false),
        new FieldDef("vdu_processed", "boolean", true, true, List.of(), null, null, false),
        new FieldDef("vdu_enrichment", "text", true, false, List.of(), null, null, false),
        new FieldDef("vdu_page_count", "long", true, true, List.of(), null, null, false),
        // Embedding status
        new FieldDef("embedding_status", "keyword", true, true, List.of("filter"), null, null, false),
        new FieldDef("embedding_retry_count", "long", true, true, List.of(), null, null, false)
    ));
  }

  @Test
  void mapsVduStatusKeywordWithFilterRole() {
    FieldMapper fm = new FieldMapper(createVduTestCatalog());
    Map<String, Object> in = Map.of(
        "vdu_status", "PENDING",
        SchemaFields.DOC_ID, "doc-vdu-1",
        SchemaFields.DOC_UID, "doc-vdu-1#1");
    Document doc = fm.toDocument(in, null);

    // vdu_status should be stored
    assertEquals("PENDING", doc.get("vdu_status"), "vdu_status should be stored");

    // Verify filter role creates StringField (indexed)
    List<IndexableField> fields = Arrays.asList(doc.getFields("vdu_status"));
    assertTrue(fields.stream()
        .anyMatch(f -> f.fieldType().indexOptions() != IndexOptions.NONE),
        "vdu_status with filter role should be indexed (StringField)");
    // And DocValues for sorting/filtering
    assertTrue(fields.stream()
        .anyMatch(f -> f.fieldType().docValuesType() != DocValuesType.NONE),
        "vdu_status should have DocValues");
  }

  @Test
  void mapsVduRetryCountLong() {
    FieldMapper fm = new FieldMapper(createVduTestCatalog());
    Map<String, Object> in = Map.of(
        "vdu_retry_count", 2,
        SchemaFields.DOC_ID, "doc-retry",
        SchemaFields.DOC_UID, "doc-retry#1");
    Document doc = fm.toDocument(in, null);

    // Should be stored
    assertEquals("2", doc.get("vdu_retry_count"), "vdu_retry_count should be stored");

    // Verify NumericDocValues present
    List<IndexableField> fields = Arrays.asList(doc.getFields("vdu_retry_count"));
    assertTrue(fields.stream()
        .anyMatch(f -> f.fieldType().docValuesType() == DocValuesType.NUMERIC),
        "vdu_retry_count should have NumericDocValues");
  }

  @Test
  void mapsVduProcessedBoolean() {
    FieldMapper fm = new FieldMapper(createVduTestCatalog());
    Map<String, Object> in = Map.of(
        "vdu_processed", true,
        SchemaFields.DOC_ID, "doc-processed",
        SchemaFields.DOC_UID, "doc-processed#1");
    Document doc = fm.toDocument(in, null);

    // Should be stored as "1" (true)
    String stored = doc.get("vdu_processed");
    assertTrue("1".equals(stored) || "true".equals(stored),
        "vdu_processed should be stored as truthy value");
  }

  @Test
  void storedNumericBooleanStringsKeepBooleanMeaningWithoutChangingOtherInputs() {
    var mapper = new FieldMapper(createVduTestCatalog());
    for (var entry : Map.<Object, String>ofEntries(
        Map.entry("1", "1"), Map.entry("0", "0"), Map.entry("true", "1"),
        Map.entry("false", "0"), Map.entry(true, "1"), Map.entry(false, "0"),
        Map.entry(7, "1"), Map.entry(0, "0"), Map.entry("2", "0")).entrySet()) {
      var document = mapper.toDocument(Map.of(SchemaFields.DOC_ID, "bool",
          SchemaFields.DOC_UID, "bool", SchemaFields.VDU_PROCESSED, entry.getKey()), null);
      assertEquals(entry.getValue(), document.get(SchemaFields.VDU_PROCESSED), entry.getKey().toString());
    }
    assertNull(mapper.toDocument(Map.of(SchemaFields.DOC_ID, "absent",
        SchemaFields.DOC_UID, "absent"), null).get(SchemaFields.VDU_PROCESSED));
  }

  @Test
  void mapsVduEnrichmentText() {
    FieldMapper fm = new FieldMapper(createVduTestCatalog());
    String enrichmentJson = "{\"summary\":\"Invoice document\",\"doc_type\":\"invoice\"}";
    Map<String, Object> in = Map.of(
        "vdu_enrichment", enrichmentJson,
        SchemaFields.DOC_ID, "doc-enrichment",
        SchemaFields.DOC_UID, "doc-enrichment#1");
    Document doc = fm.toDocument(in, null);

    // Should be stored (text field)
    assertEquals(enrichmentJson, doc.get("vdu_enrichment"),
        "vdu_enrichment text should be stored");
  }

  @Test
  void mapsVduPageCountLong() {
    FieldMapper fm = new FieldMapper(createVduTestCatalog());
    Map<String, Object> in = Map.of(
        "vdu_page_count", 5,
        SchemaFields.DOC_ID, "doc-pages",
        SchemaFields.DOC_UID, "doc-pages#1");
    Document doc = fm.toDocument(in, null);

    assertEquals("5", doc.get("vdu_page_count"), "vdu_page_count should be stored");
  }

  @Test
  void mapsEmbeddingStatusKeywordWithFilterRole() {
    FieldMapper fm = new FieldMapper(createVduTestCatalog());
    Map<String, Object> in = Map.of(
        "embedding_status", "PENDING",
        SchemaFields.DOC_ID, "doc-embed",
        SchemaFields.DOC_UID, "doc-embed#1");
    Document doc = fm.toDocument(in, null);

    // Should be stored
    assertEquals("PENDING", doc.get("embedding_status"), "embedding_status should be stored");

    // Verify filter role creates indexed field
    List<IndexableField> fields = Arrays.asList(doc.getFields("embedding_status"));
    assertTrue(fields.stream()
        .anyMatch(f -> f.fieldType().indexOptions() != IndexOptions.NONE),
        "embedding_status with filter role should be indexed");
  }

  @Test
  void mapsAllVduFieldsTogether() {
    // Verify all VDU fields can be mapped in a single document
    FieldMapper fm = new FieldMapper(createVduTestCatalog());
    Map<String, Object> in = new HashMap<>();
    in.put(SchemaFields.DOC_ID, "doc-full-vdu");
    in.put(SchemaFields.DOC_UID, "doc-full-vdu#1");
    in.put("vdu_status", "COMPLETED");
    in.put("vdu_retry_count", 1);
    in.put("vdu_processed", true);
    in.put("vdu_enrichment", "{\"summary\":\"Test\"}");
    in.put("vdu_page_count", 3);
    in.put("embedding_status", "PENDING");
    in.put("embedding_retry_count", 0);

    Document doc = fm.toDocument(in, null);

    assertEquals("COMPLETED", doc.get("vdu_status"));
    assertEquals("1", doc.get("vdu_retry_count"));
    assertEquals("{\"summary\":\"Test\"}", doc.get("vdu_enrichment"));
    assertEquals("3", doc.get("vdu_page_count"));
    assertEquals("PENDING", doc.get("embedding_status"));
  }

  @Test
  void mapsMultiValuedKeywordToSortedSetDocValues() {
    FieldCatalogDef catalog = new FieldCatalogDef("mv-test", List.of(
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        new FieldDef("tags", "keyword", true, true, List.of("filter", "facet"), null, null, true)
    ));
    FieldMapper fm = new FieldMapper(catalog);
    Map<String, Object> in = new HashMap<>();
    in.put(SchemaFields.DOC_ID, "doc-mv");
    in.put(SchemaFields.DOC_UID, "doc-mv#1");
    in.put("tags", List.of("Alice", "Bob"));
    Document doc = fm.toDocument(in, null);

    // Should have 2 StringField entries (filter role) and 2 SortedSetDocValuesField entries
    long stringFieldCount = doc.getFields().stream()
        .filter(f -> f.name().equals("tags") && f.fieldType().indexOptions() != IndexOptions.NONE)
        .count();
    assertEquals(2, stringFieldCount, "Should have 2 StringField entries for filter role");

    long dvCount = doc.getFields().stream()
        .filter(f -> f.name().equals("tags") && f.fieldType().docValuesType() == DocValuesType.SORTED_SET)
        .count();
    assertEquals(2, dvCount, "Should have 2 SortedSetDocValuesField entries");
  }

  @Test
  void mapsMultiValuedKeywordSingleValueFallback() {
    FieldCatalogDef catalog = new FieldCatalogDef("mv-test", List.of(
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        new FieldDef("tags", "keyword", true, true, List.of("filter", "facet"), null, null, true)
    ));
    FieldMapper fm = new FieldMapper(catalog);
    Map<String, Object> in = new HashMap<>();
    in.put(SchemaFields.DOC_ID, "doc-single");
    in.put(SchemaFields.DOC_UID, "doc-single#1");
    in.put("tags", "OnlyOne");
    Document doc = fm.toDocument(in, null);

    long dvCount = doc.getFields().stream()
        .filter(f -> f.name().equals("tags") && f.fieldType().docValuesType() == DocValuesType.SORTED_SET)
        .count();
    assertEquals(1, dvCount, "Single string should produce 1 SortedSetDocValuesField");
  }

  @Test
  void mapsMultiValuedKeywordEmptyListProducesNoFields() {
    FieldCatalogDef catalog = new FieldCatalogDef("mv-test", List.of(
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        new FieldDef("tags", "keyword", true, true, List.of("filter", "facet"), null, null, true)
    ));
    FieldMapper fm = new FieldMapper(catalog);
    Map<String, Object> in = new HashMap<>();
    in.put(SchemaFields.DOC_ID, "doc-empty");
    in.put(SchemaFields.DOC_UID, "doc-empty#1");
    in.put("tags", List.of());
    Document doc = fm.toDocument(in, null);

    long tagFields = doc.getFields().stream().filter(f -> f.name().equals("tags")).count();
    assertEquals(0, tagFields, "Empty list should produce no tag fields");
  }

  @Test
  void mapsMultiValuedKeywordNullEntriesFiltered() {
    FieldCatalogDef catalog = new FieldCatalogDef("mv-test", List.of(
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        new FieldDef("tags", "keyword", true, true, List.of("filter", "facet"), null, null, true)
    ));
    FieldMapper fm = new FieldMapper(catalog);
    // ArrayList allows null entries (unlike List.of)
    ArrayList<String> values = new ArrayList<>();
    values.add("Alice");
    values.add(null);
    values.add("Bob");
    Map<String, Object> in = new HashMap<>();
    in.put(SchemaFields.DOC_ID, "doc-nulls");
    in.put(SchemaFields.DOC_UID, "doc-nulls#1");
    in.put("tags", values);
    Document doc = fm.toDocument(in, null);

    long dvCount = doc.getFields().stream()
        .filter(f -> f.name().equals("tags") && f.fieldType().docValuesType() == DocValuesType.SORTED_SET)
        .count();
    assertEquals(2, dvCount, "Null entries should be filtered, leaving 2 values");
  }

  @Test
  void mapsMultiValuedKeywordRequiresDocValues() {
    FieldCatalogDef catalog = new FieldCatalogDef("mv-test", List.of(
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        new FieldDef("tags", "keyword", true, false, List.of("filter"), null, null, true)
    ));
    assertThrows(IllegalStateException.class, () -> new FieldMapper(catalog),
        "multiValued=true with docValues=false should throw");
  }

  // ========== SPLADE FeatureField Mapping Tests ==========

  private static FieldCatalogDef createSpladeTestCatalog() {
    return new FieldCatalogDef("splade-test-v1", List.of(
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null, false),
        new FieldDef("splade", "splade", false, false, List.of(), null, null, false)
    ));
  }

  @Nested
  class SpladeFieldMapping {

    @Test
    void indexesSpladeTokensAsFeatureFields() {
      FieldMapper fm = new FieldMapper(createSpladeTestCatalog());
      Map<String, Object> in = new HashMap<>();
      in.put(SchemaFields.DOC_ID, "doc-splade");
      in.put(SchemaFields.DOC_UID, "doc-splade#1");
      in.put("splade", Map.of("weather", 4.5f, "forecast", 2.1f, "rain", 0.8f));
      Document doc = fm.toDocument(in, null);

      long featureCount = doc.getFields().stream()
          .filter(f -> f.name().equals("splade") && f instanceof FeatureField)
          .count();
      assertEquals(3, featureCount, "Should have 3 FeatureField entries");
    }

    @Test
    void clampsWeightAbove64() {
      FieldMapper fm = new FieldMapper(createSpladeTestCatalog());
      Map<String, Object> in = new HashMap<>();
      in.put(SchemaFields.DOC_ID, "doc-clamp");
      in.put(SchemaFields.DOC_UID, "doc-clamp#1");
      in.put("splade", Map.of("extreme", 100.0f, "normal", 5.0f));
      Document doc = fm.toDocument(in, null);

      // Both tokens should be indexed (extreme is clamped, not dropped)
      long featureCount = doc.getFields().stream()
          .filter(f -> f.name().equals("splade") && f instanceof FeatureField)
          .count();
      assertEquals(2, featureCount, "Clamped token should still be indexed");
    }

    @Test
    void skipsZeroWeightTokens() {
      FieldMapper fm = new FieldMapper(createSpladeTestCatalog());
      Map<String, Object> in = new HashMap<>();
      in.put(SchemaFields.DOC_ID, "doc-zero");
      in.put(SchemaFields.DOC_UID, "doc-zero#1");
      in.put("splade", Map.of("valid", 1.0f, "zero", 0.0f));
      Document doc = fm.toDocument(in, null);

      long featureCount = doc.getFields().stream()
          .filter(f -> f.name().equals("splade") && f instanceof FeatureField)
          .count();
      assertEquals(1, featureCount, "Zero-weight token should be skipped");
    }

    @Test
    void handlesEmptyMap() {
      FieldMapper fm = new FieldMapper(createSpladeTestCatalog());
      Map<String, Object> in = new HashMap<>();
      in.put(SchemaFields.DOC_ID, "doc-empty-splade");
      in.put(SchemaFields.DOC_UID, "doc-empty-splade#1");
      in.put("splade", Map.of());
      Document doc = fm.toDocument(in, null);

      long featureCount = doc.getFields().stream()
          .filter(f -> f.name().equals("splade") && f instanceof FeatureField)
          .count();
      assertEquals(0, featureCount, "Empty map should produce no FeatureFields");
    }

    @Test
    void handlesNullValue() {
      FieldMapper fm = new FieldMapper(createSpladeTestCatalog());
      Map<String, Object> in = new HashMap<>();
      in.put(SchemaFields.DOC_ID, "doc-null-splade");
      in.put(SchemaFields.DOC_UID, "doc-null-splade#1");
      in.put("splade", null);
      Document doc = fm.toDocument(in, null);

      long featureCount = doc.getFields().stream()
          .filter(f -> f.name().equals("splade") && f instanceof FeatureField)
          .count();
      assertEquals(0, featureCount, "Null value should produce no FeatureFields");
    }
  }

  @Test
  void vduStatusTransitionValues() {
    // Test all valid VDU status values map correctly
    FieldMapper fm = new FieldMapper(createVduTestCatalog());

    for (String status : List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED", "SKIPPED")) {
      Map<String, Object> in = Map.of(
          "vdu_status", status,
          SchemaFields.DOC_ID, "doc-" + status.toLowerCase(java.util.Locale.ROOT),
          SchemaFields.DOC_UID, "doc-" + status.toLowerCase(java.util.Locale.ROOT) + "#1");
      Document doc = fm.toDocument(in, null);
      assertEquals(status, doc.get("vdu_status"),
          "Status " + status + " should be stored correctly");
    }
  }
}
