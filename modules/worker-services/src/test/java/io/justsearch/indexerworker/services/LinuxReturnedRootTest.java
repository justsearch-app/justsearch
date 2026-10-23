/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
final class LinuxReturnedRootTest {
  @TempDir Path temp;

  @Test
  void deadWatcherResumesOnceAndKeepsItsCollection() throws Exception {
    var active = new AtomicBoolean();
    WorkerMethvinWatcher watcher = watcher(active);
    var registry = new RootWatcherRegistry(watcher);
    assertTrue(registry.watch(temp.toString(), "documents").watching());
    var before = registry.subscription(temp);
    active.set(false);

    assertTrue(registry.resumeAwaitingVolume(temp), "a dead physical watcher must be re-registered");

    var returned = registry.subscription(temp);
    assertNotSame(before, returned);
    assertEquals("documents", returned.collection());
    assertFalse(registry.resumeAwaitingVolume(temp), "a healthy watcher must not be duplicated");
    assertNull(registry.enterEvent(before), "late callbacks cannot enter the returned incarnation");
    verify(watcher, times(2)).registerRoot(any());
  }

  @Test
  void replacedFolderBeforeRecoveryNeverBecomesTheNewIdentity() throws Exception {
    Path root = Files.createDirectory(temp.resolve("watched"));
    var active = new AtomicBoolean();
    var registry = new RootWatcherRegistry(watcher(active));
    assertTrue(registry.watch(root.toString(), "documents").watching());
    active.set(false);
    Files.move(root, temp.resolve("original"));
    Files.createDirectory(root);

    assertFalse(registry.resumeAwaitingVolume(root));
    assertNull(registry.subscription(root));
    assertTrue(registry.awaitsVolume(root), "uncertainty must keep the root held");
    assertFalse(registry.resumeAwaitingVolume(root));
    assertTrue(registry.unwatch(root.toString()));
    assertFalse(registry.resumeAwaitingVolume(root), "removal cancels recovery");
  }

  @Test
  void handoverRetainsTheFolderWitnessEvenWhileItsPathIsMissing() throws Exception {
    Path root = Files.createDirectory(temp.resolve("handover"));
    var prior = new RootWatcherRegistry();
    assertTrue(prior.watch(root.toString(), "notes").watching());
    Files.move(root, temp.resolve("absent"));
    var next = new RootWatcherRegistry();
    prior.handOverTo(next, (registry, path, collection) -> registry.watch(path.toString(), collection));
    Files.createDirectory(root);
    assertFalse(next.resumeAwaitingVolume(root), "a reload must not adopt a replacement folder");
    Files.delete(root);
    Files.move(temp.resolve("absent"), root);
    assertTrue(next.resumeAwaitingVolume(root));
    assertEquals("notes", next.subscription(root).collection());
    assertTrue(prior.unwatch(root.toString()));
    assertFalse(next.resumeAwaitingVolume(root));
  }

  @Test
  void handoverOfADeadWatcherKeepsRecoveryWhenTheRootIsAlreadyBack() throws Exception {
    var active = new AtomicBoolean();
    var prior = new RootWatcherRegistry(watcher(active));
    assertTrue(prior.watch(temp.toString(), "notes").watching());
    active.set(false);
    var next = new RootWatcherRegistry(watcher(new AtomicBoolean()));
    prior.handOverTo(next, (registry, path, collection) -> registry.watch(path.toString(), collection));
    assertTrue(next.isCurrentAndActive(next.subscription(temp)));
    assertTrue(needsRecovery(next, temp), "registration during handover cannot discard the missed scan");
    assertTrue(prior.unwatch(temp.toString()));
    assertFalse(needsRecovery(next, temp), "removal cancels the transferred recovery");
  }

  private static boolean needsRecovery(RootWatcherRegistry registry, Path root) throws Exception {
    try {
      var method = RootWatcherRegistry.class.getDeclaredMethod("recoveryRoots");
      method.setAccessible(true);
      return ((java.util.Set<?>) method.invoke(registry)).contains(root);
    } catch (NoSuchMethodException beforeRepair) { return false; }
  }

