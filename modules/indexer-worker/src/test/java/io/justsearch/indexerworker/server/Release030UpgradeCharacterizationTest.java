/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Deliberately pins issue #739's before-state. The upgrade fixes must invert these expectations. */
@Timeout(90)
final class Release030UpgradeCharacterizationTest {
  @Test
  void issue739CurrentMainStartsMigrationForAuthenticReleaseIndex(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models;
          var executors = new TestEngineExecutors();
          var server = new KnowledgeServer(executors, WorkerBootFixture.workerConfig(root.resolve("data")), null)) {
        server.start();
        var state = new IndexGenerationManager(root.resolve("data/index/default")).readStateBestEffort();
        assertNotNull(state);
        assertEquals("MIGRATING", state.migration_state(), "#739: today's unnecessary rebuild");
        assertNotNull(state.building_generation());
        var building = new tools.jackson.databind.ObjectMapper().readTree(root.resolve("data/index/default/indices")
            .resolve(state.building_generation()).resolve(".justsearch-index-generation.json").toFile());
        assertEquals("schema_mismatch", building.get("source").asText(),
            "a separate embedding-model migration must not mask #739's schema rejection");
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void issue739CurrentMainRetiresReadyLegacyJobsWithoutReadingThem(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models;
          var executors = new TestEngineExecutors();
          var server = new KnowledgeServer(executors, WorkerBootFixture.workerConfig(root.resolve("data")), null)) {
        server.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        var expected = Map.of(
            io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(root.resolve("explicit.txt")), "MISSING_INGESTION_BOUNDARY",
            io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(root.resolve("roots/alpha/pending.txt")), "MISSING_INGESTION_BOUNDARY",
            io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(root.resolve("roots/beta/processing.txt")), "MISSING_INGESTION_BOUNDARY");
        Map<String, String> retired = new java.util.HashMap<>();
        while (System.nanoTime() < deadline) {
          try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
              var query = connection.prepareStatement("SELECT path,state,last_reason_code FROM jobs WHERE path IN (?,?,?)")) {
            int parameter = 1;
            for (String path : expected.keySet()) query.setString(parameter++, path);
            try (var rows = query.executeQuery()) {
              int found = 0;
              retired.clear();
              while (rows.next()) {
                found++;
                if ("DONE".equals(rows.getString(2))) retired.put(rows.getString(1), rows.getString(3));
              }
              assertEquals(3, found, "all captured ready jobs must survive schema migration");
            }
          }
          if (retired.size() == expected.size()) break;
          Thread.sleep(25);
        }
        assertEquals(expected, retired,
            "#739: real PENDING, PROCESSING and explicit-file jobs are retired before source extraction");
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static ConfigStore configure(Path root) {
    WorkerBootFixture.publishConfig(root.resolve("data"), root.resolve("data/index/default"),
        "BLUE_GREEN_MIGRATE", Map.of(
            "justsearch.ai.disabled", "true",
            "justsearch.ai.embed.enabled", "false",
            "justsearch.splade.enabled", "false",
            "justsearch.ner.enabled", "false"));
    return ConfigStore.global();
  }

  /** Hold model identity constant without shipping/loading model binaries. Restore exact caches. */
  private static final class ReleasedModelIdentities implements AutoCloseable {
    private final java.util.List<Runnable> restorers = new java.util.ArrayList<>();

    ReleasedModelIdentities(Path root) throws Exception {
      var json = new tools.jackson.databind.ObjectMapper();
      var state = json.readTree(root.resolve("data/index/default/state.json").toFile());
      Path generation = root.resolve("data/index/default/indices").resolve(state.get("active_generation").asText());
      try (var directory = org.apache.lucene.store.FSDirectory.open(generation);
          var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
        var inputs = json.readTree(reader.getIndexCommit().getUserData().get("index_fingerprint_inputs"));
        install(io.justsearch.indexerworker.embed.EmbeddingFingerprint.class,
            inputs.get("embedding_model_sha256").asText(), root);
        install(io.justsearch.indexerworker.splade.SpladeFingerprint.class,
            inputs.get("splade_model_sha256").asText(), root);
        install(io.justsearch.indexerworker.ner.NerFingerprint.class,
            inputs.get("ner_model_sha256").asText(), root);
        var identities = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource.RuntimeFingerprintInputs(
            null,
            io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint.present(inputs.get("embedding_model_sha256").asText()),
            io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint.present(inputs.get("splade_model_sha256").asText()),
            io.justsearch.adapters.lucene.commit.IndexFingerprint.ModelFingerprint.present(inputs.get("ner_model_sha256").asText()));
        var current = (tools.jackson.databind.node.ObjectNode) json.readTree(
            String.valueOf(new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
                ConfigStore.global().get(), identities).build().get("index_fingerprint_inputs")));
        ((tools.jackson.databind.node.ObjectNode) inputs).remove("fields");
        current.remove("fields");
        assertEquals(inputs, current, "Only the field projection may differ; a model/config change would mask #739");
      } catch (Exception | Error failure) {
        close();
        throw failure;
      }
    }

    @SuppressWarnings("unchecked")
    private void install(Class<?> owner, String digest, Path root) throws Exception {
      var field = owner.getDeclaredField("cachedResult");
      field.setAccessible(true);
      var cache = (java.util.concurrent.atomic.AtomicReference<Object>) field.get(null);
      Object previous = cache.get();
      var record = Class.forName(owner.getName() + "$CachedResult");
      var constructor = record.getDeclaredConstructor(java.util.Optional.class, java.util.Optional.class);
      constructor.setAccessible(true);
      cache.set(constructor.newInstance(java.util.Optional.of(root.resolve("model-identity-only.onnx")),
          java.util.Optional.of(digest)));
      restorers.add(() -> cache.set(previous));
    }

    @Override
    public void close() {
      for (Runnable restore : restorers) restore.run();
      restorers.clear();
    }
  }
}
