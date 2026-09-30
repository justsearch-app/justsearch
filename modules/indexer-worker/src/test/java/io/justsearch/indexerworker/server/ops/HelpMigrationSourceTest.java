/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Real source resolution, filesystem enumeration and generation-scoped SQLite admission. */
final class HelpMigrationSourceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path directory;

  @Test
  void greenIncludesResolvedHelpDespiteCurrentStartupMarkerAndNoOldJobs() throws Exception {
    Path ssot = directory.resolve("separate-ssot");
    Path help = Files.createDirectories(ssot.resolve("docs/help"));
    Path file = Files.writeString(help.resolve("welcome.md"), "# Help\n");
    Files.writeString(help.resolve("ignored.txt"), "not startup help");
    Files.writeString(Files.createDirectories(help.resolve("nested")).resolve("ignored.md"), "nested");
    Files.writeString(directory.resolve(".help-ingested-version"), "v2");
    var config = resolved(ssot);

    var sources = KnowledgeServerMigrationOps.loadMigrationRoots(directory, config.collections(), JSON);
    assertEquals(1, sources.size(), "Green must enumerate the bundled help source");
    assertSame(config.collections().bundledHelp(), sources.getFirst());
    assertEquals("justsearch-help", sources.getFirst().collection());
    assertEquals(help, sources.getFirst().path());
    assertTrue(config.collections().items().isEmpty(), "help must not become an operator collection");
    assertFalse(Files.exists(directory.resolve("watched_roots.json")));

    var manager = new IndexGenerationManager(directory.resolve("index"));
    manager.initializeOrLoad();
    var migration = manager.startFreshMigration("help-regression");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open();
      assertEquals(0, queue.queueDepth(), "no old job may supply collection identity");
      assertTrue(queue.putSwitchBufferForGeneration(migration.building_generation(),
          "collection:books", "DELETE_COLLECTION", "books"));
      var context = new KnowledgeServerMigrationOps.EnqueueContext(sources, queue, () -> true,
          () -> manager, new AtomicLong(), new AtomicLong(), new AtomicLong(),
          new AtomicReference<>(""), () -> null, () -> null, ignored -> {},
          LoggerFactory.getLogger(getClass()));
      assertEquals(1, KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(context));
      var journal = queue.listSwitchBufferOpsStrict().stream()
          .filter(operation -> "UPSERT".equals(operation.op())).toList();
      assertEquals(1, journal.size());
      assertEquals(migration.building_generation(), journal.getFirst().generation());
      var upsert = SwitchBufferUpsert.decode(journal.getFirst().payload());
      assertEquals("justsearch-help", upsert.collection(), "candidate replay must retain help identity");
      assertEquals(io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file), upsert.path());
      assertEquals("justsearch-help", queue.pollPending(1).getFirst().collection());
    }
    assertEquals("v2", Files.readString(directory.resolve(".help-ingested-version")));
    assertFalse(Files.exists(directory.resolve("watched_roots.json")));
  }

  @Test
  void missingPhysicalHelpIsSkippedWithoutError() throws Exception {
    var config = resolved(directory.resolve("missing-ssot"));
    assertEquals("justsearch-help", config.collections().bundledHelp().collection());
    assertEquals(List.of(), KnowledgeServerMigrationOps.loadMigrationRoots(directory, config.collections(), JSON));
  }

  @Test
  void configuredAndWatchedSourcesRetainTheirCollections() throws Exception {
    Path root = Files.createDirectory(directory.resolve("books"));
    Path file = Files.writeString(root.resolve("one.txt"), "one");
    Files.writeString(directory.resolve("watched_roots.json"), JSON.writeValueAsString(
        java.util.Map.of("schemaVersion", 1, "roots", List.of(
            java.util.Map.of("path", root.toString(), "collection", "books")))));
    var sources = KnowledgeServerMigrationOps.loadMigrationRoots(directory,
        new ResolvedConfig.Collections(List.of()), JSON);
    assertEquals("books", sources.getFirst().collection());
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open();
      var context = new KnowledgeServerMigrationOps.EnqueueContext(sources, queue, () -> true,
          () -> null, new AtomicLong(), new AtomicLong(), new AtomicLong(),
          new AtomicReference<>(""), () -> null, () -> null, ignored -> {}, LoggerFactory.getLogger(getClass()));
      assertEquals(1, KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(context));
      assertEquals("books", queue.pollPending(1).getFirst().collection());
    }
    Files.delete(directory.resolve("watched_roots.json"));
    var configured = KnowledgeServerMigrationOps.loadMigrationRoots(directory,
        new ResolvedConfig.Collections(List.of(new ResolvedConfig.CollectionCfg("books", List.of(file)))), JSON);
    assertEquals(new ResolvedConfig.FileSource(file, "books"), configured.getFirst());
  }

  private ResolvedConfig resolved(Path ssot) {
    return ResolvedConfig.builder().putDefault("justsearch.ssot.path", ssot.toString()).build();
  }
}
