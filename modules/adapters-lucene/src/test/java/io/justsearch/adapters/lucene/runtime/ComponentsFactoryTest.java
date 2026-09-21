package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SoftDeletesMetrics;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.SoftDeletesRetentionMergePolicy;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

class ComponentsFactoryTest {

  @TempDir Path tempDir;

  private FieldMapper fieldMapper;
  private SsotAnalyzerRegistry analyzerRegistry;
  private NrtReopenStats nrtStats;

  @BeforeEach
  void setUp() {
    fieldMapper = new FieldMapper(FieldCatalogDef.forTesting(4));
    analyzerRegistry = new SsotAnalyzerRegistry();
    nrtStats = new NrtReopenStats();
  }

  private static ResolvedConfig resolveForTest(String yaml) {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeEnvRegistry();
    try {
      JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(yaml);
      builder.contributeYaml(root);
    } catch (Exception ignored) {
      // test helper: invalid/empty yaml falls back to the env-registry-only config
    }
    return builder.build();
  }

  private Components buildComponents(
      String yaml, Path path, boolean readOnly, KnnVectorsFormat knnOverride) throws IOException {
    ResolvedConfig rc = resolveForTest(yaml);
    return ComponentsFactory.build(
        rc, null, path, readOnly, fieldMapper, analyzerRegistry,
        knnOverride, null, null, new AtomicLong(), nrtStats, 500L, Long.MAX_VALUE);
  }

  private void closeComponents(Components c) {
    if (c == null) return;
    try {
      if (c.crtrt() != null) c.crtrt().close();
    } catch (Exception e) {
      /* best-effort */
    }
    try {
      if (c.searcherManager() != null) c.searcherManager().close();
    } catch (Exception e) {
      /* best-effort */
    }
    try {
      if (c.writer() != null) c.writer().close();
    } catch (Exception e) {
      /* best-effort */
    }
    try {
      if (c.directory() != null) c.directory().close();
    } catch (Exception e) {
      /* best-effort */
    }
  }

  private Components buildComponentsWithMetrics(
      String yaml,
      Path path,
      boolean readOnly,
      KnnVectorsFormat knnOverride,
      SoftDeletesMetrics metrics)
      throws IOException {
    ResolvedConfig rc = resolveForTest(yaml);
    return ComponentsFactory.build(
        rc, null, path, readOnly, fieldMapper, analyzerRegistry,
        knnOverride, metrics, null, new AtomicLong(), nrtStats, 500L, Long.MAX_VALUE);
  }

  // -- Directory type tests --

  @Test
  void buildWithDefaultConfigCreatesMmapDirectory() throws Exception {
    String yaml = "index:\n  directory: {}";
    Path idx = tempDir.resolve("mmap-idx");
    Components c = buildComponents(yaml, idx, false, null);
    try {
      assertInstanceOf(MMapDirectory.class, c.directory());
    } finally {
      closeComponents(c);
    }
  }

  @Test
  void buildWithNiofsDirectoryType() throws Exception {
    String yaml = "index:\n  directory:\n    type: niofs";
    Path idx = tempDir.resolve("niofs-idx");
    Components c = buildComponents(yaml, idx, false, null);
    try {
      assertInstanceOf(NIOFSDirectory.class, c.directory());
    } finally {
      closeComponents(c);
    }
  }

  // -- Vector format tests --

  @Test
  void buildWithQuantizationEnabledUsesQuantizedFormat() throws Exception {
    String yaml = "index:\n  vector:\n    quantization:\n      enabled: true";
    Path idx = tempDir.resolve("quant-idx");
    Components c = buildComponents(yaml, idx, false, null);
    try {
      assertInstanceOf(Lucene104HnswScalarQuantizedVectorsFormat.class, c.knnVectorsFormat());
    } finally {
      closeComponents(c);
    }
  }

  @Test
  void buildWithQuantizationDisabledUsesFloat32Format() throws Exception {
    String yaml = "index:\n  vector:\n    quantization:\n      enabled: false";
    Path idx = tempDir.resolve("float32-idx");
    Components c = buildComponents(yaml, idx, false, null);
    try {
      assertInstanceOf(Lucene99HnswVectorsFormat.class, c.knnVectorsFormat());
      assertFalse(
          c.knnVectorsFormat() instanceof Lucene104HnswScalarQuantizedVectorsFormat,
          "should be plain Float32, not quantized");
    } finally {
      closeComponents(c);
    }
  }

