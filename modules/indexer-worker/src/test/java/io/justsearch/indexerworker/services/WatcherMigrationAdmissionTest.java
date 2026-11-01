/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real watcher dispatch and candidate SQLite admission, without attempting main's stalled replay. */
final class WatcherMigrationAdmissionTest {
  @TempDir Path temp;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"CREATE", "MODIFY"})
  void inRootLinkKeepsMainAdmissionWithoutReplayLatch(String event) throws Exception {
    Path root = Files.createDirectory(temp.resolve("root"));
    assertInRootLinkAdmission(root, event);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"CREATE", "MODIFY"})
  void symlinkedParentRootKeepsMainLinkAdmissionWithoutReplayLatch(String event) throws Exception {
    Path realParent = Files.createDirectory(temp.resolve("real-parent"));
    Files.createDirectory(realParent.resolve("root"));
    Path linkedParent = Files.createSymbolicLink(temp.resolve("link-parent"), realParent);
    Path root = linkedParent.resolve("root");
    assertInRootLinkAdmission(root, event);
  }

  private void assertInRootLinkAdmission(Path root, String event) throws Exception {
    Path target = Files.writeString(root.resolve("target.txt"), "inside bytes");
    Path link = Files.createSymbolicLink(root.resolve("event.txt"), target);
    try (var f = new Fixture(root)) {
      assertDoesNotThrow(() -> f.watcher.dispatchEvent(f.witness, WorkerMethvinWatcher.Kind.valueOf(event), link));
      assertNull(f.failure.get(), "an in-root link must not enter the watcher failure handler");
      assertTrue(f.admission.replayCertain(), "main admits this event without latching uncertainty");
      assertEquals(1, f.queue.queueDepth());
      var row = f.queue.watcherReconciliationRow(PathNormalizer.normalizeKey(link));
      assertNotNull(row);
      assertEquals("PENDING", row.get("state"));
      var receipt = f.queue.listSwitchBufferOpsStrict().getFirst();
      assertEquals(f.generation, receipt.generation());
      var upsert = SwitchBufferUpsert.decode(receipt.payload());
      assertEquals(PathNormalizer.normalizeKey(link), upsert.path());
      assertEquals(root, upsert.ingestionRoot());
      assertEquals("docs", upsert.collection());
      assertEquals(SourceContentHash.sha256(target), upsert.sourceSha256());
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"file", "link"})
  void outsideAncestorIsRejectedBeforeCandidateContentAccess(String source) throws Exception {
    Path root = Files.createDirectory(temp.resolve("root"));
    Path parent = Files.createDirectory(root.resolve("parent"));
    Path file = Files.writeString(parent.resolve("event.txt"), "inside bytes");
    Path outside = Files.createDirectory(temp.resolve("outside"));
    Path target = Files.writeString(outside.resolve("target.txt"), "outside bytes");
    if (source.equals("link")) Files.createSymbolicLink(outside.resolve("event.txt"), target);
    else Files.writeString(outside.resolve("event.txt"), "outside bytes");
    try (var f = new Fixture(root)) {
      Files.delete(file);
      Files.delete(parent);
      Files.createSymbolicLink(parent, outside);
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        f.watcher.dispatchEvent(f.witness, WorkerMethvinWatcher.Kind.CREATE, file);
        assertDoesNotThrow(() -> hashes.verify(
            () -> SourceContentHash.sha256(any(Path.class), any(BooleanSupplier.class)), never()),
            "no outside bytes may reach the content hash");
      }
      assertEquals(0, f.queue.queueDepth());
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
      assertNotNull(f.failure.get(), "refused admission retains the watcher failure signal");
      assertFalse(f.admission.replayCertain());
    }
  }

  private final class Fixture implements AutoCloseable {
    final SqliteJobQueue queue = new SqliteJobQueue(temp.resolve("jobs.db"));
    final TestEngineExecutors executors = new TestEngineExecutors();
    final WorkerMutationAdmission admission;
    final RootWatcherRegistry.Subscription witness;
    final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    final WorkerMethvinWatcher watcher;
    final String generation;

    Fixture(Path root) throws Exception {
      Path base = temp.resolve("index");
      var generations = new IndexGenerationManager(base);
      var layout = generations.initializeOrLoad();
      generation = generations.startMigration("watcher-admission").building_generation();
      queue.open();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, layout.activeGenerationPath(), null, null, null, 0L);
      Object owner = new Object();
      admission = new WorkerMutationAdmission(owner);
      service.setMutationAdmission(admission, owner);
      witness = new RootWatcherRegistry.Subscription(root, "docs", new Object(), RootIdentity.capture(root));
      var registration = executors.register(new EngineExecutorSpec("migration-watcher-test",
          EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.SCHEDULED, 1, 64, 2));
      watcher = new WorkerMethvinWatcher(registration, queue, null, ignored -> {}, null,
          service::acceptWatcherUpsert, refused -> {
            failure.set(refused);
            admission.markReplayUncertain();
          });
    }

    @Override public void close() throws Exception {
      try { watcher.close(); } finally {
        try { executors.close(); } finally { queue.close(); }
      }
    }
  }
}
