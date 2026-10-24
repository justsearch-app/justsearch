/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

/** Issue #739: compatible in-place upgrade and durable recovery through the real Worker boot. */
@Timeout(120)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030UpgradeCharacterizationTest {
  @Test
  void issue739CompatibleReleaseIndexStaysWritableAndSearchableAcrossRestart(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        String original = state(root).active_generation();
        for (int boot = 0; boot < 2; boot++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            assertEquals("IDLE", state(root).migration_state(), "U1/U2: no rebuild or migration");
            assertEquals(original, state(root).active_generation());
            assertEquals(null, state(root).building_generation());
            var compatibility = server.appServices().ingestService().indexStatus(
                io.justsearch.ipc.StatusRequest.getDefaultInstance(),
                io.justsearch.indexerworker.services.CallContext.none()).getCompatibility();
            assertEquals("COMPATIBLE", compatibility.getSchemaCompatState(),
                "status must make the same compatibility decision as every open path; current="
                    + compatibility.getSchemaFpCurrent() + " stored=" + compatibility.getSchemaFpStored());
            assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))),
                "the release's old content stays searchable before writer activation");
            activate(server);
            String word = "cobaltupgrade" + boot;
            Path added = java.nio.file.Files.writeString(root.resolve("roots/alpha/added-" + boot + ".txt"), word);
            var accepted = server.appServices().ingestService().submitBatch(
                io.justsearch.ipc.BatchRequest.newBuilder().addFilePaths(added.toString())
                    .setRootAdded(true).build(), io.justsearch.indexerworker.services.CallContext.none());
            assertEquals(1, accepted.getAcceptedCount());
            await(() -> search(server, word)
                .contains(key(added)), "new file must be searchable within an indexing cycle");
            assertTrue(server.lifecycleManagerForTests() instanceof io.justsearch.adapters.lucene.runtime.RunningRuntime running
                && running.isAcceptingWrites(), "old generation is writable");
            server.lifecycleManagerForTests().commitOps().commitAndTrack(
                io.justsearch.adapters.lucene.runtime.CommitReason.DRAIN);
            assertEquals("IDLE", state(root).migration_state());
          }
        }
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void issue739LegacyJobsAreReadAndSelectiveWriteRepairSurvivesRestart(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    long waitingUntil = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
    sql(root, "UPDATE jobs SET retry_after=" + waitingUntil
        + " WHERE last_outcome_class IN ('PARSER_TIMEOUT','IO_FAILED')");
    // Copy the authentic terminal row under a watched root too: automatic startup scans must
    // not turn an unchanged terminal failure into new work. Its outside-root original stays too.
    java.nio.file.Files.copy(root.resolve("orphan/failed.bin"), root.resolve("roots/alpha/failed.bin"));
    sql(root, "INSERT INTO jobs(path,state,attempts,last_updated,collection,last_outcome_class,"
        + "last_reason_code,last_retry_policy,first_failed_at,last_outcome_at) SELECT '"
        + key(root.resolve("roots/alpha/failed.bin")) + "',state,attempts,last_updated,collection,"
        + "last_outcome_class,last_reason_code,last_retry_policy,first_failed_at,last_outcome_at "
        + "FROM jobs WHERE path='" + key(root.resolve("orphan/failed.bin")) + "'");
    java.nio.file.Files.delete(root.resolve("roots/alpha/file-02.txt"));
    java.nio.file.Files.writeString(root.resolve("roots/beta/offline-add.txt"), "violetarrival");
    // An unrelated neighbor of the explicit file must never be discovered.
    java.nio.file.Files.writeString(root.resolve("neighbor.txt"), "unrequestedneighbor");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (int boot = 0; boot < 2; boot++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            assertEquals("IDLE", state(root).migration_state());
            activate(server);
            // The full Engine restores roots through these automatic INITIAL scans as well as sync.
            for (String name : java.util.List.of("alpha", "beta")) {
              server.appServices().ingestService().scanRoot(io.justsearch.ipc.ScanRootRequest.newBuilder()
                  .setRootPath(root.resolve("roots/" + name).toString())
                  .setMode(io.justsearch.ipc.ScanMode.SCAN_MODE_INITIAL).build(), progress -> {},
                  io.justsearch.indexerworker.services.CallContext.none());
            }
            await(() -> count(root, "state='PENDING' AND retry_after IS NULL OR state='PROCESSING'") == 0
                && !java.nio.file.Files.exists(root.resolve("data/upgrade-030-recovery.properties")),
                "U7/U14b: all ready work drains and durable recovery completes");
            var updated = search(server, "copper");
            for (String path : java.util.List.of("roots/alpha/file-00.txt", "roots/alpha/pending.txt",
                "roots/beta/processing.txt", "explicit.txt", "orphan/exhausted.txt")) {
              assertTrue(updated.contains(key(root.resolve(path))), "queued source is read: " + path);
            }
            assertTrue(search(server, "violetarrival").contains(key(root.resolve("roots/beta/offline-add.txt"))));
            assertFalse(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-02.txt"))),
                "U10: a conclusively deleted source is removed");
            assertEquals(0, count(root, "last_reason_code='MISSING_INGESTION_BOUNDARY'"));
            assertEquals(2, count(root, "retry_after=" + waitingUntil
                + " AND last_outcome_class IN ('PARSER_TIMEOUT','IO_FAILED')"), "U18: unrelated waits survive");
            assertEquals(2, count(root, "state='FAILED'"), "U12: terminal failures inside/outside roots are retained");
            assertEquals(0, count(root, "path='" + key(root.resolve("neighbor.txt")) + "'"),
                "U8: exact queued files never grant parent-folder authority");
            assertEquals("IDLE", state(root).migration_state());
            System.out.println("Release030 boot=" + boot + " DONE=" + count(root, "state='DONE'")
                + " waiting=" + count(root, "state='PENDING'") + " failed=" + count(root, "state='FAILED'"));
          }
        }
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void grandfatheredExplicitFileStillHonorsCurrentExclusions(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    ConfigStore previous = ConfigStore.globalOrNull();
    WorkerBootFixture.publishConfig(root.resolve("data"), root.resolve("data/index/default"),
        "BLUE_GREEN_MIGRATE", Map.of("justsearch.ai.disabled", "true", "justsearch.ai.embed.enabled", "false",
            "justsearch.splade.enabled", "false", "justsearch.ner.enabled", "false",
            "justsearch.ui.exclude_patterns", "[\"explicit.txt\"]"));
    var installed = ConfigStore.global();
    try {
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors();
        var server = boot(root, executors)) {
        assertEquals("IDLE", state(root).migration_state());
        activate(server);
        await(() -> count(root, "path='" + key(root.resolve("explicit.txt"))
            + "' AND state='DONE' AND last_reason_code='SKIPPED_TEMP_OR_SYSTEM'") == 1,
            "grandfathering is subject to current exclusions");
        assertFalse(search(server, "copper").contains(key(root.resolve("explicit.txt"))));
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  static KnowledgeServer boot(Path root, TestEngineExecutors executors) throws Exception {
    return boot(root, root, executors);
  }

  static KnowledgeServer boot(Path root, Path sources, TestEngineExecutors executors) throws Exception {
    var server = org.mockito.Mockito.spy(new KnowledgeServer(executors,
        WorkerBootFixture.workerConfig(root.resolve("data")), null));
    org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    server.bindBootRootBindings(java.util.List.of(
        new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(sources.resolve("roots/alpha"), null),
        new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(sources.resolve("roots/beta"), null)));
    // A spy copies fields; the constructor's default supplier still closes over its original
    // instance. Bind the same durable membership explicitly, as EngineRoot does in production.
    server.bindExclusionRoots(() -> java.util.List.of(sources.resolve("roots/alpha"), sources.resolve("roots/beta")));
    try { server.start(); } catch (Exception | Error failure) { server.close(); throw failure; }
    return server;
  }

  static void activate(KnowledgeServer server) throws Exception {
    var runtime = server.lifecycleManagerForTests();
    if (runtime instanceof io.justsearch.adapters.lucene.runtime.DeferredRuntime deferred) {
      var upgrade = KnowledgeServer.class.getDeclaredMethod("upgradeDeferredServing",
          io.justsearch.adapters.lucene.runtime.DeferredRuntime.class);
      upgrade.setAccessible(true);
      upgrade.invoke(server, deferred);
    }
    server.releaseModelReadyLatchForTests();
  }

  static java.util.List<String> search(KnowledgeServer server, String word) {
    return server.appServices().searchService().search(io.justsearch.ipc.SearchRequest.newBuilder()
        .setQuery(word).setLimit(100).build(), io.justsearch.indexerworker.services.CallContext.none())
        .getResultsList().stream().map(io.justsearch.ipc.SearchResult::getId).toList();
  }

  static String key(Path path) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(path);
  }

  static IndexGenerationManager.State state(Path root) {
    return new IndexGenerationManager(root.resolve("data/index/default")).readStateBestEffort();
  }

  static void sql(Path root, String command) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var statement = connection.createStatement()) { statement.executeUpdate(command); }
  }

  static long count(Path root, String condition) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var query = connection.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM jobs WHERE " + condition)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  static void await(Condition condition, String message) throws Exception {
    await(condition, message, 60);
  }

  static void await(Condition condition, String message, long seconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      if (condition.holds()) return;
      Thread.sleep(50);
    }
    assertTrue(condition.holds(), message);
  }

  @FunctionalInterface
  interface Condition { boolean holds() throws Exception; }

  static ConfigStore configure(Path root) {
    WorkerBootFixture.publishConfig(root.resolve("data"), root.resolve("data/index/default"),
        "BLUE_GREEN_MIGRATE", Map.of(
            "justsearch.ai.disabled", "true",
            "justsearch.ai.embed.enabled", "false",
            "justsearch.splade.enabled", "false",
            "justsearch.ner.enabled", "false"));
    return ConfigStore.global();
  }

  /** Hold model identity constant without shipping/loading model binaries. Restore exact caches. */
  static final class ReleasedModelIdentities implements AutoCloseable {
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
