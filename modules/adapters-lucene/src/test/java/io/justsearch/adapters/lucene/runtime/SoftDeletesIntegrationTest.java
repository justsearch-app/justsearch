package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.index.SoftDeletesRetentionMergePolicy;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

class SoftDeletesIntegrationTest extends LuceneExecutorTestBase {
  private static Path writeConfig(String yaml) throws IOException {
    Path f = Files.createTempFile("justsearch-runtime-soft-delete-", ".yaml");
    Files.writeString(f, yaml);
    return f;
  }

  @Test
  void softDeletesRetentionConfigured() throws Exception {
    Path base = Files.createTempDirectory("justsearch-softdelete-");
    String yaml =
        "app:\n"
            + "  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n"
            + "  collections:\n"
            + "    - name: runtime\n"
            + "      roots: ['ignored']\n"
            + "  vector:\n"
            + "    dimension: 768\n"
            + "  soft_deletes:\n"
            + "    field: \"_soft_delete_flag\"\n"
            + "    retention:\n"
            + "      enabled: true\n"
            + "      days: 5\n"
            + "      max_versions: 2\n";
    Path cfg = writeConfig(yaml);
    String prev = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    try {
      RunningRuntime runtime =
          IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
      var a = new LifecycleTestAccessor(runtime);
      assertEquals("_soft_delete_flag", a.softDeletesField());
      SoftDeletesRetentionMergePolicy policy = (SoftDeletesRetentionMergePolicy) a.mergePolicy();
      assertNotNull(policy, "Expected SoftDeletesRetentionMergePolicy");
      Query retentionQuery = retentionQuery(policy);
      assertTrue(retentionQuery instanceof BooleanQuery, "Expected composite retention query");
      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @Test
  void retentionQueryUsesSingleClauseWhenOnlyDaysConfigured() throws Exception {
    Path base = Files.createTempDirectory("justsearch-softdelete-days-");
    String yaml =
        "app:\n"
            + "  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n"
            + "  collections:\n"
            + "    - name: runtime\n"
            + "      roots: ['ignored']\n"
            + "  vector:\n"
            + "    dimension: 768\n"
            + "  soft_deletes:\n"
            + "    retention:\n"
            + "      enabled: true\n"
            + "      days: 3\n";
    Path cfg = writeConfig(yaml);
    String prev = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    try {
      RunningRuntime runtime =
          IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
      SoftDeletesRetentionMergePolicy policy =
          (SoftDeletesRetentionMergePolicy) new LifecycleTestAccessor(runtime).mergePolicy();
      assertNotNull(policy, "Expected SoftDeletesRetentionMergePolicy");
      Query retentionQuery = retentionQuery(policy);
      assertTrue(!(retentionQuery instanceof BooleanQuery));
      assertTrue(!(retentionQuery instanceof MatchNoDocsQuery));
      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @Test
  void retentionQueryDisablesWhenNoRetentionLimits() throws Exception {
    Path base = Files.createTempDirectory("justsearch-softdelete-zero-");
    String yaml =
        "app:\n"
            + "  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n"
            + "  collections:\n"
            + "    - name: runtime\n"
            + "      roots: ['ignored']\n"
            + "  vector:\n"
            + "    dimension: 768\n"
            + "  soft_deletes:\n"
            + "    retention:\n"
            + "      enabled: true\n"
            + "      days: 0\n";
    Path cfg = writeConfig(yaml);
    String prev = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    try {
      RunningRuntime runtime =
          IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
      SoftDeletesRetentionMergePolicy policy =
          (SoftDeletesRetentionMergePolicy) new LifecycleTestAccessor(runtime).mergePolicy();
      assertNotNull(policy, "Expected SoftDeletesRetentionMergePolicy");
      Query retentionQuery = retentionQuery(policy);
      assertTrue(retentionQuery instanceof MatchNoDocsQuery);
      runtime.close();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  private static Query retentionQuery(SoftDeletesRetentionMergePolicy policy) throws Exception {
    Field supplierField = SoftDeletesRetentionMergePolicy.class.getDeclaredField("retentionQuerySupplier");
    supplierField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.function.Supplier<Query> supplier =
        (java.util.function.Supplier<Query>) supplierField.get(policy);
    return supplier.get();
  }
}
