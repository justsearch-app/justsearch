/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Watcher callbacks admit retryable work even when their event source is no longer a file. */
final class WatcherUpsertCompatibilityTest {
  @TempDir Path temp;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"folder", "in-root-link", "vanished", "atomic-temp", "ordinary"})
  void watcherSourcesKeepMainAdmissionBehaviour(String kind) throws Exception {
    Path root = Files.createDirectory(temp.resolve("root"));
    Path path = root.resolve("event.txt");
    if (kind.equals("folder")) Files.createDirectory(path);
    else if (kind.equals("in-root-link")) {
      Files.createSymbolicLink(path, Files.writeString(root.resolve("target.txt"), "target"));
    } else if (kind.equals("atomic-temp")) {
      path = root.resolve("notes.txt.123456");
      Files.writeString(path, "temporary save");
      Files.move(path, root.resolve("notes.txt"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } else if (!kind.equals("vanished")) Files.writeString(path, "source");
    var queue = mock(JobQueue.class);
    when(queue.enqueueMaintenanceScanEntries(anyList(), any(), any())).thenReturn(1);
    var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        null, null, null, null, null, 0L);
    var witness = new RootWatcherRegistry.Subscription(root, "docs", new Object(), RootIdentity.capture(root));
    Path event = path;
    assertDoesNotThrow(() -> service.acceptWatcherUpsert(witness, "docs", event));
    var entries = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(queue).enqueueMaintenanceScanEntries(entries.capture(), eq("docs"), isNull());
    assertEquals(path, ((JobQueue.EnqueueEntry) entries.getValue().getFirst()).path());
    assertEquals(root, ((JobQueue.EnqueueEntry) entries.getValue().getFirst()).ingestionRoot());
  }
}
