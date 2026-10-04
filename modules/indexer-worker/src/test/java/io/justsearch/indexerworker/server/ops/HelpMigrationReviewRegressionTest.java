/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class HelpMigrationReviewRegressionTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path directory;

  @Test
  void defaultWatchedBindingWinsSamePathConfiguredCollection() throws Exception {
    Path root = Files.createDirectory(directory.resolve("books"));
    Path file = Files.writeString(root.resolve("one.txt"), "one");
    Map<String, Object> watched = new LinkedHashMap<>();
    watched.put("path", root.toString());
    watched.put("collection", null);
    Files.writeString(directory.resolve("watched_roots.json"), JSON.writeValueAsString(
        Map.of("schemaVersion", 1, "roots", List.of(watched))));
    var collections = new ResolvedConfig.Collections(
        List.of(new ResolvedConfig.CollectionCfg("books", List.of(root))));
    var sources = KnowledgeServerMigrationOps.loadMigrationRoots(directory, collections, JSON);
    assertEquals(1, sources.size());
    assertNull(sources.getFirst().collection(),
        "watched-root presence must preserve its default binding over configured books");
    var manager = new IndexGenerationManager(directory.resolve("index"));
    manager.initializeOrLoad();
    var migration = manager.startFreshMigration("collision-regression");
    try (var queue = new SqliteJobQueue(directory.resolve("jobs.db"))) {
      queue.open();
      assertEquals(1, queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(file, 3)), "default"));
      var context = new KnowledgeServerMigrationOps.EnqueueContext(sources, queue, () -> true,
          () -> manager, new AtomicLong(), new AtomicLong(), new AtomicLong(),
          new AtomicReference<>(""), () -> null, () -> null, ignored -> {},
          LoggerFactory.getLogger(getClass()));
      assertEquals(1, KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(context));
      assertEquals("default", queue.pollPending(1).getFirst().collection());
      var operation = queue.listSwitchBufferOpsStrict().getFirst();
      assertEquals(migration.building_generation(), operation.generation());
      assertEquals("default", SwitchBufferUpsert.decode(operation.payload()).collection());
    }
  }

  @Test
  void evalMigrationExcludesPhysicalHelpButKeepsConfiguredCorpus() throws Exception {
    Path ssot = directory.resolve("SSOT");
    Path help = Files.createDirectories(ssot.resolve("docs/help"));
    Files.writeString(help.resolve("welcome.md"), "# Help");
    Path corpus = Files.createDirectory(directory.resolve("eval"));
    Files.writeString(corpus.resolve("one.txt"), "eval corpus");
    var config = ResolvedConfig.builder().putDefault("justsearch.ssot.path", ssot.toString()).build();
    var collections = new ResolvedConfig.Collections(
        List.of(new ResolvedConfig.CollectionCfg("eval", List.of(corpus))),
        config.collections().bundledHelp());
    String previous = System.getProperty("justsearch.eval.mode");
    try {
      System.setProperty("justsearch.eval.mode", "true");
      var sources = KnowledgeServerMigrationOps.loadMigrationRoots(directory, collections, JSON);
      assertTrue(sources.stream().noneMatch(source -> "justsearch-help".equals(source.collection())),
          "eval Green migration must exclude bundled help");
      assertEquals(List.of(new ResolvedConfig.FileSource(corpus, "eval")), sources);
      System.setProperty("justsearch.eval.mode", "false");
      assertEquals(2, KnowledgeServerMigrationOps.loadMigrationRoots(
          directory, collections, JSON).size());
    } finally {
      if (previous == null) System.clearProperty("justsearch.eval.mode");
      else System.setProperty("justsearch.eval.mode", previous);
    }
  }
}
