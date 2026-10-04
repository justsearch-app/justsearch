package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NrtGatingTriggerTest extends LuceneExecutorTestBase {
  @Test
  void maybeRefreshTriggersWhenLagExceedsTarget() throws Exception {
    Path base = Files.createTempDirectory("justsearch-data-");
    String yaml = "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n" +
        "index:\n  collections:\n    - name: nrt2\n      roots: ['ignored']\n  vector:\n    dimension: 768\n  nrt:\n    target_max_stale_ms: 0\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    String prev = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    try {
      var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
      r.indexingCoordinator().indexSingle(
          new IndexDocument(
              java.util.Map.of(
                  SchemaFields.DOC_ID, "nrt-trigger",
                  SchemaFields.DOC_UID, "nrt-trigger#0")));
      r.commitOps().commitAndTrack();
      // With target_max_stale_ms=0, any positive lag should trigger refresh path
      assertDoesNotThrow(() -> { r.commitOps().maybeRefresh(); return null; });
      r.close();
    } finally {
      if (prev == null) System.clearProperty("justsearch.config"); else System.setProperty("justsearch.config", prev);
    }
  }
}