  @Test
  void buildWithKnnFormatOverrideIgnoresConfig() throws Exception {
    String yaml = "index:\n  vector:\n    quantization:\n      enabled: true";
    KnnVectorsFormat override = JustSearchCodec.float32Format();
    Path idx = tempDir.resolve("override-idx");
    Components c = buildComponents(yaml, idx, false, override);
    try {
      assertSame(override, c.knnVectorsFormat(), "override should take precedence over config");
    } finally {
      closeComponents(c);
    }
  }

  // -- Read-only vs read-write tests --

  @Test
  void buildReadOnlyReturnsNullWriterAndCrtrt() throws Exception {
    String yaml = "index:\n  directory: {}";
    Path idx = tempDir.resolve("ro-idx");

    // First create an index so read-only open succeeds
    Components writeC = buildComponents(yaml, idx, false, null);
    writeC.writer().commit();
    closeComponents(writeC);

    // Re-open read-only
    Components c = buildComponents(yaml, idx, true, null);
    try {
      assertNull(c.writer(), "writer should be null in read-only mode");
      assertNull(c.crtrt(), "crtrt should be null in read-only mode");
      assertNotNull(c.searcherManager(), "searcherManager should exist in read-only mode");
    } finally {
      closeComponents(c);
    }
  }

  @Test
  void freshWritableIndexCommitsEmptyBootstrapBeforePublication() throws Exception {
    String yaml = "index:\n  directory: {}";
    Path idx = tempDir.resolve("durable-empty-idx");
    Components writable = buildComponents(yaml, idx, false, null);
    try {
      assertTrue(
          DirectoryReader.indexExists(writable.directory()),
          "a live fresh writer must already have a durable commit");
      try (DirectoryReader reader = DirectoryReader.open(writable.directory())) {
        assertEquals(0, reader.numDocs(), "the bootstrap commit must be empty");
      }

      writable.writer().addDocument(new Document());
      writable.crtrt().close();
      writable.searcherManager().close();
      writable.writer().rollback();
      writable.directory().close();
      writable = null;

      Components readOnly = buildComponents(yaml, idx, true, null);
      try {
        assertNull(readOnly.writer(), "strict read-only reopen must not create a writer");
        try (DirectoryReader reader = DirectoryReader.open(readOnly.directory())) {
          assertEquals(0, reader.numDocs(), "rollback must not promote the uncommitted document");
        }
      } finally {
        closeComponents(readOnly);
      }
    } finally {
      closeComponents(writable);
    }
  }

  @Test
  void buildReadWriteReturnsNonNullWriterAndCrtrt() throws Exception {
    String yaml = "index:\n  directory: {}";
    Path idx = tempDir.resolve("rw-idx");
    Components c = buildComponents(yaml, idx, false, null);
    try {
      assertNotNull(c.writer(), "writer should exist in read-write mode");
      assertNotNull(c.crtrt(), "crtrt should exist in read-write mode");
      assertNotNull(c.searcherManager(), "searcherManager should exist in read-write mode");
    } finally {
      closeComponents(c);
    }
  }

  // -- Merge policy test --

  @Test
  void buildWithRetentionEnabledUsesSoftDeletesMergePolicy() throws Exception {
    String yaml =
        """
        index:
          soft_deletes:
            retention:
              enabled: true
              days: 7
        """;
    Path idx = tempDir.resolve("retention-idx");
    Components c = buildComponents(yaml, idx, false, null);
    try {
      MergePolicy mp = c.writer().getConfig().getMergePolicy();
      assertInstanceOf(
          SoftDeletesRetentionMergePolicy.class,
          mp,
          "retention enabled without metrics should use SoftDeletesRetentionMergePolicy");
    } finally {
      closeComponents(c);
    }
  }

  @Test
  void buildWithRetentionDisabledUsesBaseMergePolicy() throws Exception {
    String yaml = "index:\n  directory: {}";
    Path idx = tempDir.resolve("base-mp-idx");
    Components c = buildComponents(yaml, idx, false, null);
    try {
      MergePolicy mp = c.writer().getConfig().getMergePolicy();
      assertInstanceOf(
          TieredMergePolicy.class,
          mp,
          "no retention + no metrics should use base TieredMergePolicy");
    } finally {
      closeComponents(c);
    }
  }

  // -- NRT configuration tests --

