/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;

/** Real process death at cross-store publication boundaries; no close or shutdown hook runs. */
@Timeout(600)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementCrashTest {
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.MethodSource("cuts")
  void killedRetirementResumesAndBackupCannotReviveBuild(String cut, String fixture, @TempDir Path temporary)
      throws Exception {
      Path root = Release030Fixture.unpack(fixture, temporary.resolve(fixture));
      var original = state(root);
      Path updated = Files.writeString(root.resolve("roots/alpha/pending.txt"), "retirementcrashcobalt");
      Files.delete(root.resolve("explicit.txt"));
      Path rootOnly = Files.writeString(root.resolve("roots/alpha/root-only.txt"), "rootrecoveryadmittedcobalt");
      try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
        queue.open();
        assertTrue(queue.enqueueAndBufferFileForGeneration(original.building_generation(),
            JobQueue.EnqueueEntry.stat(updated).withinRoot(root.resolve("roots/alpha")), null));
        String deleted = key(root.resolve("explicit.txt"));
        assertTrue(queue.putSwitchBufferForGeneration(original.building_generation(), "path:" + deleted,
            "DELETE", new io.justsearch.indexerworker.queue.SwitchBufferDelete(deleted,
                io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.EXPLICIT, null).encode()));
      }
      if (cut.equals("during-deletion")) {
        // The paused release can have no B payload yet. A real empty commit supplies a
        // physical build file so the existing deletion harness can cut after removing it.
        Path building = root.resolve("data/index/default/indices").resolve(original.building_generation());
        try (var directory = org.apache.lucene.store.FSDirectory.open(building);
            var writer = new org.apache.lucene.index.IndexWriter(directory,
                new org.apache.lucene.index.IndexWriterConfig())) {
          writer.commit();
        }
      }
      killAt(root, cut, original.building_generation());
      if (cut.equals("during-deletion") || cut.equals("release030-building-retired")) {
        Path base = root.resolve("data/index/default");
        Files.copy(base.resolve("state.json.prev"), base.resolve("state.json"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      ConfigStore previous = ConfigStore.globalOrNull();
      var installed = configure(root);
      try {
        var models = new ReleasedModelIdentities(root);
        try (models) {
          for (int restart = 0; restart < 2; restart++) {
            try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
              assertEquals("IDLE", state(root).migration_state(), "restoring a backup cannot revive the build");
              assertNull(state(root).building_generation());
              activate(server);
              awaitEligibleRecovery(server, () -> state(root).previous_generation() == null
                  && search(server, "retirementcrashcobalt").contains(key(updated))
                  && !search(server, "copper").contains(key(root.resolve("explicit.txt")))
                  && !Files.exists(root.resolve("data/upgrade-030-recovery.properties")),
                  "replay and owned-executor root recovery converge after " + cut, 150);
              assertTrue(search(server, "rootrecoveryadmittedcobalt").contains(key(rootOnly)));
              assertEquals(original.active_generation(), state(root).active_generation());
              assertEquals("IDLE", state(root).migration_state());
              assertNull(state(root).building_generation());
              assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
              assertTrue(Release030RetirementSafetyTest.queue(server).listSwitchBufferOpsStrict().isEmpty());
              assertFalse(Files.exists(root.resolve("data/index/default/migration_progress.json")));
              try (var directories = Files.list(root.resolve("data/index/default/indices"))) {
                assertTrue(directories.noneMatch(path -> path.getFileName().toString()
                    .startsWith(original.building_generation())), "no original or marked B remains");
              }
            }
          }
        }
      } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> cuts() {
    return java.util.stream.Stream.of("release030-checkpointed", "release030-cancelled", "release030-queue-prepared",
        "release030-preparation-published", "release030-roots-journaled", "release030-root-admitted", "release030-root-walked", "release030-differences-committed", "release030-replay-before-commit", "release030-replay-committed",
        "release030-journal-acknowledged", "release030-replay-complete", "during-deletion",
        "release030-building-retired", "release030-retirement-complete")
        .flatMap(cut -> java.util.stream.Stream.of("migrating", "switching")
            .map(fixture -> org.junit.jupiter.params.provider.Arguments.of(cut, fixture)));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"migrating", "switching"})
  void defaultSchedulerRecoversAcrossStartupAndRestart(String fixture, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack(fixture, temporary.resolve(fixture));
    var original = state(root);
    Path rootOnly = Files.writeString(root.resolve("roots/alpha/root-only.txt"), "defaultschedulercobalt");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      try (var _ = new ReleasedModelIdentities(root)) {
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = createServer(root, executors)) {
            var attempts = new AtomicInteger();
            boolean leaveForRestart = restart == 0;
            server.installMigrationBarrierForTests(transition -> {
              if (transition.point().equals("release030-before-replay")
                  && (attempts.incrementAndGet() == 1 || leaveForRestart)) {
                throw new java.io.IOException("default scheduler must retry eligible recovery");
              }
            });
            server.start();
            var task = (Future<?>) Release030ExtractionRecoveryTest.field(server, "release030RecoveryTask");
            assertNotNull(task, "default recovery must actually be registered on each boot");
            assertFalse(task.isDone());
            activate(server);
            if (leaveForRestart) {
              await(() -> attempts.get() >= 2, "the registered default task must repeat", 150);
              assertFalse(task.isDone(), "a failed callback must leave periodic recovery registered");
              assertTrue(Files.exists(root.resolve("data/upgrade-030-recovery.properties")));
            } else {
              await(() -> !Files.exists(root.resolve("data/upgrade-030-recovery.properties"))
                  && search(server, "defaultschedulercobalt").contains(key(rootOnly)),
                  "default scheduler must recover the root after a real restart", 150);
              assertTrue(attempts.get() >= 2, "restart recovery must also repeat after failure");
              assertEquals(original.active_generation(), state(root).active_generation());
              assertEquals("IDLE", state(root).migration_state());
              assertNull(state(root).building_generation());
            }
          }
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test
  void cancellationDrainsOwnedCallbackAndSubmittedFailurePropagates(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("migrating", temporary.resolve("migrating"));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      try (var _ = new ReleasedModelIdentities(root);
          var executors = new TestEngineExecutors(); var server = createServer(root, executors)) {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var owner = new AtomicReference<Thread>();
        var failure = new AssertionError("owned recovery callback failure");
        server.installMigrationBarrierForTests(transition -> {
          if (transition.point().equals("release030-before-replay")) {
            owner.compareAndSet(null, Thread.currentThread());
            assertSame(owner.get(), Thread.currentThread(), "recovery stays on the owned maintenance executor");
            entered.countDown();
            release.await();
            throw failure;
          }
        });
        Future<?> barrier;
        try {
          server.start();
          activate(server);
          assertTrue(entered.await(30, TimeUnit.SECONDS), "a real default callback must be in flight");
          barrier = cancelPeriodicRecovery(server);
          assertFalse(barrier.isDone(), "cancel(false) alone cannot drain an in-flight callback");
        } finally { release.countDown(); }
        barrier.get(15, TimeUnit.SECONDS);
        assertSame(owner.get(), maintenance(server).submit(Thread::currentThread).get(15, TimeUnit.SECONDS));
        var observed = assertThrows(ExecutionException.class,
            () -> submitRecovery(server).get(15, TimeUnit.SECONDS));
        assertSame(failure, observed.getCause(), "callback errors must reach the test thread");
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  private static KnowledgeServer createServer(Path root, TestEngineExecutors executors) {
    var server = org.mockito.Mockito.spy(new KnowledgeServer(executors,
        WorkerBootFixture.workerConfig(root.resolve("data")), null));
    shortenRecoveryWaits(server);
    org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    server.bindBootRootBindings(List.of(
        new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/alpha"), null),
        new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/beta"), null)));
    server.bindExclusionRoots(() -> List.of(root.resolve("roots/alpha"), root.resolve("roots/beta")));
    return server;
  }

  private static ScheduledExecutorService maintenance(KnowledgeServer server) throws Exception {
    return (ScheduledExecutorService) Release030ExtractionRecoveryTest.field(server, "stuckJobReaper");
  }

  private static Future<?> cancelPeriodicRecovery(KnowledgeServer server) throws Exception {
    var task = (Future<?>) Release030ExtractionRecoveryTest.field(server, "release030RecoveryTask");
    if (task != null) task.cancel(false); // A completed first restart leaves no recovery task on the second.
    // The single owned executor orders this barrier after any in-flight periodic callback.
    return maintenance(server).submit(() -> null);
  }

  private static Future<?> submitRecovery(KnowledgeServer server) throws Exception {
    var callback = KnowledgeServer.class.getDeclaredMethod("recoverRelease030RootsSafely");
    callback.setAccessible(true);
    return maintenance(server).submit(() -> {
      try {
        callback.invoke(server);
      } catch (java.lang.reflect.InvocationTargetException failure) {
        if (failure.getCause() instanceof Error error) throw error;
        if (failure.getCause() instanceof Exception exception) throw exception;
        throw new AssertionError(failure.getCause());
      }
      return null;
    });
  }

  private static void awaitEligibleRecovery(KnowledgeServer server, Condition condition,
      String message, long seconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    cancelPeriodicRecovery(server).get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
    while (!condition.holds() && System.nanoTime() < deadline) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) break;
      submitRecovery(server).get(remaining, TimeUnit.NANOSECONDS);
      Thread.sleep(50);
    }
    assertTrue(condition.holds(), message);
  }

  private static void killAt(Path root, String cut, String building) throws Exception {
    Path args = root.resolve("child.args");
    Files.writeString(args, "-cp\n\"" + System.getProperty("java.class.path")
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx512m", "--enable-native-access=ALL-UNNAMED",
        "-Djustsearch.test.noVolumeIdentity=" + Boolean.getBoolean("justsearch.test.noVolumeIdentity"), "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
        "@" + args, Release030RetirementCrashTest.class.getName(), root.toString(), cut));
    var builder = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(root.resolve("child.log").toFile());
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().put("JUSTSEARCH_HOME", root.resolve("home").toString());
    builder.environment().put("JUSTSEARCH_DATA_DIR", root.resolve("data").toString());
    if (cut.equals("during-deletion")) {
      builder.environment().put("JUSTSEARCH_SUPERVISOR_HARNESS", "1");
      builder.environment().put("JUSTSEARCH_RETIREMENT_CUT_GENERATION", building);
    }
    Process child = builder.start();
    Path reached = cut.equals("during-deletion")
        ? root.resolve("data/index/default/retirement-cut-reached.txt") : root.resolve("cut");
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
      while (child.isAlive() && !Files.exists(reached) && System.nanoTime() < deadline) Thread.sleep(20);
      assertTrue(Files.exists(reached), () -> "child must reach " + cut + ": " + readLog(root));
      if (child.isAlive()) child.destroyForcibly();
      assertTrue(child.waitFor(15, TimeUnit.SECONDS));
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); child.waitFor(15, TimeUnit.SECONDS); }
    }
  }

  private static String readLog(Path root) {
    try { return Files.readString(root.resolve("child.log")); }
    catch (java.io.IOException failure) { return failure.toString(); }
  }

  public static void main(String[] args) throws Exception {
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.installVolumeLookupIfAbsent();
    Path root = Path.of(args[0]);
    String cut = args[1];
    configure(root);
    var models = new ReleasedModelIdentities(root);
    try (models; var executors = new TestEngineExecutors()) {
      var server = createServer(root, executors);
      server.installMigrationBarrierForTests(transition -> {
        if (transition.point().equals(cut)) {
          if (cut.equals("release030-replay-before-commit")) {
            io.justsearch.adapters.lucene.runtime.LuceneRuntime active;
            try {
              var field = KnowledgeServer.class.getDeclaredField("searchLifecycle");
              field.setAccessible(true);
              active = (io.justsearch.adapters.lucene.runtime.LuceneRuntime) field.get(server);
            } catch (ReflectiveOperationException failure) { throw new java.io.IOException(failure); }
            active.commitOps().maybeRefreshBlocking();
            String deleted = key(root.resolve("explicit.txt"));
            assertEquals(0, active.indexCountOps().countByIdAndChunksStrict(deleted));
            try (var directory = org.apache.lucene.store.FSDirectory.open(Release030CompatibilityTest.generation(root));
                var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
              assertEquals(1, new org.apache.lucene.search.IndexSearcher(reader).count(
                  new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("doc_id", deleted))),
                  "the crash really cuts an uncommitted A deletion");
            }
            System.out.println("UNCOMMITTED_REPLAY_DELETE memory=absent disk=present");
          }
          Files.writeString(root.resolve("cut"), Long.toString(ProcessHandle.current().pid()));
          new CountDownLatch(1).await();
        }
      });
      try (server) {
        server.start();
        assertEquals("IDLE", state(root).migration_state(), "an obsolete migration must cancel before replay");
        activate(server);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(170);
        cancelPeriodicRecovery(server).get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        while (System.nanoTime() < deadline) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) break;
          submitRecovery(server).get(remaining, TimeUnit.NANOSECONDS);
          Thread.sleep(100);
        }
        fail("retirement cut was never reached");
      }
    }
  }
}