  @Test
  void removalRacingRegistrationWinsAndReAdditionGetsOneFreshWatcher() throws Exception {
    var active = new AtomicBoolean();
    WorkerMethvinWatcher watcher = watcher(active);
    var registry = new RootWatcherRegistry(watcher);
    assertTrue(registry.watch(temp.toString(), "old").watching());
    var old = registry.subscription(temp);
    active.set(false);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    doAnswer(ignored -> {
      entered.countDown();
      assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
      active.set(true);
      return true;
    }).when(watcher).registerRoot(any());
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var recovery = executor.submit(() -> registry.resumeAwaitingVolume(temp));
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      var removal = executor.submit(() -> registry.unwatch(temp.toString()));
      release.countDown();
      assertTrue(recovery.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(removal.get(5, java.util.concurrent.TimeUnit.SECONDS));
    } finally { release.countDown(); }
    assertNull(registry.subscription(temp));
    assertFalse(registry.resumeAwaitingVolume(temp));
    assertTrue(registry.watch(temp.toString(), "new").watching());
    assertNull(registry.enterEvent(old));
    assertEquals("new", registry.subscription(temp).collection());
    assertEquals(1, registry.watchedRoots().size());
    verify(watcher, times(3)).registerRoot(any());
  }

  @Test
  void changedDeviceReceiptAppliesButARecreatedFileSurvives() throws Exception {
    Path root = diskRoot();
    try {
      RootIdentity current = RootIdentity.capture(root);
      String old = current.toString().replaceFirst("dev=[0-9a-f]+", "dev=ffffffff");
      var queue = mock(JobQueue.class);
      when(queue.enqueueReappearedFile(any(), nullable(String.class))).thenReturn(true);
      var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
      var service = service(queue, runtime);
      Path absent = root.resolve("gone.txt");
      var receipt = new SwitchBufferDelete(absent.toString(), SwitchBufferDelete.Origin.WATCHER,
          root.toString(), old, "documents");

      assertEquals(WorkerIngestService.BufferedDeleteReplay.SETTLED,
          service.replayBufferedFileDelete(runtime, receipt));
      verify(runtime.indexingCoordinator()).deleteByIdAndChunks(absent.toString());
      Files.writeString(absent, "recreated content");
      assertEquals(WorkerIngestService.BufferedDeleteReplay.SETTLED,
          service.replayBufferedFileDelete(runtime, receipt));
      verify(runtime.indexingCoordinator(), times(1)).deleteByIdAndChunks(absent.toString());
      verify(queue).enqueueReappearedFile(any(), nullable(String.class));
    } finally { Files.deleteIfExists(root.resolve("gone.txt")); Files.delete(root); }
  }

  @Test
  void unknownOrDifferentFilesystemFolderTypeMountRootAndPathNeverMatchAReceipt() throws Exception {
    Path root = diskRoot();
    try {
      RootIdentity current = RootIdentity.capture(root);
      var linux = (RootIdentity.LinuxFileIdentity) current.stableFileIdentity();
      var service = service(mock(JobQueue.class), mock(RunningRuntime.class, RETURNS_DEEP_STUBS));
      String old = current.toString().replaceFirst("dev=[0-9a-f]+", "dev=ffffffff");
      String[] volume = linux.volume().split(":");
      for (String invalid : java.util.List.of(
          old.replaceFirst("ino=[0-9]+", "ino=0"),
          old.replace(linux.volume(), "null"),
          old.replace(linux.volume(), LinuxRootIdentityLease.encodeVolume("different", "ext4", Path.of("/"))),
          old.replace(linux.volume(), LinuxRootIdentityLease.encodeVolume(volume[1], "different-type", Path.of("/"))),
          old.replace(linux.volume(), LinuxRootIdentityLease.encodeVolume(volume[1], volume[2], Path.of("/different-subtree"))),
          old.replace(linux.volume(), linux.volume() + "extra"),
          old.replace(root.toString(), root.resolve("other").toString()))) {
        assertEquals(WorkerIngestService.BufferedDeleteReplay.UNCERTAIN,
            service.replayBufferedFileDelete(mock(RunningRuntime.class), new SwitchBufferDelete(
                root.resolve("missing.txt").toString(), SwitchBufferDelete.Origin.WATCHER,
                root.toString(), invalid, "documents")), invalid);
      }
      RootIdentity changed = new RootIdentity(current.realPath(), new RootIdentity.LinuxFileIdentity(
          linux.fileKey().toString().replaceFirst("dev=[0-9a-f]+", "dev=ffffffff"), linux.volume()));
      assertThrows(IOException.class, () -> current.requireSame(changed, root),
          "the within-operation comparator stays strict");
    } finally { Files.delete(root); }
  }