  @Test
  void buildReturnsConfiguredNrtValues() throws Exception {
    String yaml =
        """
        index:
          nrt:
            target_max_stale_ms: 200
            max_stale_ms: 5000
        """;
    Path idx = tempDir.resolve("nrt-cfg-idx");
    Components c = buildComponents(yaml, idx, false, null);
    try {
      assertEquals(200L, c.nrtTargetMaxStaleMs(), "target stale should come from config");
      assertEquals(5000L, c.nrtHardMaxStaleMs(), "hard stale should come from config");
    } finally {
      closeComponents(c);
    }
  }

  @Test
  void buildWithNullNrtConfigUsesDefaults() throws Exception {
    String yaml = "index:\n  directory: {}";
    Path idx = tempDir.resolve("nrt-default-idx");
    ResolvedConfig rc = resolveForTest(yaml);
    Components c =
        ComponentsFactory.build(
            rc, null, idx, false, fieldMapper, analyzerRegistry,
            null, null, null, new AtomicLong(), nrtStats, 750L, 15_000L);
    try {
      assertEquals(750L, c.nrtTargetMaxStaleMs(), "target stale should use default");
      assertEquals(15_000L, c.nrtHardMaxStaleMs(), "hard stale should use default");
    } finally {
      closeComponents(c);
    }
  }

  /**
   * Tempdoc 885 item 19: the initially constructed reopen thread must use the configured
   * {@code index.nrt.*} values. Before the fix {@code ComponentsFactory} hardcoded
   * {@code (0.5, 0.05)} seconds regardless of config, so this asserted 250ms/30ms against a
   * thread built at 500ms/50ms.
   */
  @Test
  void initialReopenThreadUsesConfiguredNrtValues() throws Exception {
    String yaml =
        """
        index:
          nrt:
            target_max_stale_ms: 250
            max_stale_ms: 30
        """;
    Path idx = tempDir.resolve("nrt-thread-cfg-idx");
    ResolvedConfig rc = resolveForTest(yaml);
    Components c =
        ComponentsFactory.build(
            rc, null, idx, false, fieldMapper, analyzerRegistry,
            null, null, null, new AtomicLong(), nrtStats, 500L, 50L);
    try {
      assertNotNull(c.crtrt(), "read-write open must produce a reopen thread");
      assertEquals(
          250_000_000L,
          reopenThreadStaleNs(c.crtrt(), "targetMaxStaleNS"),
          "initial reopen thread must use the configured index.nrt.target_max_stale_ms");
      assertEquals(
          30_000_000L,
          reopenThreadStaleNs(c.crtrt(), "targetMinStaleNS"),
          "initial reopen thread must use the configured index.nrt.max_stale_ms");
    } finally {
      closeComponents(c);
    }
  }

  /** The 500/50 defaults reproduce the previously hardcoded 0.5s/0.05s exactly. */
  @Test
  void initialReopenThreadUsesDefaultsWhenNrtUnconfigured() throws Exception {
    String yaml = "index:\n  directory: {}";
    Path idx = tempDir.resolve("nrt-thread-default-idx");
    ResolvedConfig rc = resolveForTest(yaml);
    Components c =
        ComponentsFactory.build(
            rc, null, idx, false, fieldMapper, analyzerRegistry,
            null, null, null, new AtomicLong(), nrtStats, 500L, 50L);
    try {
      assertEquals(500_000_000L, reopenThreadStaleNs(c.crtrt(), "targetMaxStaleNS"));
      assertEquals(50_000_000L, reopenThreadStaleNs(c.crtrt(), "targetMinStaleNS"));
    } finally {
      closeComponents(c);
    }
  }

  /**
   * Lucene rejects {@code targetMaxStaleSec < targetMinStaleSec}; the shared helper clamps the
   * waiting-reopen bound instead of failing the index open.
   */
  @Test
  void initialReopenThreadClampsInvertedNrtBounds() throws Exception {
    String yaml =
        """
        index:
          nrt:
            target_max_stale_ms: 200
            max_stale_ms: 5000
        """;
    Path idx = tempDir.resolve("nrt-thread-inverted-idx");
    ResolvedConfig rc = resolveForTest(yaml);
    Components c =
        ComponentsFactory.build(
            rc, null, idx, false, fieldMapper, analyzerRegistry,
            null, null, null, new AtomicLong(), nrtStats, 500L, 50L);
    try {
      assertEquals(200_000_000L, reopenThreadStaleNs(c.crtrt(), "targetMaxStaleNS"));
      assertEquals(200_000_000L, reopenThreadStaleNs(c.crtrt(), "targetMinStaleNS"));
    } finally {
      closeComponents(c);
    }
  }

