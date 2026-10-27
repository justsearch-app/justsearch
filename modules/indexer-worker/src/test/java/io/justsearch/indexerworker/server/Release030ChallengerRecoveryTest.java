/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** C1's five claims, exercised against real boot, queue claims, disk evidence and reconciliation. */
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030ChallengerRecoveryTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void replacementExplicitFolderCannotAuthorizeDeletion(boolean noVolumeIdentity, @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    // A readable replacement parent with none of the indexed sources is not evidence of absence.
    for (String name : List.of("roots", "bundled-help", "explicit.txt", "orphan")) {
      Path source = root.resolve(name);
      if (Files.exists(source)) Files.move(source, root.resolve("held-" + name));
    }
    var unavailableField = Class.forName("io.justsearch.indexerworker.services.RootIdentity")
        .getDeclaredField("VOLUME_IDENTITY_UNAVAILABLE");
    unavailableField.setAccessible(true);
    var unavailable = (java.util.concurrent.atomic.AtomicBoolean) unavailableField.get(null);
    boolean previousVolumeMode = unavailable.getAndSet(noVolumeIdentity);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        Path explicit = root.resolve("explicit.txt");
        assertTrue(search(server, "apricot").contains(key(explicit)), "indexed explicit-file precondition");
        activate(server);
        await(() -> count(root, "path='" + key(explicit)
            + "' AND state='PENDING' AND retry_after>0") == 1,
            "unavailable exact files wait on the retry ladder without deletion", 20);
        assertTrue(Files.exists(checkpoint(root)), "unproven exact-file admission retains recovery debt");
        assertTrue(search(server, "apricot").contains(key(explicit)), "uncertainty never deletes old content");
        assertEquals(0, count(root, "path='" + key(explicit) + "' AND state='DONE'"));
      }
    } finally {
      unavailable.set(previousVolumeMode);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void processingExplicitClaimRetainsDebtUntilItsDriveReturns(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path folder = root.resolve("orphan");
    Path held = root.resolve("held-orphan");
    Path file = folder.resolve("exhausted.txt");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          sql(root, "UPDATE jobs SET retry_after=4102444800000 WHERE state='PENDING' "
              + "AND retry_after IS NULL AND path<>'" + key(file) + "'");
          var queue = queue(server);
          var claims = queue.pollPending(1);
          assertEquals(1, claims.size());
          assertEquals(key(file), key(claims.getFirst().path()));
          assertEquals(1, count(root, "path='" + key(file) + "' AND state='PROCESSING'"));
          // The pause isolated this claim only; other exact paths must also resolve before
          // the shared checkpoint can clear. Original parser/I/O waits are left untouched.
          sql(root, "UPDATE jobs SET retry_after=NULL WHERE retry_after=4102444800000");
          Files.move(folder, held);
          assertFalse(Files.exists(file), "the issued exact file is now unavailable");
          activate(server);
          recover(server);
          assertTrue(Files.exists(checkpoint(root)), "in-flight admission cannot clear the checkpoint");
          queue.returnUnfinishedClaims(claims);
        }
        Files.move(held, folder);
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          activate(server);
          await(() -> search(server, "copper").contains(key(file)),
              "the original queued explicit file resumes without another explicit add", 90);
          await(() -> !Files.exists(checkpoint(root)), "explicit admission eventually clears its debt");
        }
      }
    } finally {
      if (Files.exists(held)) Files.move(held, folder);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void backupRestoreInvalidatesPreparedCheckpoint(boolean corrupt, @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path beta = root.resolve("roots/beta");
    Path held = Files.move(beta, root.resolve("held-beta"));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          assertNotNull(server.lifecycleManagerForTests());
          assertTrue(Files.readString(checkpoint(root)).contains("prepared=true"));
          assertEquals(0, count(root, "state IN ('PENDING','PROCESSING','RETRY_EXHAUSTED') AND ingestion_root IS NULL"));
        }
        if (corrupt) {
          // Preserve the SQLite header/user_version; corrupt the schema b-tree to exercise
          // production SQLITE_CORRUPT triage rather than the distinct NOTADB refusal.
          try (var database = new java.io.RandomAccessFile(root.resolve("data/jobs.db").toFile(), "rw")) {
            database.seek(100);
            database.write(0);
          }
        }
        else Files.copy(root.resolve("data/jobs.db.bak"), root.resolve("data/jobs.db"), StandardCopyOption.REPLACE_EXISTING);
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          assertEquals(0, count(root, "state IN ('PENDING','PROCESSING','RETRY_EXHAUSTED') AND ingestion_root IS NULL"),
              "F3: a restored database must repeat boundary preparation");
          assertEquals(0, count(root, "state='RETRY_EXHAUSTED' OR state='PENDING' AND last_outcome_class='WRITE_FAILED'"));
          Files.move(held, beta);
          assertFalse(server.appServices().ingestService().refusesRootVolume(beta.toString(),
              io.justsearch.indexerworker.services.CallContext.none()));
          activate(server);
          await(() -> search(server, "copper").contains(key(root.resolve("explicit.txt")))
              && search(server, "copper").contains(key(root.resolve("orphan/exhausted.txt"))),
              "restored unread work is indexed");
          assertEquals(0, count(root, "last_reason_code='MISSING_INGESTION_BOUNDARY'"));
        }
      }
    } finally {
      if (Files.exists(held)) Files.move(held, beta);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"migrating", "switching"})
  void interruptedUpgradePreparesBeforeClaimsAndCompletesNativeCutover(String fixture, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpackNativeMigration(fixture, temporary);
    // Add C1's stated input: an independently queued explicit path in the v13 database.
    Path pending = Files.writeString(root.resolve("native-pending.txt"), "nativequeuedcopper");
    sql(root, "INSERT INTO jobs(path,state,attempts,last_updated) VALUES ('" + key(pending)
        + "','PENDING',0,0)");
    assertEquals(1, count(root, "path='" + key(pending) + "' AND state='PENDING'"));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root, true);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        Release030ExactFileRecoveryTest.activateNative(server);
        var resumed = server.appServices().ingestService().resumeMigration(
            io.justsearch.ipc.MigrationResumeRequest.getDefaultInstance(),
            io.justsearch.indexerworker.services.CallContext.none());
        assertTrue(resumed.getAccepted(), resumed.getError());
        await(() -> "IDLE".equals(state(root).migration_state())
            && search(server, "nativequeuedcopper").contains(key(pending)),
            "the native interrupted migration actually completes", 30);
        assertEquals(0, count(root, "last_reason_code='MISSING_INGESTION_BOUNDARY'"));
        assertEquals(0, count(root, "state IN ('PENDING','PROCESSING','RETRY_EXHAUSTED') AND ingestion_root IS NULL"));
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void excludedUnreadableFileDoesNotKeepRecoveryDebt(boolean indexed, @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path file = indexed ? root.resolve("roots/alpha/file-01.txt")
        : Files.writeString(root.resolve("roots/alpha/excluded.txt"), "excluded");
    var permissions = Files.getPosixFilePermissions(file);
    Files.setPosixFilePermissions(file, java.util.Set.of());
    assertFalse(Files.isReadable(file));
    ConfigStore previous = ConfigStore.globalOrNull();
    WorkerBootFixture.publishConfig(root.resolve("data"), root.resolve("data/index/default"), "BLUE_GREEN_MIGRATE",
        Map.of("justsearch.ai.disabled", "true", "justsearch.ai.embed.enabled", "false",
            "justsearch.splade.enabled", "false", "justsearch.ner.enabled", "false",
            "justsearch.ui.exclude_patterns", "[\"" + file.getFileName() + "\"]"));
    var installed = ConfigStore.global();
    try {
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        stopRecovery(server);
        activate(server);
        var result = server.appServices().ingestService().recoverRelease030Root(root.resolve("roots/alpha"), null, () -> false);
        assertFalse(result.getDeleteDetectionUnverified(), "F5: excluded unreadable entries are outside recovery debt");
        await(() -> { recover(server); return !Files.exists(checkpoint(root)); },
            "eligible exact work resolves and root recovery completes without the excluded source");
      }
    } finally {
      Files.setPosixFilePermissions(file, permissions);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void includedUnreadableFileKeepsDebtWithoutRepeatedWalksAcrossRestart(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path file = root.resolve("roots/alpha/file-01.txt");
    var permissions = Files.getPosixFilePermissions(file);
    Files.setPosixFilePermissions(file, java.util.Set.of());
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (int boot = 0; boot < 2; boot++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            stopRecovery(server);
            activate(server);
            Object service = server.appServices().ingestService();
            var field = service.getClass().getDeclaredField("syncOps");
            field.setAccessible(true);
            Object walks = org.mockito.Mockito.spy(field.get(service));
            field.set(service, walks);
            for (int tick = 0; tick < 4; tick++) recover(server);
            long calls = org.mockito.Mockito.mockingDetails(walks).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("execute") && call.getArguments().length == 8
                    && call.getArgument(0).equals(root.resolve("roots/alpha").toString())).count();
            assertEquals(boot == 0 ? 1 : 0, calls,
                "F5: a durable cooldown prevents a full rewalk on each recovery tick and restart");
            assertTrue(Files.exists(checkpoint(root)), "included unreadable source keeps recovery debt");
            assertTrue(search(server, "nebula").contains(key(file)), "old indexed content is not deleted");
            if (boot == 1) {
              Files.setPosixFilePermissions(file, permissions);
              await(() -> { recover(server); return !Files.exists(checkpoint(root)); },
                  "confirmed readable source finishes after the recovery cooldown", 90);
            }
          }
        }
      }
    } finally {
      Files.setPosixFilePermissions(file, permissions);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  private static SqliteJobQueue queue(KnowledgeServer server) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField("jobQueue");
    field.setAccessible(true);
    return (SqliteJobQueue) field.get(server);
  }

  private static Path checkpoint(Path root) { return root.resolve("data/upgrade-030-recovery.properties"); }

  private static void stopRecovery(KnowledgeServer server) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField("release030RecoveryTask");
    field.setAccessible(true);
    if (field.get(server) instanceof java.util.concurrent.Future<?> task) task.cancel(false);
  }

  private static void recover(KnowledgeServer server) throws Exception {
    var method = KnowledgeServer.class.getDeclaredMethod("recoverRelease030RootsSafely");
    method.setAccessible(true);
    method.invoke(server);
  }
}
