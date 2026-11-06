/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SpladeOptInTest {
  @TempDir Path temp;

  @Test void installedFilesAndDefaultProviderDoNotRequestInference() throws Exception {
    Path model = Files.createDirectories(temp.resolve("models/splade/naver-splade-v3"));
    for (String name : new String[] {"model.onnx", "tokenizer.json", "vocab.txt"}) {
      Files.writeString(model.resolve(name), "fixture");
    }
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", temp.resolve("models").toString(),
        "justsearch.sparse_model", "splade"));
    assertFalse(SpladeConfig.from(cfg).enabled(), "S1/S3/S4: installed files are not demand");
  }

  @Test void explicitPathAloneDoesNotRequestInference() {
    var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.splade.model_path", temp.toString()));
    assertFalse(SpladeConfig.from(cfg).enabled(), "S3: a path is identity, not permission");
    assertEquals(temp, SpladeConfig.from(cfg).modelPath());
  }

  @Test void chunkDemandPermitsParentFromTheSuppliedSnapshot() {
    ConfigStore previous = ConfigStore.globalOrNull();
    try {
      ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
          "justsearch.splade.enabled", "false"))));
      var supplied = TestResolvedConfigHelper.fromEntries(Map.of(
          "rag.chunk_splade.enabled", "true", "justsearch.models.dir", temp.toString()));
      assertTrue(SpladeConfig.from(supplied).enabled(), "S3/S7: supplied chunk demand wins over an unrelated global snapshot");
      assertNull(SpladeConfig.from(supplied).modelPath());
      assertEquals(Boolean.FALSE, ConfigStore.global().get().ai().splade().enabled());
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  @Test void resolvedSourcePrecedenceKeepsAnExplicitGlobalVeto() {
    var cfg = io.justsearch.configuration.resolved.ResolvedConfig.builder()
        .put("justsearch.splade.enabled", 400, "env_var", "fixture", "true")
        .put("justsearch.splade.enabled", 500, "jvm_arg", "fixture", "false")
        .putDefault("rag.chunk_splade.enabled", "true")
        .putDefault("justsearch.splade.model_path", temp.toString()).build();
    assertFalse(SpladeConfig.from(cfg).enabled());
    assertEquals(500, cfg.resolution("justsearch.splade.enabled").sourceOrdinal());
  }

  @Test void explicitParentTrueAndFalseKeepTheirMeaning() {
    for (String chunk : new String[] {"false", "true"}) {
      for (String parent : new String[] {"false", "true"}) {
        var cfg = TestResolvedConfigHelper.fromEntries(Map.of(
            "justsearch.splade.enabled", parent, "rag.chunk_splade.enabled", chunk,
            "justsearch.splade.model_path", temp.toString()));
        assertEquals(Boolean.parseBoolean(parent), SpladeConfig.from(cfg).enabled());
      }
    }
  }
}