  // -- Tempdoc 885 item 19: index.nrt.mode --

  /**
   * The default arm must be untouched: no {@code index.nrt.mode} configured resolves to
   * CONTINUOUS, and the reopen thread keeps the {@code index.nrt.*} bounds — the
   * {@code background_reopen_ms} knob must not leak into it.
   */
  @Test
  void continuousModeIsTheDefaultAndIgnoresTheBackgroundReopenKnob() throws Exception {
    String yaml =
        """
        index:
          nrt:
            background_reopen_ms: 9000
        """;
    Path idx = tempDir.resolve("nrt-mode-default-idx");
    ResolvedConfig rc = resolveForTest(yaml);
    Components c =
        ComponentsFactory.build(
            rc, null, idx, false, fieldMapper, analyzerRegistry,
            null, null, null, new AtomicLong(), nrtStats, 500L, 50L);
    try {
      assertEquals(NrtMode.CONTINUOUS, c.nrtMode());
      assertEquals(500_000_000L, reopenThreadStaleNs(c.crtrt(), "targetMaxStaleNS"));
      assertEquals(50_000_000L, reopenThreadStaleNs(c.crtrt(), "targetMinStaleNS"));
    } finally {
      closeComponents(c);
    }
  }

  /**
   * In on_demand mode the background thread drops to {@code background_reopen_ms} on BOTH Lucene
   * bounds — the foreground refresh in {@code SearcherBridge}, not the thread, is what makes a new
   * document visible. Asserting on the thread's own nanosecond fields (not on the record) is what
   * makes this discriminate: the record carried the configured 500/50 either way.
   */
  @Test
  void onDemandModeSlowsTheBackgroundReopenThread() throws Exception {
    String yaml =
        """
        index:
          nrt:
            mode: on_demand
            background_reopen_ms: 2000
            on_demand_max_stale_ms: 750
        """;
    Path idx = tempDir.resolve("nrt-mode-ondemand-idx");
    ResolvedConfig rc = resolveForTest(yaml);
    Components c =
        ComponentsFactory.build(
            rc, null, idx, false, fieldMapper, analyzerRegistry,
            null, null, null, new AtomicLong(), nrtStats, 500L, 50L);
    try {
      assertEquals(NrtMode.ON_DEMAND, c.nrtMode());
      assertEquals(750L, c.nrtOnDemandMaxStaleMs());
      assertEquals(2_000_000_000L, reopenThreadStaleNs(c.crtrt(), "targetMaxStaleNS"));
      assertEquals(2_000_000_000L, reopenThreadStaleNs(c.crtrt(), "targetMinStaleNS"));
      assertEquals(500L, c.nrtTargetMaxStaleMs(), "the raw index.nrt.* pair is carried unchanged");
      assertEquals(
          2000L,
          c.reopenTargetMs(),
          "the MODE-RESOLVED bound is what the thread was built with, and what a suspend/resume "
              + "rebuild must reuse (see NrtOnDemandRefreshTest)");
    } finally {
      closeComponents(c);
    }
  }

  /** A typo must not silently select an arm the operator did not ask for. */
  @Test
  void unrecognisedNrtModeFallsBackToContinuous() throws Exception {
    String yaml = "index:\n  nrt:\n    mode: on-demand\n";
    Path idx = tempDir.resolve("nrt-mode-typo-idx");
    ResolvedConfig rc = resolveForTest(yaml);
    Components c =
        ComponentsFactory.build(
            rc, null, idx, false, fieldMapper, analyzerRegistry,
            null, null, null, new AtomicLong(), nrtStats, 500L, 50L);
    try {
      assertEquals(NrtMode.CONTINUOUS, c.nrtMode());
      assertEquals(500_000_000L, reopenThreadStaleNs(c.crtrt(), "targetMaxStaleNS"));
    } finally {
      closeComponents(c);
    }
  }

  private static long reopenThreadStaleNs(Object crtrt, String fieldName) throws Exception {
    var field =
        org.apache.lucene.search.ControlledRealTimeReopenThread.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getLong(crtrt);
  }

  // -- Merge policy with metrics tests --

