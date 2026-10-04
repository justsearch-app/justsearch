/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatcherConstructorBoundaryTest {
  @TempDir Path directory;

  @AfterEach
  void resetPolicy() {
    IngestionSkipPolicy.resetToDefaults();
  }

  @Test
  void freshCreateThroughEveryConstructorRetainsBoundaryForSqliteConsumer() throws Exception {
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    // Each constructor starts with an empty queue. Replacement must not supply a missing root.
    for (int route = 4; route <= 9; route++) {
      Path root = Files.createDirectories(directory.resolve("private").resolve("route-" + route))
          .toAbsolutePath().normalize();
      Path publicFile = Files.writeString(root.resolve("public.txt"), "public content");
      Path excludedFile = Files.writeString(
          Files.createDirectory(root.resolve("private")).resolve("notes.txt"), "private content");
      var witness = new RootWatcherRegistry.Subscription(root, "docs", new Object(),
          RootIdentity.capture(root));
      Path database = directory.resolve("route-" + route + ".db");
      try (var queue = new SqliteJobQueue(database); var executors = new TestEngineExecutors()) {
        queue.open();
        var registration = executors.register(new EngineExecutorSpec("watcher-route-" + route,
            EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.SCHEDULED, 1, 64, 2));
        WorkerMethvinWatcher.WitnessedUpsertSink upsert = (observed, collection, path) -> {
          assertSame(witness, observed);
          assertEquals(1, queue.enqueueEntries(
              List.of(WorkerMethvinWatcher.entryForLiveEvent(path).withinRoot(observed.root())),
              collection));
        };
        try (var watcher = watcher(route, registration, queue, upsert)) {
          watcher.dispatchEvent(witness, WorkerMethvinWatcher.Kind.CREATE, excludedFile);
          watcher.dispatchEvent(witness, WorkerMethvinWatcher.Kind.MODIFY, excludedFile);
          assertTrue(queue.pollPending(10).isEmpty());
          watcher.dispatchEvent(witness, WorkerMethvinWatcher.Kind.CREATE, publicFile);
        }
      }
      // Restart is part of the assertion: the consumer uses the persisted subscription boundary.
      try (var queue = new SqliteJobQueue(database)) {
        queue.open();
        var claims = queue.pollPending(10);
        assertEquals(1, claims.size(), "constructor " + route);
        var claim = claims.getFirst();
        assertEquals(publicFile, claim.path());
        assertEquals("ADMIT", consumerAction(claim));
        assertEquals(root, claim.ingestionRoot());
        // A later policy change is enforced by the consumer against the same saved root.
        IngestionSkipPolicy.installResolved(
            new IngestionSkipPolicy(null, null, Set.of("private", root.getFileName().toString())));
        assertEquals("ADMIT", consumerAction(claim), "the boundary itself is not a descendant");
      }
    }
  }

  private static WorkerMethvinWatcher watcher(int route,
      EngineExecutorRegistry.Registration registration, JobQueue queue,
      WorkerMethvinWatcher.WitnessedUpsertSink upsert) {
    Consumer<String> delete = ignored -> {};
    BiConsumer<Path, Boolean> reconcile = (ignored, force) -> {};
    Consumer<RuntimeException> failure = error -> { throw error; };
    WorkerMethvinWatcher.EventRouter router = (ignored, effect) -> effect.run();
    return switch (route) {
      case 4 -> new WorkerMethvinWatcher(registration, queue, null, delete);
      case 5 -> new WorkerMethvinWatcher(registration, queue, null, delete, reconcile);
      case 6 -> new WorkerMethvinWatcher(registration, queue, null, delete, reconcile, upsert);
      case 7 -> new WorkerMethvinWatcher(registration, queue, null, delete, reconcile, upsert, failure);
      case 8 -> new WorkerMethvinWatcher(registration, queue, null, delete, reconcile, upsert,
          failure, router);
      case 9 -> new WorkerMethvinWatcher(registration, queue, null, delete, reconcile, upsert,
          failure, router, (ignored, path) -> delete.accept(path));
      default -> throw new IllegalArgumentException("Unknown watcher constructor");
    };
  }

  private static String consumerAction(JobQueue.IndexJob claim) throws Exception {
    // The real consumer authority is package-private in worker-services' loop package.
    // Keep its visibility unchanged while exercising it with a real SQLite-issued claim.
    Class<?> authority = Class.forName("io.justsearch.indexerworker.loop.WorkerIngestionAuthority");
    var constructor = authority.getDeclaredConstructor();
    constructor.setAccessible(true);
    var admit = authority.getDeclaredMethod("admit", JobQueue.IndexJob.class);
    admit.setAccessible(true);
    Object admission = admit.invoke(constructor.newInstance(), claim);
    var action = admission.getClass().getDeclaredMethod("action");
    action.setAccessible(true);
    return action.invoke(admission).toString();
  }
}
