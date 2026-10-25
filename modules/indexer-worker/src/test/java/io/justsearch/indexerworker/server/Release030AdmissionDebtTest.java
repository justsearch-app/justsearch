/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.Release030RecoveryState;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.NoVolumeIdentityExtension;
import io.justsearch.ipc.BatchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** C3: admission identity, cutover-independent exhausted debt and corrupt checkpoint keys. */
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(
    NoVolumeIdentityExtension.class)
final class Release030AdmissionDebtTest {
  @Test
  void newExplicitAdmissionCannotBorrowLegacyAuthority(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Files.move(root.resolve("explicit.txt"), root.resolve("held-explicit.txt"));
    Path alpha = root.resolve("roots/alpha"), file = alpha.resolve("file-01.txt");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          assertTrue(search(server, "nebula").contains(key(file)));
          var response = server.appServices().ingestService().submitBatch(BatchRequest.newBuilder()
              .addFilePaths(file.toString()).setForceReindex(true).build(), CallContext.none());
          assertTrue(response.getAcceptedCount() > 0, response.getErrorMessage());
          // A valid ledger record for a different volume deterministically models replacement.
          Path ledger = root.resolve("data/index/default/root-volumes.properties");
          var volumes = properties(ledger);
          volumes.setProperty(key(alpha), "windows-volume:123abc");
          store(ledger, volumes);
          Files.writeString(file, "replacementcobaltbytes");
          assertTrue(server.appServices().ingestService().refusesRootVolume(alpha.toString(), CallContext.none()),
              "replacement-volume evidence must be refused before processing");
          // Isolate queued processing from the separate recovery walk, which can re-enqueue
          // this same path with its correct enclosing root and conceal the selector defect.
          var recovery = (Release030RecoveryState) field(server, "release030Recovery");
          for (Path pending : recovery.pendingRoots()) recovery.deferred(pending, System.currentTimeMillis());
          if (server.lifecycleManagerForTests() instanceof io.justsearch.adapters.lucene.runtime.DeferredRuntime deferred) {
            var opening = KnowledgeServer.class.getDeclaredMethod("upgradeDeferredServing",
                io.justsearch.adapters.lucene.runtime.DeferredRuntime.class);
            opening.setAccessible(true); opening.invoke(server, deferred);
          }
          recover(server);
          activate(server);
          await(() -> count(root, "path='" + key(file) + "' AND (state='DONE' OR retry_after>0)") == 1,
              "new explicit work is processed or deferred", 20);
          assertTrue(search(server, "nebula").contains(key(file)),
              "C3-1: new admissions cannot overwrite the witnessed root's old content");
          assertTrue(search(server, "replacementcobaltbytes").isEmpty());
          assertFalse(queue(server).isRelease030ExactFile(file));
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          activate(server);
          recover(server);
          assertFalse(queue(server).isRelease030ExactFile(file), "new authority cannot become legacy on restart");
          assertTrue(search(server, "nebula").contains(key(file)));
          assertTrue(search(server, "replacementcobaltbytes").isEmpty());
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test
  void reenqueueRevokesThePreviousLegacyAdmissionImmediately(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        stopRecovery(server);
        Path file = root.resolve("explicit.txt");
        var queue = queue(server);
        assertTrue(queue.isRelease030ExactFile(file), "old queued admission is protected");
        queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file, null).withinRoot(file)), null);
        assertFalse(queue.isRelease030ExactFile(file),
            "C3-1: the old exact-path cache cannot authorize a new admission revision");
        recover(server);
        assertFalse(queue.isRelease030ExactFile(file));
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {true, false})
  void deliberateModernExactAdmissionWorksWithoutBorrowingLegacyAuthority(boolean volumeAvailable,
      @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path file = Files.writeString(root.resolve("modern-explicit.txt"), "currentexplicitcobalt");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    var evidence = NoVolumeIdentityExtension.withVolumeIdentity(volumeAvailable);
    try (evidence) {
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        stopRecovery(server);
        var response = server.appServices().ingestService().submitBatch(BatchRequest.newBuilder()
            .addFilePaths(file.toString()).setRootAdded(true).build(), CallContext.none());
        assertEquals(1, response.getAcceptedCount(), response.getErrorMessage());
        assertEquals(1, count(root, "path='" + key(file) + "' AND ingestion_root=path"));
        assertFalse(queue(server).isRelease030ExactFile(file));
        activate(server);
        await(() -> search(server, "currentexplicitcobalt").contains(key(file)),
            "an intentional current explicit request retains its own exact authority", 20);
        assertEquals(0, count(root, "path='" + key(root.resolve("neighbor.txt")) + "'"));
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {true, false})
  void replacementModernDatabaseCannotBorrowV13AdmissionIdentity(boolean volumeAvailable, @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path db = root.resolve("data/jobs.db"), file = root.resolve("explicit.txt");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    var evidence = NoVolumeIdentityExtension.withVolumeIdentity(volumeAvailable);
    try (evidence) {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          assertTrue(queue(server).isRelease030ExactFile(file));
          assertTrue(search(server, "apricot").contains(key(file)));
          assertTrue(search(server, "unownedreplacementcobalt").isEmpty());
        }
        io.justsearch.indexerworker.services.ExplicitIngestRecords.recordFileFolder(
            root.resolve("data/index/default"), root);
        // A different current-schema database does not inherit the old database's cohort,
        // even if its caller did not supply a policy boundary. Retain the original DB intact.
        for (String suffix : List.of("", "-wal", "-shm")) {
          Path original = Path.of(db + suffix);
          if (Files.exists(original)) Files.move(original, root.resolve("data/old-jobs.db" + suffix));
        }
        try (var modern = new SqliteJobQueue(db)) {
          modern.open();
          assertEquals(1, modern.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file, null)), null));
          Path failed = root.resolve("modern-terminal.bin");
          assertEquals(1, modern.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(failed, null)), null));
          for (int attempt = 0; attempt < SqliteJobQueue.DEFAULT_MAX_ATTEMPTS; attempt++)
            modern.markFailed(failed, "modern terminal failure");
        }
        assertEquals(1, count(root, "state='PENDING' AND ingestion_root IS NULL AND unit_revision NOT LIKE 'release030/%'"),
            "a genuinely new modern admission precedes the stale-sidecar check");
        Files.writeString(file, "unownedreplacementcobalt");
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          assertFalse(queue(server).isRelease030ExactFile(file),
              "C3-1: re-preparing a replacement database cannot manufacture v13 provenance");
          assertEquals(0, queue(server).unownedFailureCount(), "modern failures retain their cutover budget");
          assertEquals(1, count(root, "path='" + key(file) + "' AND ingestion_root IS NULL"),
              "X4: a stale checkpoint cannot create a boundary for a non-legacy admission");
          activate(server);
          await(() -> count(root, "path='" + key(file) + "' AND retry_after>0") == 1,
              "unowned modern work waits for authority without deletion", 20);
          assertTrue(search(server, "apricot").contains(key(file)));
          assertTrue(search(server, "unownedreplacementcobalt").isEmpty());
          assertEquals(0, count(root, "state='DONE' AND path='" + key(file) + "'"));
          assertTrue(Files.exists(root.resolve("data/old-jobs.db")));
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          activate(server);
          assertFalse(queue(server).isRelease030ExactFile(file));
          assertTrue(search(server, "apricot").contains(key(file)), "unowned work stays held after restart");
          assertTrue(search(server, "unownedreplacementcobalt").isEmpty());
          assertEquals(0, count(root, "state='DONE' AND path='" + key(file) + "'"));
          var admitted = server.appServices().ingestService().submitBatch(BatchRequest.newBuilder()
              .addFilePaths(file.toString()).setRootAdded(true).setForceReindex(true).build(), CallContext.none());
          assertEquals(1, admitted.getAcceptedCount(), admitted.getErrorMessage());
          await(() -> search(server, "unownedreplacementcobalt").contains(key(file)),
              "a fresh intentional exact admission resolves the held modern debt", 20);
          assertFalse(queue(server).isRelease030ExactFile(file));
          assertEquals(0, count(root, "path='" + key(root.resolve("neighbor.txt")) + "'"));
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {true, false})
  void uncertainAdmissionRevisionCannotAuthorizeAnAbsentFileDelete(boolean volumeAvailable, @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path file = root.resolve("explicit.txt");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    var evidence = NoVolumeIdentityExtension.withVolumeIdentity(volumeAvailable);
    try (evidence) {
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        stopRecovery(server);
        var queue = queue(server);
        assertTrue(queue.isRelease030ExactFile(file));
        assertTrue(search(server, "apricot").contains(key(file)), "old explicit content exists before processing");
        Path ledger = root.resolve("data/index/default/root-volumes.properties");
        var volumes = properties(ledger);
        if (volumeAvailable) {
          volumes.setProperty("explicit-file-folder|" + key(root),
              java.util.Objects.requireNonNull(volumes.getProperty(key(root.resolve("roots/alpha")))));
          store(ledger, volumes);
        }
        assertTrue(server.appServices().ingestService().rootCheck().vouchesForFile(root),
            "the normal explicit-folder rule alone would authorize absence; admission uncertainty must stop it");
        Files.move(file, root.resolve("held-explicit.txt"));
        var field = SqliteJobQueue.class.getDeclaredField("connection");
        field.setAccessible(true);
        var original = (java.sql.Connection) field.get(queue);
        var revisionReads = new AtomicInteger();
        var proxy = java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
            new Class<?>[] {java.sql.Connection.class}, (ignored, method, arguments) -> {
              if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql
                  && sql.startsWith("SELECT 1 FROM jobs WHERE path=? AND ingestion_root=path")) {
                revisionReads.incrementAndGet();
                throw new java.sql.SQLException("Admission identity read is unavailable");
              }
              try { return method.invoke(original, arguments); }
              catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
            });
        field.set(queue, proxy);
        try {
          activate(server);
          await(() -> count(root, "path='" + key(file) + "' AND (state='DONE' OR retry_after>0)") == 1,
              "uncertain admission is attempted", 20);
          assertTrue(search(server, "apricot").contains(key(file)),
              "unknown current admission revision cannot authorize deleting old indexed content");
          assertEquals(0, count(root, "path='" + key(file) + "' AND state='DONE'"));
          assertTrue(revisionReads.get() > 0, "the queue admission identity read actually failed");
          System.out.println("Admission revision failure read attempts=" + revisionReads.get());
        } finally { field.set(queue, original); }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"migrating", "switching"})
  void neverIndexedExhaustedDebtDoesNotHoldNativeCutover(String fixture, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpackNativeMigration(fixture, temporary);
    Path missing = root.resolve("missing-exhausted.txt");
    exhausted(root, missing);
    // The native archives drained this file to DONE before capture. This scenario concerns
    // queued explicit work, so give its existing updated source a real pending admission.
    sql(root, "UPDATE jobs SET state='PENDING',attempts=0 WHERE path='" + key(root.resolve("explicit.txt")) + "'");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root, true);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        Release030ExactFileRecoveryTest.activateNative(server);
        resume(server);
        await(() -> "IDLE".equals(state(root).migration_state()),
            "C3-2: never-indexed exhausted debt cannot hold native " + fixture + " cutover", 25);
        assertTrue(Files.exists(checkpoint(root)), "the exact file's debt survives promotion");
        assertEquals(1, count(root, "path='" + key(missing)
            + "' AND state IN ('PENDING','PROCESSING','RETRY_EXHAUSTED')"));
        assertFalse(Files.exists(missing));
        await(() -> search(server, "copper").contains(key(root.resolve("explicit.txt"))),
            "queued explicit content is searchable through the published serving runtime", 10);
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void actualDeadlineFailureRestartsWithIndependentExhaustedDebt(boolean returnsWhileFailed, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpackNativeMigration("switching", temporary);
    Path missing = root.resolve("missing-exhausted.txt");
    exhausted(root, missing);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root, true);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopMonitor(server);
          Release030ExactFileRecoveryTest.activateNative(server);
          resume(server);
          await(() -> count(root, "path='" + key(missing) + "' AND retry_after>0") == 1,
              "original exhausted work is attempted and retained", 20);
          var manager = (IndexGenerationManager) field(server, "indexGenerationManager");
          var expired = new AtomicInteger();
          Thread.sleep(20);
          // The existing context accepts a duration. Actual production transitions produce
          // SWITCHING/FAILED; no state field, timestamp or on-disk migration state is edited.
          KnowledgeServerMigrationOps.runMigrationCutoverLoop(new KnowledgeServerMigrationOps.CutoverContext(
              manager, queue(server), () -> true, () -> true, () -> null, Long.MAX_VALUE, 0L, 0,
              () -> (io.justsearch.adapters.lucene.runtime.LuceneRuntime) uncheckedField(server, "ingestLifecycle"),
              () -> true, () -> { expired.incrementAndGet(); invoke(server, "drainSwitchBufferBestEffort"); },
              org.slf4j.LoggerFactory.getLogger(Release030AdmissionDebtTest.class),
              observed -> transition(server, observed), () -> { throw new AssertionError("an expired cutover cannot promote"); }));
          assertEquals(1, expired.get(), "the real deadline branch ran");
          assertEquals("FAILED", state(root).migration_state());
          if (returnsWhileFailed) {
            Files.writeString(missing, "returnedcobaltdebt");
            await(() -> count(root, "path='" + key(missing) + "' AND state='DONE'") == 1,
                "the file may finish on retained Green before the FAILED Engine stops", 90);
          }
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          Release030ExactFileRecoveryTest.activateNative(server);
          resume(server);
          await(() -> "IDLE".equals(state(root).migration_state()),
              "C3-2: a native deadline failure recovers without waiting for missing exhausted work", 25);
          if (!returnsWhileFailed) {
            assertTrue(Files.exists(checkpoint(root)), "promotion cannot discard exact-file debt");
            Files.writeString(missing, "returnedcobaltdebt");
          }
          await(() -> search(server, "returnedcobaltdebt").contains(key(missing)),
              "returning exact file is indexed after failed-cutover recovery", 90);
          await(() -> !Files.exists(checkpoint(root)), "all debt is eventually discharged", 30);
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test
  void indexedExhaustedExactContentIsNotDiscardedAtCutover(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpackNativeMigration("migrating", temporary);
    Path file = root.resolve("explicit.txt"), held = root.resolve("held-explicit.txt");
    sql(root, "UPDATE jobs SET state='RETRY_EXHAUSTED',attempts=6 WHERE path='" + key(file) + "'");
    Files.move(file, held);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root, true);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        Release030ExactFileRecoveryTest.activateNative(server);
        resume(server);
        await(() -> count(root, "path='" + key(file) + "' AND retry_after>0") == 1, "unavailable old content retries");
        long settling = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        await(() -> "IDLE".equals(state(root).migration_state()) || System.nanoTime() >= settling,
            "native cutover reaches its decision", 10);
        assertTrue(search(server, "copper").contains(key(file)), "old Blue content remains searchable");
        Files.move(held, file);
        await(() -> "IDLE".equals(state(root).migration_state()) && search(server, "copper").contains(key(file)),
            "indexed exhausted work settles without losing its old content", 90);
      }
    } finally {
      if (Files.exists(held)) Files.move(held, file);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void corruptRootKeyIsQuarantinedWhileOtherRootsRecover(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path alpha = root.resolve("roots/alpha"), beta = root.resolve("roots/beta");
    Path heldAlpha = Files.move(alpha, root.resolve("held-alpha"));
    Path heldBeta = Files.move(beta, root.resolve("held-beta"));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
        }
        var properties = properties(checkpoint(root));
        properties.setProperty("root|/bad\0root", "pending");
        store(checkpoint(root), properties);
        assertDoesNotThrow(() -> Release030RecoveryState.load(root.resolve("data/jobs.db")).pendingRoots(),
            "C3-3: one invalid decoded root key cannot poison the pending-root list");
        Files.move(heldBeta, beta);
        Path added = Files.writeString(beta.resolve("new-key.txt"), "healthykeycobalt");
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          activate(server);
          for (int tick = 0; tick < 3; tick++) recover(server);
          await(() -> search(server, "healthykeycobalt").contains(key(added)), "valid root recovers");
          assertTrue(search(server, "nebula").contains(key(alpha.resolve("file-01.txt"))));
          assertTrue(Files.exists(checkpoint(root)), "unknown root debt stays durable");
          assertTrue(Release030RecoveryState.load(root.resolve("data/jobs.db")).pendingRoots().contains(alpha));
        }
        var retained = Release030RecoveryState.load(root.resolve("data/jobs.db"));
        for (Path pending : retained.pendingRoots()) retained.completed(pending);
        retained.finish();
        assertTrue(Files.exists(checkpoint(root)), "quarantined unknown debt prevents checkpoint deletion");
        assertEquals(1, properties(checkpoint(root)).stringPropertyNames().stream()
            .filter(key -> key.startsWith("quarantined-root|")).count());
      }
    } finally {
      if (Files.exists(heldAlpha)) Files.move(heldAlpha, alpha);
      if (Files.exists(heldBeta)) Files.move(heldBeta, beta);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static void transition(KnowledgeServer server, IndexGenerationManager.State observed) throws java.io.IOException {
    try {
      var method = KnowledgeServer.class.getDeclaredMethod("enterSwitchingWithMutationAdmission", IndexGenerationManager.State.class);
      method.setAccessible(true); method.invoke(server, observed);
    } catch (ReflectiveOperationException failure) { throw new java.io.IOException(failure); }
  }
  private static void exhausted(Path root, Path missing) throws Exception {
    assertFalse(Files.exists(missing));
    sql(root, "INSERT INTO jobs(path,state,attempts,last_updated) VALUES ('" + key(missing) + "','RETRY_EXHAUSTED',6,0)");
    // Authentic v13 uses unscoped raw-path UPSERT payloads. Replay must not turn this
    // original exhausted admission into a fresh unowned request or reset its repair identity.
    sql(root, "INSERT INTO switch_buffer(key,op,payload,last_updated) VALUES ('path:"
        + key(missing) + "','UPSERT','" + key(missing) + "',0)");
  }
  private static Path checkpoint(Path root) { return root.resolve("data/upgrade-030-recovery.properties"); }
  private static Properties properties(Path path) throws Exception {
    var p = new Properties();
    try (var in = Files.newInputStream(path)) { p.load(in); }
    return p;
  }
  private static void store(Path path, Properties p) throws Exception {
    try (var out = Files.newOutputStream(path)) { p.store(out, "test evidence"); }
  }
  private static SqliteJobQueue queue(KnowledgeServer server) throws Exception { return (SqliteJobQueue) field(server, "jobQueue"); }
  private static Object field(KnowledgeServer server, String name) throws Exception {
    var f = KnowledgeServer.class.getDeclaredField(name); f.setAccessible(true); return f.get(server);
  }
  private static Object uncheckedField(KnowledgeServer server, String name) {
    try { return field(server, name); } catch (Exception e) { throw new IllegalStateException(e); }
  }
  private static void invoke(KnowledgeServer server, String name) {
    try { var m = KnowledgeServer.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(server); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  private static void recover(KnowledgeServer server) { invoke(server, "recoverRelease030RootsSafely"); }
  private static void stopRecovery(KnowledgeServer server) throws Exception {
    if (field(server, "release030RecoveryTask") instanceof java.util.concurrent.Future<?> task) task.cancel(false);
  }
  private static void stopMonitor(KnowledgeServer server) throws Exception {
    if (field(server, "migrationCutoverThread") instanceof Thread monitor) {
      monitor.interrupt(); monitor.join(10_000); assertFalse(monitor.isAlive());
    }
  }
  private static void resume(KnowledgeServer server) {
    var response = server.appServices().ingestService().resumeMigration(
        io.justsearch.ipc.MigrationResumeRequest.getDefaultInstance(), CallContext.none());
    assertTrue(response.getAccepted(), response.getError());
  }
}