  @Test
  void buildWithRetentionEnabledAndMetricsUsesTelemetryMergePolicy() throws Exception {
    String yaml =
        """
        index:
          soft_deletes:
            retention:
              enabled: true
              days: 7
        """;
    SoftDeletesMetrics metrics =
        new SoftDeletesMetrics() {
          @Override
          public void onDocsKept(long count) {}

          @Override
          public void onDocsPurged(long count) {}
        };
    Path idx = tempDir.resolve("retention-metrics-idx");
    Components c = buildComponentsWithMetrics(yaml, idx, false, null, metrics);
    try {
      MergePolicy mp = c.writer().getConfig().getMergePolicy();
      assertInstanceOf(
          TelemetrySoftDeletesMergePolicy.class,
          mp,
          "retention enabled + metrics should use TelemetrySoftDeletesMergePolicy");
    } finally {
      closeComponents(c);
    }
  }

  @Test
  void buildWithRetentionDisabledAndMetricsUsesTelemetryMergePolicy() throws Exception {
    String yaml =
        """
        index:
          soft_deletes:
            retention:
              enabled: false
              days: -2
              max_versions: 0
        """;
    SoftDeletesMetrics metrics =
        new SoftDeletesMetrics() {
          @Override
          public void onDocsKept(long count) {}

          @Override
          public void onDocsPurged(long count) {}
        };
    Path idx = tempDir.resolve("no-retention-metrics-idx");
    Components c = buildComponentsWithMetrics(yaml, idx, false, null, metrics);
    try {
      MergePolicy mp = c.writer().getConfig().getMergePolicy();
      assertInstanceOf(
          TelemetrySoftDeletesMergePolicy.class,
          mp,
          "no retention + metrics should still use TelemetrySoftDeletesMergePolicy");
      assertEquals(
          true,
          c.runtimeConfiguration()
              .values()
              .get(
                  io.justsearch.configuration.ConfigKey.INDEX_SOFT_DELETES_RETENTION_ENABLED
                      .configKey()),
          "projection must report the retention wrapper that metrics actually installed");
      assertEquals(
          0,
          c.runtimeConfiguration()
              .values()
              .get(
                  io.justsearch.configuration.ConfigKey.INDEX_SOFT_DELETES_RETENTION_DAYS
                      .configKey()));
      assertNull(
          c.runtimeConfiguration()
              .values()
              .get(
                  io.justsearch.configuration.ConfigKey
                      .INDEX_SOFT_DELETES_RETENTION_MAX_VERSIONS
                      .configKey()));
    } finally {
      closeComponents(c);
    }
  }

  // -- Negative test --

  @Test
  void buildReadOnlyOnNonExistentIndexThrows() {
    String yaml = "index:\n  directory: {}";
    Path idx = tempDir.resolve("does-not-exist");
    assertThrows(
        IndexRuntimeIOException.class,
        () -> buildComponents(yaml, idx, true, null),
        "read-only open on non-existent index should throw IndexRuntimeIOException");
  }

  // -- commitMetadataEnabled wiring --

  @Test
  void buildPassesCommitMetadataEnabledThroughToComponents() throws Exception {
    String yamlEnabled =
        """
        index:
          commit_metadata:
            enabled: true
        """;
    Path idx1 = tempDir.resolve("meta-enabled-idx");
    Components c1 = buildComponents(yamlEnabled, idx1, false, null);
    try {
      assertTrue(c1.commitMetadataEnabled(), "commitMetadataEnabled should be true when configured");
    } finally {
      closeComponents(c1);
    }

    String yamlDisabled =
        """
        index:
          commit:
            meta:
              enabled: false
        """;
    Path idx2 = tempDir.resolve("meta-disabled-idx");
    Components c2 = buildComponents(yamlDisabled, idx2, false, null);
    try {
      assertFalse(
          c2.commitMetadataEnabled(),
          "commitMetadataEnabled should be false when explicitly disabled");
    } finally {
      closeComponents(c2);
    }
  }

  // -- Schema compatibility tests --

  /**
   * Creates a FieldCatalogDef with a multi-valued keyword field (produces SORTED_SET docValues) and
   * a single-valued keyword field (produces SORTED docValues). This exercises the schema
   * compatibility check's multiValued branch.
   */
  private static FieldCatalogDef catalogWithMultiValuedField() {
    return new FieldCatalogDef(
        "schema-test",
        List.of(
            new FieldCatalogDef.FieldDef(
                "doc_id", "keyword", true, true, List.of("id"), null, null, false),
            new FieldCatalogDef.FieldDef(
                "tags", "keyword", true, true, List.of("filter"), null, null, true),
            new FieldCatalogDef.FieldDef(
                "status", "keyword", true, true, List.of("filter"), null, null, false)));
  }