  @Test
  void sameFilesystemWithDifferentMountRootCannotVouchForAReceipt() throws Exception {
    Path root = diskRoot();
    try {
      RootIdentity identity = RootIdentity.capture(root);
      var linux = (RootIdentity.LinuxFileIdentity) identity.stableFileIdentity();
      String[] volume = linux.volume().split(":");
      String otherSubtree = LinuxRootIdentityLease.encodeVolume(
          volume[1], volume[2], Path.of("/another-bind-subtree"));
      var target = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
      var service = service(mock(JobQueue.class), target);
      Path file = root.resolve("absent.txt");
      assertEquals(WorkerIngestService.BufferedDeleteReplay.UNCERTAIN,
          service.replayBufferedFileDelete(target, new SwitchBufferDelete(file.toString(),
              SwitchBufferDelete.Origin.WATCHER, root.toString(),
              identity.toString().replace(linux.volume(), otherSubtree), "documents")),
          "the UUID, type, inode, path and device match; only the bind subtree differs");
      verify(target.indexingCoordinator(), never()).deleteByIdAndChunks(any());
      assertEquals(WorkerIngestService.BufferedDeleteReplay.SETTLED,
          service.replayBufferedFileDelete(target, new SwitchBufferDelete(file.toString(),
              SwitchBufferDelete.Origin.WATCHER, root.toString(), identity.toString(), "documents")),
          "an equivalent bind keeps the same mount filesystem-root");
    } finally { Files.delete(root); }
  }

  @Test
  void removingAParentRetainsTheVolumesOfDeclaredChildrenAndSiblings() throws Exception {
    Path parent = diskRoot();
    Path child = Files.createDirectory(parent.resolve("child"));
    Path sibling = diskRoot();
    var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    var service = new WorkerIngestService(mock(JobQueue.class), null, null,
        IndexingPacing.unthrottled(), temp.resolve("index"), null, runtime, runtime, null, 0L);
    try {
      for (Path root : java.util.List.of(parent, child, sibling)) {
        assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
            .setRootPath(root.toString()).setRootAdded(true).setCollection("documents").build(),
            CallContext.none()).getWatching());
        assertTrue(service.rootOnRecordedVolume(root.toString()));
      }
      service.unwatchRoot(io.justsearch.ipc.UnwatchRootRequest.newBuilder()
          .setRootPath(parent.toString()).build(), CallContext.none());

      assertFalse(service.rootOnRecordedVolume(parent.toString()));
      assertTrue(service.rootOnRecordedVolume(child.toString()), "a declared child keeps its own volume record");
      assertTrue(service.rootOnRecordedVolume(sibling.toString()));
    } finally {
      Files.delete(child);
      Files.delete(parent);
      Files.delete(sibling);
    }
  }

  @Test
  void aNewRegistryKeepsTheExistingRestartPolicyForAReplacedFolder() throws Exception {
    Path root = Files.createDirectory(temp.resolve("restart"));
    var old = new RootWatcherRegistry();
    assertTrue(old.watch(root.toString(), "notes").watching());
    Files.move(root, temp.resolve("old-folder"));
    Files.createDirectory(root);
    var restarted = new RootWatcherRegistry();
    assertTrue(restarted.watch(root.toString(), "notes").watching(),
        "folder identity is deliberately not persisted across Engine processes");
  }

  private static Path diskRoot() throws Exception {
    Path base = Files.createDirectories(Path.of("build/tmp/linux-return-receipts").toAbsolutePath());
    Path root = Files.createTempDirectory(base, "root-");
    // Unit receipt comparison needs a real UUID; the mount integration campaign supplies its own.
    if (RootIdentity.capture(root).volume() == null) {
      Files.delete(root);
      org.junit.jupiter.api.Assumptions.abort("This filesystem exposes no UUID for receipt comparison");
    }
    return root;
  }

  private static WorkerIngestService service(JobQueue queue, RunningRuntime runtime) {
    return new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
  }

  private static WorkerMethvinWatcher watcher(AtomicBoolean active) {
    var watcher = mock(WorkerMethvinWatcher.class);
    when(watcher.registerRoot(any())).thenAnswer(ignored -> { active.set(true); return true; });
    when(watcher.activateRoot(any())).thenReturn(true);
    when(watcher.isActive(any(), any())).thenAnswer(ignored -> active.get());
    return watcher;
  }
}
