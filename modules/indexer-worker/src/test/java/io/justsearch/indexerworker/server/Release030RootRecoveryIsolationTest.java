/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.RootWatcherRegistry;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.UnwatchRootRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** C5: each registered root owns its absence evidence and recovery admissions. */
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RootRecoveryIsolationTest {
  @ParameterizedTest
  @CsvSource({"true,false", "false,false", "true,true", "false,true"})
  void parentPruneNeverBorrowsItsVolumeForNestedRoot(boolean recovery, boolean replacement, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path parent = root.resolve("roots"), child = parent.resolve("alpha");
    Path protectedFile = child.resolve("file-01.txt"), deleted = parent.resolve("beta/file-01.txt");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
        }
        Path ledger = root.resolve("data/index/default/root-volumes.properties");
        var volumes = new Properties();
        try (var in = Files.newInputStream(ledger)) { volumes.load(in); }
        String parentVolume = volumes.getProperty(key(parent.resolve("beta")));
        if (io.justsearch.indexerworker.services.NoVolumeIdentityExtension.volumeIdentityAvailable(parent)) {
          assertNotNull(parentVolume, "fixture boot records the injected available volume");
          volumes.setProperty(key(parent), parentVolume);
        } else {
          assertNull(parentVolume, "an unavailable UUID must not manufacture a parent volume");
        }
        volumes.setProperty(key(child), "windows-volume:123abc");
        try (var out = Files.newOutputStream(ledger)) { volumes.store(out, "nested volume unavailable"); }
        Files.move(child, root.resolve("held-alpha"));
        Files.createDirectory(child); // The empty mount folder survives unplugging the nested volume.
        if (replacement) Files.writeString(protectedFile, "nestedreplacementcobalt");
        Files.delete(deleted);
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors();
              var server = nestedBoot(root, parent, child, executors)) {
            stopRecovery(server);
            openWriter(server);
            var ingest = server.appServices().ingestService();
            assertFalse(ingest.watchRoot(WatchRootRequest.newBuilder().setRootPath(child.toString()).build(),
                CallContext.none()).getWatching(), "nested root waits on its own volume");
            assertTrue(search(server, "nebula").contains(key(protectedFile)), "old child content opens");
            var childAdmission = Release030CrashRecoveryTest.savedJobs(root).get(key(protectedFile));
            assertNotNull(childAdmission);
            var result = recovery
                ? ingest.recoverRelease030Root(parent, null, () -> false)
                : ingest.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(parent.toString())
                    .setForce(true).build(), CallContext.none());
            assertTrue(result.getError().isBlank(), result.getError());
            assertTrue(search(server, "nebula").contains(key(protectedFile)),
                "C5-1: parent absence evidence must never prune another registered root");
            assertEquals(childAdmission, Release030CrashRecoveryTest.savedJobs(root).get(key(protectedFile)),
                "C5-1: a parent walk must not readmit a nested source under the parent volume");
            server.releaseModelReadyLatchForTests();
            await(() -> count(root, "ingestion_root='" + key(child) + "' AND retry_after>0") > 0,
                "queued child work defers to the child volume");
            assertTrue(search(server, "nebula").contains(key(protectedFile)));
            assertTrue(search(server, "nestedreplacementcobalt").isEmpty());
            assertFalse(search(server, "nebula").contains(key(deleted)),
                "the available parent still prunes its own conclusive deletion");
            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
              deniedNestedRootDoesNotBlockParent(ingest, parent, child, recovery);
              assertTrue(search(server, "nebula").contains(key(protectedFile)));
            }
          }
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test
  void rootRemovedBeforeSpoolAdmissionCannotRecreateJobs(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary), alpha = root.resolve("roots/alpha");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    var membership = new AtomicReference<>(List.of(alpha, root.resolve("roots/beta")));
    try {
      var models = new ReleasedModelIdentities(root);
      try (models;
          var executors = new TestEngineExecutors();
          var server = declaredBoot(root, membership.get(), membership::get, executors)) {
        stopRecovery(server);
        openWriter(server);
        var ingest = server.appServices().ingestService();
        ingest.setRootWatcherRegistry(new RootWatcherRegistry());
        assertTrue(ingest.watchRoot(WatchRootRequest.newBuilder().setRootPath(alpha.toString()).build(),
            CallContext.none()).getWatching());
        Path added = Files.writeString(alpha.resolve("not-admitted-before-removal.txt"), "removedrootcobalt");
        var reached = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var once = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
          try {
            ingest.recoverRelease030Root(alpha, null, () -> {
              boolean draining = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                  .anyMatch(frame -> frame.getClassName().endsWith("ReconciliationSpool")
                      && frame.getMethodName().equals("drain"));
              if (draining && once.compareAndSet(false, true)) {
                reached.countDown();
                try { assertTrue(resume.await(20, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return true; }
              }
              return false;
            });
          } catch (Exception | Error problem) { failure.set(problem); }
        }, "release030-removal-race");
        worker.start();
        try {
          assertTrue(reached.await(20, TimeUnit.SECONDS), "recovery has walked but has not admitted its spool");
          membership.set(List.of(root.resolve("roots/beta")));
          assertTrue(ingest.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(alpha.toString()).build(),
              CallContext.none()).getUnwatched());
          assertTrue(ingest.deleteByPath(DeleteByPathRequest.newBuilder().setPath(alpha.toString()).build(),
              CallContext.none()).getError().isBlank());
          assertEquals(0, count(root, "path LIKE '" + key(alpha) + "/%'"));
        } finally { resume.countDown(); worker.join(20_000); }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(0, count(root, "path LIKE '" + key(alpha) + "/%'"),
            "C5-2: removing a root before admission must not recreate any of its jobs");
        assertEquals(0, count(root, "path='" + key(added) + "'"));
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test
  void recoveredDoneFileGetsANewFailureWindowAndRetriesItsUpdate(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary), alpha = root.resolve("roots/alpha");
    Path file = alpha.resolve("file-01.txt");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          openWriter(server);
          long oldFailure = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8);
          sql(root, "UPDATE jobs SET state='DONE',attempts=5,first_failed_at=" + oldFailure
              + ",retry_after=NULL WHERE path='" + key(file) + "'");
          Files.writeString(file, "freshfailurewindowcobalt");
          var result = server.appServices().ingestService().recoverRelease030Root(alpha, null, () -> false);
          assertTrue(result.getError().isBlank(), result.getError());
          assertEquals(1, count(root, "path='" + key(file)
              + "' AND state='PENDING' AND attempts=0 AND first_failed_at IS NULL"),
              "C5-3: a fresh recovery admission must discard the completed admission's failure history");
          var queue = (SqliteJobQueue) field(server, "jobQueue");
          queue.markFailed(file, IngestionOutcome.of(IngestionOutcomeClass.PARSER_TIMEOUT,
              "EXTRACTION_TIMEOUT", IngestionRetryPolicy.RETRY_WITH_BACKOFF));
          assertEquals(1, count(root, "path='" + key(file)
              + "' AND state='PENDING' AND attempts=1 AND first_failed_at>" + oldFailure));
          server.releaseModelReadyLatchForTests();
          await(() -> search(server, "freshfailurewindowcobalt").contains(key(file))
              && count(root, "path='" + key(file) + "' AND state='DONE'") == 1,
              "a genuine next retry indexes the changed source", 90);
          assertEquals(1, count(root, "path='" + key(file) + "' AND state='DONE'"));
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          assertTrue(search(server, "freshfailurewindowcobalt").contains(key(file)));
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  private static void deniedNestedRootDoesNotBlockParent(
      io.justsearch.indexerworker.services.WorkerIngestService ingest,
      Path parent, Path child, boolean recovery) throws Exception {
    var permissions = Files.getPosixFilePermissions(child);
    try {
      Files.setPosixFilePermissions(child, java.util.Set.of());
      assertFalse(Files.isReadable(child), "precondition: the nested mount folder is denied");
      var result = recovery ? ingest.recoverRelease030Root(parent, null, () -> false)
          : ingest.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(parent.toString())
              .setForce(true).build(), CallContext.none());
      assertFalse(result.getDeleteDetectionUnverified(),
          "C5-1: a denied nested root belongs to its own recovery, not the parent's debt");
      assertTrue(result.getError().isBlank(), result.getError());
    } finally { Files.setPosixFilePermissions(child, permissions); }
  }

  private static KnowledgeServer nestedBoot(Path root, Path parent, Path child,
      TestEngineExecutors executors) throws Exception {
    return declaredBoot(root, List.of(parent, child), () -> List.of(parent, child), executors);
  }
  private static KnowledgeServer declaredBoot(Path root, List<Path> roots,
      java.util.function.Supplier<java.util.Collection<Path>> membership, TestEngineExecutors executors)
      throws Exception {
    var server = org.mockito.Mockito.spy(new KnowledgeServer(executors,
        WorkerBootFixture.workerConfig(root.resolve("data")), null));
    org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    server.bindBootRootBindings(roots.stream().map(path -> new RootBinding(path, null)).toList());
    server.bindExclusionRoots(membership);
    try { server.start(); } catch (Exception | Error failure) { server.close(); throw failure; }
    return server;
  }
  private static void openWriter(KnowledgeServer server) throws Exception {
    if (server.lifecycleManagerForTests() instanceof io.justsearch.adapters.lucene.runtime.DeferredRuntime runtime) {
      var upgrade = KnowledgeServer.class.getDeclaredMethod("upgradeDeferredServing", runtime.getClass());
      upgrade.setAccessible(true);
      upgrade.invoke(server, runtime);
    }
  }
  private static void stopRecovery(KnowledgeServer server) throws Exception {
    if (field(server, "release030RecoveryTask") instanceof java.util.concurrent.Future<?> task) task.cancel(false);
  }
  private static Object field(KnowledgeServer server, String name) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(server);
  }
}
