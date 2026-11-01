/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.queue.JobQueue;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.hashing.FileHash;
import io.methvin.watcher.hashing.FileHasher;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkerWatcherRestoredMtimeTest {
  @TempDir Path temporary;

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void methvinModifyDeduplicationKeepsEditsWithRestoredMtime(boolean equalSize) throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "old");
    var stamp = Files.getLastModifiedTime(file);
    FileHasher hasher;
    // Read the actual configured hasher; no substitute production fingerprint in this check.
    try (var worker = new WorkerMethvinWatcher(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(),
        mock(JobQueue.class), null, ignored -> {})) {
      var registration = new RootWatcherRegistry.Subscription(temporary, null, new Object(), RootIdentity.capture(temporary));
      assertTrue(worker.registerRoot(registration));
      Map<Path, ?> subscriptions = field(worker, "watchers");
      DirectoryWatcher installed = field(subscriptions.get(temporary), "watcher");
      hasher = field(installed, "fileHasher");
    }
    FileHash before = hasher == null ? null : hasher.hash(file);
    Files.writeString(file, equalSize ? "new" : "new content of a different size");
    Files.setLastModifiedTime(file, stamp);
    var observed = new ArrayList<DirectoryChangeEvent>();
    WatchService events = mock(WatchService.class);
    WatchKey key = mock(WatchKey.class);
    @SuppressWarnings("unchecked")
    WatchEvent<Path> modify = mock(WatchEvent.class);
    when(modify.kind()).thenReturn(StandardWatchEventKinds.ENTRY_MODIFY);
    when(modify.context()).thenReturn(file.getFileName());
    when(modify.count()).thenReturn(1);
    when(key.pollEvents()).thenReturn(List.of(modify));
    when(key.reset()).thenReturn(false);
    when(events.poll()).thenReturn(key);
    // Drive the library's real duplicate filter with a deterministic WatchService event.
    var watcher = new DirectoryWatcher(List.of(), observed::add, events, hasher, null,
        org.slf4j.LoggerFactory.getLogger(getClass()));
    Map<Path, FileHash> hashes = field(watcher, "pathHashes");
    if (before != null) hashes.put(file, before);
    Map<WatchKey, Path> roots = field(watcher, "keyRoots");
    roots.put(key, temporary);
    Map<Path, Path> registered = field(watcher, "registeredPathToRootPath");
    registered.put(temporary, temporary);
    var loop = DirectoryWatcher.class.getDeclaredMethod("runEventLoop");
    loop.setAccessible(true);
    assertDoesNotThrow(() -> loop.invoke(watcher));
    assertEquals(1, observed.size(), "content change with equal mtime must survive Methvin's duplicate filter");
    assertEquals(DirectoryChangeEvent.EventType.MODIFY, observed.getFirst().eventType());
    assertEquals(file, observed.getFirst().path());
    if (hasher != null) assertEquals(hasher.hash(file), hashes.get(file));
  }

  @SuppressWarnings("unchecked")
  private static <T> T field(Object owner, String name) throws ReflectiveOperationException {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return (T) field.get(owner);
  }
}
