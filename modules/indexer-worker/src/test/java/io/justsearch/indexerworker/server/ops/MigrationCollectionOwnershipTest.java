/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.InternalCollections;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Migration owns an exact, frozen root binding, unlike maintenance admissions. */
final class MigrationCollectionOwnershipTest {
  @TempDir Path temp;

  @Test
  void oneOwningAdmissionHandlesDeepRootsSiblingPrefixesFilesAndHelp() throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("C"));
    Path deep = Files.createDirectories(child.resolve("D"));
    Path sibling = Files.createDirectories(parent.resolve("C2"));
    Path help = Files.createDirectories(parent.resolve("help"));
    Path single = Files.writeString(parent.resolve("single.txt"), "single");
    Path own = Files.writeString(Files.createDirectories(parent.resolve("undeclared")).resolve("own.txt"), "own");
    Path c = Files.writeString(child.resolve("child.txt"), "child");
    Path d = Files.writeString(deep.resolve("deep.txt"), "deep");
    Path beside = Files.writeString(sibling.resolve("beside.txt"), "beside");
    Path markdown = Files.writeString(help.resolve("help.md"), "help");
    Files.writeString(help.resolve("ignored.txt"), "ignored");
    Files.writeString(Files.createDirectories(help.resolve("nested")).resolve("ignored.md"), "ignored");
    var roots = new ArrayList<>(List.of(source(parent, "P"), source(child, "C"),
        source(deep, "D"), source(sibling, "C2"), source(single, "single"), source(help, InternalCollections.HELP)));
    for (int order = 0; order < 2; order++) {
      try (var queue = open("coverage-" + order)) {
        AtomicLong seen = new AtomicLong();
        assertEquals(6, enumerate(roots, queue, seen, () -> true));
        assertEquals(6, seen.get(), "each file belongs to exactly one declared root");
        assertEquals(6, queue.listSwitchBufferOpsStrict().size());
        for (var expected : Map.of(own, "P", c, "C", d, "D", beside, "C2",
            single, "single", markdown, InternalCollections.HELP).entrySet()) {
          assertEquals(expected.getValue(), receipt(queue, expected.getKey()).collection());
        }
      }
      Collections.reverse(roots);
    }
  }

  @Test
  void loadedOwnershipSurvivesDeclarationChangesDuringWalkAndRestart() throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Files.writeString(parent.resolve("own.txt"), "own");
    Path nested = Files.writeString(child.resolve("nested.txt"), "nested");
    var loaded = List.of(source(child, "C"), source(parent, "P"));
    AtomicLong seen = new AtomicLong();
    try (var queue = open("snapshot")) {
      assertEquals(2, enumerate(loaded, queue, seen, () -> {
        if (seen.get() > 0) {
          try {
            Files.writeString(temp.resolve("watched_roots.json"), new ObjectMapper().writeValueAsString(
                Map.of("schemaVersion", 1, "roots", List.of(Map.of("path", parent.toString(), "collection", "P")))));
          } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
        }
        return true;
      }));
      assertEquals("C", receipt(queue, nested).collection());
    }
    // A restarted, complete walk gets a fresh snapshot; none of the first snapshot's child
    // receipts may defeat the new parent's ownership after the declaration was removed.
    try (var queue = open("snapshot")) {
      var reloaded = KnowledgeServerMigrationOps.loadMigrationRoots(temp,
          new ResolvedConfig.Collections(List.of()), new ObjectMapper());
      assertEquals(2, enumerate(reloaded, queue, new AtomicLong(), () -> true));
      assertEquals("P", receipt(queue, nested).collection());
      AtomicLong secondSeen = new AtomicLong();
      assertEquals(2, enumerate(reloaded, queue, secondSeen, () -> {
        if (secondSeen.get() > 0) {
          try {
            Files.writeString(temp.resolve("watched_roots.json"), new ObjectMapper().writeValueAsString(
                Map.of("schemaVersion", 1, "roots", List.of(
                    Map.of("path", child.toString(), "collection", "new-C"),
                    Map.of("path", parent.toString(), "collection", "P")))));
          } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
        }
        return true;
      }));
      assertEquals("P", receipt(queue, nested).collection(),
          "a newly added declaration does not change the loaded ownership snapshot mid-walk");
    }
    try (var queue = open("snapshot")) {
      var reloaded = KnowledgeServerMigrationOps.loadMigrationRoots(temp,
          new ResolvedConfig.Collections(List.of()), new ObjectMapper());
      assertEquals(2, enumerate(reloaded, queue, new AtomicLong(), () -> true));
      assertEquals("new-C", receipt(queue, nested).collection(),
          "a restart takes a complete new snapshot including the added child");
    }
  }

  @Test
  void exactDefaultRepairsUnchangedWatcher() throws Exception {
    Path file = Files.writeString(temp.resolve("file.txt"), "unchanged");
    try (var queue = open("watcher")) {
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)), "stale-parent"));
      assertTrue(queue.enqueueAndBufferFileForGeneration("green",
          JobQueue.EnqueueEntry.stat(file), null));
      assertEquals("stale-parent", receipt(queue, file).collection(),
          "maintenance admission must retain its existing collection-preserving contract");
      assertEquals(1, enumerate(List.of(source(file, null)), queue, new AtomicLong(), () -> true));
      assertNull(receipt(queue, file).collection(), "unchanged watcher bytes do not authorize a stale label");
      assertTrue(queue.matchesExpectedCollection(file, null));
    }
  }

  @Test
  void newerAcceptedBytesBeatOlderEnumerationAndStaleReceiptsYieldToCurrentSource() throws Exception {
    Path file = Files.writeString(temp.resolve("file.txt"), "older enumeration bytes");
    String oldHash = SourceContentHash.sha256(file);
    long oldSize = Files.size(file);
    try (var queue = open("mutations")) {
      Files.writeString(file, "newer accepted mutation");
      assertTrue(queue.enqueueAndBufferFileForGeneration("green",
          JobQueue.EnqueueEntry.stat(file), "accepted"));
      var newer = queue.listSwitchBufferOpsStrict().getFirst();
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("green", List.of(
          new JobQueue.EnqueueEntry(file, oldSize, null, oldHash, null)), null));
      assertEquals(newer, queue.listSwitchBufferOpsStrict().getFirst(),
          "an older baseline witness cannot replace newer accepted source bytes");
      assertTrue(queue.matchesExpectedCollection(file, "accepted"));
      assertEquals(SourceContentHash.sha256(file), queue.pollPending(1).getFirst().plannedSourceSha256());
      Files.writeString(file, "changed after the foreground receipt");
      assertEquals(1, enumerate(List.of(source(file, null)), queue, new AtomicLong(), () -> true));
      assertEquals(SourceContentHash.sha256(file), receipt(queue, file).sourceSha256(),
          "a stale foreground receipt yields to newly observed source bytes");
      assertNull(receipt(queue, file).collection());
    }
  }

  @Test
  void collectionDeletionUsesExactOwnerEvenForDefaultAndStaleQueueRows() throws Exception {
    Path defaultFile = Files.writeString(temp.resolve("default.txt"), "default");
    Path labelled = Files.writeString(temp.resolve("labelled.txt"), "labelled");
    try (var queue = open("deletion")) {
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(defaultFile)), "deleted");
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(labelled)));
      assertTrue(queue.putSwitchBufferForGeneration("green", "collection:deleted", "DELETE_COLLECTION", "deleted"));
      assertEquals(2, enumerate(List.of(source(defaultFile, null), source(labelled, "deleted")),
          queue, new AtomicLong(), () -> true));
      assertNull(receipt(queue, defaultFile).collection());
      assertTrue(queue.matchesExpectedCollection(defaultFile, null));
      assertTrue(queue.listSwitchBufferOpsStrict().stream()
          .noneMatch(op -> op.key().equals("path:" + PathNormalizer.normalizeKey(labelled))), "the labelled root remains deleted");
    }
  }

  @Test
  void watchedDefaultWinsConfiguredLabelAndStillPartitionsItsParent() throws Exception {
    Path parent = Files.createDirectories(temp.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path file = Files.writeString(child.resolve("nested.txt"), "nested");
    Files.writeString(temp.resolve("watched_roots.json"), new ObjectMapper().writeValueAsString(
        Map.of("schemaVersion", 1, "roots", List.of(Map.of("path", child.toString())))));
    var configured = new ResolvedConfig.Collections(List.of(
        new ResolvedConfig.CollectionCfg("P", List.of(parent)),
        new ResolvedConfig.CollectionCfg("configured-child", List.of(child))));
    var loaded = KnowledgeServerMigrationOps.loadMigrationRoots(temp, configured, new ObjectMapper());
    assertEquals(List.of(source(child, null), source(parent, "P")), loaded);
    try (var queue = open("configured")) {
      assertEquals(1, enumerate(loaded, queue, new AtomicLong(), () -> true));
      assertNull(receipt(queue, file).collection());
    }
  }

  private SqliteJobQueue open(String name) throws Exception {
    var queue = new SqliteJobQueue(temp.resolve(name + ".db"));
    queue.open();
    return queue;
  }

  private static ResolvedConfig.FileSource source(Path path, String collection) {
    return new ResolvedConfig.FileSource(path, collection);
  }

  private static SwitchBufferUpsert receipt(SqliteJobQueue queue, Path file) throws Exception {
    var found = queue.listSwitchBufferOpsStrict().stream().filter(op -> op.key().equals("path:" + PathNormalizer.normalizeKey(file)))
        .map(op -> SwitchBufferUpsert.decode(op.payload())).findFirst();
    assertTrue(found.isPresent(), "the owning root must retain an admitted receipt for " + file);
    return found.orElseThrow();
  }

  private static int enumerate(List<ResolvedConfig.FileSource> roots, SqliteJobQueue queue,
      AtomicLong seen, java.util.function.BooleanSupplier running) throws Exception {
    var manager = mock(IndexGenerationManager.class);
    when(manager.readStateBestEffort()).thenReturn(new IndexGenerationManager.State(
        2, "blue", "green", null, "MIGRATING", false, null, null,
        System.currentTimeMillis(), null, null, null));
    return KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        new KnowledgeServerMigrationOps.EnqueueContext(roots, queue, running, () -> manager,
            seen, new AtomicLong(), new AtomicLong(), new AtomicReference<>(), () -> null,
            () -> null, ignored -> {}, LoggerFactory.getLogger(MigrationCollectionOwnershipTest.class)));
  }
}
