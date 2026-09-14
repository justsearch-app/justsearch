package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SoftDeletesMetrics;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.index.SoftDeletesRetentionMergePolicy;
import org.junit.jupiter.api.Test;

class SoftDeletesMetricsTest extends LuceneExecutorTestBase {

  @Test
  void installsTelemetryMergePolicyWhenListenerProvided() throws Exception {
    Path base = Files.createTempDirectory("justsearch-soft-metrics");
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: metrics\n      roots: ['ignored']\n  vector:\n    dimension: 768\n"
            + "  soft_deletes:\n    retention:\n      enabled: true\n      days: 7\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    String prev = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    try {
      RunningRuntime runtime =
          IndexSchema.fromCatalog(
                  FieldCatalogDef.forTesting(768),
                  new SsotCommitMetadataSource(),
                  new JsonSchemaCommitMetadataValidator())
              .ephemeral().withExecutorRegistrations(testLuceneExecutors())
              .withSoftDeletesMetrics(
                  new SoftDeletesMetrics() {
                    @Override
                    public void onDocsKept(long count) {}

                    @Override
                    public void onDocsPurged(long count) {}
                  })
              .open();
      assertInstanceOf(TelemetrySoftDeletesMergePolicy.class, new LifecycleTestAccessor(runtime).mergePolicy());
      runtime.close();
    } finally {
      if (prev == null) System.clearProperty("justsearch.config"); else System.setProperty("justsearch.config", prev);
    }
  }

  @Test
  void fallsBackToDefaultMergePolicyWithoutListener() throws Exception {
    Path base = Files.createTempDirectory("justsearch-soft-default");
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: metrics\n      roots: ['ignored']\n  vector:\n    dimension: 768\n"
            + "  soft_deletes:\n    retention:\n      enabled: true\n      days: 7\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    String prev = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    try {
      RunningRuntime runtime =
          IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
      var a = new LifecycleTestAccessor(runtime);
      assertNotNull(a.mergePolicy());
      assertInstanceOf(SoftDeletesRetentionMergePolicy.class, a.mergePolicy());
      runtime.close();
    } finally {
      if (prev == null) System.clearProperty("justsearch.config"); else System.setProperty("justsearch.config", prev);
    }
  }
}