  @Test
  void schemaCheckPassesForMultiValuedKeywordField() throws Exception {
    FieldMapper mapper = new FieldMapper(catalogWithMultiValuedField());
    Path idx = tempDir.resolve("schema-mv-idx");

    // Write a document with multi-valued and single-valued keyword fields
    try (MMapDirectory dir = new MMapDirectory(idx);
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document doc =
          mapper.toDocument(Map.of("doc_id", "d1", "tags", List.of("a", "b"), "status", "active"), null);
      writer.addDocument(doc);
      writer.commit();
    }

    // Re-open and verify schema compatibility — should NOT throw
    try (MMapDirectory dir = new MMapDirectory(idx)) {
      assertDoesNotThrow(
          () -> ComponentsFactory.checkFieldSchemaCompatibility(dir, idx, mapper),
          "Multi-valued field with SORTED_SET should pass schema check");
    }
  }

  @Test
  void schemaCheckDetectsMismatchWhenMultiValuedChanges() throws Exception {
    // Write with multi-valued field (produces SORTED_SET on disk)
    FieldMapper multiMapper = new FieldMapper(catalogWithMultiValuedField());
    Path idx = tempDir.resolve("schema-mismatch-idx");

    try (MMapDirectory dir = new MMapDirectory(idx);
        IndexWriter writer =
            new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document doc =
          multiMapper.toDocument(
              Map.of("doc_id", "d1", "tags", List.of("a", "b"), "status", "active"), null);
      writer.addDocument(doc);
      writer.commit();
    }

    // Re-check with a mapper where "tags" is now single-valued (expects SORTED, finds SORTED_SET)
    FieldCatalogDef singleCatalog =
        new FieldCatalogDef(
            "schema-test",
            List.of(
                new FieldCatalogDef.FieldDef(
                    "doc_id", "keyword", true, true, List.of("id"), null, null, false),
                new FieldCatalogDef.FieldDef(
                    "tags", "keyword", true, true, List.of("filter"), null, null, false),
                new FieldCatalogDef.FieldDef(
                    "status", "keyword", true, true, List.of("filter"), null, null, false)));
    FieldMapper singleMapper = new FieldMapper(singleCatalog);

    try (MMapDirectory dir = new MMapDirectory(idx)) {
      IndexRuntimeIOException ex =
          assertThrows(
              IndexRuntimeIOException.class,
              () -> ComponentsFactory.checkFieldSchemaCompatibility(dir, idx, singleMapper),
              "Should detect SORTED_SET vs SORTED mismatch for 'tags' field");
      assertTrue(
          ex.getMessage().contains("tags"),
          "Error message should reference the mismatched field: " + ex.getMessage());
    }
  }

  @Test
  void schemaCheckDetectsMismatchWhenSingleValuedBecomesMultiValued() throws Exception {
    // Write with single-valued field (produces SORTED on disk)
    FieldCatalogDef singleCatalog =
        new FieldCatalogDef(
            "schema-test",
            List.of(
                new FieldCatalogDef.FieldDef(
                    "doc_id", "keyword", true, true, List.of("id"), null, null, false),
                new FieldCatalogDef.FieldDef(
                    "tags", "keyword", true, true, List.of("filter"), null, null, false)));
    FieldMapper singleMapper = new FieldMapper(singleCatalog);
    Path idx = tempDir.resolve("schema-reverse-mismatch-idx");

    try (MMapDirectory dir = new MMapDirectory(idx);
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document doc = singleMapper.toDocument(Map.of("doc_id", "d1", "tags", "solo"), null);
      writer.addDocument(doc);
      writer.commit();
    }

    // Re-check with a mapper where "tags" is now multi-valued (expects SORTED_SET, finds SORTED)
    FieldMapper multiMapper = new FieldMapper(catalogWithMultiValuedField());

    try (MMapDirectory dir = new MMapDirectory(idx)) {
      IndexRuntimeIOException ex =
          assertThrows(
              IndexRuntimeIOException.class,
              () -> ComponentsFactory.checkFieldSchemaCompatibility(dir, idx, multiMapper),
              "Should detect SORTED vs SORTED_SET mismatch for 'tags' field");
      assertTrue(
          ex.getMessage().contains("tags"),
          "Error message should reference the mismatched field: " + ex.getMessage());
    }
  }
}
