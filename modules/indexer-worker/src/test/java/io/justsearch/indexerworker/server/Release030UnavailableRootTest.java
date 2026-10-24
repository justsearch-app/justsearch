/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** U11: the volume evidence must hold before boot, sync, watcher or queued work can delete. */
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030UnavailableRootTest {
  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void deniedSingleFileKeepsItsRetryAndOldSearchUntilItCanBeRead(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path file = root.resolve("roots/alpha/file-00.txt");
    var permissions = Files.getPosixFilePermissions(file);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      Files.setPosixFilePermissions(file, java.util.Set.of());
      assertFalse(Files.isReadable(file), "precondition: this user cannot read the file");
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        assertEquals("IDLE", state(root).migration_state());
        var task = KnowledgeServer.class.getDeclaredField("release030RecoveryTask");
        task.setAccessible(true);
        ((java.util.concurrent.Future<?>) task.get(server)).cancel(false);
        activate(server);
        await(() -> count(root, "last_reason_code='INDEXED_SOURCE_UNREADABLE' AND state='PENDING'") == 1,
            "a genuine denied source uses retry-until-available");
        var retry = Release030CrashRecoveryTest.savedJobs(root).get(key(file));
        assertEquals("RETRY_UNTIL_AVAILABLE", retry.get("last_retry_policy"));
        assertEquals(1, retry.get("attempts"));
        long firstWait = ((Number) retry.get("retry_after")).longValue()
            - ((Number) retry.get("first_failed_at")).longValue();
        assertTrue(firstWait >= 60_000L && firstWait <= 61_000L,
            "the existing first retry step is one minute plus at most one second of jitter: " + firstWait);
        assertTrue(search(server, "nebula").contains(key(file)), "old indexed text remains searchable");
        var recover = KnowledgeServer.class.getDeclaredMethod("recoverRelease030RootsSafely");
        recover.setAccessible(true);
        recover.invoke(server);
        assertEquals(retry, Release030CrashRecoveryTest.savedJobs(root).get(key(file)),
            "upgrade reconciliation does not restart a genuine file's retry ladder");
        Files.setPosixFilePermissions(file, permissions);
        await(() -> search(server, "copper").contains(key(file)),
            "the original retry indexes the source once it becomes readable", 90);
      }
    } finally {
      Files.setPosixFilePermissions(file, permissions);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void deniedRootAtFirstBootKeepsItsRowsWhileOtherRootRecovers(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path beta = root.resolve("roots/beta");
    var permissions = Files.getPosixFilePermissions(beta);
    Files.writeString(root.resolve("roots/alpha/allowed.txt"), "allowedorchid");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      Files.setPosixFilePermissions(beta, java.util.Set.of());
      assertFalse(Files.isReadable(beta), "precondition: this user cannot read the denied root");
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors();
          var server = boot(root, executors)) {
        assertEquals("IDLE", state(root).migration_state());
        activate(server);
        await(() -> search(server, "allowedorchid").contains(key(root.resolve("roots/alpha/allowed.txt"))),
            "other root becomes current while access is denied");
        assertTrue(search(server, "nebula").contains(key(beta.resolve("file-01.txt"))));
        assertTrue(Files.exists(root.resolve("data/upgrade-030-recovery.properties")));
        var sync = server.appServices().ingestService().syncDirectory(SyncDirectoryRequest.newBuilder()
            .setRootPath(beta.toString()).setForce(true).build(), CallContext.none());
        assertEquals(0, sync.getFilesDeleted());
        assertFalse(sync.getError().isBlank());
        Files.setPosixFilePermissions(beta, permissions);
        await(() -> !Files.exists(root.resolve("data/upgrade-030-recovery.properties")),
            "recovery resumes when the original root becomes readable");
        assertTrue(search(server, "nebula").contains(key(beta.resolve("file-01.txt"))));
      }
    } finally {
      Files.setPosixFilePermissions(beta, permissions);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void unavailableThenReplacementRootKeepsOldSearchAndResumesOnlyOnConfirmedReturn(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    long waitingUntil = 4_102_444_800_000L;
    sql(root, "UPDATE jobs SET retry_after=" + waitingUntil
        + " WHERE last_outcome_class IN ('PARSER_TIMEOUT','IO_FAILED')");
    Path beta = root.resolve("roots/beta");
    Path held = Files.move(beta, root.resolve("held-beta"));
    Files.writeString(held.resolve("returning.txt"), "returningorchid");
    Files.writeString(root.resolve("roots/alpha/arrived.txt"), "availableorchid");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models;
        var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        activate(server);
        await(() -> search(server, "availableorchid").contains(key(root.resolve("roots/alpha/arrived.txt"))),
            "available roots become current while another drive is absent");
        String old = key(beta.resolve("file-01.txt"));
        assertTrue(search(server, "nebula").contains(old));
        assertTrue(Files.exists(root.resolve("data/upgrade-030-recovery.properties")),
            "unavailable root keeps a durable recovery checkpoint");
        var service = server.appServices().ingestService();
        var sync = service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(beta.toString())
            .setForce(true).build(), CallContext.none());
        assertFalse(sync.getError().isBlank(), "an absent root is not reconciled");
        assertEquals(0, sync.getFilesDeleted());
        service.watchRoot(WatchRootRequest.newBuilder().setRootPath(beta.toString()).build(), CallContext.none());
        assertTrue(search(server, "nebula").contains(old), "sync, watcher and queue keep the old source");
        if (io.justsearch.indexerworker.services.NoVolumeIdentityExtension.volumeIdentityAvailable(held)) {
          Files.createDirectories(beta);
          Files.writeString(beta.resolve("replacement.txt"), "replacementorchid");
          sync = service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(beta.toString())
              .setForce(true).build(), CallContext.none());
          assertFalse(sync.getError().isBlank(), "a readable replacement with no indexed evidence is refused");
          assertEquals(0, sync.getFilesDeleted());
          service.watchRoot(WatchRootRequest.newBuilder().setRootPath(beta.toString()).build(), CallContext.none());
          assertTrue(service.refusesRootVolume(beta.toString(), CallContext.none()));
          assertTrue(search(server, "replacementorchid").isEmpty());
          assertTrue(search(server, "nebula").contains(old));
          System.out.println("Readable replacement root refused on indexed-file evidence");
          Release030Fixture.deleteTree(beta);
        } else {
          System.out.println("Replacement-volume variant unverified: this filesystem exposes no stable volume identity");
        }
        Files.move(held, beta);
        // Existing root-evidence retry is the same operation the periodic root check uses.
        assertFalse(service.refusesRootVolume(beta.toString(), CallContext.none()));
        await(() -> search(server, "returningorchid").contains(key(beta.resolve("returning.txt"))),
            "confirmed returning root resumes its checkpointed walk");
        await(() -> !Files.exists(root.resolve("data/upgrade-030-recovery.properties")),
            "per-root checkpoint clears only after confirmed recovery");
        // Returning-root watcher reconciliation uses ordinary internal sync, including overflow.
        // Discovery must not turn an existing scheduled failure into an immediate retry.
        for (boolean force : java.util.List.of(false, true)) {
          var reconciled = service.syncDirectory(SyncDirectoryRequest.newBuilder()
              .setRootPath(beta.toString()).setForce(force).build(), CallContext.none());
          assertTrue(reconciled.getError().isBlank());
          assertEquals(2, count(root, "retry_after=" + waitingUntil
              + " AND last_outcome_class IN ('PARSER_TIMEOUT','IO_FAILED')"),
              "U18: returning-root discovery preserves both original retry dates");
        }
        assertTrue(search(server, "nebula").contains(old));
        assertEquals("IDLE", state(root).migration_state());
        System.out.println("Unavailable-root recovery: DONE=" + count(root, "state='DONE'")
            + " waiting=" + count(root, "state='PENDING'"));
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }
}
