/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.queue.JobQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Coverage and terminal accounting for the migration source walk. */
final class MigrationEnumerationCompletenessTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void absentRegistryAndEmptyConfigurationAreAnEmptyMigration(@TempDir Path dataDir)
      throws Exception {
    assertTrue(KnowledgeServerMigrationOps.loadMigrationRoots(dataDir, List.of(), JSON).isEmpty());
  }

  @Test
  void emptyRegistryAndConfigurationAreAnEmptyMigration(@TempDir Path dataDir) throws Exception {
    Files.writeString(dataDir.resolve("watched_roots.json"), "{\"schemaVersion\":1,\"roots\":[]}");
    assertTrue(KnowledgeServerMigrationOps.loadMigrationRoots(dataDir, List.of(), JSON).isEmpty());
  }

  @Test
  void legacyStringArrayLoadsExistingRoots(@TempDir Path dataDir) throws Exception {
    Path root = Files.createDirectory(dataDir.resolve("legacy"));
    Files.writeString(dataDir.resolve("watched_roots.json"),
        JSON.writeValueAsString(List.of(root.toString())));

    assertEquals(List.of(root.toAbsolutePath().normalize()),
        KnowledgeServerMigrationOps.loadMigrationRoots(dataDir, List.of(), JSON));
  }

  @Test
  void objectRootsArrayMergesConfigurationRoots(@TempDir Path dataDir) throws Exception {
    Path persisted = Files.createDirectory(dataDir.resolve("persisted"));
    Path configured = Files.createDirectory(dataDir.resolve("configured"));
    String registry = "{\"schemaVersion\":1,\"roots\":[{\"path\":\"%s\"}]}"
        .formatted(persisted.toString().replace("\\", "\\\\"));
    Files.writeString(dataDir.resolve("watched_roots.json"), registry);

    var collections = List.of(new ResolvedConfig.CollectionCfg("documents", List.of(configured)));
    assertEquals(List.of(persisted.toAbsolutePath().normalize(), configured.toAbsolutePath().normalize()),
        KnowledgeServerMigrationOps.loadMigrationRoots(dataDir, collections, JSON));
  }

  @Test
  void malformedRegistryIsRejected(@TempDir Path dataDir) throws Exception {
    Files.writeString(dataDir.resolve("watched_roots.json"), "{not-json");
    assertThrows(IOException.class,
        () -> KnowledgeServerMigrationOps.loadMigrationRoots(dataDir, List.of(), JSON));
  }

  @Test
  void invalidRegistryEntriesAreRejected(@TempDir Path dataDir) throws Exception {
    List<String> documents = List.of(
        "[1]",
        "[null]",
        "[\"  \"]",
        "{\"roots\":[{\"path\":1}]}",
        "{\"roots\":[{\"path\":\"  \"}]}" );
    for (String document : documents) {
      Files.writeString(dataDir.resolve("watched_roots.json"), document);
      assertThrows(IOException.class,
          () -> KnowledgeServerMigrationOps.loadMigrationRoots(dataDir, List.of(), JSON),
          document);
    }
  }

  @Test
  void futureRegistrySchemaIsRejected(@TempDir Path dataDir) throws Exception {
    Files.writeString(dataDir.resolve("watched_roots.json"),
        "{\"schemaVersion\":99,\"roots\":[]}");
    assertThrows(UnsupportedStoreVersionException.class,
        () -> KnowledgeServerMigrationOps.loadMigrationRoots(dataDir, List.of(), JSON));
  }

  @Test
  void missingDeclaredRootIsRejected(@TempDir Path dataDir) {
    Path missing = dataDir.resolve("does-not-exist");
    var collections = List.of(new ResolvedConfig.CollectionCfg("documents", List.of(missing)));
    assertThrows(IOException.class,
        () -> KnowledgeServerMigrationOps.loadMigrationRoots(dataDir, collections, JSON));
  }

  @Test
  void inaccessibleDeclaredRootIsRejectedWhenFilesystemCanExpressIt(@TempDir Path dataDir)
      throws Exception {
    Path root = Files.createDirectory(dataDir.resolve("inaccessible"));
    Assumptions.assumeTrue(Files.getFileAttributeView(root,
        java.nio.file.attribute.PosixFileAttributeView.class) != null);
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(root);
    try {
      Files.setPosixFilePermissions(root, Set.of(PosixFilePermission.OWNER_WRITE));
      Assumptions.assumeFalse(Files.isReadable(root));
      var collections = List.of(new ResolvedConfig.CollectionCfg("documents", List.of(root)));
      assertThrows(IOException.class,
          () -> KnowledgeServerMigrationOps.loadMigrationRoots(dataDir, collections, JSON));
    } finally {
      Files.setPosixFilePermissions(root, original);
    }
  }

  @Test
  void emptyValidScanReturnsZero(@TempDir Path directory) throws Exception {
    Counters counters = new Counters();
    JobQueue queue = acceptingQueue();
    int accepted = KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(Files.createDirectory(directory.resolve("empty"))), queue, counters,
            () -> true));

    assertEquals(0, accepted);
    assertEquals(0, counters.filesSeen.get());
    assertEquals(0, counters.filesEnqueued.get());
    assertEquals(1, counters.rootsDone.get());
  }

  @Test
  void directoryRootEnqueuesEveryFileAndUpdatesCounters(@TempDir Path directory) throws Exception {
    Path root = Files.createDirectory(directory.resolve("documents"));
    Files.writeString(root.resolve("one.txt"), "one");
    Path nested = Files.createDirectory(root.resolve("nested"));
    Files.writeString(nested.resolve("two.txt"), "two");
    Counters counters = new Counters();
    JobQueue queue = acceptingQueue();

    int accepted = KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(root), queue, counters, () -> true));

    assertEquals(2, accepted);
    assertEquals(2, counters.filesSeen.get());
    assertEquals(2, counters.filesEnqueued.get());
    assertEquals(1, counters.rootsDone.get());
    verify(queue).enqueueEntries(anyList());
  }

  @Test
  void singleFileRootIsAccepted(@TempDir Path directory) throws Exception {
    Path file = directory.resolve("single.txt");
    Files.writeString(file, "one file");
    Counters counters = new Counters();

    int accepted = KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(file), acceptingQueue(), counters, () -> true));

    assertEquals(1, accepted);
    assertEquals(1, counters.filesSeen.get());
    assertEquals(1, counters.filesEnqueued.get());
    assertEquals(1, counters.rootsDone.get());
  }

  @Test
  void missingRootStopsBeforeItCanBeCountedDone(@TempDir Path directory) throws Exception {
    Path missing = directory.resolve("missing");
    Counters counters = new Counters();
    assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(missing), acceptingQueue(), counters, () -> true)));
    assertEquals(0, counters.rootsDone.get());
    assertEquals(0, counters.filesSeen.get());
  }

  @Test
  void nullQueueIsAProvenanceFailure(@TempDir Path directory) {
    Counters counters = new Counters();
    assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(directory), null, counters, () -> true)));
    assertEquals(0, counters.rootsDone.get());
  }

  @Test
  void shortAdmissionFailsAndDoesNotCloseTheRoot(@TempDir Path directory) throws Exception {
    Path file = directory.resolve("one.txt");
    Files.writeString(file, "one");
    Counters counters = new Counters();
    JobQueue queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList())).thenReturn(0);

    assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(file), queue, counters, () -> true)));
    assertEquals(1, counters.filesSeen.get());
    assertEquals(0, counters.filesEnqueued.get());
    assertEquals(0, counters.rootsDone.get());
  }

  @Test
  void queueFailureIsRetainedForTheEnumerationFailureLatch(@TempDir Path directory)
      throws Exception {
    Path file = directory.resolve("one.txt");
    Files.writeString(file, "one");
    Counters counters = new Counters();
    JobQueue queue = mock(JobQueue.class);
    doThrow(new IllegalStateException("queue unavailable")).when(queue).enqueueEntries(anyList());

    assertThrows(IllegalStateException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(file), queue, counters, () -> true)));
    assertEquals(1, counters.filesSeen.get());
    assertEquals(0, counters.filesEnqueued.get());
    assertEquals(0, counters.rootsDone.get());
  }

  @Test
  void stoppedRunFailsBeforeAdmission(@TempDir Path directory) throws Exception {
    Path file = directory.resolve("one.txt");
    Files.writeString(file, "one");
    Counters counters = new Counters();
    JobQueue queue = mock(JobQueue.class);

    assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(file), queue, counters, () -> false)));
    verify(queue, never()).enqueueEntries(anyList());
    assertEquals(0, counters.rootsDone.get());
  }

  @Test
  void interruptedRunFailsBeforeAdmission(@TempDir Path directory) throws Exception {
    Path file = directory.resolve("one.txt");
    Files.writeString(file, "one");
    Counters counters = new Counters();
    try {
      Thread.currentThread().interrupt();
      assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
          context(List.of(file), acceptingQueue(), counters, () -> true)));
      assertEquals(0, counters.rootsDone.get());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void queueCallbackInterruptsWithoutCountingAnIncompleteRoot(@TempDir Path directory)
      throws Exception {
    Path root = Files.createDirectory(directory.resolve("first"));
    Files.writeString(root.resolve("one.txt"), "one");
    Counters counters = new Counters();
    JobQueue queue = mock(JobQueue.class);
    doAnswer(invocation -> {
      Thread.currentThread().interrupt();
      return 1;
    }).when(queue).enqueueEntries(anyList());

    try {
      assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
          context(List.of(root), queue, counters, () -> true)));
      assertEquals(1, counters.filesSeen.get());
      assertEquals(1, counters.filesEnqueued.get());
      assertEquals(0, counters.rootsDone.get());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void queueCallbackStopsBeforeRootCompletion(@TempDir Path directory) throws Exception {
    Path root = Files.createDirectory(directory.resolve("first"));
    Files.writeString(root.resolve("one.txt"), "one");
    Counters counters = new Counters();
    AtomicBoolean running = new AtomicBoolean(true);
    JobQueue queue = mock(JobQueue.class);
    doAnswer(invocation -> {
      running.set(false);
      return 1;
    }).when(queue).enqueueEntries(anyList());

    assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(root), queue, counters, running::get)));
    assertEquals(1, counters.filesSeen.get());
    assertEquals(1, counters.filesEnqueued.get());
    assertEquals(0, counters.rootsDone.get());
  }

  @Test
  void removingLaterRootFailsBeforeItIsCountedDone(@TempDir Path directory) throws Exception {
    Path first = Files.createDirectory(directory.resolve("first"));
    Files.writeString(first.resolve("one.txt"), "one");
    Path later = Files.createDirectory(directory.resolve("later"));
    Counters counters = new Counters();
    JobQueue queue = mock(JobQueue.class);
    doAnswer(invocation -> {
      Files.delete(later);
      return 1;
    }).when(queue).enqueueEntries(anyList());

    assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(first, later), queue, counters, () -> true)));
    assertEquals(1, counters.filesSeen.get());
    assertEquals(1, counters.filesEnqueued.get());
    assertEquals(1, counters.rootsDone.get());
  }

  @Test
  void symbolicDirectoryRootCannotCertifyAnEmptyScan(@TempDir Path data) throws Exception {
    Path target = Files.createDirectory(data.resolve("target"));
    Files.writeString(target.resolve("document.txt"), "content");
    Path link = createSymbolicLinkOrSkip(data.resolve("link"), target);
    var collections = List.of(new ResolvedConfig.CollectionCfg("documents", List.of(link)));
    assertThrows(IOException.class,
        () -> KnowledgeServerMigrationOps.loadMigrationRoots(data, collections, JSON));
    Counters counters = new Counters();
    assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(link), acceptingQueue(), counters, () -> true)));
    assertEquals(0, counters.rootsDone.get());
  }

  @Test
  void nestedSymbolicFileCannotEscapeNoFollowCoverage(@TempDir Path data) throws Exception {
    Path target = data.resolve("outside.txt");
    Files.writeString(target, "content");
    Path root = Files.createDirectory(data.resolve("root"));
    createSymbolicLinkOrSkip(root.resolve("link.txt"), target);
    Counters counters = new Counters();
    JobQueue queue = acceptingQueue();
    assertThrows(IOException.class, () -> KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        context(List.of(root), queue, counters, () -> true)));
    assertEquals(0, counters.rootsDone.get());
    verify(queue, never()).enqueueEntries(anyList());
  }

  private static Path createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
    try {
      return Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
      Assumptions.abort("Filesystem cannot create symbolic-link fixture: " + unavailable);
      throw new AssertionError("unreachable", unavailable);
    }
  }

  private static JobQueue acceptingQueue() {
    JobQueue queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList())).thenAnswer(invocation -> {
      List<?> entries = invocation.getArgument(0);
      return entries.size();
    });
    return queue;
  }

  private static KnowledgeServerMigrationOps.EnqueueContext context(
      List<Path> roots, JobQueue queue, Counters counters, java.util.function.BooleanSupplier running) {
    return new KnowledgeServerMigrationOps.EnqueueContext(
        roots,
        queue,
        running,
        () -> null,
        counters.filesSeen,
        counters.filesEnqueued,
        counters.rootsDone,
        counters.lastPath,
        () -> null,
        () -> null,
        ignored -> {},
        LoggerFactory.getLogger(MigrationEnumerationCompletenessTest.class));
  }

  private static final class Counters {
    private final AtomicLong filesSeen = new AtomicLong();
    private final AtomicLong filesEnqueued = new AtomicLong();
    private final AtomicLong rootsDone = new AtomicLong();
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
  }
}
