/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.worker.OnnxModelStatus;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeRerankerPreferenceTest {
  @Test
  void quarantineAndNextRestartKeepOtherStatusAndRerankerUnknown(@TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("settings.json");
    java.nio.file.Files.writeString(file, "{broken settings");
    var first = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
    first.load(); // Normal boot quarantines corruption before status is requested.
    assertTrue(first.lastRecovery().isPresent());
    var prior = ConfigStore.globalOrNull();
    var published = new ConfigStore(ConfigStoreRebuilder.prepare(new UiSettings()));
    ConfigStore.setGlobal(published);
    try (var executors = new TestEngineExecutors()) {
      for (var store : List.of(first,
          new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file))) {
        store.load();
        var service = new RuntimeActivationService(executors, OnlineAiService.unavailable(),
            store, null, null, () -> List.of(
                new OnnxModelStatus("reranker", true, "ranker", true, true),
                new OnnxModelStatus("citation-scorer", true, "citations", true, true)));
        var status = assertDoesNotThrow(service::getStatus);
        var reranker = status.onnxFeatures().stream()
            .filter(row -> "reranker".equals(row.id())).findFirst().orElseThrow();
        assertEquals("unknown", reranker.status());
        assertFalse(reranker.modelActive());
        var citation = status.onnxFeatures().stream()
            .filter(row -> "citation_scorer".equals(row.id())).findFirst().orElseThrow();
        assertEquals("active", citation.status());
        assertEquals("citations", citation.modelPath());
        assertTrue(citation.modelActive());
        assertNotNull(status.activation());
        assertNotNull(status.active());
      }
    } finally {
      ConfigStore.restoreGlobal(published, prior);
    }
  }

  @Test
  void statusReportsSavedOffAndAnActiveOperatorOverrideTruthfully(@TempDir Path dir) {
    var settings = new UiSettings();
    settings.setRerankerEnabled(false);
    settings.setRerankerModelPath(dir.resolve("custom").toString());
    var prior = ConfigStore.globalOrNull();
    var published = new ConfigStore(ConfigStoreRebuilder.prepare(settings));
    ConfigStore.setGlobal(published);
    try (var executors = new TestEngineExecutors()) {
      var service = new RuntimeActivationService(executors, OnlineAiService.unavailable(),
          new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, dir.resolve("settings.json")),
          null, null, () -> List.of(new OnnxModelStatus("reranker", true, "custom", true, true)));
      var off = service.getStatus().onnxFeatures().stream()
          .filter(row -> "reranker".equals(row.id())).findFirst().orElseThrow();
      assertEquals("inactive", off.status());
      assertEquals("disabled", off.reason());
      var overridden = new ResolvedConfigBuilder()
          .putSettings("justsearch.rerank.enabled", "false")
          .put("justsearch.rerank.model_path", ResolvedConfigBuilder.ORDINAL_ENV_VAR,
              "env_var", "JUSTSEARCH_RERANK_MODEL_PATH", dir.resolve("operator").toString()).build();
      published.swap(overridden);
      var on = service.getStatus().onnxFeatures().stream()
          .filter(row -> "reranker".equals(row.id())).findFirst().orElseThrow();
      assertEquals("active", on.status());
      assertEquals("operator_override", on.reason());
      assertEquals(dir.resolve("operator").toString(), on.modelPath());
      assertTrue(on.modelActive());
    } finally {
      ConfigStore.restoreGlobal(published, prior);
    }
  }
}
