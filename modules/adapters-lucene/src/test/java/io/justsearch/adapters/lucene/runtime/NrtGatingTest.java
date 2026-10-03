package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NrtGatingTest extends LuceneExecutorTestBase {
  @Test
  void maybeRefreshReturnsWhenLagBelowTarget() throws Exception {
    // Configure a very large target_max_stale_ms so gating returns without refresh
    Path base = Files.createTempDirectory("justsearch-data-");
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n  collections:\n    - name: nrt\n      roots: ['ignored']\n  vector:\n    dimension: 768\n  nrt:\n    target_max_stale_ms: 10000000\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    String prev = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    try {
      var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
      assertDoesNotThrow(() -> r.commitOps().maybeRefresh());
      r.close();
    } finally {
      if (prev == null) System.clearProperty("justsearch.config"); else System.setProperty("justsearch.config", prev);
    }
  }
}
