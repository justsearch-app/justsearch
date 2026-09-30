package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class ConfigWiringTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  @RegisterExtension
  SystemPropertyExtension sysprops = new SystemPropertyExtension("justsearch.config");

  private Path writeConfig(String yaml) throws IOException {
    Path f = tempDir.resolve("config.yaml");
    Files.writeString(f, yaml);
    return f;
  }

  private Path dataDir() throws IOException {
    Path d = tempDir.resolve("data");
    Files.createDirectories(d);
    return d;
  }

  private static int readPrivateInt(Object target, String fieldName) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.getInt(target);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new LinkageError("Failed to read field " + fieldName + " from " + target.getClass(), e);
    }
  }

  @Test
  void directoryAndMergeKnobsApplied() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n" +
        "  collections:\n    - name: testcol\n      roots: ['ignored']\n" +
        "  directory:\n" +
        "    type: niofs\n" +
        "  merge:\n" +
        "    tiered:\n" +
        "      segs_per_tier: 7\n" +
        "      max_merged_segment_mb: 256\n" +
        "  vector:\n" +
        "    dimension: 768\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    var a = new LifecycleTestAccessor(r);
    assertNotNull(a.directory());
    assertEquals(NIOFSDirectory.class, a.directory().getClass());
    Path expected = base.resolve("index").resolve("testcol");
    assertEquals(expected.toAbsolutePath().normalize(), a.indexPath().toAbsolutePath().normalize());
    TieredMergePolicy tmp = (TieredMergePolicy) a.mergePolicy();
    assertEquals(7.0, tmp.getSegmentsPerTier(), 0.01);
    assertEquals(256.0, tmp.getMaxMergedSegmentMB(), 0.01);
    r.close();
  }

  @Test
  void writerMemoryKnobsApplied() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n" +
        "  collections:\n    - name: mem\n      roots: ['ignored']\n" +
        "  vector:\n    dimension: 768\n" +
        "  writer:\n    ram_buffer_mb: 64\n    max_buffered_docs: 123\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    var a = new LifecycleTestAccessor(r);
    assertEquals(64.0, a.ramBufferMb(), 0.01);
    assertEquals(123, a.maxBufferedDocs());
    r.indexingCoordinator().indexSingle(
        new IndexDocument(
            java.util.Map.of(
                SchemaFields.DOC_ID, "mem-doc",
                SchemaFields.DOC_UID, "mem-doc#0",
                "title", "x")));
    r.commitOps().commitAndTrack();
    r.close();
  }

  @Test
  void vectorDefaultsAppliedWhenNotConfigured() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n" +
        "  collections:\n    - name: vec\n      roots: ['ignored']\n" +
        "  vector:\n    dimension: 768\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    var a = new LifecycleTestAccessor(r);
    var format = a.knnVectorsFormat();
    assertNotNull(format);
    assertEquals(16, readPrivateInt(format, "maxConn"));
    assertEquals(200, readPrivateInt(format, "beamWidth"));
    assertNull(a.vectorEfSearchOverrideOrNull()); // default: no override
    // Lane F PR 0b — index.vector.exhaustive_search defaults OFF, so the kNN factory keeps
    // building today's approximate query. Asserted at the SET-SITE, not just at the config record.
    assertFalse(a.vectorExhaustiveSearch(), "exhaustive_search must default to false");
    r.close();
  }

  @Test
  void vectorExhaustiveSearchReachesTheRuntimeSessionWhenSet() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n" +
        "  collections:\n    - name: vecExhaustive\n      roots: ['ignored']\n" +
        "  vector:\n    dimension: 768\n    exhaustive_search: true\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    var a = new LifecycleTestAccessor(r);
    assertTrue(
        a.vectorExhaustiveSearch(),
        "index.vector.exhaustive_search must reach RuntimeSession, which is what the kNN factory reads");
    r.close();
  }

  @Test
  void vectorConfigOverridesApplied() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n" +
        "  collections:\n    - name: vecOverride\n      roots: ['ignored']\n" +
        "  vector:\n" +
        "    dimension: 768\n" +
        "    hnsw:\n" +
        "      m: 48\n" +
        "      ef_construction: 320\n" +
        "    ef_search: 450\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    var a = new LifecycleTestAccessor(r);
    var format = a.knnVectorsFormat();
    assertNotNull(format);
    assertEquals(48, readPrivateInt(format, "maxConn"));
    assertEquals(320, readPrivateInt(format, "beamWidth"));
    assertEquals(450, a.vectorEfSearchOverrideOrNull());
    r.close();
  }

  @Test
  void vectorDimensionMismatchValidated() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n  collections:\n    - name: testcol\n      roots: ['ignored']\n  vector:\n    dimension: 1\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    // Tightened from `Exception.class` per observations.md. The actual thrown
    // type is `IllegalStateException` from
    // `ComponentsFactory.validateVectorDimension` ("Configured vector.dimension
    // does not match SSOT dimension"), not the `IllegalArgumentException` the
    // original observation hypothesized. Pinning the real class so a future
    // regression that silently swaps the failure path back is caught.
    assertThrows(
        IllegalStateException.class,
        () ->
            IndexSchema.fromCatalog(
                    FieldCatalogDef.forTesting(768),
                    new SsotCommitMetadataSource(),
                    new JsonSchemaCommitMetadataValidator())
                .ephemeral().withExecutorRegistrations(testLuceneExecutors())
                .open());
  }

  @Test
  void directoryTypeMmapDefault() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n  collections:\n    - name: def\n      roots: ['ignored']\n  directory:\n    type: mmap\n  vector:\n    dimension: 768\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    assertEquals(org.apache.lucene.store.MMapDirectory.class, new LifecycleTestAccessor(r).directory().getClass());
    r.close();
  }

  @Test
  void simplefsMapsToNiofs() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n  collections:\n    - name: simp\n      roots: ['ignored']\n  directory:\n    type: simplefs\n  vector:\n    dimension: 768\n  merge:\n    tiered:\n      segs_per_tier: 8\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    var a = new LifecycleTestAccessor(r);
    assertEquals(NIOFSDirectory.class, a.directory().getClass());
    TieredMergePolicy tmp = (TieredMergePolicy) a.mergePolicy();
    assertEquals(8.0, tmp.getSegmentsPerTier(), 0.01);
    r.close();
  }

  @Test
  void unknownDirTypeFallsBackToDefault() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n  collections:\n    - name: unk\n      roots: ['ignored']\n  directory:\n    type: unknown\n  vector:\n    dimension: 768\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    assertEquals(org.apache.lucene.store.MMapDirectory.class, new LifecycleTestAccessor(r).directory().getClass());
    r.close();
  }

  @Test
  void literalDataDirResolves() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\nindex:\n  collections:\n    - name: homecol\n      roots: ['ignored']\n  vector:\n    dimension: 768\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    Path expected = base.resolve("index").resolve("homecol");
    assertEquals(expected.toAbsolutePath().normalize(), new LifecycleTestAccessor(r).indexPath().toAbsolutePath().normalize());
    r.close();
  }

  @Test
  void collectionNameDerivedFromYaml() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n  collections:\n    - name: envcol\n      roots: ['ignored']\n  vector:\n    dimension: 768\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    Path expected = base.resolve("index").resolve("envcol");
    assertEquals(expected.toAbsolutePath().normalize(), new LifecycleTestAccessor(r).indexPath().toAbsolutePath().normalize());
    r.close();
  }

  @Test
  void indexSortAppliedAndValidated() throws Exception {
    Path base = dataDir();
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n" +
        "  collections:\n    - name: sortcol\n      roots: ['ignored']\n" +
        "  sort:\n" +
        "    - field: modified_at\n      reverse: true\n" +
        "    - field: path\n      reverse: false\n" +
        "  vector:\n" +
        "    dimension: 768\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    org.apache.lucene.search.Sort sort = new LifecycleTestAccessor(r).indexSort();
    assertNotNull(sort);
    org.apache.lucene.search.SortField[] sfs = sort.getSort();
    assertEquals(2, sfs.length);
    assertEquals("modified_at", sfs[0].getField());
    assertEquals(org.apache.lucene.search.SortField.Type.LONG, sfs[0].getType());
    assertEquals(true, sfs[0].getReverse());
    assertEquals("path", sfs[1].getField());
    assertEquals(org.apache.lucene.search.SortField.Type.STRING, sfs[1].getType());
    assertEquals(false, sfs[1].getReverse());
    r.close();
  }

}
