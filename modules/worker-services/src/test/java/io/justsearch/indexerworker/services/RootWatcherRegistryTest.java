package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RootWatcherRegistryTest {

  @TempDir Path tempDir;

  @Test
  void watchAcceptsValidDirectoryAndIsIdempotent() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("watched"));
    RootWatcherRegistry registry = new RootWatcherRegistry();

    RootWatcherRegistry.WatchResult first = registry.watch(root.toString(), "docs");
    assertTrue(first.watching());
    assertEquals(1, registry.watchedRoots().size());

    RootWatcherRegistry.WatchResult second = registry.watch(root.toString(), "docs-v2");
    assertTrue(second.watching(), "Second watch on the same root must succeed (idempotent)");
    assertEquals(1, registry.watchedRoots().size(), "Idempotent — same root must not duplicate");
  }

  @Test
  void sameValueRewatchPublishesAFreshSubscriptionIdentity() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("same-value"));
    RootWatcherRegistry registry = new RootWatcherRegistry();

    assertTrue(registry.watch(root.toString(), "docs").watching());
    RootWatcherRegistry.Subscription first = registry.subscription(root);
    assertTrue(registry.isCurrentAndActive(first));

    assertTrue(registry.watch(root.toString(), "docs").watching());
    RootWatcherRegistry.Subscription second = registry.subscription(root);
    assertNotSame(first, second, "A same-value rewatch must publish a new opaque incarnation");
    assertFalse(registry.isCurrentAndActive(first), "The replaced incarnation cannot certify a scan");
    assertTrue(registry.isCurrentAndActive(second));
  }

  @Test
  void failedPhysicalRegistrationIsNotPublished() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("failed-registration"));
    WorkerMethvinWatcher watcher = mock(WorkerMethvinWatcher.class);
    when(watcher.registerRoot(any(RootWatcherRegistry.Subscription.class)))
        .thenReturn(false);
    RootWatcherRegistry registry = new RootWatcherRegistry(watcher);

    RootWatcherRegistry.WatchResult result = registry.watch(root.toString(), "docs");

    assertFalse(result.watching());
    assertNull(registry.subscription(root), "A failed watcher startup must not become current");
  }

  @Test
  void watchRejectsNonDirectoryRoot() throws Exception {
    Path file = Files.writeString(tempDir.resolve("not-a-dir.txt"), "x");
    RootWatcherRegistry registry = new RootWatcherRegistry();

    RootWatcherRegistry.WatchResult result = registry.watch(file.toString(), null);
    assertFalse(result.watching());
    assertTrue(
        result.errorMessage() != null && result.errorMessage().contains("not a directory"),
        "Non-directory root must surface a typed error message");
    assertTrue(registry.watchedRoots().isEmpty());
  }

  @Test
  void unwatchReturnsTrueWhenSubscriptionExisted() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("transient"));
    RootWatcherRegistry registry = new RootWatcherRegistry();
    registry.watch(root.toString(), null);

    assertTrue(registry.unwatch(root.toString()));
    assertTrue(registry.watchedRoots().isEmpty());
    assertFalse(registry.unwatch(root.toString()), "Second unwatch returns false");
  }
}
