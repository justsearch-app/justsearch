/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.engine.RuntimeReloadProducersHttpIntegrationTest.Fixture;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.IndexingController;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real Engine, search, queue and physical Linux watchers on disposable udisks loop filesystems. */
final class LinuxWatchedDriveReturnIntegrationTest {
  @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;
  private static final EngineContext CONTEXT = EngineProvenance.internal(
      "watched-drive-return", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);

  @org.junit.jupiter.params.ParameterizedTest(name = "realUnmountReturnsWithHealthyWatcherReceiptsAndOfflineChanges({0})")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Timeout(300)
  void realUnmountReturnsWithHealthyWatcherReceiptsAndOfflineChanges(boolean changedDevice)
      throws Exception {
    try (var volume = LinuxLoopVolume.create(directory.resolve("loop")); var f = fixture()) {
      Path mount = volume.mounted();
      Path root = Files.createDirectory(mount.resolve("documents"));
      Path gone = Files.writeString(root.resolve("gone.txt"), "mountgone old document");
      Path exact = Files.writeString(root.resolve("exact.txt"), "mountexact old document");
      Path recreated = Files.writeString(root.resolve("recreated.txt"), "mountrecreated original");
      Path modified = Files.writeString(root.resolve("modified.txt"), "mountoldcontent original");
      Files.writeString(root.resolve("kept.txt"), "mountkept unaffected");
      f.client.addWatchedRoot("default", root, CONTEXT);
      for (String marker : List.of("mountgone", "mountexact", "mountrecreated", "mountoldcontent", "mountkept")) {
        f.awaitCount(marker, 1);
      }
      for (int i = 0; i < 9; i++) {
        Path other = Files.createDirectories(directory.resolve("other-" + i));
        f.client.addWatchedRoot("default", other, CONTEXT);
      }
      assertEquals(10, f.client.getWatchedRoots(CONTEXT).size());
      try (var slowScheduler = new HeldPeriodicSync(f)) {
        Object registry = inherited(f.server.appServices(), "rootWatcherRegistry");
        Object subscription = invoke(registry, "subscription", new Class<?>[] {Path.class}, root);
        String identity = invoke(subscription, "rootIdentity", new Class<?>[0]).toString();
        var debt = new io.justsearch.indexerworker.queue.WatcherRootReconciliation(root.toString(), identity, "default");
        assertTrue(f.queue().putSwitchBuffer(debt.key(), io.justsearch.indexerworker.queue.WatcherRootReconciliation.OPERATION, debt.encode()));
        for (Path file : List.of(exact, recreated)) {
          var deletion = new io.justsearch.indexerworker.queue.SwitchBufferDelete(file.toString(),
              io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.WATCHER, root.toString(), identity, "default");
          assertTrue(f.queue().putSwitchBuffer("path:" + file, "DELETE", deletion.encode()));
        }
        long device = ((Number) Files.getAttribute(root, "unix:dev")).longValue();
        volume.away();
        awaitPhysicalWatcher(f, root, false, 10);
        assertEquals(1, f.count("mountgone"));
        assertEquals(1, f.count("mountkept"));
        var unavailable = f.client.syncDirectory(root.toString(), false, CONTEXT);
        assertFalse(unavailable.getError().isBlank(), unavailable.getError());
        assertEquals(0, unavailable.getFilesDeleted());
        assertEquals(3, f.queue().listSwitchBufferOpsStrict().size(), "both receipt kinds wait while absent");
        volume.label("JS_RETURN_AWAY");
        Path elsewhere = volume.attachAndMount(false).resolve("documents");
        assertNotEquals(root, elsewhere, "offline changes happen at another mount path");
        Files.delete(elsewhere.resolve(gone.getFileName()));
        Files.delete(elsewhere.resolve(exact.getFileName()));
        Files.delete(elsewhere.resolve(recreated.getFileName()));
        Files.writeString(elsewhere.resolve(recreated.getFileName()), "mountrecreated new document");
        Files.writeString(elsewhere.resolve(modified.getFileName()), "mountnewcontent changed size and timestamp");
        Files.writeString(elsewhere.resolve("added.txt"), "mountadded offline addition");
        if (changedDevice) Files.setPosixFilePermissions(elsewhere, java.util.Set.of());
        Thread.sleep(6_000);
        awaitPhysicalWatcher(f, root, false, 1);
        assertEquals(1, f.count("mountgone"), "a return elsewhere never moves the declared root");
        assertEquals(0, f.count("mountadded"), "another mount path contributes no files");
        assertEquals(3, f.queue().listSwitchBufferOpsStrict().size());
        volume.away();
        volume.label("");
        if (changedDevice) volume.holdDecoy();
        long returnedAt = System.nanoTime();
        assertEquals(mount, volume.attachAndMount(false));
        long returnedDevice = ((Number) Files.getAttribute(root, "unix:dev")).longValue();
        if (changedDevice) assertNotEquals(device, returnedDevice, "the decoy forces a changed device");
        else assertEquals(device, returnedDevice, "same-device campaign");
        if (changedDevice) {
          Thread.sleep(6_000);
          awaitPhysicalWatcher(f, root, false, 1);
          assertEquals(1, f.count("mountgone"), "an unreadable return never prunes");
          assertEquals(3, f.queue().listSwitchBufferOpsStrict().size());
          Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        awaitPhysicalWatcher(f, root, true, 60);
        long registeredAt = System.nanoTime();
        assertTrue(registeredAt - returnedAt < TimeUnit.SECONDS.toNanos(60));
        for (String marker : List.of("mountgone", "mountexact", "mountoldcontent")) f.awaitCount(marker, 0);
        for (String marker : List.of("mountadded", "mountnewcontent", "mountrecreated", "mountkept")) f.awaitCount(marker, 1);
        assertTrue(System.nanoTime() - registeredAt < TimeUnit.SECONDS.toNanos(60));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!f.queue().listSwitchBufferOpsStrict().isEmpty() && System.nanoTime() < until) Thread.sleep(50);
        assertTrue(f.queue().listSwitchBufferOpsStrict().isEmpty(), "both receipt kinds are settled");
        Path live = Files.writeString(root.resolve("live.txt"), "mountlivecreated event");
        f.awaitCount("mountlivecreated", 1);
        Files.writeString(live, "mountlivemodified event with different bytes");
        f.awaitCount("mountlivemodified", 1);
        f.awaitCount("mountlivecreated", 0);
        Files.delete(live);
        f.awaitCount("mountlivemodified", 0);
        assertTrue(System.nanoTime() - registeredAt < TimeUnit.SECONDS.toNanos(60),
            "live create/modify/delete all arrive faster than one periodic scan");
        volume.releaseDecoy();
        awaitRecoverySettled(f, root);
        awaitIndexingIdle(f);
        long descriptors = descriptorCount();
        var initialTargets = descriptorTargets();
        long watcherThreads = watcherThreadCount();
        assertTrue(watcherThreads > 0);
        for (int cycle = 1; cycle < 5; cycle++) {
          if (cycle == 4) {
            Files.writeString(root.resolve("readonly-gone.txt"), "mountreadonlygone original");
            f.awaitCount("mountreadonlygone", 1);
          }
          awaitIndexingIdle(f);
          volume.away();
          awaitPhysicalWatcher(f, root, false, 10);
          if (cycle == 4) {
            volume.label("JS_READONLY_AWAY");
            Files.delete(volume.attachAndMount(false).resolve("documents/readonly-gone.txt"));
            volume.away();
            volume.label("");
          }
          if ((cycle & 1) == 1) volume.holdDecoy();
          volume.attachAndMount(cycle == 4); // a readable read-only return is fully recoverable
          awaitPhysicalWatcher(f, root, true, 60);
          if (cycle == 4) f.awaitCount("mountreadonlygone", 0);
          assertEquals(1, f.count("mountkept"));
          Object watcher = inherited(f.server.appServices(), "workerWatcher");
          assertEquals(10, ((java.util.Map<?, ?>) inherited(watcher, "watchers")).size());
          awaitRecoverySettled(f, root);
          awaitIndexingIdle(f);
          volume.releaseDecoy();
          long returnedDescriptors = descriptorCount();
          var addedTargets = descriptorTargets().entrySet().stream()
              .filter(entry -> entry.getValue() > initialTargets.getOrDefault(entry.getKey(), 0L))
              .toList();
          assertTrue(returnedDescriptors <= descriptors,
              "repeated returns must not retain handles: cycle=" + cycle + " before=" + descriptors
                  + " after=" + returnedDescriptors + " added=" + addedTargets);
          assertTrue(watcherThreadCount() <= watcherThreads, "repeated returns must not retain dead watcher threads");
        }
        assertFalse(slowScheduler.task.isDone(), "recovery and live events did not use periodic sync");
      }
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Timeout(60)
  void unmountedRootIsUnavailableInTheStatusApiUsedByTheUi() throws Exception {
    try (var volume = LinuxLoopVolume.create(directory.resolve("loop")); var f = fixture()) {
      Path root = Files.createDirectory(volume.mounted().resolve("documents"));
      Files.writeString(root.resolve("kept.txt"), "statusmountkept original");
      f.client.addWatchedRoot("default", root, CONTEXT);
      f.awaitCount("statusmountkept", 1);
      volume.away();
      var unavailable = f.client.syncDirectory(root.toString(), false, CONTEXT);
      var folders = f.transport.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + f.http.port() + "/test/roots")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, folders.statusCode(), folders.body());
      assertTrue(folders.body().contains("\"status\":\"unavailable\""), folders.body());
      assertTrue(unavailable.getError().startsWith("ROOT_NOT_DIRECTORY:"), unavailable.getError());
      assertTrue(f.client.getWatchedRoots(CONTEXT).getFirst().walkError().startsWith("ROOT_NOT_DIRECTORY"));
      assertEquals(1, f.count("statusmountkept"));
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Timeout(120)
  void anotherFilesystemCannotRecoverAndRemovalCancelsTheOldRootBeforeReAddition()
      throws Exception {
    try (var volume = LinuxLoopVolume.create(directory.resolve("original-loop"));
         var foreign = LinuxLoopVolume.create(directory.resolve("foreign-loop"));
         var f = fixture()) {
      volume.away();
      volume.label("JS_" + java.util.UUID.randomUUID().toString().substring(0, 12));
      Path mount = volume.attachAndMount(false);
      Path root = Files.createDirectory(mount.resolve("documents"));
      Path gone = Files.writeString(root.resolve("gone.txt"), "removedmountgone original");
      Path kept = Files.writeString(root.resolve("kept.txt"), "removedmountkept original");
      Files.createDirectory(foreign.mounted().resolve("documents"));
      Files.writeString(foreign.mounted().resolve("documents/wrong.txt"), "foreignmountfile rejected");
      f.client.addWatchedRoot("default", root, CONTEXT);
      f.awaitCount("removedmountgone", 1);
      f.awaitCount("removedmountkept", 1);
      addReturnReceipts(f, root, gone, kept);
      volume.away();
      awaitPhysicalWatcher(f, root, false, 10);
      foreign.away();
      foreign.label(mount.getFileName().toString());
      assertEquals(mount, foreign.attachAndMount(false));
      Thread.sleep(6_000);
      assertFalse(f.client.syncDirectory(root.toString(), false, CONTEXT).getError().isBlank());
      assertEquals(1, f.count("removedmountgone"));
      assertEquals(1, f.count("removedmountkept"));
      assertEquals(0, f.count("foreignmountfile"));
      assertEquals(2, f.queue().listSwitchBufferOpsStrict().size());
      foreign.away();
      assertTrue(f.client.removeWatchedPath(root, CONTEXT) >= 0);
      assertTrue(f.queue().listSwitchBufferOpsStrict().isEmpty(), "removal cancels both receipt kinds");
      volume.attachAndMount(false);
      Thread.sleep(6_000);
      assertTrue(f.client.getWatchedRoots(CONTEXT).isEmpty(), "return cannot resurrect a removed declaration");
      Object registry = inherited(f.server.appServices(), "rootWatcherRegistry");
      assertNull(invoke(registry, "subscription", new Class<?>[] {Path.class}, root));
      Files.writeString(root.resolve("later.txt"), "removedmountlater deliberate readdition");
      f.client.addWatchedRoot("default", root, CONTEXT);
      awaitPhysicalWatcher(f, root, true, 10);
      f.awaitCount("removedmountlater", 1);
      assertEquals(1, ((java.util.Map<?, ?>) inherited(inherited(f.server.appServices(), "workerWatcher"), "watchers")).size());
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Timeout(180)
  void realReturnAfterRestartWhileAbsentReplaysBothReceiptKinds() throws Exception {
    try (var volume = LinuxLoopVolume.create(directory.resolve("loop"))) {
      Path mount = volume.mounted();
      Path root = Files.createDirectory(mount.resolve("documents"));
      Path gone = Files.writeString(root.resolve("gone.txt"), "restartmountgone original");
      Path recreated = Files.writeString(root.resolve("recreated.txt"), "restartmountkept original");
      try (var f = fixture()) {
        f.client.addWatchedRoot("default", root, CONTEXT);
        f.awaitCount("restartmountgone", 1);
        f.awaitCount("restartmountkept", 1);
        addReturnReceipts(f, root, gone, recreated);
        volume.away();
        awaitPhysicalWatcher(f, root, false, 10);
      }
      volume.label("JS_RESTART_AWAY");
      Path elsewhere = volume.attachAndMount(false).resolve("documents");
      Files.delete(elsewhere.resolve("gone.txt"));
      Files.delete(elsewhere.resolve("recreated.txt"));
      Files.writeString(elsewhere.resolve("recreated.txt"), "restartmountkept recreated");
      volume.away();
      volume.label("");
      volume.holdDecoy();
      try (var restarted = fixture()) {
        assertEquals(1, restarted.count("restartmountgone"));
        assertEquals(1, restarted.count("restartmountkept"));
        assertEquals(2, restarted.queue().listSwitchBufferOpsStrict().size());
        assertEquals(mount, volume.attachAndMount(false));
        awaitPhysicalWatcher(restarted, root, true, 60);
        restarted.awaitCount("restartmountgone", 0);
        restarted.awaitCount("restartmountkept", 1);
        restarted.awaitReceipts(0, "root reconciliation receipt settles after restart and return");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!restarted.queue().listSwitchBufferOpsStrict().isEmpty() && System.nanoTime() < until) Thread.sleep(50);
        assertTrue(restarted.queue().listSwitchBufferOpsStrict().isEmpty());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Timeout(180)
  void realReturnDuringFailedReloadWaitsForTheSurvivingWriter() throws Exception {
    try (var volume = LinuxLoopVolume.create(directory.resolve("loop")); var f = fixture()) {
      Path mount = volume.mounted();
      Path root = Files.createDirectory(mount.resolve("documents"));
      Path gone = Files.writeString(root.resolve("gone.txt"), "reloadmountgone original");
      Path recreated = Files.writeString(root.resolve("kept.txt"), "reloadmountkept original");
      f.client.addWatchedRoot("default", root, CONTEXT);
      f.awaitCount("reloadmountgone", 1);
      f.awaitCount("reloadmountkept", 1);
      addReturnReceipts(f, root, gone, recreated);
      volume.away();
      awaitPhysicalWatcher(f, root, false, 10);
      volume.label("JS_RELOAD_AWAY");
      Files.delete(volume.attachAndMount(false).resolve("documents/gone.txt"));
      volume.away();
      volume.label("");
      volume.holdDecoy();
      var opening = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.useReload(() -> {
        opening.countDown();
        try { assertTrue(release.await(90, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        throw new IllegalStateException("injected failed reload during volume return");
      });
      var reload = CompletableFuture.supplyAsync(() -> longReload(f));
      try {
        assertTrue(opening.await(10, TimeUnit.SECONDS));
        assertEquals(mount, volume.attachAndMount(false));
        Thread.sleep(65_000); // beyond the ordinary deadline: this runtime still cannot accept writes
        assertEquals(2, f.queue().listSwitchBufferOpsStrict().size(), "a held transition loses no receipt");
      } finally { release.countDown(); }
      assertEquals(500, reload.get(10, TimeUnit.SECONDS).statusCode());
      assertEquals(2, f.queue().listSwitchBufferOpsStrict().size(), "a failed transition loses no receipt");
      f.useNormalReload();
      assertReloadSuccess(f.post());
      awaitPhysicalWatcher(f, root, true, 60);
      f.awaitCount("reloadmountgone", 0);
      f.awaitCount("reloadmountkept", 1);
      f.awaitReceipts(0, "the surviving writer applies the pending root receipt");
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Timeout(120)
  void nestedAndSiblingRootsRecoverWithTheirOwnCollectionsAndDeletionAuthority() throws Exception {
    try (var volume = LinuxLoopVolume.create(directory.resolve("loop")); var f = fixture()) {
      Path mount = volume.mounted();
      Path parent = Files.createDirectories(mount.resolve("parent"));
      Path child = Files.createDirectory(parent.resolve("nested"));
      Path sibling = Files.createDirectory(mount.resolve("sibling"));
      Files.writeString(parent.resolve("kept.txt"), "nestedparentkept original");
      Files.writeString(child.resolve("notes.txt"), "nestedchildold original");
      Files.writeString(sibling.resolve("letters.txt"), "nestedsiblingold original");
      f.client.addWatchedRoot("default", parent, CONTEXT);
      f.client.addWatchedRoot("letters", sibling, CONTEXT);
      // The Head disallows adding new overlaps; the Worker still restores legacy nested roots.
      var response = f.server.appServices().ingestService().watchRoot(
          io.justsearch.ipc.WatchRootRequest.newBuilder().setRootPath(child.toString())
              .setCollection("notes").setRootAdded(true).build(),
          io.justsearch.indexerworker.services.CallContext.none());
      assertTrue(response.getWatching(), response.getErrorMessage());
      f.awaitCount("nestedparentkept", 1);
      f.awaitCount("nestedchildold", 1);
      f.awaitCount("nestedsiblingold", 1);
      volume.away();
      awaitPhysicalWatcher(f, parent, false, 10);
      awaitPhysicalWatcher(f, child, false, 10);
      awaitPhysicalWatcher(f, sibling, false, 10);
      volume.label("JS_NESTED_AWAY");
      Path elsewhere = volume.attachAndMount(false);
      Files.writeString(elsewhere.resolve("parent/nested/notes.txt"), "nestedchildnew changed document");
      Files.writeString(elsewhere.resolve("sibling/letters.txt"), "nestedsiblingnew changed document");
      // Keep the registered child missing when its parent first returns. Parent recovery must not prune it.
      Files.move(elsewhere.resolve("parent/nested"), elsewhere.resolve("held-child"));
      volume.away();
      volume.label("");
      volume.holdDecoy();
      volume.attachAndMount(false);
      awaitPhysicalWatcher(f, parent, true, 60);
      awaitPhysicalWatcher(f, sibling, true, 60);
      f.awaitCount("nestedsiblingnew", 1);
      assertEquals(1, f.count("nestedchildold"), "the parent's recovery cannot prune a missing child root");
      Files.move(mount.resolve("held-child"), child);
      awaitPhysicalWatcher(f, child, true, 60);
      f.awaitCount("nestedchildnew", 1);
      f.awaitCount("nestedchildold", 0);
      var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime) inherited(f.server, "ingestLifecycle");
      assertEquals("notes", runtime.documentFieldOps().getDocumentFieldOrThrow(
          child.resolve("notes.txt").toString(), io.justsearch.indexing.SchemaFields.COLLECTION));
      assertEquals("letters", runtime.documentFieldOps().getDocumentFieldOrThrow(
          sibling.resolve("letters.txt").toString(), io.justsearch.indexing.SchemaFields.COLLECTION));
      f.server.appServices().ingestService().unwatchRoot(
          io.justsearch.ipc.UnwatchRootRequest.newBuilder().setRootPath(child.toString()).build(),
          io.justsearch.indexerworker.services.CallContext.none());
      assertEquals(1, f.count("nestedparentkept"));
      assertEquals(1, f.count("nestedsiblingnew"));
      awaitPhysicalWatcher(f, parent, true, 1);
      awaitPhysicalWatcher(f, sibling, true, 1);
      assertTrue(f.server.appServices().ingestService().watchRoot(
          io.justsearch.ipc.WatchRootRequest.newBuilder().setRootPath(child.toString())
              .setCollection("notes").setRootAdded(true).build(),
          io.justsearch.indexerworker.services.CallContext.none()).getWatching());
      volume.away();
      awaitPhysicalWatcher(f, child, false, 10);
      f.server.appServices().ingestService().unwatchRoot(
          io.justsearch.ipc.UnwatchRootRequest.newBuilder().setRootPath(parent.toString()).build(),
          io.justsearch.indexerworker.services.CallContext.none());
      volume.attachAndMount(false);
      awaitPhysicalWatcher(f, child, true, 60);
      awaitPhysicalWatcher(f, sibling, true, 60);
      awaitPhysicalWatcher(f, parent, false, 1);
      assertEquals(1, f.count("nestedchildnew"));
      assertEquals(1, f.count("nestedsiblingnew"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "returnDuringBootMigrationPromotes({0}, restart={1})")
  @org.junit.jupiter.params.provider.CsvSource({
      "migration-before-switching,false", "migration-green-drained,false",
      "migration-before-switching,true", "migration-green-drained,true"})
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Timeout(300)
  void returnDuringBootMigrationPromotes(String point, boolean restart) throws Exception {
    try (var volume = LinuxLoopVolume.create(directory.resolve("loop"))) {
      Path mount = volume.mounted();
      Path root = Files.createDirectory(mount.resolve("documents"));
      Path gone = Files.writeString(root.resolve("gone.txt"), "migrationmountgone original");
      Path kept = Files.writeString(root.resolve("kept.txt"), "migrationmountkept original");
      Path modified = Files.writeString(root.resolve("modified.txt"), "migrationoldcontent original");
      var modifiedTime = Files.getLastModifiedTime(modified);
      try (var old = fixture()) {
        old.client.addWatchedRoot("default", root, CONTEXT);
        old.awaitCount("migrationmountgone", 1);
        old.awaitCount("migrationmountkept", 1);
        old.awaitCount("migrationoldcontent", 1);
      }
      stampForeignFingerprint();
      var reached = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      io.justsearch.indexerworker.server.MigrationTransitionBarrier.Hook hook = transition -> {
        if (point.equals(transition.point())) {
          reached.countDown();
          assertTrue(release.await(120, TimeUnit.SECONDS), "release the owned migration barrier");
        }
      };
      var surviving = fixture(hook);
      CompletableFuture<Void> closing = null;
      Throwable primaryFailure = null;
      try {
        try {
          assertTrue(reached.await(30, TimeUnit.SECONDS), "real migration must reach " + point);
          awaitPhysicalWatcher(surviving, root, true, 10);
          addReturnReceipts(surviving, root, gone, kept);
          var beforeAbsence = surviving.queue().listSwitchBufferOpsStrict();
          volume.away();
          awaitPhysicalWatcher(surviving, root, false, 10);
          // Establish whether a real unmount used the incarnation-ended callback.
          assertRootScopedUnavailableFailure(surviving, root);
          volume.label("JS_MIGRATE_AWAY");
          Path elsewhere = volume.attachAndMount(false).resolve("documents");
          Files.delete(elsewhere.resolve("gone.txt"));
          Files.writeString(elsewhere.resolve("modified.txt"), "migrationnewcontent original");
          Files.setLastModifiedTime(elsewhere.resolve("modified.txt"), modifiedTime);
          volume.away();
          volume.label("");
          volume.holdDecoy();
          assertEquals(1, surviving.count("migrationmountgone"));
          assertEquals(1, surviving.count("migrationmountkept"));
          assertTrue(surviving.queue().listSwitchBufferOpsStrict().containsAll(beforeAbsence),
              "absence keeps every accepted receipt");
          if (!restart && point.equals("migration-before-switching")) {
            release.countDown();
            long fenced = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!surviving.client.getStatus(CONTEXT).getMigration().getPauseReason()
                .contains("Cutover fenced:") && System.nanoTime() < fenced) Thread.sleep(50);
            assertTrue(surviving.client.getStatus(CONTEXT).getMigration().getPauseReason()
                .contains("Cutover fenced:"), "status must name the root evidence blocking cutover");
            assertEquals(1, surviving.count("migrationmountkept"), "the fenced Engine remains readable");
            Thread.sleep(65_000); // A minute of refusal must preserve serving and automatic retry.
            assertEquals("SWITCHING", surviving.client.getStatus(CONTEXT).getMigration().getMigrationState());
            assertEquals(1, surviving.count("migrationmountkept"));
          }
          assertEquals(mount, volume.attachAndMount(false));
          if (restart) {
            closing = CompletableFuture.runAsync(() -> {
              try { surviving.close(); }
              catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!(boolean) inherited(surviving.server, "closeStarted")
                && System.nanoTime() < until) Thread.sleep(10);
            assertTrue((boolean) inherited(surviving.server, "closeStarted"));
            release.countDown();
          } else {
            release.countDown();
            assertPromotedDrive(surviving, root);
          }
        } finally { release.countDown(); }
      } catch (Exception | Error failure) {
        primaryFailure = failure;
        throw failure;
      } finally {
        try {
          if (closing == null) surviving.close();
          else closing.get(60, TimeUnit.SECONDS);
        } catch (Exception | Error cleanup) {
          if (primaryFailure == null) throw cleanup;
          primaryFailure.addSuppressed(cleanup);
        }
      }
      if (restart) {
        try (var restarted = fixture(io.justsearch.indexerworker.server.MigrationTransitionBarrier.NO_HOOK)) {
          assertPromotedDrive(restarted, root);
        }
      }
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Timeout(150)
  void watcherAdmissionWaitingAtFinalFenceRunsOnThePublishedWriter() throws Exception {
    Path root = Files.createDirectory(directory.resolve("watched"));
    Files.writeString(root.resolve("seed.txt"), "fenceseed");
    try (var old = fixture()) {
      old.client.addWatchedRoot("default", root, CONTEXT);
      old.awaitCount("fenceseed", 1);
    }
    stampForeignFingerprint();
    var reached = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var f = fixture(transition -> {
      if (transition.point().equals("migration-green-drained")) {
        reached.countDown();
        assertTrue(release.await(60, TimeUnit.SECONDS));
      }
    })) {
      try {
        assertTrue(reached.await(30, TimeUnit.SECONDS));
        awaitPhysicalWatcher(f, root, true, 10);
        var before = f.queue().listSwitchBufferOpsStrict();
        Path late = Files.writeString(root.resolve("late.txt"), "fencequeuedadmission");
        long waiting = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean blocked;
        do {
          blocked = Thread.getAllStackTraces().values().stream().anyMatch(stack ->
              java.util.Arrays.stream(stack).anyMatch(frame -> frame.getClassName().endsWith("$WatcherCallbacks")
                  && frame.getMethodName().equals("acquireTarget")));
          if (!blocked) Thread.sleep(10);
        } while (!blocked && System.nanoTime() < waiting);
        assertTrue(blocked, "the actual watcher admission must wait on the held final fence");
        assertEquals(before, f.queue().listSwitchBufferOpsStrict());
        release.countDown();
        f.awaitCount("fencequeuedadmission", 1);
        assertTrue(survivingWriterAccepts(f));
        var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime) inherited(f.server, "ingestLifecycle");
        assertEquals("default", runtime.documentFieldOps().getDocumentFieldOrThrow(
            late.toString(), io.justsearch.indexing.SchemaFields.COLLECTION));
      } finally { release.countDown(); }
    }
  }

  @Test
  @Timeout(90)
  void stalledCutoverCleanupRetainsBodyFailureAndPhysicallyDrainsPeriodicAdmission() throws Exception {
    Path root = Files.createDirectory(directory.resolve("shutdown-root"));
    Files.writeString(root.resolve("seed.txt"), "shutdownseed");
    try (var old = fixture()) {
      old.client.addWatchedRoot("default", root, CONTEXT);
      old.awaitCount("shutdownseed", 1);
    }
    stampForeignFingerprint();
    var reached = new CountDownLatch(1);
    var stalled = new CountDownLatch(1);
    var f = fixture(transition -> {
      if (transition.point().equals("migration-green-drained")) {
        reached.countDown();
        stalled.await(); // The real cutover must be interrupted before admission can drain.
      }
    });
    CompletableFuture<?> periodic = null;
    Thread cutover = (Thread) inherited(f.server, "migrationCutoverThread");
    boolean completedClose = false;
    try {
      assertTrue(reached.await(30, TimeUnit.SECONDS));
      periodic = CompletableFuture.runAsync(() -> f.client.syncDirectory(root.toString(), false,
          EngineProvenance.internal("periodic-root-sync", EngineContext.Survival.DURABLE,
              EngineContext.Urgency.BACKGROUND)));
      Thread blocked = awaitMutationBlockedThread();
      assertTrue(f.root.admission().activeWorkCount() > 0);
      var original = new AssertionError("original stalled D1 body assertion");
      long start = System.nanoTime();
      var observed = assertThrows(AssertionError.class, () -> {
        try (f) { throw original; }
      });
      assertSame(original, observed);
      assertEquals(0, observed.getSuppressed().length, "ordered cleanup must not add an owner-fence failure");
      completedClose = true;
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10));
      assertEquals(0, f.root.admission().activeWorkCount(), "durable physical references must drain");
      assertFalse(cutover.isAlive(), "the cutover must physically exit");
      assertFalse(blocked.isAlive(), "the owned periodic worker must physically exit");
      assertTrue(periodic.isDone(), "the caller must be released too");
    } finally {
      stalled.countDown();
      // Also make fail-before runs safe without relying on the repaired shutdown method.
      if (cutover != null && cutover.isAlive()) {
        cutover.interrupt(); cutover.join(5_000);
      }
      if (periodic != null) periodic.get(10, TimeUnit.SECONDS);
      if (!completedClose) f.close();
    }
  }

  @Test
  @Timeout(90)
  void producerQuiescenceHonorsSmallCallerDeadlineAndReportsIncompleteExit() throws Exception {
    Path root = Files.createDirectory(directory.resolve("deadline-root"));
    Files.writeString(root.resolve("seed.txt"), "deadlineseed");
    try (var old = fixture()) {
      old.client.addWatchedRoot("default", root, CONTEXT);
      old.awaitCount("deadlineseed", 1);
    }
    stampForeignFingerprint();
    var reached = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var f = fixture(transition -> {
      if (transition.point().equals("migration-green-drained")) {
        reached.countDown();
        boolean interrupted = false;
        while (release.getCount() != 0) {
          try { release.await(); }
          catch (InterruptedException stop) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
      }
    })) {
      try {
        assertTrue(reached.await(30, TimeUnit.SECONDS));
        long start = System.nanoTime();
        assertThrows(IllegalStateException.class,
            () -> f.root.quiesceProducers(java.time.Duration.ofMillis(25)));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1),
            "the caller's allowance must not become an additional ten-second join");
      } finally { release.countDown(); }
    }
  }

  private static Thread awaitMutationBlockedThread() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      for (var entry : Thread.getAllStackTraces().entrySet()) {
        if (entry.getKey().getName().startsWith("engine-call-background")
            && java.util.Arrays.stream(entry.getValue()).anyMatch(frame ->
                frame.getClassName().endsWith("WorkerMutationAdmission") && frame.getMethodName().equals("enter"))) {
          return entry.getKey();
        }
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("the real durable sync must wait on the held migration fence");
  }

  private static void assertRootScopedUnavailableFailure(Fixture f, Path root) throws Exception {
    Object admission = invoke(f.server.appServices(), "mutationAdmission", new Class<?>[0]);
    // Physical watch completion and its failure sink are separate asynchronous observations.
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try {
      List<?> roots;
      do {
        roots = (List<?>) invoke(admission, "unavailableRoots", new Class<?>[0]);
        if (!roots.isEmpty()) break;
        Thread.sleep(10);
      } while ((boolean) invoke(admission, "runtimeReplayCertain", new Class<?>[0])
          && System.nanoTime() < until);
      assertTrue((boolean) invoke(admission, "runtimeReplayCertain", new Class<?>[0]),
          "an observed root loss must leave the runtime-wide certificate intact");
      assertTrue(roots.stream().anyMatch(item -> item.toString().contains(root.toString())),
          "the real watcher termination must retain root-scoped uncertainty");
    } catch (NoSuchMethodException baseline) {
      while ((boolean) invoke(admission, "replayCertain", new Class<?>[0])
          && System.nanoTime() < until) Thread.sleep(10);
      assertTrue((boolean) invoke(admission, "replayCertain", new Class<?>[0]),
          "the real unmount must not permanently poison promotion");
    }
  }

  private static void assertPromotedDrive(Fixture f, Path root) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!survivingWriterAccepts(f) && System.nanoTime() < until) Thread.sleep(50);
    Object admission = invoke(f.server.appServices(), "mutationAdmission", new Class<?>[0]);
    String certainty;
    try {
      certainty = " runtimeCertain=" + invoke(admission, "runtimeReplayCertain", new Class<?>[0])
          + " rootScopes=" + invoke(admission, "unavailableRoots", new Class<?>[0]);
    } catch (NoSuchMethodException baseline) {
      certainty = " replayCertain=" + invoke(admission, "replayCertain", new Class<?>[0]);
    }
    assertTrue(survivingWriterAccepts(f), "one writable runtime must serve search and ingest: "
        + f.client.getStatus(CONTEXT).getMigration() + certainty);
    assertEquals("IDLE", f.client.getStatus(CONTEXT).getMigration().getMigrationState());
    awaitPhysicalWatcher(f, root, true, 60);
    f.awaitCount("migrationmountgone", 0);
    f.awaitCount("migrationmountkept", 1);
    f.awaitCount("migrationoldcontent", 0);
    f.awaitCount("migrationnewcontent", 1);
    f.awaitReceipts(0, "promotion acknowledges the exact reconciled versions");
    var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime) inherited(f.server, "ingestLifecycle");
    for (String name : List.of("kept.txt", "modified.txt")) {
      String collection = runtime.documentFieldOps().getDocumentFieldOrThrow(
          root.resolve(name).toString(), io.justsearch.indexing.SchemaFields.COLLECTION);
      assertEquals("default", collection == null ? "default" : collection);
    }
    Files.writeString(root.resolve("after-promotion.txt"), "migrationnewadmission");
    f.awaitCount("migrationnewadmission", 1);
  }

  private static boolean survivingWriterAccepts(Fixture fixture) throws Exception {
    var ingest = (io.justsearch.adapters.lucene.runtime.RunningRuntime) inherited(fixture.server, "ingestLifecycle");
    return ingest != null && ingest == inherited(fixture.server, "searchLifecycle") && ingest.isAcceptingWrites();
  }

  private void stampForeignFingerprint() throws Exception {
    var generations = new io.justsearch.indexerworker.index.IndexGenerationManager(directory.resolve("index"));
    var layout = generations.initializeOrLoad();
    try (var storage = org.apache.lucene.store.FSDirectory.open(layout.activeGenerationPath());
         var reader = org.apache.lucene.index.DirectoryReader.open(storage)) {
      var metadata = new java.util.HashMap<>(reader.getIndexCommit().getUserData());
      metadata.put(io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY, "f".repeat(64));
      try (var writer = new org.apache.lucene.index.IndexWriter(storage,
          new org.apache.lucene.index.IndexWriterConfig(new org.apache.lucene.analysis.standard.StandardAnalyzer()))) {
        writer.setLiveCommitData(metadata.entrySet());
        writer.commit();
      }
    }
  }

  private static void addReturnReceipts(Fixture fixture, Path root, Path exact, Path kept)
      throws Exception {
    Object registry = inherited(fixture.server.appServices(), "rootWatcherRegistry");
    Object subscription = invoke(registry, "subscription", new Class<?>[] {Path.class}, root);
    String identity = invoke(subscription, "rootIdentity", new Class<?>[0]).toString();
    var debt = new io.justsearch.indexerworker.queue.WatcherRootReconciliation(root.toString(), identity, "default");
    assertTrue(fixture.queue().putSwitchBuffer(debt.key(),
        io.justsearch.indexerworker.queue.WatcherRootReconciliation.OPERATION, debt.encode()));
    var deletion = new io.justsearch.indexerworker.queue.SwitchBufferDelete(exact.toString(),
        io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.WATCHER,
        root.toString(), identity, "default");
    assertTrue(fixture.queue().putSwitchBuffer("path:" + exact, "DELETE", deletion.encode()));
    assertTrue(Files.exists(kept));
  }

  private static void awaitRecoverySettled(Fixture fixture, Path root) throws Exception {
    Object registry = inherited(fixture.server.appServices(), "rootWatcherRegistry");
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (((java.util.Set<?>) invoke(registry, "recoveryRoots", new Class<?>[0])).contains(root)
        && System.nanoTime() < until) Thread.sleep(25);
    assertFalse(((java.util.Set<?>) invoke(registry, "recoveryRoots", new Class<?>[0])).contains(root));
  }

  private static long descriptorCount() throws Exception {
    try (var descriptors = Files.list(Path.of("/proc/self/fd"))) { return descriptors.count(); }
  }

  private static java.util.Map<String, Long> descriptorTargets() throws Exception {
    var targets = new java.util.TreeMap<String, Long>();
    try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
      for (Path descriptor : descriptors.toList()) {
        try { targets.merge(Files.readSymbolicLink(descriptor).toString(), 1L, Long::sum); }
        catch (java.nio.file.NoSuchFileException closed) { /* a concurrent operation closed it */ }
      }
    }
    return targets;
  }

  private static void awaitIndexingIdle(Fixture fixture) throws Exception {
    var loop = (io.justsearch.indexerworker.loop.IndexingLoop)
        inherited(fixture.server.appServices(), "indexingLoop");
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while ((fixture.queue().queueDepth() != 0
        || loop.loopState() != io.justsearch.indexerworker.loop.IndexingLoop.LoopState.IDLE)
        && System.nanoTime() < until) Thread.sleep(25);
    assertEquals(0, fixture.queue().queueDepth(), "all recovery admissions finish before resource measurement");
    assertEquals(io.justsearch.indexerworker.loop.IndexingLoop.LoopState.IDLE, loop.loopState());
  }

  private static long watcherThreadCount() {
    return Thread.getAllStackTraces().entrySet().stream().filter(entry ->
        entry.getKey().getName().startsWith("worker-watcher-")
            || java.util.Arrays.stream(entry.getValue()).anyMatch(
                frame -> frame.getClassName().startsWith("io.methvin."))).count();
  }

  /** Holds the real scan scheduler, as an unrelated slow scan would, without busy work. */
  private static final class HeldPeriodicSync implements AutoCloseable {
    private final CountDownLatch release = new CountDownLatch(1);
    private final java.util.concurrent.Future<?> task;

    HeldPeriodicSync(Fixture fixture) throws Exception {
      var sync = inherited(fixture.client, "syncOps");
      var scheduler = (java.util.concurrent.ScheduledExecutorService) inherited(sync, "syncScheduler");
      var entered = new CountDownLatch(1);
      task = scheduler.submit(() -> {
        entered.countDown();
        try { release.await(250, TimeUnit.SECONDS); }
        catch (InterruptedException closing) { Thread.currentThread().interrupt(); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    @Override public void close() throws Exception {
      release.countDown();
      task.get(5, TimeUnit.SECONDS);
    }
  }

  private static void awaitPhysicalWatcher(Fixture fixture, Path root, boolean expected, int seconds)
      throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    boolean active;
    do {
      Object services = fixture.server.appServices();
      Object registry = inherited(services, "rootWatcherRegistry");
      Object subscription = invoke(registry, "subscription", new Class<?>[] {Path.class}, root);
      Object watcher = inherited(services, "workerWatcher");
      active = subscription != null && (boolean) invoke(watcher, "isActive",
          new Class<?>[] {Path.class, Object.class}, root,
          invoke(subscription, "watcherEpoch", new Class<?>[0]));
      if (active == expected) return;
      Thread.sleep(50);
    } while (System.nanoTime() < until);
    assertEquals(expected, active, "physical WorkerMethvinWatcher state at " + root);
  }

  private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments)
      throws Exception {
    var method = target.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(target, arguments);
  }

  private Fixture fixture() throws Exception { return fixture(null); }

  private Fixture fixture(io.justsearch.indexerworker.server.MigrationTransitionBarrier.Hook hook)
      throws Exception {
    var host = new RuntimeReloadProducersHttpIntegrationTest();
    host.directory = directory;
    Fixture fixture = host.new Fixture(true, hook);
    var folders = new IndexingController(() -> fixture.client, null, directory.resolve("home"), null);
    var capability = mock(io.justsearch.app.api.lifecycle.Capability.class);
    when(capability.available()).thenReturn(true);
    folders.setWorkerCapability(capability);
    fixture.http.get("/test/roots", folders::handleListRootsSubstrate);
    return fixture;
  }

  private static HttpResponse<String> longReload(Fixture fixture) {
    try {
      return fixture.transport.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
          + fixture.http.port() + "/api/admin/runtime/reload"))
          .timeout(java.time.Duration.ofSeconds(120)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception failure) { throw new AssertionError(failure); }
  }

  private static void assertReloadSuccess(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
  }

  private static Object inherited(Object target, String name) throws Exception {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException next) { /* look in the superclass */ }
    }
    throw new NoSuchFieldException(name);
  }
}
